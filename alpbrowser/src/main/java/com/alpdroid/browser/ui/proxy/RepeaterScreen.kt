package com.alpdroid.browser.ui.proxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alpdroid.browser.AlpBrowserApp
import com.alpdroid.browser.BrowserBridge
import com.alpdroid.browser.RawHttp
import com.alpdroid.browser.ui.kit.AppButton
import com.alpdroid.browser.ui.kit.AppCard
import com.alpdroid.browser.ui.kit.Field
import com.alpdroid.browser.ui.kit.Kind
import com.alpdroid.browser.ui.kit.LabeledField
import com.alpdroid.browser.ui.kit.MethodText
import com.alpdroid.browser.ui.kit.MonoStyle
import com.alpdroid.browser.ui.kit.ScreenHeader
import com.alpdroid.browser.ui.kit.ScreenPad
import com.alpdroid.browser.ui.kit.Segmented
import com.alpdroid.browser.ui.kit.SmallChip
import com.alpdroid.browser.ui.kit.StatusText
import com.alpdroid.browser.ui.theme.Hairline
import com.alpdroid.browser.ui.theme.Method
import com.alpdroid.browser.ui.theme.NavBg
import com.alpdroid.browser.ui.theme.SecondaryBg
import com.alpdroid.browser.ui.theme.TextMuted
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
    /** 0 = Request, 1 = Response, 2 = History of sends. */
    var segment by mutableIntStateOf(0)

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
        responseText = ""; responseStatus = null; segment = 0
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

    fun send() {
        m.sending = true
        scope.launch {
            val started = System.currentTimeMillis()
            val res = withContext(Dispatchers.IO) { send(app, m) }
            val ms = System.currentTimeMillis() - started
            m.sending = false
            if (res.error != null) {
                m.responseStatus = 0; m.responseInfo = ""; m.responseText = res.error; m.hasRaw = false
            } else {
                m.responseStatus = res.status
                val bytes = res.body
                m.responseInfo = "$ms ms · ${formatSize(bytes?.size ?: 0)}"
                m.respStatus = res.status; m.respHeaders = res.headers; m.respBody = bytes; m.hasRaw = true
                m.responseText = withContext(Dispatchers.Default) { rawMessage("HTTP ${res.status}", res.headers, bytes, m.pretty) }
                m.log.add(0, RepeaterModel.Entry(m.method, m.url, m.headers, m.body, res.status, ms, bytes?.size ?: 0, m.responseText))
                if (m.log.size > 30) m.log.removeAt(m.log.size - 1)
            }
            m.segment = 1
        }
    }

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            "Repeater",
            status = if (m.responseStatus == null) "Edit a request and send it again" else "${if (m.responseStatus == 0) "failed" else m.responseStatus.toString()}${if (m.responseInfo.isNotEmpty()) " · " + m.responseInfo else ""}",
            statusOn = (m.responseStatus ?: 0) in 200..399,
        )
        Segmented(listOf("Request", "Response", "History"), m.segment, { m.segment = it })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (m.segment) {
                0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPad)) {
                    Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(m.method, { m.method = it.uppercase().filter { c -> c.isLetter() } }, Modifier.width(96.dp), singleLine = true, placeholder = "Method")
                        Field(m.url, { m.url = it }, Modifier.weight(1f), singleLine = true, placeholder = "https://…")
                    }
                    LabeledField("Headers (Name: value per line)", m.headers, { m.headers = it }, minLines = 4)
                    LabeledField("Body", m.body, { m.body = it }, minLines = 4)
                }
                1 -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Row(Modifier.padding(horizontal = ScreenPad).padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (m.hasRaw) {
                                SmallChip("Pretty", on = m.pretty, onClick = { m.pretty = true; scope.launch { m.responseText = withContext(Dispatchers.Default) { rawMessage("HTTP ${m.respStatus}", m.respHeaders, m.respBody, true) } } })
                                SmallChip("Raw", on = !m.pretty, onClick = { m.pretty = false; scope.launch { m.responseText = withContext(Dispatchers.Default) { rawMessage("HTTP ${m.respStatus}", m.respHeaders, m.respBody, false) } } })
                            }
                            if (m.responseText.isNotEmpty()) SmallChip("Copy", on = false, onClick = { clipboard.setText(AnnotatedString(m.responseText)) })
                        }
                        if (chunks.isEmpty()) Text("Send a request to see the response here.", style = MaterialTheme.typography.bodyMedium, color = TextMuted, modifier = Modifier.padding(horizontal = ScreenPad))
                    }
                    items(chunks.size) { MonoChunk(chunks[it]) }
                    item { Box(Modifier.padding(bottom = 16.dp)) }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (m.log.isEmpty()) item { Text("Nothing sent yet.", style = MaterialTheme.typography.bodyMedium, color = TextMuted, modifier = Modifier.padding(horizontal = ScreenPad)) }
                    items(m.log.toList()) { e ->
                        AppCard(onClick = {
                            m.method = e.method; m.url = e.url; m.headers = e.headers; m.body = e.body
                            m.responseStatus = e.status; m.responseText = e.responseText; m.responseInfo = "${e.ms} ms · ${formatSize(e.size)}"
                            m.hasRaw = false; m.segment = 0
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MethodText(e.method, Modifier.width(40.dp))
                                StatusText(e.status, Modifier.width(34.dp))
                                Text(e.url, style = MonoStyle.copy(fontSize = 11.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        // The Send bar stays put whichever part of the request or response is on screen.
        Row(
            Modifier.fillMaxWidth().background(NavBg).padding(horizontal = ScreenPad, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.background(SecondaryBg, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 15.dp)) {
                Text(m.method.ifBlank { "GET" }, style = MonoStyle.copy(fontSize = 13.sp, color = Method, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
            }
            AppButton(if (m.sending) "Sending…" else "Send request", { send() }, Modifier.weight(1f), kind = Kind.Primary, enabled = !m.sending)
        }
    }
}

private fun formatSize(n: Int): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> String.format("%.1f KB", n / 1024.0)
    else -> String.format("%.1f MB", n / 1024.0 / 1024.0)
}

/** Blocking: runs the request through the app's resend path (direct, so it does not add History entries). */
private fun send(app: AlpBrowserApp, m: RepeaterModel): BrowserBridge.ResendResponse {
    val url = m.url.trim()
    if (RawHttp.splitUrl(url) == null) return BrowserBridge.ResendResponse(0, emptyList(), null, error = "Enter a full http:// or https:// URL")
    val method = m.method.ifBlank { "GET" }
    val body = if (m.body.isEmpty()) null else RawHttp.textToBody(m.body)
    return app.httpResend(BrowserBridge.ResendRequest(method, url, RawHttp.parseHeaders(m.headers), body))
}
