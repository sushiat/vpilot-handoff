using System;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class DebugSnapshotServiceTests : IDisposable
    {
        private readonly string _snapshotDirectory = PathJoin.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
        private readonly FakeBroker _broker = new FakeBroker();
        // DebugSnapshotService takes the concrete RadioStateModel (it calls BuildDebugSnapshot(),
        // not part of IRadioStateModel) -- an untouched instance never started against a real
        // Handoff.RadioHost, same "no telemetry, defaults only" shape as never connecting.
        private readonly RadioStateModel _radio = new RadioStateModel();

        public void Dispose()
        {
            if (Directory.Exists(_snapshotDirectory)) Directory.Delete(_snapshotDirectory, recursive: true);
        }

        private DebugSnapshotService CreateService(out ControllerRankingModel ranking)
        {
            var chat = new ChatModel(_broker);
            var controllerState = new HandoffControllerStateModel(_broker, chat);
            var vatsimFeed = new VatsimDataFeedModel();
            var pilotSession = new PilotSessionModel();
            var operationProgress = new OperationProgressModel();
            var flightPlan = new FlightPlanModel(operationProgress, fetch: (u, n) => Task.FromResult(Plugin.FlightPlan.Empty),
                configPath: PathJoin.Combine(Path.GetTempPath(), Guid.NewGuid() + ".json"));
            var vatGlasses = new VatGlassesDataModel(operationProgress, cacheDirectory: PathJoin.Combine(Path.GetTempPath(), Guid.NewGuid().ToString()));
            var vatSpy = new VatSpyDataModel(operationProgress, cacheDirectory: PathJoin.Combine(Path.GetTempPath(), Guid.NewGuid().ToString()));
            var pairedClients = new HandoffPairedClientStore(configPath: PathJoin.Combine(Path.GetTempPath(), Guid.NewGuid() + ".json"));
            var pairingSession = new HandoffPairingSession(new FakePairingDisplay());

            ranking = new ControllerRankingModel(controllerState, _radio, flightPlan, vatsimFeed, pilotSession, vatGlasses, vatSpy);

            return new DebugSnapshotService(
                ranking, _radio, flightPlan, vatsimFeed, controllerState, vatGlasses, vatSpy, pilotSession,
                operationProgress, pairedClients, pairingSession,
                authenticatedSocketCountProvider: () => 1, pluginVersion: "0.1.0",
                snapshotDirectory: _snapshotDirectory);
        }

        [Fact]
        public void SaveSnapshot_WritesJsonFileWithMetaAndSubsystemSections()
        {
            var service = CreateService(out var ranking);
            ranking.SetDebugMode(true);

            var path = service.SaveSnapshot("snap-1", "1.4.0");

            Assert.True(File.Exists(path));
            var json = JObject.Parse(File.ReadAllText(path));
            Assert.Equal("snap-1", (string)json["snapshotId"]);
            Assert.Equal("1.4.0", (string)json["appVersion"]);
            Assert.Equal("0.1.0", (string)json["pluginVersion"]);
            Assert.NotNull(json["ranking"]);
            Assert.NotNull(json["radio"]);
            Assert.NotNull(json["vatsimFeed"]);
            Assert.NotNull(json["flightPlan"]);
            Assert.NotNull(json["vatGlasses"]);
            Assert.NotNull(json["vatSpy"]);
            Assert.NotNull(json["pairing"]);
            Assert.NotNull(json["controllerState"]);
        }

        [Fact]
        public void TrySaveScreenshot_UnknownSnapshotId_ReturnsFalse()
        {
            var service = CreateService(out _);

            var saved = service.TrySaveScreenshot("never-saved", "aGVsbG8=");

            Assert.False(saved);
        }

        [Fact]
        public void TrySaveScreenshot_KnownSnapshotId_WritesPngAlongsideJson()
        {
            var service = CreateService(out var ranking);
            ranking.SetDebugMode(true);
            var jsonPath = service.SaveSnapshot("snap-2", "1.4.0");
            var pngPath = Path.ChangeExtension(jsonPath, ".png");

            var saved = service.TrySaveScreenshot("snap-2", Convert.ToBase64String(new byte[] { 1, 2, 3 }));

            Assert.True(saved);
            Assert.True(File.Exists(pngPath));
        }

        [Fact]
        public void RenameSnapshot_UnknownSnapshotId_ReturnsFalseWithError()
        {
            var service = CreateService(out _);

            var (success, error) = service.RenameSnapshot("never-saved", "some name");

            Assert.False(success);
            Assert.NotNull(error);
        }

        [Fact]
        public void RenameSnapshot_KnownSnapshotId_RenamesJsonAndPngAndPatchesNameField()
        {
            var service = CreateService(out var ranking);
            ranking.SetDebugMode(true);
            var jsonPath = service.SaveSnapshot("snap-3", "1.4.0");
            var pngPath = Path.ChangeExtension(jsonPath, ".png");
            service.TrySaveScreenshot("snap-3", Convert.ToBase64String(new byte[] { 1, 2, 3 }));

            var (success, error) = service.RenameSnapshot("snap-3", "sequencing lag near KONAN");

            Assert.True(success);
            Assert.Null(error);
            Assert.False(File.Exists(jsonPath));
            Assert.False(File.Exists(pngPath));
            var renamedJsonPath = Directory.GetFiles(_snapshotDirectory, "*.json").Single();
            Assert.Contains("sequencing lag near KONAN", renamedJsonPath);
            var renamedPngPath = Path.ChangeExtension(renamedJsonPath, ".png");
            Assert.True(File.Exists(renamedPngPath));
            var json = JObject.Parse(File.ReadAllText(renamedJsonPath));
            Assert.Equal("sequencing lag near KONAN", (string)json["name"]);
        }

        [Fact]
        public void RenameSnapshot_TruncatesLongNameInFileNameButKeepsFullNameInJson()
        {
            var service = CreateService(out var ranking);
            ranking.SetDebugMode(true);
            service.SaveSnapshot("snap-4", "1.4.0");
            var longName = new string('x', 100);

            var (success, _) = service.RenameSnapshot("snap-4", longName);

            Assert.True(success);
            var renamedJsonPath = Directory.GetFiles(_snapshotDirectory, "*.json").Single();
            Assert.True(Path.GetFileNameWithoutExtension(renamedJsonPath).Length < longName.Length);
            var json = JObject.Parse(File.ReadAllText(renamedJsonPath));
            Assert.Equal(longName, (string)json["name"]);
        }
    }
}
