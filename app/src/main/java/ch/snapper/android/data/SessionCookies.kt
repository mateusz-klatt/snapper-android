package ch.snapper.android.data

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

interface CookieStorage {
    fun read(): List<String>
    fun write(cookies: List<String>)
}

class MemoryCookieStorage : CookieStorage {
    private var values = emptyList<String>()
    override fun read(): List<String> = values.toList()
    override fun write(cookies: List<String>) { values = cookies.toList() }
}

internal interface CookieEncryption {
    fun encrypt(plaintext: ByteArray): ByteArray?
    fun decrypt(ciphertext: ByteArray): ByteArray?
    fun clear()
}

private fun eraseCookieFile(file: File) {
    if (file.exists() && !file.delete()) {
        file.writeBytes(byteArrayOf())
    }
}

internal class AndroidCookieEncryption(private val context: Context, private val alias: String) : CookieEncryption {
    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        keyStore().deleteEntry("snapper.session.cookies")
        context.noBackupFilesDir.listFiles()?.filter {
            it.name.startsWith("session-") && !it.name.startsWith("session-v2-")
        }?.forEach(::eraseCookieFile)
    }

    private fun available(): Boolean = Build.VERSION.SDK_INT >= 30 &&
        context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    private fun key(): SecretKey {
        val store = keyStore()
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (Build.VERSION.SDK_INT < 30) throw IllegalStateException("Persistent sessions require Android 11 or newer")
        val specification = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(300, KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG)
        if (Build.VERSION.SDK_INT >= 35) specification.setUnlockedDeviceRequired(true)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(specification.build())
        }.generateKey()
    }

    override fun encrypt(plaintext: ByteArray): ByteArray? {
        if (!available()) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray? {
        if (!available()) return null
        require(ciphertext.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
        return cipher.doFinal(ciphertext.copyOfRange(12, ciphertext.size))
    }

    override fun clear() { keyStore().deleteEntry(alias) }
}

internal class EncryptedCookieStorage(private val file: File, private val encryption: CookieEncryption) : CookieStorage {
    constructor(context: Context, origin: HttpUrl) : this(context, originDigest(origin))
    private constructor(context: Context, originKey: String) : this(
        File(context.noBackupFilesDir, "session-v2-$originKey.bin"),
        AndroidCookieEncryption(context, "snapper.session.cookies.v2.$originKey"),
    )

    private fun discard() {
        encryption.clear()
        eraseCookieFile(file)
        eraseCookieFile(File(file.parentFile, "${file.name}.tmp"))
    }

    override fun read(): List<String> {
        if (!file.exists()) return emptyList()
        val result = try {
            encryption.decrypt(file.readBytes())?.let { Json.decodeFromString<List<String>>(it.decodeToString()) }
        } catch (_: Exception) {
            null
        }
        if (result == null) discard()
        return result.orEmpty()
    }

    override fun write(cookies: List<String>) {
        if (cookies.isEmpty()) {
            discard()
            return
        }
        val ciphertext = try {
            encryption.encrypt(Json.encodeToString(cookies).encodeToByteArray())
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: ProviderException) {
            null
        }
        if (ciphertext == null) {
            discard()
            return
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(ciphertext)
        check(temporary.renameTo(file)) { "Could not save session" }
    }

    companion object {
        private fun originDigest(origin: HttpUrl): String = MessageDigest.getInstance("SHA-256")
            .digest(origin.toString().encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}

internal class SessionCookies(private val origin: HttpUrl, private val storage: CookieStorage) : Interceptor {
    private var epoch = 0L
    private var blocked = false
    private var persistSession = true
    private var cookies = storage.read().mapNotNull { Cookie.parse(origin, it) }

    @Synchronized fun invalidate(): Long {
        epoch += 1
        cookies = emptyList()
        storage.write(emptyList())
        return epoch
    }
    @Synchronized fun setPersistence(enabled: Boolean) {
        persistSession = enabled
        if (!enabled) storage.write(emptyList())
    }
    @Synchronized fun pause() {
        blocked = true
        invalidate()
    }
    @Synchronized fun resume() { blocked = false }
    @Synchronized fun generation(): Long = epoch
    @Synchronized fun hasAuthentication(): Boolean = cookies.any {
        it.name in setOf("access_token", "refresh_token") && it.expiresAt > System.currentTimeMillis()
    }
    @Synchronized fun authenticationHeaders(): Pair<String, String?> {
        val applicable = cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(origin.resolve("/api/auth/logout")!!) }
        return applicable.joinToString("; ") { "${it.name}=${it.value}" } to applicable.firstOrNull { it.name == "csrf_token" }?.value
    }
    @Synchronized private fun selected(url: HttpUrl): Pair<Long, List<Cookie>> = epoch to cookies.filter {
        it.expiresAt > System.currentTimeMillis() && it.matches(url)
    }
    @Synchronized private fun save(generation: Long, responseCookies: List<Cookie>) {
        if (blocked || epoch != generation) return
        responseCookies.forEach { cookie ->
            cookies = cookies.filterNot { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path } + cookie
        }
        cookies = cookies.filter { it.expiresAt > System.currentTimeMillis() }
        try {
            storage.write(if (persistSession) cookies.filter { it.persistent }.map(Cookie::toString) else emptyList())
        } catch (error: Exception) {
            throw IOException("Could not persist session", error)
        }
    }
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        require(request.url.scheme == origin.scheme && request.url.host == origin.host && request.url.port == origin.port) {
            "Refusing credentials outside the configured origin"
        }
        val (generation, applicable) = selected(request.url)
        val builder = request.newBuilder()
        if (applicable.isNotEmpty()) builder.header("Cookie", applicable.joinToString("; ") { "${it.name}=${it.value}" })
        if (request.method !in setOf("GET", "HEAD", "OPTIONS")) {
            applicable.firstOrNull { it.name == "csrf_token" }?.let { builder.header("X-CSRF-Token", it.value) }
        }
        return chain.proceed(builder.build()).also { save(generation, Cookie.parseAll(request.url, it.headers)) }
    }
}
