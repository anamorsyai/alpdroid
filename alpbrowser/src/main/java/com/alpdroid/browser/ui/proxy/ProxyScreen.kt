package com.alpdroid.browser.ui.proxy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alpdroid.browser.AlpBrowserApp
import com.alpdroid.browser.InterceptController
import com.alpdroid.browser.RawHttp
import com.alpdroid.browser.ui.theme.RoninMono
import com.alpdroid.proxy.HttpTransaction
import com.alpdroid.proxy.RequestAction
import com.alpdroid.proxy.ResponseAction

/** Burp-style Proxy: live Intercept (pause, edit, forward or drop) and History (every captured exchange). */
@Composable
fun ProxyScreen(app: AlpBrowserApp, onSendToRepeater: () -> Unit, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf(0) }
    val held by app.intercept.pendingFlow.collectAsState()
    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(if (held.isEmpty()) "Intercept" else "Intercept (${held.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History") })
        }
        if (tab == 0) InterceptTab(app, held, onSendToRepeater) else HistoryTab(app, onSendToRepeater)
    }
}

@Composable
private fun InterceptTab(app: AlpBrowserApp, held: List<InterceptController.Held>, onSendToRepeater: () -> Unit) {
    val ic = app.intercept
    var on by remember { mutableStateOf(ic.enabled) }
    var host by remember { mutableStateOf(ic.hostFilter) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = held.firstOrNull { it.holdId == selectedId }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Intercept is ${if (on) "ON" else "off"}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = {
                on = it; ic.enabled = it
                if (!it) ic.releaseAll() // nothing should stay stuck when the breakpoint is switched off
            })
        }
        MonoField(host, { host = it; ic.hostFilter = it }, "Only hold hosts containing (blank = all)", singleLine = true)
        if (held.isNotEmpty()) {
            OutlinedButton(onClick = { ic.releaseAll(); selectedId = null }) { Text("Forward all (${held.size})") }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        if (selected != null) {
            HeldEditor(app, selected, onDone = { selectedId = null }, onSendToRepeater = onSendToRepeater)
        } else if (held.isEmpty()) {
            Text(
                if (on) "Waiting for traffic… browse in the Browser tab and requests will pause here."
                else "Turn Intercept on, then browse. Each request and response pauses here so you can edit it, forward it or drop it.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn {
                items(held, key = { it.holdId }) { h ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selectedId = h.holdId }.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(if (h.kind == "request") "REQ" else "RES", color = MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = RoninMono))
                        Text(
                            if (h.kind == "request") "${h.method} ${h.host}${h.target}" else "${h.status ?: "?"} from ${h.host}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = RoninMono), maxLines = 2,
                        )
                    }
                    HorizontalDivider()
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
    var line1 by remember(h.holdId) { mutableStateOf(if (isReq) h.method else (h.status?.toString() ?: "")) }
    var target by remember(h.holdId) { mutableStateOf(h.target) }
    var headers by remember(h.holdId) { mutableStateOf(RawHttp.headersToText(h.headers)) }
    var body by remember(h.holdId) { mutableStateOf(origBody ?: "") }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(if (isReq) "Request to ${h.host}:${h.port}" else "Response from ${h.host}:${h.port}", style = MaterialTheme.typography.titleSmall)
        MonoField(line1, { line1 = it }, if (isReq) "Method" else "Status code", singleLine = true)
        if (isReq) MonoField(target, { target = it }, "Path and query", singleLine = true)
        MonoField(headers, { headers = it }, "Headers", minLines = 3)
        if (origBody == null) {
            Text("Binary body (${h.body?.size ?: 0} bytes) — it is forwarded unchanged.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            MonoField(body, { body = it }, "Body", minLines = 3)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val newHeaders = RawHttp.parseHeaders(headers)
                val bodyChanged = origBody != null && body != origBody
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
            if (isReq) OutlinedButton(onClick = {
                val scheme = if (h.port == 443) "https" else "http"
                app.repeater.load(line1.ifBlank { h.method }, RawHttp.displayUrl(scheme, h.host, h.port, target), RawHttp.parseHeaders(headers), RawHttp.textToBody(body))
                onSendToRepeater()
            }) { Text("Repeater") }
            OutlinedButton(onClick = onDone) { Text("Back") }
        }
    }
}

@Composable
private fun HistoryTab(app: AlpBrowserApp, onSendToRepeater: () -> Unit) {
    val all by app.transactionStore.transactions.collectAsState()
    var filter by remember { mutableStateOf("") }
    var openId by remember { mutableStateOf<String?>(null) }
    val open = all.firstOrNull { it.id == openId }
    val rows = remember(all, filter) {
        val f = filter.trim()
        all.asReversed().filter { f.isEmpty() || it.host.contains(f, true) || it.path.contains(f, true) || it.method.equals(f, true) || it.responseStatus?.toString() == f }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (open != null) {
            TxDetail(app, open, onBack = { openId = null }, onSendToRepeater = onSendToRepeater)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoField(filter, { filter = it }, "Filter: host, path, method or status", Modifier.weight(1f), singleLine = true)
            OutlinedButton(onClick = { app.transactionStore.clear() }) { Text("Clear") }
        }
        Text("${rows.size} of ${all.size} requests", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn {
            items(rows, key = { it.id }) { tx ->
                Row(
                    Modifier.fillMaxWidth().clickable { openId = tx.id }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(tx.method, style = MaterialTheme.typography.labelMedium.copy(fontFamily = RoninMono), color = MaterialTheme.colorScheme.secondary)
                    if (tx.opaque) Text("tunnel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    else StatusChip(tx.responseStatus)
                    Text("${tx.host}${tx.path}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = RoninMono), maxLines = 2)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun TxDetail(app: AlpBrowserApp, tx: HttpTransaction, onBack: () -> Unit, onSendToRepeater: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            if (!tx.opaque) Button(onClick = {
                app.repeater.load(tx.method, RawHttp.displayUrl(tx.scheme, tx.host, tx.port, tx.path), tx.requestHeaders, tx.requestBody)
                onSendToRepeater()
            }) { Text("Repeater") }
            OutlinedButton(onClick = { app.transactionStore.delete(tx.id); onBack() }) { Text("Delete") }
        }
        val request = rawMessage("${tx.method} ${tx.path.ifEmpty { "/" }} (${tx.scheme}://${tx.host}:${tx.port})", tx.requestHeaders, tx.requestBody)
        val response = if (tx.responseStatus == null) "(no response recorded)" else rawMessage("HTTP ${tx.responseStatus}", tx.responseHeaders, tx.responseBody)
        SelectionContainer {
            Column {
                Text("Request", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                Text(request, style = MaterialTheme.typography.bodySmall.copy(fontFamily = RoninMono))
                Text("Response", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp))
                Text(response, style = MaterialTheme.typography.bodySmall.copy(fontFamily = RoninMono))
            }
        }
    }
}
