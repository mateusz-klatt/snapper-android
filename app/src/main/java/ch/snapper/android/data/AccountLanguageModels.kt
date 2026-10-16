package ch.snapper.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Optional session capability. Adapter performs one CSRF-protected mutation, never automatic POST replay. */
fun interface AccountLanguageClient {
    fun bindLanguage(identity: AccountLanguageIdentity): AccountLanguageBinding = AccountLanguageBinding(identity, this)
    suspend fun updateLanguage(identity: AccountLanguageIdentity, language: String): AccountLanguageReceipt
}

data class AccountLanguageIdentity(val backend: String, val sessionGeneration: Long, val actor: OrderScope)
data class AccountLanguageBinding(val identity: AccountLanguageIdentity, val client: AccountLanguageClient)
data class AccountLanguageReceipt(val identity: AccountLanguageIdentity, val language: String)
@Serializable
data class UpdateAccountLanguagePayload(@SerialName("default_language") val defaultLanguage: String)
enum class AccountLanguageFailureKind { PREPARATION, REJECTED, UNKNOWN }
class AccountLanguageException(val kind: AccountLanguageFailureKind) : java.io.IOException("Account language synchronization failed")
