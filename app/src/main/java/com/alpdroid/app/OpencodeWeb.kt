package com.alpdroid.app

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.Base64

/**
 * The opencode web server (`opencode serve`) started from the Network settings. Kept free of Android types so
 * the command and the probes can be unit-tested.
 *
 * What it adds over typing the command in a tab:
 *  - a fixed password (OPENCODE_SERVER_PASSWORD, kept in settings) — the same login after every restart, instead
 *    of a new generated one read back off the screen each time;
 *  - the choice between "this phone only" (127.0.0.1) and "other devices on the Wi-Fi" (0.0.0.0);
 *  - a restart loop: if Android or a crash kills the server it comes back by itself, with a growing pause so a
 *    server that cannot start does not spin; Stop (or Ctrl+C when run by hand) ends the loop too.
 */
object OpencodeWeb {
    const val PORT = 4096
    /** Where the real server listens when [LocalAuthProxy] fronts it on [PORT]. */
    const val INTERNAL_PORT = 4097
    const val USER = "opencode"
    const val SERVICE_ID = "opencode-web"

    /** The proxy fronting the running server (see [LocalAuthProxy]); process-wide because tabs outlive the Activity. */
    @Volatile var proxy: LocalAuthProxy? = null
        private set

    /** Starts the proxy on [PORT] in front of the server that will listen on [INTERNAL_PORT]; throws if the port is taken. */
    @Synchronized
    fun startProxy(lan: Boolean, password: String): LocalAuthProxy {
        stopProxy()
        val p = LocalAuthProxy(PORT, INTERNAL_PORT, bindAll = lan, user = USER, password = password)
        p.start()
        proxy = p
        return p
    }

    /** Stops the proxy — only if it is still [only] when given, so an old server's exit cannot stop a newer one's. */
    @Synchronized
    fun stopProxy(only: LocalAuthProxy? = null) {
        if (only != null && proxy !== only) { only.stop(); return }
        proxy?.stop()
        proxy = null
    }

    /** Wraps [s] in single quotes for `sh`, so a password or path can never end the quoting. */
    fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * The command run directly (no typing) in the server tab. [lan] true binds 0.0.0.0, false 127.0.0.1.
     * The binary is resolved in the guest (opencode, else opencode2). LD_PRELOAD gcompat: bun's FFI stub needs a
     * glibc symbol musl lacks. Restart policy: exit 0, Ctrl+C (130) and SIGTERM (143) end it; anything else
     * (a crash, SIGKILL 137) restarts after 2, 4, 8 … 30 s, and five quick failures in a row give up.
     */
    fun command(port: Int, lan: Boolean, password: String): String {
        val host = if (lan) "0.0.0.0" else "127.0.0.1"
        return buildString {
            append("[ -f /lib/libgcompat.so.0 ] && export LD_PRELOAD=/lib/libgcompat.so.0; ")
            append("export OPENCODE_SERVER_USERNAME=$USER OPENCODE_SERVER_PASSWORD=${shQuote(password)}; ")
            append("BIN=\$(command -v opencode || command -v opencode2); ")
            append("if [ -z \"\$BIN\" ]; then echo 'opencode not installed — Settings > Quick install first'; exit 1; fi; ")
            append("trap 'exit 0' INT TERM; ")
            append("wait_s=2; quick=0; ")
            append("while :; do ")
            append("t0=\$(date +%s); ")
            append("\"\$BIN\" serve --hostname $host --port $port; code=\$?; ")
            append("[ \$code -eq 0 ] || [ \$code -eq 130 ] || [ \$code -eq 143 ] && exit \$code; ")
            append("if [ \$((\$(date +%s) - t0)) -ge 60 ]; then wait_s=2; quick=0; else quick=\$((quick + 1)); fi; ")
            append("if [ \$quick -ge 5 ]; then echo \"[AlpDroid] opencode keeps failing (exit \$code) — giving up\"; exit \$code; fi; ")
            append("echo \"[AlpDroid] opencode exited (\$code) — restarting in \${wait_s}s\"; ")
            append("sleep \$wait_s; wait_s=\$((wait_s * 2)); [ \$wait_s -gt 30 ] && wait_s=30; ")
            append("done")
        }
    }

    /** True when something accepts connections on [port] of this phone. */
    fun portOpen(port: Int, timeoutMs: Int = 500): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs); true }
    } catch (_: Exception) { false }

    /**
     * Status of an authenticated request to a path the server protects, or -1 when nothing answers. Plain sockets, not
     * HttpURLConnection: Android refuses cleartext HTTP from an app that does not allow it, even to 127.0.0.1.
     * v2 serves its web page without a login and protects only the paths under /api, v1 protects everything — so a path counts as
     * protected when it answers 401 without the login; if none does, nothing is protected and the answer is 200.
     */
    fun probeLogin(port: Int, password: String, timeoutMs: Int = 3000): Int {
        val token = "Basic " + Base64.getEncoder().encodeToString("$USER:$password".toByteArray())
        var answered = false
        for (path in listOf("/api/session", "/session", "/")) {
            val plain = rawStatus(port, path, null, timeoutMs)
            if (plain == -1) continue
            answered = true
            if (plain == 401) return rawStatus(port, path, token, timeoutMs)
        }
        return if (answered) 200 else -1
    }

    /** Whether [probeLogin]'s answer means the password was accepted. */
    fun loginAccepted(status: Int): Boolean = status != -1 && status != 401 && status != 403

    private fun rawStatus(port: Int, path: String, authorization: String?, timeoutMs: Int): Int = try {
        Socket().use { s ->
            s.soTimeout = timeoutMs
            s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            val req = "GET $path HTTP/1.0\r\nHost: 127.0.0.1:$port\r\n" +
                (if (authorization != null) "Authorization: $authorization\r\n" else "") + "\r\n"
            s.getOutputStream().write(req.toByteArray())
            s.getOutputStream().flush()
            val line = s.getInputStream().bufferedReader().readLine() ?: ""
            line.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        }
    } catch (_: Exception) { -1 }

    /** `?auth_token=` value the v2 web page signs itself in with: base64 of `user:password`. */
    fun authToken(password: String): String = Base64.getEncoder().encodeToString("$USER:$password".toByteArray())

    /** A link that opens the v2 web page already signed in (older opencode versions ignore the parameter). */
    fun loginUrl(host: String, port: Int, password: String): String =
        "http://$host:$port/?auth_token=" + URLEncoder.encode(authToken(password), "UTF-8")

    /** What the "server running" dialog shows. [noLogin]: this phone needs no password (see [LocalAuthProxy]). */
    fun readyMessage(lan: Boolean, noLogin: Boolean, port: Int, ips: List<String>, password: String): String {
        val local = if (noLogin) "On this phone: http://127.0.0.1:$port — no login needed."
        else "On this phone: http://127.0.0.1:$port (opened already signed in)."
        val others = if (!lan) "Other devices cannot reach it (\"Allow other devices\" is off)."
        else if (ips.isEmpty()) "Other devices: http://<phone address>:$port"
        else "Other devices on this Wi-Fi:\n" + ips.joinToString("\n") { "http://$it:$port" }
        return "$local\n$others\n\nFor other devices — user: $USER, password: $password (or use \"Copy link for another device\").\n\n" +
            "It restarts by itself if it stops. It runs in the background — stop it from Settings → Network & SSH."
    }
}
