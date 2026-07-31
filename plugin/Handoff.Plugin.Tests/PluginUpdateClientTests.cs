using Xunit;

namespace Handoff.Plugin.Tests
{
    public class PluginUpdateClientTests
    {
        [Fact]
        public void ParseLatestRelease_ParsesInstallerAssetAndDigest()
        {
            var json = @"{
                ""tag_name"": ""v0.2.0"",
                ""assets"": [
                    {""name"": ""Handoff-Setup-v0.2.0.exe"", ""browser_download_url"": ""https://example.com/Handoff-Setup-v0.2.0.exe"", ""digest"": ""sha256:aabbcc""},
                    {""name"": ""Handoff-v0.2.0.apk"", ""browser_download_url"": ""https://example.com/Handoff-v0.2.0.apk"", ""digest"": ""sha256:ddeeff""}
                ]
            }";

            var release = PluginUpdateClient.ParseLatestRelease(json);

            Assert.NotNull(release);
            Assert.Equal(new System.Version(0, 2, 0), release.Version);
            Assert.Equal("https://example.com/Handoff-Setup-v0.2.0.exe", release.InstallerUrl);
            Assert.Equal("aabbcc", release.ExpectedSha256);
        }

        [Fact]
        public void ParseLatestRelease_TagWithoutVPrefix_StillParses()
        {
            var json = @"{
                ""tag_name"": ""0.2.0"",
                ""assets"": [
                    {""name"": ""Handoff-Setup-v0.2.0.exe"", ""browser_download_url"": ""https://example.com/Handoff-Setup-v0.2.0.exe"", ""digest"": ""sha256:aabbcc""}
                ]
            }";

            var release = PluginUpdateClient.ParseLatestRelease(json);

            Assert.NotNull(release);
            Assert.Equal(new System.Version(0, 2, 0), release.Version);
        }

        [Fact]
        public void ParseLatestRelease_NoInstallerAsset_ReturnsNull()
        {
            var json = @"{
                ""tag_name"": ""v0.2.0"",
                ""assets"": [
                    {""name"": ""Handoff-v0.2.0.apk"", ""browser_download_url"": ""https://example.com/Handoff-v0.2.0.apk"", ""digest"": ""sha256:ddeeff""}
                ]
            }";

            Assert.Null(PluginUpdateClient.ParseLatestRelease(json));
        }

        [Fact]
        public void ParseLatestRelease_InstallerAssetMissingDigest_ReturnsNull()
        {
            var json = @"{
                ""tag_name"": ""v0.2.0"",
                ""assets"": [
                    {""name"": ""Handoff-Setup-v0.2.0.exe"", ""browser_download_url"": ""https://example.com/Handoff-Setup-v0.2.0.exe""}
                ]
            }";

            Assert.Null(PluginUpdateClient.ParseLatestRelease(json));
        }

        [Fact]
        public void ParseLatestRelease_MalformedTag_ReturnsNull()
        {
            var json = @"{
                ""tag_name"": ""not-a-version"",
                ""assets"": [
                    {""name"": ""Handoff-Setup-v0.2.0.exe"", ""browser_download_url"": ""https://example.com/Handoff-Setup-v0.2.0.exe"", ""digest"": ""sha256:aabbcc""}
                ]
            }";

            Assert.Null(PluginUpdateClient.ParseLatestRelease(json));
        }

        [Fact]
        public void ParseLatestRelease_NoAssetsArray_ReturnsNull()
        {
            var json = @"{""tag_name"": ""v0.2.0""}";

            Assert.Null(PluginUpdateClient.ParseLatestRelease(json));
        }
    }
}
