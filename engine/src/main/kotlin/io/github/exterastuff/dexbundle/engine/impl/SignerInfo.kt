package io.github.exterastuff.dexbundle.engine.impl

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Date
import java.util.jar.JarEntry
import java.util.jar.JarFile
import javax.security.auth.x500.X500Principal

data class SignerInfo(
    val fingerprint: String,

    val isExpired: Boolean,
    val isRevoked: Boolean,

    val signedAt: Date?,

    val chain: List<X509Certificate>,
    val tsaChain: List<X509Certificate>,
) {
    companion object {
        private val SIG_SUFFIXES = listOf(".SF", ".DSA", ".RSA", ".EC")

        private val CN_REGEX = Regex("""(?:^|,)CN=((?:\\.|[^,\\])*)""")

        private val ESCAPED_CHAR_REGEX = Regex("""\\(.)""")

        /** CN in dName. */
        private fun X500Principal.commonName(): String? =
            CN_REGEX.find(name)
                ?.groupValues
                ?.get(1)
                ?.replace(ESCAPED_CHAR_REGEX, "$1")
                ?.takeIf(String::isNotBlank)

        /**
         * SHA-256 отпечатки сертификатов, подпись которыми считается доверенной.
         * Блять, я слишком плохо знаю английский, чтобы такое сформулировать именно на нём.
         */
        private val TRUSTED_FINGERPRINTS = setOf(
            "E61594D19F9F3B2C7FD3E7ECBB92DC80D0435E32CE7D8D1D79D273F0DAC57E51"
        )

        private fun JarEntry.isSignatureRelated(): Boolean {
            val n = name.uppercase()

            if (!n.startsWith("META-INF/"))
                return false

            val tail = n.removePrefix("META-INF/")

            // forbid nesting
            if (tail.contains('/'))
                return false

            return tail == "MANIFEST.MF"
                    || tail.startsWith("SIG-")
                    || SIG_SUFFIXES.any(tail::endsWith)
        }

        fun of(jar: JarFile): LinkedHashMap<String, SignerInfo>? {
            val entries = jar.entries().toList()

            val hasSignatureBlock = entries.any { e ->
                e.isSignatureRelated() && SIG_SUFFIXES
                    .any { e.name.uppercase().endsWith(it) }
            }

            if (!hasSignatureBlock)
                return null

            val buf = ByteArray(16 * 1024)
            val signers = LinkedHashMap<String, SignerInfo>()
            var payloadEntries = 0

            for (e in entries) {
                jar.getInputStream(e)
                    .use { ins -> while (ins.read(buf) != -1) continue }

                if (e.isDirectory || e.isSignatureRelated())
                    continue

                payloadEntries++

                val cs = e.codeSigners
                    ?: throw SecurityException("Unsigned entry ${e.name}")

                for (signer in cs) {
                    val chain = signer
                        .signerCertPath
                        .certificates
                        .map { it as X509Certificate }

                    val ts = signer.timestamp

                    val fp = MessageDigest.getInstance("SHA-256")
                        .digest(chain.first().encoded)
                        .joinToString("") { "%02X".format(it) }

                    signers.getOrPut(fp) {
                        SignerInfo(
                            fingerprint = fp,
                            isExpired = chain.first().notAfter < Date(),
                            isRevoked = false,
                            signedAt = ts?.timestamp,
                            chain = chain,
                            tsaChain = ts
                                ?.signerCertPath
                                ?.certificates
                                ?.map { it as X509Certificate }
                                ?: emptyList()
                        )
                    }
                }
            }

            return signers
        }
    }

    val leaf: X509Certificate get() = chain.first()

    /** Who issued the signing certificate. */
    val issuer: String
        get() = leaf.issuerX500Principal.let { it.commonName() ?: it.name }

    val isTrusted: Boolean get() = fingerprint in TRUSTED_FINGERPRINTS

    /** Plugin was signed before certificate expiration. */
    val isTsaValid: Boolean
        get() = signedAt
            ?.let { it >= leaf.notBefore && it <= leaf.notAfter }
            ?: false

    /**
     * Certificate isn't revoked, expired or TSA can guarantee that
     * plugin was signed before expiration.
     */
    val isValid: Boolean get() = !isRevoked && (!isExpired || isTsaValid)
}
