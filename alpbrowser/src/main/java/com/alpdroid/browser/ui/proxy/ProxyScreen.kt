package com.alpdroid.browser.ui.proxy

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.alpdroid.browser.AlpBrowserApp
import com.alpdroid.browser.InterceptController
import com.alpdroid.browser.RawHttp
import com.alpdroid.browser.ui.theme.RoninMono
import com.alpdroid.proxy.HttpTransaction
import com.alpdroid.proxy.RequestAction
import com.alpdroid.proxy.ResponseAction

/** Burp-style Proxy: live Intercept (pause, edit, forward or drop), History of everything captured, Rules and Scope. */
@Composable
fun ProxyScreen(app: AlpBrowserApp, onSendToRepeater: () -> Unit, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf(0) }
    var scope by remember { mutableStateOf(app.scope()) }
    val held by app.intercept.pendingFlow.collectAsState()
    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(if (held.isEmpty()) "Intercept" else "Intercept (${held.size})", maxLines = 1) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History", maxLines = 1) })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Rules", maxLines = 1) })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text(if (scope.isEmpty()) "Scope" else "Scope ●", maxLines = 1) })
        }
        // A scope silently hides every other host from History, so it is always announced, whichever tab is open.
        if (scope.isNotEmpty() && tab != 3) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = ScreenPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Scope is on — only ${scope.joinToString(", ")} is recorded; other sites are not shown.",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { app.setScope(emptyList()); scope = emptyList() }) { Text("Clear") }
            }
        }
        when (tab) {
            0 -> InterceptTab(app, held, onSendToRepeater)
            1 -> HistoryTab(app, onSendToRepeater)
            2 -> RulesTab(app)
            else -> ScopeTab(app, scope) { app.setScope(it); scope = app.scope() }
        }
    }
}

