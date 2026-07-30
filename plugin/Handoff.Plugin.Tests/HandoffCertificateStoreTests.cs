using System;
using System.IO;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class HandoffCertificateStoreTests : IDisposable
    {
        private readonly string _configPath = Path.Combine(Path.GetTempPath(), Guid.NewGuid() + ".pfx");

        public void Dispose()
        {
            if (File.Exists(_configPath)) File.Delete(_configPath);
        }

        [Fact]
        public void FirstConstruction_GeneratesAndCachesCertificate()
        {
            Assert.False(File.Exists(_configPath));

            var store = new HandoffCertificateStore(configPath: _configPath);

            Assert.True(File.Exists(_configPath));
            Assert.NotNull(store.Certificate);
            Assert.True(store.Certificate.HasPrivateKey);
            Assert.Equal("CN=" + Environment.MachineName, store.Certificate.Subject);
        }

        [Fact]
        public void SecondConstruction_ReloadsSameCertificateAndFingerprint()
        {
            var first = new HandoffCertificateStore(configPath: _configPath);
            var second = new HandoffCertificateStore(configPath: _configPath);

            Assert.Equal(first.Certificate.Thumbprint, second.Certificate.Thumbprint);
            Assert.Equal(first.FingerprintHex, second.FingerprintHex);
        }

        [Fact]
        public void FingerprintHex_IsUppercaseColonSeparatedSha256()
        {
            var store = new HandoffCertificateStore(configPath: _configPath);

            // SHA-256 -> 32 bytes -> 32 two-char groups joined by colons.
            var parts = store.FingerprintHex.Split(':');
            Assert.Equal(32, parts.Length);
            foreach (var part in parts)
            {
                Assert.Equal(2, part.Length);
                Assert.Equal(part.ToUpperInvariant(), part);
                Assert.True(byte.TryParse(part, System.Globalization.NumberStyles.HexNumber, null, out _));
            }
        }
    }
}
