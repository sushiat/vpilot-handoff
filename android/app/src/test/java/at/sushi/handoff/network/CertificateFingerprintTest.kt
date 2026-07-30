package at.sushi.handoff.network

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CertificateFingerprintTest {

    // A throwaway self-signed test certificate (openssl req -x509 ... -subj "/CN=TEST-MACHINE"),
    // not tied to any real plugin instance -- just needs a known public key + Subject CN to
    // check the format/parsing logic against.
    private val testCertificateBase64 =
        "MIIDDzCCAfegAwIBAgIUOPJMeRuV2W9Y9lxxTjEYF0Sltn4wDQYJKoZIhvcNAQELBQAwFzEVMBMGA1UEAwwMVEVTVC1NQUNISU5FMB4XDTI2MDcyOTE4Mzk1M1oXDTI3MDcyOTE4Mzk1M1owFzEVMBMGA1UEAwwMVEVTVC1NQUNISU5FMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA2VCStlln3XhKYGMVwDw9BnSweayQP1F8YPcjlvz7tOVPZsZtDV0yJXah0kX0yw9OZCAz5Pwss9XOC0ilshpQ2IEgBsPDEqBNF3PJChIUiHOUKlc8D52g7a6YzC7mfwH936U8OJIbHG8/GcgjA1wGhV/n00IYr6Lx5raWKs4uZ8JnB4NrYtyuDYBzQhPbMxeiE9V5M22CbuIgODIuOgklfuHUuUfEOZIz8DkhZk8hFe/8xemi1Nf7bRcu5cqJQFbYeVLhw82DesR+btv2X/CvC2uFgIJQ0fujoRi0oy2OCqsdjGbM6Yg9c/7KoUVGx2M1JtyvVGZUjSyTQcDQb/o8cwIDAQABo1MwUTAdBgNVHQ4EFgQUbP7OwAFkCEGo5jTVIUX/mGForOAwHwYDVR0jBBgwFoAUbP7OwAFkCEGo5jTVIUX/mGForOAwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEA1G/SUcH9lZ0OL5WOzbd7G6FNzwFeRdNFT6Xyp+9qCo3DP415aXWDPMYzAdukWr7b8pkENa2g/I+LZA9d9klYgvBs9f/tYjED2ZXYXpWXfJRvUe2UWfsZq3BwJgNcrXpUfWPxSwO7k1AvxhdCK10O7jRnrvARFH5o+4p2k1yZTepo+BnuEg6daobcJjW7aC748u3+757pnJTRUl7LCwxgPSRJONhP9dJLvCF5wF/hfcR8VmjnI+JGzIZoi56fNaL7fUB9EzRlVVP1/ELrhVRsA32GapHxJaNbi43Zwetc0I5SdnvSEsCk1Hw67Dq9Z52tCsiVhgWFsBfHQD4M+Onnqg=="

    // Computed independently via `openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER
    // | openssl dgst -sha256 -hex` against the same certificate.
    private val expectedFingerprint =
        "C1:09:A4:78:49:3A:BA:B2:9B:4E:78:60:74:8B:0D:19:DF:30:B0:1F:1F:E0:36:E8:91:6B:32:4B:17:B4:04:1E"

    private fun testCertificate(): X509Certificate {
        val bytes = Base64.getDecoder().decode(testCertificateBase64)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }

    @Test
    fun sha256Fingerprint_matchesKnownValue() {
        assertEquals(expectedFingerprint, sha256Fingerprint(testCertificate()))
    }

    @Test
    fun subjectCommonName_extractsCn() {
        assertEquals("TEST-MACHINE", subjectCommonName(testCertificate()))
    }

    @Test
    fun subjectCommonName_nonX509Certificate_returnsNull() {
        val notX509 = object : java.security.cert.Certificate("not-x509") {
            override fun getEncoded() = ByteArray(0)
            override fun verify(key: java.security.PublicKey?) = Unit
            override fun verify(key: java.security.PublicKey?, sigProvider: String?) = Unit
            override fun toString() = "fake"
            override fun getPublicKey(): java.security.PublicKey = testCertificate().publicKey
        }
        assertNull(subjectCommonName(notX509))
    }
}
