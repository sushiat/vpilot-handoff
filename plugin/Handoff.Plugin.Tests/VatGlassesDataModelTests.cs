using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class VatGlassesDataModelTests
    {
        private const string EmptyRegionJson = @"{""airports"":{},""airspace"":[],""positions"":{}}";

        [Fact]
        public async Task SyncAsync_ShaUnchanged_SkipsFullSyncAndReportsUpToDate()
        {
            var cacheDir = CreateTempCacheDir();
            try
            {
                Directory.CreateDirectory(cacheDir);
                File.WriteAllText(Path.Combine(cacheDir, "_commit.sha"), "abc123");

                var progress = new OperationProgressModel();
                var events = new List<OperationProgressEventArgs>();
                progress.Changed += (s, e) => events.Add(e);

                var listFilesCalled = false;
                var model = new VatGlassesDataModel(
                    progress,
                    cacheDirectory: cacheDir,
                    fetchLatestSha: () => Task.FromResult("abc123"),
                    listFiles: () =>
                    {
                        listFilesCalled = true;
                        return Task.FromResult<IReadOnlyList<VatGlassesDataFile>>(new List<VatGlassesDataFile>());
                    },
                    fetchFile: _ => Task.FromResult<string>(null));

                await model.SyncAsync();

                Assert.False(listFilesCalled);
                Assert.Contains(events, e => e.Finished && e.Status == "VatGlasses data up to date");
            }
            finally
            {
                if (Directory.Exists(cacheDir)) Directory.Delete(cacheDir, recursive: true);
            }
        }

        [Fact]
        public async Task SyncAsync_ShaChanged_FetchesAllFilesReportsPerFileProgressAndCachesToDisk()
        {
            var cacheDir = CreateTempCacheDir();
            try
            {
                var progress = new OperationProgressModel();
                var reportedStatuses = new List<string>();
                progress.Changed += (s, e) => reportedStatuses.Add(e.Status);

                var files = new List<VatGlassesDataFile>
                {
                    new VatGlassesDataFile("lo.json", "https://example/lo.json"),
                    new VatGlassesDataFile("ld.json", "https://example/ld.json")
                };

                var model = new VatGlassesDataModel(
                    progress,
                    cacheDirectory: cacheDir,
                    fetchLatestSha: () => Task.FromResult("sha-new"),
                    listFiles: () => Task.FromResult<IReadOnlyList<VatGlassesDataFile>>(files),
                    fetchFile: url => Task.FromResult(EmptyRegionJson));

                var changed = false;
                model.Changed += (s, e) => changed = true;

                await model.SyncAsync();

                Assert.True(changed);
                Assert.Equal(2, model.Regions.Count);
                Assert.Contains("Updating VatGlasses file 1/2", reportedStatuses);
                Assert.Contains("Updating VatGlasses file 2/2", reportedStatuses);
                Assert.True(File.Exists(Path.Combine(cacheDir, "_commit.sha")));
                Assert.Equal("sha-new", File.ReadAllText(Path.Combine(cacheDir, "_commit.sha")));
                Assert.True(File.Exists(Path.Combine(cacheDir, "lo.json")));
                Assert.True(File.Exists(Path.Combine(cacheDir, "ld.json")));
            }
            finally
            {
                if (Directory.Exists(cacheDir)) Directory.Delete(cacheDir, recursive: true);
            }
        }

        [Fact]
        public async Task SyncAsync_ShaFetchFails_ReportsFailureWithoutTouchingDiskCache()
        {
            var cacheDir = CreateTempCacheDir();
            try
            {
                var progress = new OperationProgressModel();
                OperationProgressEventArgs lastEvent = null;
                progress.Changed += (s, e) => lastEvent = e;

                var model = new VatGlassesDataModel(
                    progress,
                    cacheDirectory: cacheDir,
                    fetchLatestSha: () => Task.FromResult<string>(null),
                    listFiles: () => throw new InvalidOperationException("listFiles should not be called when the SHA check fails"),
                    fetchFile: _ => throw new InvalidOperationException("fetchFile should not be called when the SHA check fails"));

                await model.SyncAsync();

                Assert.True(lastEvent.Finished);
                Assert.Contains("update check failed", lastEvent.Status);
                Assert.False(Directory.Exists(cacheDir));
            }
            finally
            {
                if (Directory.Exists(cacheDir)) Directory.Delete(cacheDir, recursive: true);
            }
        }

        [Fact]
        public async Task SyncAsync_FileFetchFails_PreservesExistingDiskCacheAndLoadedRegions()
        {
            var cacheDir = CreateTempCacheDir();
            try
            {
                Directory.CreateDirectory(cacheDir);
                File.WriteAllText(Path.Combine(cacheDir, "_commit.sha"), "old-sha");
                File.WriteAllText(Path.Combine(cacheDir, "lo.json"), EmptyRegionJson);

                var progress = new OperationProgressModel();
                OperationProgressEventArgs lastEvent = null;
                progress.Changed += (s, e) => lastEvent = e;

                // Constructed after the cache directory is pre-populated, so LoadFromDiskCache
                // (run at construction) picks up the existing entry.
                var model = new VatGlassesDataModel(
                    progress,
                    cacheDirectory: cacheDir,
                    fetchLatestSha: () => Task.FromResult("new-sha"),
                    listFiles: () => Task.FromResult<IReadOnlyList<VatGlassesDataFile>>(
                        new List<VatGlassesDataFile> { new VatGlassesDataFile("ld.json", "https://example/ld.json") }),
                    fetchFile: _ => Task.FromResult<string>(null));

                Assert.Single(model.Regions);

                await model.SyncAsync();

                Assert.Single(model.Regions);
                Assert.Equal("old-sha", File.ReadAllText(Path.Combine(cacheDir, "_commit.sha")));
                Assert.True(lastEvent.Finished);
                Assert.Contains("incomplete (0/1 files)", lastEvent.Status);
            }
            finally
            {
                if (Directory.Exists(cacheDir)) Directory.Delete(cacheDir, recursive: true);
            }
        }

        [Fact]
        public async Task SyncAsync_OneFileFailsAmongSeveral_KeepsSucceededFilesButDoesNotWriteShaMarker()
        {
            var cacheDir = CreateTempCacheDir();
            try
            {
                var progress = new OperationProgressModel();
                OperationProgressEventArgs lastEvent = null;
                progress.Changed += (s, e) => lastEvent = e;

                var files = new List<VatGlassesDataFile>
                {
                    new VatGlassesDataFile("lo.json", "https://example/lo.json"),
                    new VatGlassesDataFile("broken.json", "https://example/broken.json"),
                    new VatGlassesDataFile("ld.json", "https://example/ld.json")
                };

                var model = new VatGlassesDataModel(
                    progress,
                    cacheDirectory: cacheDir,
                    fetchLatestSha: () => Task.FromResult("sha-new"),
                    listFiles: () => Task.FromResult<IReadOnlyList<VatGlassesDataFile>>(files),
                    fetchFile: url => Task.FromResult(url.Contains("broken") ? null : EmptyRegionJson));

                await model.SyncAsync();

                // The one file that failed to fetch never lands on disk or in Regions, but it
                // doesn't take the other two -- fetched independently -- down with it.
                Assert.Equal(2, model.Regions.Count);
                Assert.True(model.Regions.ContainsKey("lo.json"));
                Assert.True(model.Regions.ContainsKey("ld.json"));
                Assert.False(model.Regions.ContainsKey("broken.json"));
                Assert.True(File.Exists(Path.Combine(cacheDir, "lo.json")));
                Assert.True(File.Exists(Path.Combine(cacheDir, "ld.json")));
                Assert.False(File.Exists(Path.Combine(cacheDir, "broken.json")));

                // Marker withheld -- the next sync attempt must see this as still out of date and
                // retry the full list (including re-fetching lo.json/ld.json, which is fine).
                Assert.False(File.Exists(Path.Combine(cacheDir, "_commit.sha")));
                Assert.True(lastEvent.Finished);
                Assert.Contains("incomplete (2/3 files)", lastEvent.Status);
            }
            finally
            {
                if (Directory.Exists(cacheDir)) Directory.Delete(cacheDir, recursive: true);
            }
        }

        [Fact]
        public void Constructor_OneCorruptCachedFileAmongSeveral_LoadsTheRest()
        {
            var cacheDir = CreateTempCacheDir();
            try
            {
                Directory.CreateDirectory(cacheDir);
                File.WriteAllText(Path.Combine(cacheDir, "lo.json"), EmptyRegionJson);
                File.WriteAllText(Path.Combine(cacheDir, "broken.json"), "{ not valid json");
                File.WriteAllText(Path.Combine(cacheDir, "ld.json"), EmptyRegionJson);

                var progress = new OperationProgressModel();
                var model = new VatGlassesDataModel(progress, cacheDirectory: cacheDir);

                Assert.Equal(2, model.Regions.Count);
                Assert.True(model.Regions.ContainsKey("lo.json"));
                Assert.True(model.Regions.ContainsKey("ld.json"));
                Assert.False(model.Regions.ContainsKey("broken.json"));
            }
            finally
            {
                if (Directory.Exists(cacheDir)) Directory.Delete(cacheDir, recursive: true);
            }
        }

        private static string CreateTempCacheDir() =>
            Path.Combine(Path.GetTempPath(), "HandoffTests-VatGlasses-" + Guid.NewGuid());
    }
}
