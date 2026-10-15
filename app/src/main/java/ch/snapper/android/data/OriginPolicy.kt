package ch.snapper.android.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object OriginPolicy {
    fun normalize(raw: String, allowLocalHttp: Boolean): String = validate(raw, allowLocalHttp).toString().removeSuffix("/")
    fun validate(origin: String, allowLocalHttp: Boolean): HttpUrl {
        val url = origin.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid server origin")
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null && url.encodedPath == "/") {
            "Use a server origin without credentials, path, query, or fragment"
        }
        require(url.isHttps || (allowLocalHttp && url.scheme == "http" && url.host in setOf("localhost", "127.0.0.1", "::1", "10.0.2.2"))) {
            "HTTPS is required; debug builds also allow local development servers"
        }
        return url
    }
}
