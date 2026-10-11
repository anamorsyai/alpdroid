package com.alpdroid.browser

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

/**
 * A strong default ad / tracker blocker for the browsing WebView. The block list ships in
 * `assets/adblock_hosts.txt` (one registered domain per line, `#` comments and `0.0.0.0 host`
 * hosts-file lines both accepted) and is loaded once into a hash set off the main thread.
 *
 * Matching is by domain suffix: a request to `pagead2.googlesyndication.com` is blocked because
 * `googlesyndication.com` is listed — so one entry covers every subdomain — while the walk stops
 * before the public suffix, so a listed ad subdomain never takes its whole parent site down.
 *
 * [shouldBlock] is called from the WebView's IO thread (shouldInterceptRequest), so the list is a
 * volatile immutable set and the counter is atomic; nothing here touches the UI thread.
 */
class AdBlocker(private val context: Context) {
    @Volatile var enabled: Boolean = true

    /** How many requests have been blocked this process — shown in the in-app settings. */
    val blockedCount = AtomicLong(0)

    @Volatile private var hosts: Set<String> = emptySet()
    @Volatile var ready: Boolean = false
        private set

    /** Loads the bundled list in the background; safe to call more than once (later calls are cheap no-ops once ready). */
    fun loadAsync() {
        if (ready) return
        Thread({
            val set = HashSet<String>(8192)
            runCatching {
                context.assets.open(ASSET).bufferedReader().useLines { lines ->
                    for (raw in lines) {
                        val line = raw.substringBefore('#').trim()
                        if (line.isEmpty()) continue
                        // Accept a bare domain or a hosts-file line ("0.0.0.0 host" / "127.0.0.1 host").
                        val host = line.substringAfterLast(' ').substringAfterLast('\t').removePrefix(".").lowercase()
                        if (host.length >= 3 && host.contains('.') && host != "localhost") set.add(host)
                    }
                }
            }
            hosts = set
            ready = true
        }, "alpbrowser-adblock").apply { isDaemon = true }.start()
    }

    /** Number of domains on the active block list. */
    fun size(): Int = hosts.size

    /** True when [host] — or any parent domain down to (but not including) its public suffix — is listed. */
    fun shouldBlock(host: String?): Boolean {
        if (!enabled || host.isNullOrEmpty()) return false
        val set = hosts
        if (set.isEmpty()) return false
        var cur = host.lowercase()
        while (true) {
            if (set.contains(cur)) return true
            val dot = cur.indexOf('.')
            if (dot < 0) return false
            cur = cur.substring(dot + 1)
            if (!cur.contains('.')) return false // reached the TLD / public suffix — stop
        }
    }

    private companion object {
        const val ASSET = "adblock_hosts.txt"
    }
}
