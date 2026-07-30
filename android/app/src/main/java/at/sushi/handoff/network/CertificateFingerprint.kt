package at.sushi.handoff.network

import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/** SHA-256 of a certificate's public key, formatted the same way as the plugin side
 *  (HandoffCertificateStore.ComputeFingerprint, issue #15) -- uppercase, colon-separated hex --
 *  so a fingerprint typed/shown on either side compares directly against the other. */
fun sha256Fingerprint(cert: Certificate): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
    return digest.joinToString(":") { "%02X".format(it) }
}

/** Pulls the CN out of the presented cert's subject DN -- the plugin bakes its Windows machine
 *  name in as the Subject CN at generation time (HandoffCertificateStore) specifically so the
 *  trust dialog can show a recognizable hostname without any extra discovery-protocol field.
 *  Returns null if the cert has no CN or isn't parseable (e.g. not actually X.509). */
fun subjectCommonName(cert: Certificate): String? {
    val x509 = cert as? X509Certificate ?: return null
    return parseCommonName(x509.subjectX500Principal)
}

private fun parseCommonName(principal: X500Principal): String? {
    // RFC 2253 canonical form starts with the most-specific RDN, which for a plugin-generated
    // cert (subject is just "CN=<machine name>") is always CN -- no need for a full LDAP-name
    // parser for a single-RDN subject.
    val name = principal.getName(X500Principal.RFC2253)
    return name.split(",")
        .map { it.trim() }
        .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
        ?.substringAfter("=")
}
