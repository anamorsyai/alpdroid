package com.alpdroid.browser.ui.proxy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.alpdroid.browser.AlpBrowserApp
import com.alpdroid.browser.BrowserBridge
import com.alpdroid.browser.RawHttp
import com.alpdroid.browser.ui.theme.RoninMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Repeater's state, held by the Application so it survives switching tabs: the request being edited, every send
 * made this session (tap one to load it back), and a draft handed over from the Proxy screen.
 */
class RepeaterModel {
    class Entry(val method: String, val url: String, val headers: String, val body: String, val status: Int, val ms: Long, val size: Int, val responseText: String)

    var method by mutableStateOf("GET")
    var url by mutableStateOf("")
    var headers by mutableStateOf("")
    var body by mutableStateOf("")
    var sending by mutableStateOf(false)
    var responseText by mutableStateOf("")
    var responseStatus by mutableStateOf<Int?>(null)
    var responseInfo by mutableStateOf("")
    /** The last response as received, so Pretty/Raw can be switched without sending again. */
    var respStatus: Int = 0
    var respHeaders: List<Pair<String, String>> = emptyList()
    var respBody: ByteArray? = null
    var pretty by mutableStateOf(true)
    var hasRaw by mutableStateOf(false)

    fun renderResponse() { if (hasRaw) responseText = rawMessage("HTTP $respStatus", respHeaders, respBody, pretty) }

    val log = mutableStateListOf<Entry>()

    fun load(method: String, url: String, headers: List<Pair<String, String>>, body: ByteArray?) {
        this.method = method
        this.url = url
        // Content-Length and Host are recomputed on send, so editing a body never leaves a stale length behind.
        this.headers = RawHttp.headersToText(headers.filterNot { it.first.equals("Content-Length", true) })
        val text = RawHttp.bodyToText(body)
        // A text field cannot take a multi-hundred-KB body without freezing; such a body is left out and says so.
        val tooBig = text != null && text.length > MAX_EDITABLE_BODY
        this.body = if (text == null || tooBig) "" else text
        responseText = ""; responseStatus = null
        responseInfo = if (text == null && (body?.size ?: 0) > 0) "Binary body (${body?.size} bytes) not copied"
        else if (tooBig) "Body too large to edit here (${body?.size} bytes) — not copied" else ""
    }

    companion object { const val MAX_EDITABLE_BODY = 60_000 }
}

@Composable
fun RepeaterScreen(app: AlpBrowserApp, modifier: Modifier = Modifier) {
    val m = app.repeater
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val chunks = remember(m.responseText) { if (m.responseText.isEmpty()) emptyList() else chunkText(m.responseText) }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoField(m.method, { m.method = it.uppercase().filter { c -> c.isLetter() } }, "Method", Modifier.weight(0.35f), singleLine = true)
                Button(
                    enabled = !m.sending,
                    onClick = {
                        m.sending = true
                        scope.launch {
                            val started = System.currentTimeMillis()
                            val res = withContext(Dispatchers.IO) { send(app, m) }
                            val ms = System.currentTimeMillis() - started
                            m.sending = false
                            if (res.error != null) {
                                m.responseStatus = 0; m.responseInfo = ""; m.responseText = res.error
                            } else {
                                m.responseStatus = res.status
                                val bytes = res.body
                                m.responseInfo = "${ms} ms · ${bytes?.size ?: 0} bytes"
                                m.respStatus = res.status; m.respHeaders = res.headers; m.respBody = bytes; m.hasRaw = true
                                m.responseText = withContext(Dispatchers.Default) { rawMessage("HTTP ${res.status}", res.headers, bytes, m.pretty) }
                                m.log.add(0, RepeaterModel.Entry(m.method, m.url, m.headers, m.body, res.status, ms, bytes?.size ?: 0, m.responseText))
                                if (m.log.size > 30) m.log.removeAt(m.log.size - 1)
                            }
                        }
                    },
                ) { Text(if (m.sending) "Sending…" else "Send") }
            }
            MonoField(m.url, { m.url = it }, "URL (http:// or https://)")
            MonoField(m.headers, { m.headers = it }, "Headers (Name: value per line)", minLines = 3)
            MonoField(m.body, { m.body = it }, "Body", minLines = 3)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Response", style = MaterialTheme.typography.titleSmall)
                StatusChip(m.responseStatus)
                Text(m.responseInfo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (m.hasRaw) FilterChip(selected = m.pretty, onClick = {
                    m.pretty = !m.pretty
                    scope.launch { val t = withContext(Dispatchers.Default) { rawMessage("HTTP ${m.respStatus}", m.respHeaders, m.respBody, m.pretty) }; m.responseText = t }
                }, label = { Text("Pretty") })
                if (m.responseText.isNotEmpty()) OutlinedButton(onClick = { clipboard.setText(AnnotatedString(m.responseText)) }) { Text("Copy") }
            }
            if (chunks.isEmpty()) Text("Send a request to see the response here.", style = MaterialTheme.typography.bodySmall.copy(fontFamily = RoninMono),
                modifier = Modifier.padding(vertical = 6.dp))
        }
        items(chunks.size) { MonoChunk(chunks[it]) }

        if (m.log.isNotEmpty()) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Sent this session (tap to reload)", style = MaterialTheme.typography.titleSmall)
            }
            items(m.log.toList()) { e ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        m.method = e.method; m.url = e.url; m.headers = e.headers; m.body = e.body
                        m.responseStatus = e.status; m.responseText = e.responseText; m.responseInfo = "${e.ms} ms · ${e.size} bytes"
                        m.hasRaw = false
                    }.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatusChip(e.status)
                    Text("${e.method} ${e.url}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = RoninMono), maxLines = 1)
                }
            }
        }
    }
}

/** Blocking: runs the request through the app's resend path (direct, so it does not add History entries). */
private fun send(app: AlpBrowserApp, m: RepeaterModel): BrowserBridge.ResendResponse {
    val url = m.url.trim()
    if (RawHttp.splitUrl(url) == null) return BrowserBridge.ResendResponse(0, emptyList(), null, error = "Enter a full http:// or https:// URL")
    val method = m.method.ifBlank { "GET" }
    val body = if (m.body.isEmpty()) null else RawHttp.textToBody(m.body)
    return app.httpResend(BrowserBridge.ResendRequest(method, url, RawHttp.parseHeaders(m.headers), body))
}
