package ch.snapper.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.security.KeyStore
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

internal class EncryptedCookieStorage(context: Context, origin: HttpUrl) : CookieStorage {
    private val originKey = MessageDigest.getInstance("SHA-256").digest(origin.toString().encodeToByteArray()).joinToString("") { "%02x".format(it) }
    private val file = File(context.noBackupFilesDir, "session-$originKey.bin")
    private val alias = "snapper.session.cookies"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): List<String> {
        if (!file.exists()) return emptyList()
        return try {
            val bytes = file.readBytes()
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            Json.decodeFromString<List<String>>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString())
        } catch (_: Exception) {
            file.delete()
            emptyList()
        }
    }
    override fun write(cookies: List<String>) {
        if (cookies.isEmpty()) {
            check(!file.exists() || file.delete()) { "Could not remove stored session" }
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.iv + cipher.doFinal(Json.encodeToString(cookies).encodeToByteArray())
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(ciphertext)
        check(temporary.renameTo(file)) { "Could not save session" }
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
