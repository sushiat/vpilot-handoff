using System;
using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Reflection;
using System.Security.Cryptography;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;

namespace Handoff.Plugin
{
    /// <summary>
    /// Checks this repo's GitHub releases for a newer plugin version and, if found, downloads and
    /// sha256-verifies the Handoff-Setup installer before launching it silently (issue #34). The
    /// installer itself (plugin/installer/Handoff-Setup.iss) owns everything past that point --
    /// waiting for vPilot to exit, resolving the install folder from the registry, and copying
    /// files -- so this class's job ends at "hand off a verified, trusted binary."
    ///
    /// Also reports a one-shot "update applied" notification: the installer writes
    /// Plugins\update-applied.json after an upgrade (not a fresh install), and CheckMarker (called
    /// once from HandoffPlugin.Initialize, not tied to the network connection) surfaces it through
    /// OperationProgressModel/PostDebugMessage so a reconnecting Android app sees it, then deletes
    /// the marker.
    /// </summary>
    public sealed class PluginUpdateModel
    {
        private const string OperationIdPrefix = "pluginUpdate";
        private const string MarkerFileName = "update-applied.json";

        private static readonly HttpClient Http = new HttpClient();

        private readonly OperationProgressModel _operationProgress;
        private readonly Action<string> _logDebug;

        public PluginUpdateModel(OperationProgressModel operationProgress, Action<string> logDebug)
        {
            _operationProgress = operationProgress ?? throw new ArgumentNullException(nameof(operationProgress));
            _logDebug = logDebug;
        }

        /// <summary>
        /// Checks for and applies an update. Fire-and-forget from the caller's perspective (never
        /// throws) -- runs on whatever thread it's called from, so callers should not await it on
        /// vPilot's own event-dispatch thread; see HandoffPlugin's NetworkConnected wiring.
        /// </summary>
        public async Task CheckAsync()
        {
            try
            {
                var release = await PluginUpdateClient.FetchLatestReleaseAsync(_logDebug).ConfigureAwait(false);
                if (release == null) return;

                var currentVersion = Assembly.GetExecutingAssembly().GetName().Version;
                if (release.Version <= currentVersion)
                {
                    _logDebug?.Invoke($"PluginUpdateModel: up to date (running {currentVersion}, latest is {release.Version}).");
                    return;
                }

                await DownloadVerifyAndLaunchAsync(release, currentVersion).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                _logDebug?.Invoke("PluginUpdateModel: CheckAsync threw: " + ex.Message);
            }
        }

        private async Task DownloadVerifyAndLaunchAsync(PluginLatestRelease release, Version currentVersion)
        {
            var operationId = OperationIdPrefix + "-" + Guid.NewGuid().ToString("N");
            _operationProgress.Report(operationId, $"Downloading Handoff plugin update {release.Version}...");

            var stagingDir = Path.Combine(Path.GetTempPath(), "Handoff-Update", release.Version.ToString());
            try
            {
                Directory.CreateDirectory(stagingDir);
                var installerPath = Path.Combine(stagingDir, $"Handoff-Setup-v{release.Version}.exe");

                await DownloadFileAsync(release.InstallerUrl, installerPath).ConfigureAwait(false);

                _operationProgress.Report(operationId, "Verifying update...");
                // Verified against GitHub's own per-asset digest (see PluginUpdateClient), which
                // GitHub computes server-side from the bytes it received -- rules out a tampered
                // exe paired with a matching tampered checksum (that would need compromising
                // GitHub itself, not just repo/release-upload access), though it still isn't a
                // substitute for code-signing to prove the maintainer built this binary.
                var actualHash = ComputeSha256(installerPath);
                if (!string.Equals(release.ExpectedSha256, actualHash, StringComparison.OrdinalIgnoreCase))
                {
                    _logDebug?.Invoke($"PluginUpdateModel: sha256 mismatch (expected {release.ExpectedSha256}, got {actualHash}) -- discarding download.");
                    _operationProgress.Finish(operationId, "Update verification failed -- discarded.", success: false);
                    return;
                }

                _operationProgress.Report(operationId, "Installing update...");
                Process.Start(new ProcessStartInfo
                {
                    FileName = installerPath,
                    Arguments = "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART",
                    UseShellExecute = false,
                    CreateNoWindow = true,
                });

                // The installer waits for vPilot to exit before it does anything to the live
                // install, so this is "handed off successfully," not "install complete" -- the
                // marker-file check on next plugin load reports actual completion.
                _operationProgress.Finish(operationId, $"Update {release.Version} downloading in background -- restart vPilot to apply.", success: true);
            }
            catch (Exception ex)
            {
                _logDebug?.Invoke("PluginUpdateModel: download/verify/launch failed: " + ex.Message);
                _operationProgress.Finish(operationId, "Update failed -- will retry next connect.", success: false);
                TryDeleteDirectory(stagingDir);
            }
        }

        private static async Task DownloadFileAsync(string url, string destinationPath)
        {
            using (var response = await Http.GetAsync(url).ConfigureAwait(false))
            {
                response.EnsureSuccessStatusCode();
                using (var stream = await response.Content.ReadAsStreamAsync().ConfigureAwait(false))
                using (var file = File.Create(destinationPath))
                {
                    await stream.CopyToAsync(file).ConfigureAwait(false);
                }
            }
        }

        private static string ComputeSha256(string filePath)
        {
            using (var sha256 = SHA256.Create())
            using (var stream = File.OpenRead(filePath))
            {
                var hash = sha256.ComputeHash(stream);
                return BitConverter.ToString(hash).Replace("-", "");
            }
        }

        private static void TryDeleteDirectory(string path)
        {
            try
            {
                if (Directory.Exists(path)) Directory.Delete(path, recursive: true);
            }
            catch
            {
                // Best effort -- leftover temp files aren't worth failing over.
            }
        }

        /// <summary>
        /// Checks for the installer's one-shot update-applied marker (see
        /// plugin/installer/Handoff-Setup.iss) next to the running plugin DLL. Call once from
        /// HandoffPlugin.Initialize -- not network-tied, since the marker (and the fact that this
        /// code is now running at all, post-update) has nothing to do with VATSIM connectivity.
        /// </summary>
        public void CheckMarker()
        {
            try
            {
                var pluginDir = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);
                if (pluginDir == null) return;

                var markerPath = Path.Combine(pluginDir, MarkerFileName);
                if (!File.Exists(markerPath)) return;

                var marker = JObject.Parse(File.ReadAllText(markerPath));
                var version = (string)marker["version"];

                var operationId = OperationIdPrefix + "-" + Guid.NewGuid().ToString("N");
                var status = $"Handoff plugin updated to {version}.";
                _operationProgress.Report(operationId, status);
                _operationProgress.Finish(operationId, status, success: true);
                _logDebug?.Invoke("PluginUpdateModel: " + status);

                File.Delete(markerPath);
            }
            catch (Exception ex)
            {
                _logDebug?.Invoke("PluginUpdateModel: CheckMarker failed: " + ex.Message);
            }
        }
    }
}
