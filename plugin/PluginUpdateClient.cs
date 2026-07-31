using System;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;

namespace Handoff.Plugin
{
    /// <summary>
    /// Fetches this repo's latest GitHub release for the plugin auto-updater (issue #34). Mirrors
    /// VatsimDataFeedClient's pattern: static HttpClient, catch-and-return-null on failure, and a
    /// pure ParseLatestRelease method split out so it's unit testable against a fixture JSON
    /// string with no network I/O.
    /// </summary>
    public static class PluginUpdateClient
    {
        private const string Endpoint = "https://api.github.com/repos/sushiat/vpilot-handoff/releases/latest";
        private const string InstallerAssetPrefix = "Handoff-Setup-";
        private const string InstallerAssetSuffix = ".exe";
        private const string Sha256DigestPrefix = "sha256:";

        private static readonly HttpClient Http = CreateHttpClient();

        private static HttpClient CreateHttpClient()
        {
            var http = new HttpClient();
            // GitHub's API rejects requests with no User-Agent header.
            http.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("Handoff-Plugin", null));
            http.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
            return http;
        }

        /// <summary>
        /// Returns null on failure (HTTP error, malformed response, or any exception) -- never
        /// throws to the caller, same reasoning as VatsimDataFeedClient/SimBriefClient.
        /// </summary>
        public static async Task<PluginLatestRelease> FetchLatestReleaseAsync(Action<string> logDebug = null)
        {
            try
            {
                using (var response = await Http.GetAsync(Endpoint).ConfigureAwait(false))
                {
                    if (!response.IsSuccessStatusCode)
                    {
                        logDebug?.Invoke($"PluginUpdateClient: fetch failed, HTTP {(int)response.StatusCode}.");
                        return null;
                    }

                    var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                    var release = ParseLatestRelease(body);
                    if (release == null)
                        logDebug?.Invoke("PluginUpdateClient: latest release response had no matching installer asset.");
                    return release;
                }
            }
            catch (Exception ex)
            {
                logDebug?.Invoke("PluginUpdateClient: fetch threw: " + ex.Message);
                return null;
            }
        }

        /// <summary>
        /// Parses a GitHub "get latest release" API response. Looks for a Handoff-Setup-v*.exe
        /// asset and reads its expected hash straight from GitHub's own per-asset "digest" field
        /// (a "sha256:&lt;hex&gt;" string GitHub computes and serves itself -- no separate
        /// .sha256 sidecar file needs publishing) -- returns null if the asset or its digest is
        /// missing (a release with no installer asset yet, or a malformed tag, isn't something
        /// the updater can act on).
        /// </summary>
        public static PluginLatestRelease ParseLatestRelease(string json)
        {
            var root = JObject.Parse(json);

            var tagName = (string)root["tag_name"];
            if (string.IsNullOrEmpty(tagName)) return null;

            var versionText = tagName.StartsWith("v", StringComparison.OrdinalIgnoreCase)
                ? tagName.Substring(1)
                : tagName;
            if (!Version.TryParse(versionText, out var version)) return null;

            var assets = root["assets"] as JArray;
            if (assets == null) return null;

            foreach (var asset in assets)
            {
                var name = (string)asset["name"];
                var url = (string)asset["browser_download_url"];
                var digest = (string)asset["digest"];
                if (string.IsNullOrEmpty(name) || string.IsNullOrEmpty(url) || string.IsNullOrEmpty(digest)) continue;

                if (!name.StartsWith(InstallerAssetPrefix, StringComparison.OrdinalIgnoreCase)
                    || !name.EndsWith(InstallerAssetSuffix, StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                if (!digest.StartsWith(Sha256DigestPrefix, StringComparison.OrdinalIgnoreCase)) continue;

                var sha256 = digest.Substring(Sha256DigestPrefix.Length);
                return new PluginLatestRelease(version, url, sha256);
            }

            return null;
        }
    }

    /// <summary>Parsed result of PluginUpdateClient.FetchLatestReleaseAsync/ParseLatestRelease.</summary>
    public sealed class PluginLatestRelease
    {
        public Version Version { get; }
        public string InstallerUrl { get; }
        public string ExpectedSha256 { get; }

        public PluginLatestRelease(Version version, string installerUrl, string expectedSha256)
        {
            Version = version;
            InstallerUrl = installerUrl;
            ExpectedSha256 = expectedSha256;
        }
    }
}