@Composable
private fun ScopeTab(app: AlpBrowserApp, scope: List<String>, onSave: (List<String>) -> Unit) {
    var text by remember(scope) { mutableStateOf(scope.joinToString("\n")) }
    Column(Modifier.fillMaxSize().padding(horizontal = ScreenPadding, vertical = 10.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Scope")
        Text(
            "Only hosts matching these patterns are recorded in History (and by Capture). One per line; * is a wildcard, " +
                "e.g. *.example.com. Leave it empty to record every site.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MonoField(text, { text = it }, "Hosts (one per line)", minLines = 4)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(text.lines().map { it.trim() }.filter { it.isNotEmpty() }) }) { Text("Save scope") }
            OutlinedButton(onClick = { text = ""; onSave(emptyList()) }) { Text("Record everything") }
        }
        if (scope.isEmpty()) Text("No scope: every site is recorded.", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InterceptTab(app: AlpBrowserApp, held: List<InterceptController.Held>, onSendToRepeater: () -> Unit) {
    val ic = app.intercept
    var on by remember { mutableStateOf(ic.enabled) }
    var responses by remember { mutableStateOf(ic.interceptResponses) }
    var skipStatic by remember { mutableStateOf(ic.skipStatic) }
    var capture by remember { mutableStateOf(app.captureEnabled()) }
    var host by remember { mutableStateOf(ic.hostFilter) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = held.firstOrNull { it.holdId == selectedId }

    Column(Modifier.fillMaxSize().padding(horizontal = ScreenPadding, vertical = 10.dp)) {
        if (selected == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Intercept is ${if (on) "ON" else "off"}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = {
                    on = it; ic.enabled = it
                    if (!it) { ic.releaseAll(); selectedId = null } // nothing should stay stuck when the breakpoint is switched off
                })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = responses, onClick = { responses = !responses; ic.interceptResponses = responses }, label = { Text("Responses") })
                FilterChip(selected = skipStatic, onClick = { skipStatic = !skipStatic; ic.skipStatic = skipStatic }, label = { Text("Skip images/CSS/JS") })
                FilterChip(
                    selected = capture || on, enabled = !on,
                    onClick = { capture = !capture; app.setCaptureEnabled(capture) },
                    label = { Text("Capture (proxy)") },
                )
            }
            Text(
                if (capture || on) "Traffic goes through the proxy — History has full decrypted requests and responses."
                else "Direct browsing (fastest). Turn on Capture or Intercept to see decrypted traffic.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            MonoField(host, { host = it; ic.hostFilter = it }, "Only hold hosts containing (blank = all)", singleLine = true)
            if (held.isNotEmpty()) OutlinedButton(onClick = { ic.releaseAll(); selectedId = null }) { Text("Forward all (${held.size})") }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        if (selected != null) {
            HeldEditor(app, selected, onDone = { selectedId = null }, onSendToRepeater = onSendToRepeater)
        } else if (held.isEmpty()) {
            Text(
                if (on) "Waiting for traffic… browse in the Browser tab and requests will pause here."
                else "Turn Intercept on, then browse. Each request pauses here so you can edit it, forward it or drop it.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn {
                items(held, key = { it.holdId }) { h ->
                    ListCard(onClick = { selectedId = h.holdId }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (h.kind == "request") "REQ" else "RES", color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = RoninMono))
                            Text(
                                if (h.kind == "request") "${h.method} ${h.host}${h.target}" else "${h.status ?: "?"} from ${h.host}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = RoninMono), maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeldEditor(app: AlpBrowserApp, h: InterceptController.Held, onDone: () -> Unit, onSendToRepeater: () -> Unit) {
    val isReq = h.kind == "request"
    val ic = app.intercept
    val origBody = remember(h.holdId) { RawHttp.bodyToText(h.body) }
    // A body too big for a text field is not editable (it would freeze the screen); it is forwarded unchanged.
    val editable = origBody != null && origBody.length <= RepeaterModel.MAX_EDITABLE_BODY
    var line1 by remember(h.holdId) { mutableStateOf(if (isReq) h.method else (h.status?.toString() ?: "")) }
    var target by remember(h.holdId) { mutableStateOf(h.target) }
    var headers by remember(h.holdId) { mutableStateOf(RawHttp.headersToText(h.headers)) }
    var body by remember(h.holdId) { mutableStateOf(if (editable) origBody ?: "" else "") }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(if (isReq) "Request to ${h.host}:${h.port}" else "Response from ${h.host}:${h.port}", style = MaterialTheme.typography.titleSmall)
        MonoField(line1, { line1 = it }, if (isReq) "Method" else "Status code", singleLine = true)
        if (isReq) MonoField(target, { target = it }, "Path and query", singleLine = true)
        MonoField(headers, { headers = it }, "Headers", minLines = 3)
        if (!editable) {
            Text(
                if (origBody == null) "Binary body (${h.body?.size ?: 0} bytes) — it is forwarded unchanged."
                else "Large body (${h.body?.size ?: 0} bytes) — too big to edit here, it is forwarded unchanged.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            MonoField(body, { body = it }, "Body", minLines = 3)
            OutlinedButton(onClick = {
                body = com.alpdroid.browser.BodyFormat.pretty(RawHttp.parseHeaders(headers), body.toByteArray()) ?: body
            }) { Text("Prettify body") }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val newHeaders = RawHttp.parseHeaders(headers)
                val bodyChanged = editable && body != origBody
                val newBody = if (bodyChanged) RawHttp.textToBody(body) else null
                val unchanged = newHeaders == h.headers && !bodyChanged &&
                    (if (isReq) line1 == h.method && target == h.target else line1 == (h.status?.toString() ?: ""))
                if (isReq) {
                    ic.resolveRequest(h.holdId, if (unchanged) RequestAction.Forward else
                        RequestAction.Replace(method = line1.trim().ifEmpty { null }, target = target.trim().ifEmpty { null }, headers = newHeaders, body = newBody))
                } else {
                    ic.resolveResponse(h.holdId, if (unchanged) ResponseAction.Forward else
                        ResponseAction.Replace(status = line1.trim().toIntOrNull(), headers = newHeaders, body = newBody))
                }
                onDone()
            }) { Text("Forward") }
            OutlinedButton(onClick = {
                if (isReq) ic.resolveRequest(h.holdId, RequestAction.Drop) else ic.resolveResponse(h.holdId, ResponseAction.Drop)
                onDone()
            }) { Text("Drop") }
            OutlinedButton(onClick = onDone) { Text("Back") }
        }
        if (isReq) {
            fun toRepeater() {
                val scheme = if (h.port == 443) "https" else "http"
                app.repeater.load(line1.ifBlank { h.method }, RawHttp.displayUrl(scheme, h.host, h.port, target), RawHttp.parseHeaders(headers), if (editable) RawHttp.textToBody(body) else h.body)
                onSendToRepeater()
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { toRepeater() }) { Text("To Repeater") }
                OutlinedButton(onClick = { toRepeater(); ic.resolveRequest(h.holdId, RequestAction.Drop); onDone() }) { Text("Repeater + drop") }
            }
            Text(
                "To Repeater keeps this request paused here (forward or drop it when you're ready). Repeater + drop also stops the browser's copy from ever being sent.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryTab(app: AlpBrowserApp, onSendToRepeater: () -> Unit) {
    val all by app.transactionStore.transactions.collectAsState()
    var filter by remember { mutableStateOf("") }
    var hideTunnels by remember { mutableStateOf(true) }
    var openId by remember { mutableStateOf<String?>(null) }
    var capturePulse by remember { mutableStateOf(0) } // read below so the banner disappears right after Capture is turned on
    @Suppress("UNUSED_EXPRESSION") capturePulse
    val open = all.firstOrNull { it.id == openId }
    val rows = remember(all, filter, hideTunnels) {
        val f = filter.trim()
        all.asReversed().filter { (!hideTunnels || !it.opaque) &&
            (f.isEmpty() || it.host.contains(f, true) || it.path.contains(f, true) || it.method.equals(f, true) || it.responseStatus?.toString() == f) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = ScreenPadding, vertical = 10.dp)) {
        if (open != null) {
            TxDetail(app, open, onBack = { openId = null }, onSendToRepeater = onSendToRepeater)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoField(filter, { filter = it }, "Filter: host, path, method or status", Modifier.weight(1f), singleLine = true)
            OutlinedButton(onClick = { app.transactionStore.clear() }) { Text("Clear") }
        }
        if (!app.proxyActive()) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Direct browsing: only the URLs are listed. Turn on Capture to see decrypted requests and responses.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f),
                )
                Button(onClick = { capturePulse++; app.setCaptureEnabled(true) }) { Text("Capture") }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            FilterChip(selected = hideTunnels, onClick = { hideTunnels = !hideTunnels }, label = { Text("Hide CONNECT tunnels") })
            Text("${rows.size} of ${all.size}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
        LazyColumn {
            items(rows, key = { it.id }) { tx ->
                ListCard(onClick = { openId = tx.id }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tx.method, style = MaterialTheme.typography.labelMedium.copy(fontFamily = RoninMono), color = MaterialTheme.colorScheme.secondary)
                            if (tx.opaque) Text("tunnel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                            else StatusChip(tx.responseStatus)
                            tx.durationMs?.let { Text("$it ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text("${tx.host}${tx.path}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = RoninMono), maxLines = 2,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TxDetail(app: AlpBrowserApp, tx: HttpTransaction, onBack: () -> Unit, onSendToRepeater: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var pretty by remember { mutableStateOf(true) }
    // Laying out a big JSON/HTML body takes real work; do it off the main thread so the screen never stalls.
    val rendered by produceState(initialValue = "" to "", tx.id, pretty) {
        value = withContext(Dispatchers.Default) {
            rawMessage("${tx.method} ${tx.path.ifEmpty { "/" }} (${tx.scheme}://${tx.host}:${tx.port})", tx.requestHeaders, tx.requestBody, pretty) to
                if (tx.responseStatus == null) "(no response recorded — direct browsing keeps only the request; turn on Capture)"
                else rawMessage("HTTP ${tx.responseStatus}", tx.responseHeaders, tx.responseBody, pretty)
        }
    }
    val request = rendered.first
    val response = rendered.second
    val reqChunks = remember(request) { chunkText(request) }
    val resChunks = remember(response) { chunkText(response) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                if (!tx.opaque) Button(onClick = {
                    app.repeater.load(tx.method, RawHttp.displayUrl(tx.scheme, tx.host, tx.port, tx.path), tx.requestHeaders, tx.requestBody)
                    onSendToRepeater()
                }) { Text("Repeater") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(request + "\n\n" + response)) }) { Text("Copy") }
                OutlinedButton(onClick = { app.transactionStore.delete(tx.id); onBack() }) { Text("Delete") }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = pretty, onClick = { pretty = !pretty }, label = { Text("Pretty") })
                Text("JSON, HTML and forms laid out; Raw shows the bytes as sent", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Request", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
        }
        items(reqChunks.size) { MonoChunk(reqChunks[it]) }
        item { Text("Response", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp)) }
        items(resChunks.size) { MonoChunk(resChunks[it]) }
    }
}
