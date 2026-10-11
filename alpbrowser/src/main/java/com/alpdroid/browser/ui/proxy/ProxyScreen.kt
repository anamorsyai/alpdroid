package com.alpdroid.browser.ui.proxy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alpdroid.browser.AlpBrowserApp
import com.alpdroid.browser.InterceptController
import com.alpdroid.browser.RawHttp
import com.alpdroid.browser.ui.kit.AppButton
import com.alpdroid.browser.ui.kit.AppCard
import com.alpdroid.browser.ui.kit.ChipTabs
import com.alpdroid.browser.ui.kit.Field
import com.alpdroid.browser.ui.kit.Kind
import com.alpdroid.browser.ui.kit.LabeledField
import com.alpdroid.browser.ui.kit.MethodText
import com.alpdroid.browser.ui.kit.MonoStyle
import com.alpdroid.browser.ui.kit.ScreenHeader
import com.alpdroid.browser.ui.kit.ScreenPad
import com.alpdroid.browser.ui.kit.SectionLabel
import com.alpdroid.browser.ui.kit.SmallChip
import com.alpdroid.browser.ui.kit.StatusText
import com.alpdroid.browser.ui.kit.Tag
import com.alpdroid.browser.ui.kit.ToggleRow
import com.alpdroid.browser.ui.theme.Accent
import com.alpdroid.browser.ui.theme.AccentSoft
import com.alpdroid.browser.ui.theme.Hairline
import com.alpdroid.browser.ui.theme.OnAccent
import com.alpdroid.browser.ui.theme.SecondaryBg
import com.alpdroid.browser.ui.theme.SheetBg
import com.alpdroid.browser.ui.theme.TextMain
import com.alpdroid.browser.ui.theme.TextMuted
import com.alpdroid.browser.ui.theme.TextSoft
import com.alpdroid.proxy.HttpTransaction
import com.alpdroid.proxy.RequestAction
import com.alpdroid.proxy.ResponseAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Proxy: what is waiting (Held), everything captured (History), Rules, Scope and Options. */
@Composable
fun ProxyScreen(app: AlpBrowserApp, onSendToRepeater: () -> Unit, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var scope by remember { mutableStateOf(app.scope()) }
    var rulesCount by remember { mutableStateOf(app.intercept.rules.all().size) }
    val held by app.intercept.pendingFlow.collectAsState()
    val on by app.intercept.enabledFlow.collectAsState()
    val captured by app.transactionStore.transactions.collectAsState()

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = listOf("Intercept", "History", "Rules", "Scope", "Options")[tab],
            status = when (tab) {
                0 -> if (on) "On · ${held.size} waiting · ${app.intercept.hostFilter.ifBlank { "all hosts" }}" else "Off · nothing is paused"
                1 -> if (app.proxyActive()) "Capturing · ${captured.count { !it.opaque }} requests" else "Direct browsing · URLs only"
                2 -> "$rulesCount rule${if (rulesCount == 1) "" else "s"}"
                3 -> if (scope.isEmpty()) "Recording every site" else "${scope.size} pattern${if (scope.size == 1) "" else "s"} · other sites hidden"
                else -> "Proxy options"
            },
            statusOn = when (tab) { 0 -> on; 1 -> app.proxyActive(); 3 -> scope.isNotEmpty(); else -> false },
            trailing = if (tab == 0) {
                { Switch(
                    checked = on, onCheckedChange = { app.intercept.enabled = it; if (!it) app.intercept.releaseAll() },
                    colors = SwitchDefaults.colors(checkedThumbColor = OnAccent, checkedTrackColor = Accent, checkedBorderColor = Accent,
                        uncheckedThumbColor = TextMuted, uncheckedTrackColor = SecondaryBg, uncheckedBorderColor = SecondaryBg),
                ) }
            } else null,
        )
        ChipTabs(
            listOf(if (held.isEmpty()) "Held" else "Held (${held.size})", "History", "Rules", if (scope.isEmpty()) "Scope" else "Scope ●", "Options"),
            tab, { tab = it },
        )
        // A scope silently hides every other host from History, so it is always announced, whichever tab is open.
        if (scope.isNotEmpty() && tab != 3) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = ScreenPad).padding(bottom = 8.dp).background(AccentSoft, RoundedCornerShape(12.dp)).padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Scope is on — only ${scope.joinToString(", ")} is recorded.", style = MaterialTheme.typography.bodySmall, color = TextSoft, modifier = Modifier.weight(1f))
                AppButton("Clear", { app.setScope(emptyList()); scope = emptyList() }, kind = Kind.Ghost, compact = true)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> HeldTab(app, held, on, onSendToRepeater)
                1 -> HistoryTab(app, onSendToRepeater)
                2 -> RulesTab(app) { rulesCount = it }
                3 -> ScopeTab(scope) { app.setScope(it); scope = app.scope() }
                else -> OptionsTab(app, on)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- Held

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeldTab(app: AlpBrowserApp, held: List<InterceptController.Held>, on: Boolean, onSendToRepeater: () -> Unit) {
    val ic = app.intercept
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = held.firstOrNull { it.holdId == selectedId }

    Column(Modifier.fillMaxSize()) {
        if (held.isEmpty()) {
            Text(
                if (on) "Waiting for traffic… browse in the Browser tab and requests will pause here."
                else "Turn Intercept on with the switch above, then browse. Each request pauses here so you can edit it, forward it or drop it.",
                style = MaterialTheme.typography.bodyMedium, color = TextMuted, modifier = Modifier.padding(horizontal = ScreenPad, vertical = 8.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(held, key = { it.holdId }) { h ->
                    AppCard(onClick = { selectedId = h.holdId }, selected = h.holdId == selectedId) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Tag(if (h.kind == "request") "REQ" else "RES", color = if (h.kind == "request") Accent else com.alpdroid.browser.ui.theme.StatusRedirect)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (h.kind == "request") "${h.method} ${h.host}" else "${h.status ?: "?"} from ${h.host}",
                                    style = MaterialTheme.typography.titleSmall, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                if (h.kind == "request") Text(h.target, style = MonoStyle.copy(fontSize = 11.sp, color = TextMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPad, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton("Drop all", { held.forEach { dropHeld(ic, it) }; selectedId = null }, Modifier.weight(1f), kind = Kind.Danger, compact = true)
                AppButton("Forward all (${held.size})", { ic.releaseAll(); selectedId = null }, Modifier.weight(2f), kind = Kind.Primary, compact = true)
            }
        }
    }

    if (selected != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedId = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = SheetBg, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        ) { HeldSheet(app, selected, onDone = { selectedId = null }, onSendToRepeater = onSendToRepeater) }
    }
}

private fun dropHeld(ic: InterceptController, h: InterceptController.Held) {
    if (h.kind == "request") ic.resolveRequest(h.holdId, RequestAction.Drop) else ic.resolveResponse(h.holdId, ResponseAction.Drop)
}

@Composable
private fun HeldSheet(app: AlpBrowserApp, h: InterceptController.Held, onDone: () -> Unit, onSendToRepeater: () -> Unit) {
    val isReq = h.kind == "request"
    val ic = app.intercept
    val origBody = remember(h.holdId) { RawHttp.bodyToText(h.body) }
    // A body too big for a text field is not editable (it would freeze the screen); it is forwarded unchanged.
    val editable = origBody != null && origBody.length <= RepeaterModel.MAX_EDITABLE_BODY
    var line1 by remember(h.holdId) { mutableStateOf(if (isReq) h.method else (h.status?.toString() ?: "")) }
    var target by remember(h.holdId) { mutableStateOf(h.target) }
    var headers by remember(h.holdId) { mutableStateOf(RawHttp.headersToText(h.headers)) }
    var body by remember(h.holdId) { mutableStateOf(if (editable) origBody ?: "" else "") }

    fun toRepeater() {
        val scheme = if (h.port == 443) "https" else "http"
        app.repeater.load(line1.ifBlank { h.method }, RawHttp.displayUrl(scheme, h.host, h.port, target), RawHttp.parseHeaders(headers), if (editable) RawHttp.textToBody(body) else h.body)
        onSendToRepeater()
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPad).padding(bottom = 18.dp).navigationBarsPadding()) {
        Text(if (isReq) "Request to ${h.host}:${h.port}" else "Response from ${h.host}:${h.port}", style = MaterialTheme.typography.titleMedium, color = TextMain, modifier = Modifier.padding(bottom = 12.dp))
        if (isReq) {
            Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(line1, { line1 = it }, Modifier.width(92.dp), singleLine = true, placeholder = "Method")
                Field(target, { target = it }, Modifier.weight(1f), singleLine = true, placeholder = "Path and query")
            }
        } else {
            LabeledField("Status code", line1, { line1 = it }, singleLine = true)
        }
        LabeledField("Headers", headers, { headers = it }, minLines = 4)
        if (!editable) {
            Text(
                if (origBody == null) "Binary body (${h.body?.size ?: 0} bytes) — forwarded unchanged."
                else "Large body (${h.body?.size ?: 0} bytes) — too big to edit here, forwarded unchanged.",
                style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.padding(bottom = 12.dp),
            )
        } else {
            LabeledField("Body", body, { body = it }, minLines = 3)
            Row(Modifier.padding(bottom = 12.dp)) {
                SmallChip("Prettify body", on = false, onClick = { body = com.alpdroid.browser.BodyFormat.pretty(RawHttp.parseHeaders(headers), body.toByteArray()) ?: body })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Drop", { dropHeld(ic, h); onDone() }, Modifier.weight(1f), kind = Kind.Danger)
            if (isReq) AppButton("Repeater", { toRepeater() }, Modifier.weight(1.2f))
            AppButton("Forward", {
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
            }, Modifier.weight(2f), kind = Kind.Primary)
        }
        if (isReq) {
            Text(
                "Repeater keeps this request paused here. “Repeater + drop” also stops the browser's own copy from ever being sent.",
                style = MaterialTheme.typography.labelSmall, color = TextMuted, modifier = Modifier.padding(top = 10.dp),
            )
            AppButton("Repeater + drop", { toRepeater(); dropHeld(ic, h); onDone() }, Modifier.padding(top = 8.dp), kind = Kind.Ghost, compact = true)
        }
    }
}

// ---------------------------------------------------------------------------------------------------- History

@Composable
private fun HistoryTab(app: AlpBrowserApp, onSendToRepeater: () -> Unit) {
    val all by app.transactionStore.transactions.collectAsState()
    var filter by remember { mutableStateOf("") }
    var filterOpen by remember { mutableStateOf(false) }
    var hideTunnels by remember { mutableStateOf(true) }
    var openId by remember { mutableStateOf<String?>(null) }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var capturePulse by remember { mutableStateOf(0) } // read below so the notice disappears right after Capture is turned on
    @Suppress("UNUSED_EXPRESSION") capturePulse
    val open = all.firstOrNull { it.id == openId }

    val groups = remember(all, filter, hideTunnels) {
        val f = filter.trim()
        val rows = all.asReversed().filter { (!hideTunnels || !it.opaque) &&
            (f.isEmpty() || it.host.contains(f, true) || it.path.contains(f, true) || it.method.equals(f, true) || it.responseStatus?.toString() == f) }
        rows.groupBy { it.host }.toList() // groupBy keeps first-seen order, i.e. the host with the newest request first
    }

    if (open != null) {
        TxDetail(app, open, onBack = { openId = null }, onSendToRepeater = onSendToRepeater)
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (!app.proxyActive()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = ScreenPad).padding(bottom = 6.dp).background(AccentSoft, RoundedCornerShape(12.dp)).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Direct browsing: only URLs are listed. Turn on Capture to see responses.", style = MaterialTheme.typography.bodySmall, color = TextSoft, modifier = Modifier.weight(1f))
                    AppButton("Capture", { capturePulse++; app.setCaptureEnabled(true) }, kind = Kind.Primary, compact = true)
                }
            }
            if (filterOpen) {
                Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPad).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(filter, { filter = it }, Modifier.weight(1f), singleLine = true, placeholder = "host, path, method or status")
                    SmallChip("Tunnels", on = !hideTunnels, onClick = { hideTunnels = !hideTunnels })
                    AppButton("Clear", { app.transactionStore.clear() }, kind = Kind.Danger, compact = true)
                }
            }
            if (groups.isEmpty()) {
                Text("Nothing recorded yet.", style = MaterialTheme.typography.bodyMedium, color = TextMuted, modifier = Modifier.padding(horizontal = ScreenPad, vertical = 8.dp))
            }
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 84.dp)) {
                groups.forEach { (host, txs) ->
                    item(key = "h:$host") {
                        Row(
                            Modifier.fillMaxWidth().clickable { collapsed = if (host in collapsed) collapsed - host else collapsed + host }
                                .padding(horizontal = ScreenPad).padding(top = 10.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(host, style = MaterialTheme.typography.titleSmall, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${txs.size}${if (host in collapsed) "  ▸" else ""}", style = MonoStyle.copy(fontSize = 11.sp, color = TextMuted))
                        }
                        HorizontalDivider(Modifier.padding(horizontal = ScreenPad), color = Hairline)
                    }
                    if (host !in collapsed) items(txs, key = { it.id }) { tx ->
                        Row(
                            Modifier.fillMaxWidth().clickable { openId = tx.id }.padding(horizontal = ScreenPad, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MethodText(tx.method, Modifier.width(40.dp))
                            if (tx.opaque) Text("tunnel", style = MonoStyle.copy(fontSize = 11.sp, color = TextMuted), modifier = Modifier.width(34.dp))
                            else StatusText(tx.responseStatus, Modifier.width(34.dp))
                            Text(tx.path.ifEmpty { "/" }, style = MonoStyle.copy(fontSize = 11.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            tx.durationMs?.let { Text("$it", style = MonoStyle.copy(fontSize = 11.sp, color = TextMuted)) }
                        }
                    }
                }
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(18.dp).background(Accent, RoundedCornerShape(25.dp)).clickable { filterOpen = !filterOpen }.padding(horizontal = 20.dp, vertical = 14.dp),
        ) { Text(if (filterOpen) "Close" else "Filter", style = MaterialTheme.typography.titleSmall, color = OnAccent) }
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
            Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPad), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppButton("Back", onBack, Modifier.weight(1f), kind = Kind.Ghost, compact = true)
                if (!tx.opaque) AppButton("Repeater", {
                    app.repeater.load(tx.method, RawHttp.displayUrl(tx.scheme, tx.host, tx.port, tx.path), tx.requestHeaders, tx.requestBody)
                    onSendToRepeater()
                }, Modifier.weight(1.3f), kind = Kind.Primary, compact = true)
                AppButton("Copy", { clipboard.setText(AnnotatedString(request + "\n\n" + response)) }, Modifier.weight(1f), compact = true)
                AppButton("Delete", { app.transactionStore.delete(tx.id); onBack() }, Modifier.weight(1f), kind = Kind.Danger, compact = true)
            }
            Row(Modifier.padding(horizontal = ScreenPad).padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChip("Pretty", on = pretty, onClick = { pretty = !pretty })
                SmallChip("Raw", on = !pretty, onClick = { pretty = !pretty })
            }
            SectionLabel("Request")
        }
        items(reqChunks.size) { MonoChunk(reqChunks[it]) }
        item { SectionLabel("Response") }
        items(resChunks.size) { MonoChunk(resChunks[it]) }
        item { Box(Modifier.padding(bottom = 24.dp)) }
    }
}

