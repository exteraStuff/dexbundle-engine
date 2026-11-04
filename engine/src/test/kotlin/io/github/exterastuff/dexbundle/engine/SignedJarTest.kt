package io.github.exterastuff.dexbundle.engine

import io.github.exterastuff.dexbundle.engine.impl.SignerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/** Настоящая проверка подписи средствами JDK, локально, без TSA и сетевых запросов. */
class SignedJarTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun tool(name: String, vararg args: String) {
        val log = temp.newFile()

        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/$name").path, *args)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("$name timed out")
        }

        assertEquals(log.readText(), 0, process.exitValue())
    }

    @Test
    fun verifiesSignedJarRejectsTamperingAndPartialSigning() {
        val store = File(temp.root, "test.p12")

        tool(
            "keytool",
            "-genkeypair",
            "-alias",
            "test",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-dname",
            "CN=Unit Test",
            "-validity",
            "2",
            "-keystore",
            store.path,
            "-storepass",
            "test-password",
            "-noprompt",
        )

        val signed = temp.newFile("signed.jar")

        JarOutputStream(
            signed.outputStream(),
            Manifest().apply { mainAttributes.putValue("Manifest-Version", "1.0") },
        ).use {
            for (name in listOf("classes.dex", "resource.txt")) {
                it.putNextEntry(JarEntry(name))
                it.write("payload".toByteArray())
                it.closeEntry()
            }
        }

        tool(
            "jarsigner",
            "-keystore",
            store.path,
            "-storepass",
            "test-password",
            signed.path,
            "test",
        )

        JarFile(signed, true)
            .use {
                val signers = SignerInfo.of(it)!!

                assertEquals(1, signers.size)

                val signer = signers.values.single()

                assertEquals(64, signer.fingerprint.length)
                assertEquals("Unit Test", signer.issuer)
                assertEquals(signer.fingerprint, signers.keys.single())
                assertTrue(signer.isValid)
                assertFalse(signer.isTrusted)
                assertNull(signer.signedAt)
                assertTrue(signer.tsaChain.isEmpty())
                assertEquals(1, signer.chain.size)
            }

        for (tamper in listOf(false, true)) {
            val changed = temp.newFile()

            JarFile(signed).use { input ->
                JarOutputStream(changed.outputStream()).use { output ->
                    for (entry in input.entries()) {
                        output.putNextEntry(JarEntry(entry.name))
                        output.write(
                            if (tamper && entry.name == "classes.dex") "changed".toByteArray()
                            else input.getInputStream(entry).use { it.readBytes() }
                        )
                        output.closeEntry()
                    }

                    if (!tamper) {
                        output.putNextEntry(JarEntry("unsigned.txt"))
                        output.write(1)
                        output.closeEntry()
                    }
                }
            }

            JarFile(changed, true).use { jar ->
                assertThrows(SecurityException::class.java) { SignerInfo.of(jar) }
            }
        }
    }
}
