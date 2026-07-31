using System;
using System.IO;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class HandoffPairedClientStoreTests : IDisposable
    {
        private readonly string _configPath = PathJoin.Combine(Path.GetTempPath(), Guid.NewGuid() + ".json");

        public void Dispose()
        {
            if (File.Exists(_configPath)) File.Delete(_configPath);
        }

        [Fact]
        public void IssuedToken_IsValid()
        {
            var store = new HandoffPairedClientStore(configPath: _configPath);

            var token = store.IssueToken();

            Assert.True(store.IsTokenValid(token));
        }

        [Fact]
        public void UnknownToken_IsNotValid()
        {
            var store = new HandoffPairedClientStore(configPath: _configPath);
            store.IssueToken();

            Assert.False(store.IsTokenValid("some-token-nobody-issued"));
        }

        [Fact]
        public void NullOrEmptyToken_IsNotValid()
        {
            var store = new HandoffPairedClientStore(configPath: _configPath);

            Assert.False(store.IsTokenValid(null));
            Assert.False(store.IsTokenValid(""));
        }

        [Fact]
        public void MultipleIssuedTokens_AreAllValid()
        {
            var store = new HandoffPairedClientStore(configPath: _configPath);

            var first = store.IssueToken();
            var second = store.IssueToken();

            Assert.True(store.IsTokenValid(first));
            Assert.True(store.IsTokenValid(second));
        }

        [Fact]
        public void TokensPersistAcrossInstances()
        {
            var first = new HandoffPairedClientStore(configPath: _configPath);
            var token = first.IssueToken();

            var second = new HandoffPairedClientStore(configPath: _configPath);

            Assert.True(second.IsTokenValid(token));
        }

        [Fact]
        public void IssueToken_SameDeviceIdAsExisting_InvalidatesThePreviousToken()
        {
            var store = new HandoffPairedClientStore(configPath: _configPath);

            var first = store.IssueToken(deviceId: "same-tablet");
            var second = store.IssueToken(deviceId: "same-tablet");

            // The re-pair from the same physical device replaces its old entry outright --
            // doesn't just add alongside it.
            Assert.False(store.IsTokenValid(first));
            Assert.True(store.IsTokenValid(second));
        }

        [Fact]
        public void IssueToken_DifferentDeviceIds_BothRemainValid()
        {
            var store = new HandoffPairedClientStore(configPath: _configPath);

            var tablet = store.IssueToken(deviceId: "tablet");
            var phone = store.IssueToken(deviceId: "phone");

            Assert.True(store.IsTokenValid(tablet));
            Assert.True(store.IsTokenValid(phone));
        }

        [Fact]
        public void IssueToken_NullDeviceId_NeverReplacesAnything()
        {
            var store = new HandoffPairedClientStore(configPath: _configPath);

            var first = store.IssueToken();
            var second = store.IssueToken();

            Assert.True(store.IsTokenValid(first));
            Assert.True(store.IsTokenValid(second));
        }
    }
}
