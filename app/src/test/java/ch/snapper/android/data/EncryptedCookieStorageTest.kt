package ch.snapper.android.data

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedCookieStorageTest {
    @get:Rule val directory = TemporaryFolder()

    private class TestEncryption : CookieEncryption {
        private var key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        var available = true
        var expired = false
        var cleared = false
        override fun encrypt(plaintext: ByteArray): ByteArray? {
            if (!available) return null
            if (expired) throw GeneralSecurityException("Device authentication expired")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return cipher.iv + cipher.doFinal(plaintext)
        }
        override fun decrypt(ciphertext: ByteArray): ByteArray? {
            if (!available) return null
            if (expired) throw GeneralSecurityException("Device authentication expired")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
            return cipher.doFinal(ciphertext.copyOfRange(12, ciphertext.size))
        }
        override fun clear() {
            cleared = true
            key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        }
    }

    @Test fun encryptedSessionRestoresAndLogoutMakesCapturedCiphertextUnusable() {
        val file = File(directory.root, "session.bin")
        val encryption = TestEncryption()
        val storage = EncryptedCookieStorage(file, encryption)
        val cookies = listOf("refresh_token=private-secret; Path=/; Max-Age=3600")
        storage.write(cookies)
        val captured = file.readBytes()
        assertFalse(captured.decodeToString().contains("private-secret"))
        assertEquals(cookies, EncryptedCookieStorage(file, encryption).read())
        storage.write(emptyList())
        assertTrue(encryption.cleared)
        assertFalse(file.exists())
        file.writeBytes(captured)
        assertTrue(storage.read().isEmpty())
        assertFalse(file.exists())
    }

    @Test fun expiredAuthenticationOnWriteErasesPreviousSessionWithoutRejectingLogin() {
        val file = File(directory.root, "session.bin")
        val encryption = TestEncryption()
        val storage = EncryptedCookieStorage(file, encryption)
        storage.write(listOf("refresh_token=old"))
        encryption.expired = true
        storage.write(listOf("refresh_token=new"))
        assertTrue(encryption.cleared)
        assertFalse(file.exists())
        assertTrue(storage.read().isEmpty())
    }

    @Test fun unavailableAuthenticationDoesNotCreatePersistentCredentials() {
        val file = File(directory.root, "session.bin")
        val encryption = TestEncryption().apply { available = false }
        val storage = EncryptedCookieStorage(file, encryption)
        storage.write(listOf("refresh_token=secret"))
        assertTrue(storage.read().isEmpty())
        assertFalse(file.exists())
        assertTrue(encryption.cleared)
    }

    @Test fun corruptOrExpiredSessionIsErasedAndCanBeReplaced() {
        val file = File(directory.root, "session.bin")
        val encryption = TestEncryption()
        val storage = EncryptedCookieStorage(file, encryption)
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(storage.read().isEmpty())
        assertFalse(file.exists())
        storage.write(listOf("refresh_token=old"))
        encryption.expired = true
        assertTrue(storage.read().isEmpty())
        assertFalse(file.exists())
        encryption.expired = false
        storage.write(listOf("refresh_token=new"))
        assertEquals(listOf("refresh_token=new"), storage.read())
    }

    @Test fun failedFileRemovalStillRevokesTheEncryptionKeyFirst() {
        val file = directory.newFolder("nonempty")
        File(file, "child").writeText("obstruction")
        val encryption = TestEncryption()
        val captured = encryption.encrypt("private-cookie".encodeToByteArray())!!
        val storage = EncryptedCookieStorage(file, encryption)
        assertThrows(IOException::class.java) { storage.write(emptyList()) }
        assertTrue(encryption.cleared)
        assertThrows(GeneralSecurityException::class.java) { encryption.decrypt(captured) }
    }
}
