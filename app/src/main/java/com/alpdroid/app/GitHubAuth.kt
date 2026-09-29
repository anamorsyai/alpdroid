package com.alpdroid.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * GitHub sign-in without ever typing a password: OAuth Device Flow (a code + link approved in the
 * user's own browser). Needs the user's own OAuth App client ID with Device Flow enabled
 * (github.com → Settings → Developer settings → OAuth Apps). The token is stored AES-GCM
 * encrypted under a non-exportable Android Keystore key, never in plain preferences.
 */
object GitHubAuth {
    const val SCOPE = "repo workflow read:org gist"

    data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val expiresIn: Int, val interval: Int)

    sealed interface Poll {
        data class Granted(val token: String) : Poll
        data class Failed(val reason: String) : Poll
    }

    private const val KEY_ALIAS = "alpdroid_github_token_key"
    private fun prefs(c: Context) = c.getSharedPreferences("alpineterm_github", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    fun saveToken(context: Context, token: String, login: String?) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString("token", Base64.encodeToString(cipher.iv + enc, Base64.NO_WRAP))
            .putString("login", login)
            .apply()
    }

    fun token(context: Context): String? = runCatching {
        val raw = Base64.decode(prefs(context).getString("token", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12))) }
        String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }.getOrNull()

    fun login(context: Context): String? = prefs(context).getString("login", null)

    fun signOut(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun post(url: String, form: Map<String, String>): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(form.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }.toByteArray()) }
        val text = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
        return JSONObject(text)
    }

    /** Blocking — call off the main thread. */
    fun requestDeviceCode(clientId: String): DeviceCode? = runCatching {
        val r = post("https://github.com/login/device/code", mapOf("client_id" to clientId.trim(), "scope" to SCOPE))
        DeviceCode(
            r.getString("device_code"), r.getString("user_code"),
            r.optString("verification_uri", "https://github.com/login/device"),
            r.optInt("expires_in", 900), r.optInt("interval", 5).coerceAtLeast(5),
        )
    }.getOrNull()

    /** Blocking poll until approved, denied, expired or [cancelled]. */
    fun pollToken(clientId: String, code: DeviceCode, cancelled: () -> Boolean): Poll {
        var wait = code.interval
        val deadline = System.currentTimeMillis() + code.expiresIn.coerceAtMost(900) * 1000L
        while (System.currentTimeMillis() < deadline && !cancelled()) {
            Thread.sleep(wait * 1000L)
            val r = runCatching {
                post(
                    "https://github.com/login/oauth/access_token",
                    mapOf("client_id" to clientId.trim(), "device_code" to code.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"),
                )
            }.getOrNull() ?: continue
            r.optString("access_token").takeIf { it.isNotBlank() }?.let { return Poll.Granted(it) }
            when (r.optString("error")) {
                "authorization_pending" -> {}
                "slow_down" -> wait += 5
                "expired_token" -> return Poll.Failed("The code expired — start again.")
                "access_denied" -> return Poll.Failed("Approval was denied.")
                else -> return Poll.Failed(r.optString("error_description", "Sign-in failed."))
            }
        }
        return Poll.Failed(if (cancelled()) "Cancelled." else "The code expired — start again.")
    }

    /** GitHub login name for a token, or null if it doesn't work. Blocking. */
    fun fetchLogin(token: String): String? = runCatching {
        val conn = URL("https://api.github.com/user").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        if (conn.responseCode != 200) return null
        JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("login").takeIf { it.isNotBlank() }
    }.getOrNull()
}
