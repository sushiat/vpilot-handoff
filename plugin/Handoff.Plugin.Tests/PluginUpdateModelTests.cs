using System.Collections.Generic;
using System.IO;
using System.Reflection;
using Xunit;

namespace Handoff.Plugin.Tests
{
    /// <summary>Records ShowUpdated calls instead of touching WinForms -- see
    /// IHandoffUpdateAppliedDisplay's doc comment, same pattern as FakePairingDisplay.</summary>
    public class FakeUpdateAppliedDisplay : IHandoffUpdateAppliedDisplay
    {
        public List<string> ShownVersions { get; } = new List<string>();
        public void ShowUpdated(string version) => ShownVersions.Add(version);
    }

    /// <summary>The update prompt is never exercised by CheckMarker (only by the download path,
    /// which Process.Starts a real installer and so isn't unit-tested); this satisfies the ctor.
    /// </summary>
    public class FakeUpdatePromptDisplay : IHandoffUpdatePromptDisplay
    {
        public bool AskToInstall(System.Version version) => false;
    }

    public class PluginUpdateModelTests
    {
        // CheckMarker reads the marker from next to the running Handoff.Plugin assembly
        // (Assembly.GetExecutingAssembly().Location); under test that's the test output dir, where
        // Handoff.Plugin.dll is copied. Compute the same path so the test writes where it reads.
        private static string MarkerPath =>
            Path.Combine(
                Path.GetDirectoryName(typeof(PluginUpdateModel).Assembly.Location),
                "update-applied.json");

        private static PluginUpdateModel BuildModel(FakeUpdateAppliedDisplay applied) =>
            new PluginUpdateModel(
                new OperationProgressModel(),
                new FakeUpdatePromptDisplay(),
                applied,
                _ => { });

        [Fact]
        public void CheckMarker_WithMarker_ShowsUpdatedOnceAndDeletesMarker()
        {
            File.WriteAllText(MarkerPath, "{\"version\":\"0.2.0\"}");
            var applied = new FakeUpdateAppliedDisplay();
            try
            {
                BuildModel(applied).CheckMarker();

                Assert.Single(applied.ShownVersions);
                Assert.Equal("0.2.0", applied.ShownVersions[0]);
                Assert.False(File.Exists(MarkerPath));
            }
            finally
            {
                if (File.Exists(MarkerPath)) File.Delete(MarkerPath);
            }
        }

        [Fact]
        public void CheckMarker_WithoutMarker_ShowsNothing()
        {
            if (File.Exists(MarkerPath)) File.Delete(MarkerPath);
            var applied = new FakeUpdateAppliedDisplay();

            BuildModel(applied).CheckMarker();

            Assert.Empty(applied.ShownVersions);
        }
    }
}
