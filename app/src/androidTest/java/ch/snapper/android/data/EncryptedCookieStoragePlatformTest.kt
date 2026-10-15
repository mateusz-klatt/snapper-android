package ch.snapper.android.data

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.ParcelFileDescriptor
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EncryptedCookieStoragePlatformTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var alias: String
    private lateinit var encryption: AndroidCookieEncryption
    private var configuredAuthentication = false

    @Before fun setup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext
        InstrumentationRegistry.getArguments().getString("snapperTestDevicePin")?.let { pin ->
            require(pin.matches(Regex("[0-9]{4,16}")))
            val command = instrumentation.uiAutomation.executeShellCommand("timeout 10 locksettings verify --old $pin")
            val output = ParcelFileDescriptor.AutoCloseInputStream(command).bufferedReader().use { it.readText() }
            check(output.contains("verified successfully")) { "Disposable test device credential verification failed" }
            configuredAuthentication = true
        }
        directory = File(application.cacheDir, "cookie-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
        context = object : ContextWrapper(application) {
            override fun getNoBackupFilesDir(): File = directory
        }
        alias = "snapper.test.cookies.${UUID.randomUUID()}"
        encryption = AndroidCookieEncryption(context, alias)
    }

    @After fun cleanup() {
        encryption.clear()
        check(directory.deleteRecursively())
    }

    @Test fun persistentKeyRequiresRecentDeviceAuthenticationWhenAvailable() {
        val file = File(directory, "session.bin")
        val storage = EncryptedCookieStorage(file, encryption)
        val secure = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
        if (Build.VERSION.SDK_INT < 30 || !secure) {
            storage.write(listOf("refresh_token=secret"))
            assertFalse(file.exists())
            assertTrue(storage.read().isEmpty())
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertNull(store.getKey(alias, null))
            return
        }
        val result = runCatching { encryption.encrypt("private-cookie".encodeToByteArray()) }
        if (configuredAuthentication) assertTrue(result.isSuccess)
        if (result.isFailure) assertTrue(result.exceptionOrNull() is UserNotAuthenticatedException)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(alias, null) as SecretKey
        val information = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertTrue(information.isUserAuthenticationRequired)
        assertEquals(300, information.userAuthenticationValidityDurationSeconds)
        assertEquals(KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG, information.userAuthenticationType)
        result.getOrNull()?.let { ciphertext ->
            assertEquals("private-cookie", encryption.decrypt(ciphertext)?.decodeToString())
        }
        encryption.clear()
        assertNull(store.getKey(alias, null))
    }

    @Test fun corruptSessionAndLogoutLeaveNoReusableStorage() {
        val file = File(directory, "session.bin")
        val storage = EncryptedCookieStorage(file, encryption)
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(storage.read().isEmpty())
        assertFalse(file.exists())
        storage.write(listOf("refresh_token=secret"))
        if (configuredAuthentication) assertTrue(file.exists())
        if (file.exists()) assertEquals(listOf("refresh_token=secret"), EncryptedCookieStorage(file, encryption).read())
        val captured = if (file.exists()) file.readBytes() else null
        storage.write(emptyList())
        assertFalse(file.exists())
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertNull(store.getKey(alias, null))
        captured?.let {
            file.writeBytes(it)
            assertTrue(storage.read().isEmpty())
            assertFalse(file.exists())
        }
    }

    @Test fun legacySessionFilesAreRemovedDuringMigration() {
        val legacy = File(directory, "session-legacy.bin")
        legacy.writeBytes(byteArrayOf(1, 2, 3))
        AndroidCookieEncryption(context, alias)
        assertFalse(legacy.exists())
    }
}
