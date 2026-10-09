package com.alpdroid.app

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * The opencode web server (`opencode serve`) started from the Network settings. Kept free of Android types so
 * the command and the probes can be unit-tested.
 *
 * What it adds over typing the command in a tab:
 *  - a fixed password (OPENCODE_SERVER_PASSWORD, kept in settings) — the same login after every restart, instead
 *    of a new generated one read back off the screen each time;
 *  - the choice between "this phone only" (127.0.0.1) and "other devices on the Wi-Fi" (0.0.0.0);
 *  - a restart loop: if Android or a crash kills the server it comes back by itself, with a growing pause so a
 *    server that cannot start does not spin; Ctrl+C (or Stop) ends the loop too.
 */
object OpencodeWeb {
    const val PORT = 4096
    const val USER = "opencode"
    const val TAB_LABEL = "opencode serve"

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

    /** HTTP status of GET / with the server login, or -1 when nothing answered (200 = the password works, 401 = it does not). */
    fun probeLogin(port: Int, password: String, timeoutMs: Int = 3000): Int = try {
        val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = false
        val token = java.util.Base64.getEncoder().encodeToString("$USER:$password".toByteArray())
        conn.setRequestProperty("Authorization", "Basic $token")
        val code = conn.responseCode
        conn.disconnect()
        code
    } catch (_: Exception) { -1 }

    /** What the "server running" dialog shows. [loginWorks] false means the stored password was rejected and the
     *  server printed its own — [password] is then the one read from the tab. */
    fun readyMessage(lan: Boolean, port: Int, ips: List<String>, password: String): String {
        val local = "On this phone: http://127.0.0.1:$port"
        val others = if (!lan) "Other devices cannot reach it (\"Allow other devices\" is off)."
        else if (ips.isEmpty()) "Other devices: http://<phone address>:$port"
        else "Other devices on this Wi-Fi:\n" + ips.joinToString("\n") { "http://$it:$port" }
        return "$local\n$others\n\nUser: $USER\nPassword: $password\n\nIt restarts by itself if it stops. " +
            "Stop it with the Stop button in Settings → Network, or Ctrl+C in the \"$TAB_LABEL\" tab."
    }
}