// ---------------------------------------------------------------------------------------------------- Scope & Options

@Composable
private fun ScopeTab(scope: List<String>, onSave: (List<String>) -> Unit) {
    var text by remember(scope) { mutableStateOf(scope.joinToString("\n")) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPad)) {
        Text(
            "Only hosts matching these patterns are recorded in History (and by Capture). One per line; * is a wildcard, e.g. *.example.com. Leave it empty to record every site.",
            style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.padding(bottom = 12.dp),
        )
        LabeledField("Hosts (one per line)", text, { text = it }, minLines = 4)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Save scope", { onSave(text.lines().map { it.trim() }.filter { it.isNotEmpty() }) }, Modifier.weight(1f), kind = Kind.Primary)
            AppButton("Record everything", { text = ""; onSave(emptyList()) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun OptionsTab(app: AlpBrowserApp, interceptOn: Boolean) {
    val ic = app.intercept
    var responses by remember { mutableStateOf(ic.interceptResponses) }
    var skipStatic by remember { mutableStateOf(ic.skipStatic) }
    var capture by remember { mutableStateOf(app.captureEnabled()) }
    var host by remember { mutableStateOf(ic.hostFilter) }
    val stats = app.browserEngine.stats
    var down by remember { mutableStateOf(0L) }
    var up by remember { mutableStateOf(0L) }
    var requests by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { down = stats.bytesDown.get(); up = stats.bytesUp.get(); requests = stats.requests.get(); delay(1000) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ToggleRow("Pause responses too", "Also hold each server reply, not just the request", responses) { responses = it; ic.interceptResponses = it }
        ToggleRow("Skip images, CSS and JS", "Never pause static files", skipStatic) { skipStatic = it; ic.skipStatic = it }
        ToggleRow(
            "Capture (proxy)",
            if (interceptOn) "On while Intercept is on" else if (capture) "Traffic goes through the proxy: full decrypted History" else "Off: direct, fastest browsing",
            capture || interceptOn,
        ) { if (!interceptOn) { capture = it; app.setCaptureEnabled(it) } }
        Column(Modifier.padding(horizontal = ScreenPad).padding(top = 6.dp)) {
            LabeledField("Only pause hosts containing (blank = all)", host, { host = it; ic.hostFilter = it }, singleLine = true)
        }
        SectionLabel("Through the proxy this session")
        AppCard {
            Column {
                Text("↓ ${formatBytes(down)}    ↑ ${formatBytes(up)}    $requests requests", style = MonoStyle.copy(fontSize = 13.sp, color = TextMain))
                Text(
                    "These are the real figures. While Capture is on, a speed meter that also reads the phone's loopback interface sees the browser-to-proxy leg too, so it can show about double.",
                    style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Box(Modifier.padding(bottom = 24.dp))
    }
}

private fun formatBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> String.format("%.1f KB", n / 1024.0)
    else -> String.format("%.1f MB", n / 1024.0 / 1024.0)
}
