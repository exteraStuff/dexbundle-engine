package io.github.exterastuff.dexbundle.engine

import io.github.exterastuff.dexbundle.engine.impl.SignerInfo
import io.github.exterastuff.dexbundle.engine.ui.PluginSignatureState
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class SignerTest {
    private val trusted = "551895F5E515D5326F7D3761A4F61BCF924B4367EC7BB22BFED01793625A8663"
    private val revoked = "E61594D19F9F3B2C7FD3E7ECBB92DC80D0435E32CE7D8D1D79D273F0DAC57E51"

    private fun signer(
        fp: String = "other",
        start: Long = 0,
        end: Long = Long.MAX_VALUE,
        signed: Long? = null,
        issuer: String = "CN=Test,O=Example",
    ): SignerInfo {
        val cert = mock(X509Certificate::class.java)

        `when`(cert.notBefore).thenReturn(Date(start))
        `when`(cert.notAfter).thenReturn(Date(end))
        `when`(cert.issuerX500Principal).thenReturn(X500Principal(issuer))

        return SignerInfo(fp, signed?.let(::Date), listOf(cert), emptyList())
    }

    @Test
    fun certificateValidityAndTimestampBoundaries() {
        assertTrue(signer().isValid)
        assertFalse(signer().isTsaValid)
        assertFalse(signer(end = 100).isValid)
        assertFalse(signer(start = Long.MAX_VALUE).isValid)

        for (time in listOf(10L, 50L, 100L)) {
            val s = signer(start = 10, end = 100, signed = time)

            assertTrue(s.isExpired)
            assertTrue(s.isTsaValid)
            assertTrue(s.isValid)
        }

        for (time in listOf(9L, 101L)) {
            assertFalse(signer(start = 10, end = 100, signed = time).isValid)
        }

        assertTrue(signer(trusted).isTrusted)
        assertFalse(signer().isTrusted)
        assertTrue(signer(revoked).isRevoked)
        assertTrue(signer(revoked, signed = 0).isRevoked)
        assertFalse(signer(revoked, signed = -1).isRevoked)
        assertFalse(signer(revoked).isValid)
    }

    @Test
    fun issuerCommonNameEscapesAndFallback() {
        assertEquals("Test", signer().issuer)
        assertEquals("Test, Inc", signer(issuer = "CN=Test\\, Inc,O=Example").issuer)
        assertEquals("O=Example", signer(issuer = "O=Example").issuer)
    }

    @Test
    fun signatureStatePrecedenceAndInstallBlocking() {
        fun state(vararg signers: SignerInfo, installed: Map<String, SignerInfo>? = null) =
            PluginSignatureState.of(signers.associateBy { it.fingerprint }, installed)

        val old = mapOf("old" to signer("old"))

        assertEquals(PluginSignatureState.MISSING, PluginSignatureState.of(null, null))
        assertEquals(PluginSignatureState.MISSING, state())
        assertEquals(PluginSignatureState.UNTRUSTED, state(signer()))
        assertEquals(PluginSignatureState.TRUSTED, state(signer(), signer(trusted)))
        assertEquals(PluginSignatureState.EXPIRED, state(signer(end = 1), signer(trusted)))
        assertEquals(PluginSignatureState.REVOKED, state(signer(end = 1), signer(revoked)))
        assertEquals(PluginSignatureState.MISMATCH, state(signer(revoked), installed = old))
        assertEquals(PluginSignatureState.MISMATCH, state(installed = old))
        assertEquals(
            PluginSignatureState.UNTRUSTED,
            state(signer("old"), signer("new"), installed = old),
        )

        for (s in PluginSignatureState.entries) {
            assertEquals(
                s in setOf(PluginSignatureState.MISMATCH, PluginSignatureState.REVOKED),
                s.blocksInstall,
            )
        }
    }
}
