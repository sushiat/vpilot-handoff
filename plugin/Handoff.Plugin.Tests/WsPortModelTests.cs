using System;
using System.IO;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class WsPortModelTests : IDisposable
    {
        private readonly string _configPath = PathJoin.Combine(Path.GetTempPath(), Guid.NewGuid() + ".json");

        public void Dispose()
        {
            if (File.Exists(_configPath)) File.Delete(_configPath);
        }

        [Fact]
        public void DefaultPort_BeforeAnySet_Is48765()
        {
            var model = new WsPortModel(configPath: _configPath);

            Assert.Equal(48765, model.CurrentPort);
        }

        [Fact]
        public void SetPort_PersistsAndReloadsFromFreshInstance()
        {
            var model = new WsPortModel(configPath: _configPath);

            model.SetPort(48901);

            Assert.Equal(48901, model.CurrentPort);

            var reloaded = new WsPortModel(configPath: _configPath);
            Assert.Equal(48901, reloaded.CurrentPort);
        }

        [Fact]
        public void SetPort_ToDifferentValue_RaisesChanged()
        {
            var model = new WsPortModel(configPath: _configPath);
            var raised = 0;
            model.Changed += (s, e) => raised++;

            model.SetPort(48901);

            Assert.Equal(1, raised);
        }

        [Fact]
        public void SetPort_ToSameValue_DoesNotRaiseChanged()
        {
            var model = new WsPortModel(configPath: _configPath);
            model.SetPort(48901);
            var raised = 0;
            model.Changed += (s, e) => raised++;

            model.SetPort(48901);

            Assert.Equal(0, raised);
        }

        [Theory]
        [InlineData(1023)]
        [InlineData(65536)]
        [InlineData(-1)]
        public void SetPort_OutOfRange_IsIgnored(int badPort)
        {
            var model = new WsPortModel(configPath: _configPath);
            var raised = 0;
            model.Changed += (s, e) => raised++;

            model.SetPort(badPort);

            Assert.Equal(48765, model.CurrentPort);
            Assert.Equal(0, raised);
        }
    }
}
