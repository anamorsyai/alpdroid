package com.alpdroid.browser

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A cookie to set, with the attributes `browserctl setcookie` accepts. */
data class CookieSpec(
    val name: String,
    val value: String,
    val domain: String? = null,
    val path: String? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val sameSite: String? = null,
    /** Absolute expiry, seconds since the epoch. */
    val expires: Long? = null,
    /** Lifetime in seconds from now. */
    val maxAge: Long? = null,
)

/** Builds the `Set-Cookie`-style text Android's CookieManager takes, refusing anything that could smuggle extra attributes. */
object CookieHeader {
    private val token = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")
    private val host = Regex("^[A-Za-z0-9.-]+$")

    /** The attribute text for [c], or an error message. */
    fun build(c: CookieSpec): Result<String> {
        if (!token.matches(c.name)) return Result.failure(IllegalArgumentException("invalid cookie name"))
        if (c.value.any { it == ';' || it == '\r' || it == '\n' || it == '\u0000' }) return Result.failure(IllegalArgumentException("the cookie value may not contain ';' or line breaks"))
        c.domain?.let { if (!host.matches(it)) return Result.failure(IllegalArgumentException("invalid domain")) }
        val path = c.path ?: "/"
        if (!path.startsWith("/") || path.any { it == ';' || it == '\r' || it == '\n' }) return Result.failure(IllegalArgumentException("invalid path"))
        val sameSite = c.sameSite?.let { s ->
            when (s.lowercase()) { "lax" -> "Lax"; "strict" -> "Strict"; "none" -> "None"; else -> return Result.failure(IllegalArgumentException("samesite must be Lax, Strict or None")) }
        }
        if (sameSite == "None" && !c.secure) return Result.failure(IllegalArgumentException("SameSite=None requires --secure"))
        c.maxAge?.let { if (it < 0) return Result.failure(IllegalArgumentException("max-age must not be negative")) }
        return Result.success(buildString {
            append(c.name).append('=').append(c.value)
            append("; Path=").append(path)
            c.domain?.let { append("; Domain=").append(it) }
            if (c.secure) append("; Secure")
            if (c.httpOnly) append("; HttpOnly")
            sameSite?.let { append("; SameSite=").append(it) }
            c.maxAge?.let { append("; Max-Age=").append(it) }
            c.expires?.let { append("; Expires=").append(httpDate(it)) }
        })
    }

    internal fun httpDate(epochSeconds: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(epochSeconds * 1000))
}
