using System;
using System.IO;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using Org.BouncyCastle.Asn1.X509;
using Org.BouncyCastle.Crypto;
using Org.BouncyCastle.Crypto.Generators;
using Org.BouncyCastle.Crypto.Operators;
using Org.BouncyCastle.Crypto.Prng;
using Org.BouncyCastle.Math;
using Org.BouncyCastle.Pkcs;
using Org.BouncyCastle.Security;
using Org.BouncyCastle.X509;

namespace Handoff.Plugin
{
    /// <summary>
    /// Generates (or loads a cached) self-signed TLS certificate for HandoffWebSocketServer's
    /// wss:// listener -- see issue #15. .NET Framework 4.8 has no reliable built-in self-signed
    /// generation API (CertificateRequest is a newer-.NET addition), hence BouncyCastle.
    ///
    /// The certificate's Subject CN is the Windows machine name, baked in at generation time so
    /// the Android app's trust-on-first-use dialog can show a recognizable hostname without any
    /// extra field in the discovery protocol. A later OS rename won't retroactively change it --
    /// same recovery path as a genuinely rotated certificate: the pilot just re-trusts once.
    ///
    /// Cached alongside FlightPlanModel's simbrief.json, same %LOCALAPPDATA%\Handoff\ directory,
    /// so the identity (and therefore the fingerprint the Android app has pinned) survives
    /// plugin/vPilot restarts instead of forcing a re-trust every session.
    /// </summary>
    public sealed class HandoffCertificateStore
    {
        // Not a real secret -- PKCS12 requires *some* password to satisfy the container format,
        // and this cert is self-signed for a LAN-local pairing, not protecting anything the
        // password itself could leak.
        private const string PfxPassword = "handoff-local-cert";

        private static readonly string Default_configPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Handoff", "cert.pfx");

        private readonly Action<string> _logDebug;
        private readonly string _configPath;

        public X509Certificate2 Certificate { get; }

        /// <summary>SHA-256 of the certificate's public key, formatted as uppercase colon-hex
        /// (e.g. "AB:12:CD:34:...") -- matches the format shown in the Android trust dialog and
        /// sent in HandoffDiscoveryListener's reply.</summary>
        public string FingerprintHex { get; }

        /// <summary>Loads (or generates, on first run) this machine's self-signed TLS certificate.</summary>
        /// <param name="configPath">Overridable only for tests, same reasoning as
        /// FlightPlanModel's configPath.</param>
        public HandoffCertificateStore(Action<string> logDebug = null, string configPath = null)
        {
            _logDebug = logDebug;
            _configPath = configPath ?? Default_configPath;
            Certificate = LoadOrGenerate();
            FingerprintHex = ComputeFingerprint(Certificate);
        }

        private X509Certificate2 LoadOrGenerate()
        {
            if (File.Exists(_configPath))
            {
                try
                {
                    return new X509Certificate2(_configPath, PfxPassword, X509KeyStorageFlags.Exportable);
                }
                catch (Exception ex)
                {
                    Log("Failed to load cached certificate, regenerating: " + ex.Message);
                }
            }

            var certificate = Generate();
            Save(certificate);
            return certificate;
        }

        private static X509Certificate2 Generate()
        {
            var random = new SecureRandom(new CryptoApiRandomGenerator());

            var keyGenerator = new RsaKeyPairGenerator();
            keyGenerator.Init(new KeyGenerationParameters(random, 2048));
            var keyPair = keyGenerator.GenerateKeyPair();

            var subject = new X509Name("CN=" + Environment.MachineName);

            var serialNumberBytes = new byte[16];
            random.NextBytes(serialNumberBytes);
            serialNumberBytes[0] &= 0x7F; // keep the serial number positive

            var generator = new X509V3CertificateGenerator();
            generator.SetSerialNumber(new BigInteger(1, serialNumberBytes));
            generator.SetSubjectDN(subject);
            generator.SetIssuerDN(subject);
            generator.SetNotBefore(DateTime.UtcNow.AddDays(-1));
            generator.SetNotAfter(DateTime.UtcNow.AddYears(10));
            generator.SetPublicKey(keyPair.Public);

            var signatureFactory = new Asn1SignatureFactory("SHA256WITHRSA", keyPair.Private, random);
            var bcCertificate = generator.Generate(signatureFactory);

            var store = new Pkcs12StoreBuilder().Build();
            var certificateEntry = new X509CertificateEntry(bcCertificate);
            store.SetCertificateEntry(Environment.MachineName, certificateEntry);
            store.SetKeyEntry(Environment.MachineName, new AsymmetricKeyEntry(keyPair.Private), new[] { certificateEntry });

            using (var pfxStream = new MemoryStream())
            {
                store.Save(pfxStream, PfxPassword.ToCharArray(), random);
                return new X509Certificate2(pfxStream.ToArray(), PfxPassword, X509KeyStorageFlags.Exportable);
            }
        }

        private void Save(X509Certificate2 certificate)
        {
            try
            {
                var directory = Path.GetDirectoryName(_configPath);
                if (directory != null) Directory.CreateDirectory(directory);
                File.WriteAllBytes(_configPath, certificate.Export(X509ContentType.Pfx, PfxPassword));
            }
            catch (Exception ex)
            {
                Log("Failed to persist certificate: " + ex.Message);
            }
        }

        private static string ComputeFingerprint(X509Certificate2 certificate)
        {
            using (var sha256 = SHA256.Create())
            {
                var hash = sha256.ComputeHash(certificate.GetPublicKey());
                return string.Join(":", Array.ConvertAll(hash, b => b.ToString("X2")));
            }
        }

        private void Log(string message)
        {
            var line = "HandoffCertificateStore: " + message;
            System.Diagnostics.Debug.WriteLine(line);
            _logDebug?.Invoke(line);
        }
    }
}
