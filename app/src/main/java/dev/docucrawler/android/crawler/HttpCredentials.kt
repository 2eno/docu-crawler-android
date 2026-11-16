package dev.docucrawler.android.crawler

import okhttp3.Credentials
import java.nio.charset.StandardCharsets

/**
 * Zugangsdaten für HTTP-Authentifizierung (Basic Auth, z. B. `.htaccess`-geschützte Bereiche).
 * Werden nur an [host] gesendet – nie an fremde Domains.
 */
data class HttpCredentials(
    val host: String,
    val realm: String?,
    val username: String,
    val password: String,
) {
    /** Wert für den `Authorization`-Header (UTF-8, wie aktuelle Browser). */
    fun basicHeader(): String = Credentials.basic(username, password, StandardCharsets.UTF_8)

    /** Gleicher Host; `www.` wird ignoriert (`follow.de` == `www.follow.de`). */
    fun appliesTo(requestHost: String): Boolean =
        requestHost.lowercase().removePrefix("www.") == host.lowercase().removePrefix("www.")

    override fun toString() = "HttpCredentials(host=$host, realm=$realm, username=$username, password=***)"
}
