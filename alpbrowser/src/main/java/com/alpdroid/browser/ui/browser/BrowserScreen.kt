package com.alpdroid.browser.ui.browser

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.roundToInt
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.alpdroid.browser.AlpBrowserApp
import com.alpdroid.browser.BrowserTab
import com.alpdroid.browser.BrowserWebViewHolder
import com.alpdroid.browser.ui.kit.AppButton
import com.alpdroid.browser.ui.kit.AppCard
import com.alpdroid.browser.ui.kit.Kind
import com.alpdroid.browser.ui.kit.MonoStyle
import com.alpdroid.browser.ui.kit.ScreenPad
import com.alpdroid.browser.ui.theme.Accent
import com.alpdroid.browser.ui.theme.AccentSoft
import com.alpdroid.browser.ui.theme.Ground
import com.alpdroid.browser.ui.theme.Method
import com.alpdroid.browser.ui.theme.SheetBg
import com.alpdroid.browser.ui.theme.StatusOk
import com.alpdroid.browser.ui.theme.Surface1
import com.alpdroid.browser.ui.theme.TextFaint
import com.alpdroid.browser.ui.theme.TextMain
import com.alpdroid.browser.ui.theme.TextMuted
import com.alpdroid.browser.ui.theme.TextSoft
import com.alpdroid.proxy.BrowserCommand

/**
 * The browser surface, sized for a phone: one compact toolbar (back / forward / address / tabs / menu), a thin load
 * bar, and the page. Tabs live in [BrowserWebViewHolder] on the Application so they survive recomposition and switching
 * screens. While Capture or Intercept is on, traffic is pointed at the in-process MITM proxy (see
 * [AlpBrowserApp.applyProxyMode]); otherwise the WebView talks to the network directly, which is what keeps plain
 * browsing as fast as any WebView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(onOpenControlInfo: () -> Unit, onOpenRepeater: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as AlpBrowserApp
    val holder = app.browserWebViewHolder

    val tab = holder.active
    if (tab == null) { Box(Modifier.fillMaxSize()); return }

    var showSettings by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    // The whole Intercept screen (Held, History, Rules, Scope, Options) opens over the page from a floating button, so switching
    // between browsing and intercepting is one tap each way and the page underneath stays loaded.
    val interceptOn by app.intercept.enabledFlow.collectAsState()
    val held by app.intercept.pendingFlow.collectAsState()
    var showIntercept by remember { mutableStateOf(false) }

    // The tab in front gets its WebView on first view; the others are paused so they cost no CPU or battery.
    LaunchedEffect(tab.id) {
        ensureTabWebView(context, app, tab)
        holder.tabs.forEach { t -> t.webView?.let { if (t.id == tab.id) it.onResume() else it.onPause() } }
        app.startKeepAliveOnce(context)
    }

    val webView = tab.webView

    LaunchedEffect(app.browserCommandBus) {
        app.browserCommandBus.commands.collect { command ->
            when (command) {
                is BrowserCommand.Navigate -> {
                    val target = holder.active ?: return@collect
                    val wv = ensureTabWebView(context, app, target)
                    wv.loadUrl(normalizeUrl(command.url))
                }
            }
        }
    }

    var urlField by remember(tab.id) { mutableStateOf(tab.url) }
    var editing by remember(tab.id) { mutableStateOf(false) }
    // Follow the page's address while the user isn't typing.
    LaunchedEffect(tab.url, editing) { if (!editing) urlField = tab.url }
    val secure = tab.url.startsWith("https://")

    var areaSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Box(Modifier.fillMaxSize().onSizeChanged { areaSize = it }) {
    Column(modifier = Modifier.fillMaxSize().background(Ground)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            NavIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", enabled = tab.canGoBack) { webView?.goBack() }
            NavIcon(Icons.AutoMirrored.Filled.ArrowForward, "Forward", enabled = tab.canGoForward) { webView?.goForward() }
            Row(
                Modifier.weight(1f).height(40.dp).padding(horizontal = 4.dp).background(Surface1, RoundedCornerShape(12.dp)).padding(start = 10.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (secure) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (secure) "Secure" else "Not secure",
                    tint = if (secure) StatusOk else Method, modifier = Modifier.size(14.dp),
                )
                BasicTextField(
                    value = urlField,
                    onValueChange = { urlField = it; editing = true },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    singleLine = true,
                    textStyle = MonoStyle.copy(fontSize = 12.sp, color = TextMain),
                    cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        editing = false
                        webView?.loadUrl(normalizeUrl(urlField))
                    }),
                )
                IconButton(onClick = { webView?.reload() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, "Reload", modifier = Modifier.size(16.dp), tint = TextMuted)
                }
            }
            if (app.proxyActive()) Box(Modifier.size(8.dp).background(Accent, CircleShape)) // the page is going through the proxy
            TabsButton(holder.tabs.size) { showTabs = true }
            NavIcon(Icons.Default.MoreVert, "Menu") { showSettings = true }
        }

        if (tab.progress in 1..99) {
            LinearProgressIndicator(
                progress = { tab.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Accent,
                trackColor = Ground,
            )
        } else Box(Modifier.fillMaxWidth().height(2.dp))

        // Hosts whichever tab's WebView is in front; swapping tabs swaps the child.
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx -> FrameLayout(ctx) },
            update = { frame ->
                val wv = tab.webView
                if (!(frame.childCount == 1 && frame.getChildAt(0) === wv)) {
                    frame.removeAllViews()
                    if (wv != null) {
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        frame.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                }
            },
            onRelease = { it.removeAllViews() },
        )
    }

    // Intercept floats over the page instead of crowding the toolbar: a round button you can drag out of the way (tap = on/off, the
    // number is how many messages are paused), and, only while something is paused, a bar at the bottom with Review / Forward all.
    var fabX by rememberSaveable { mutableStateOf(0f) }
    var fabY by rememberSaveable { mutableStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fabBox = with(density) { 48.dp.toPx() + 32.dp.toPx() }
    Box(
        Modifier.align(Alignment.BottomEnd)
            .offset { androidx.compose.ui.unit.IntOffset(fabX.roundToInt(), fabY.roundToInt()) }
            .padding(16.dp)
            .pointerInput(areaSize) {
                detectDragGestures { change, drag ->
                    change.consume()
                    // Stays on screen: the button's own corner is the origin, so it can move left and up by the free space only.
                    fabX = (fabX + drag.x).coerceIn(-(areaSize.width - fabBox).coerceAtLeast(0f), 0f)
                    fabY = (fabY + drag.y).coerceIn(-(areaSize.height - fabBox).coerceAtLeast(0f), 0f)
                }
            },
    ) {
        InterceptButton(
            on = interceptOn, waiting = held.size,
            onClick = { showIntercept = true },
            // Long press is the quick on/off, for when the panel is not needed.
            onLongClick = { val next = !interceptOn; app.intercept.enabled = next; if (!next) app.intercept.releaseAll() },
        )
    }
    }

    if (showIntercept) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showIntercept = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(Modifier.fillMaxSize().background(Ground).statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showIntercept = false }) { Icon(Icons.Default.Close, "Back to the browser", tint = TextSoft) }
                    Text("Back to the page", style = MaterialTheme.typography.labelLarge, color = TextMuted)
                }
                com.alpdroid.browser.ui.proxy.ProxyScreen(
                    app,
                    onSendToRepeater = { showIntercept = false; onOpenRepeater() },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }

    if (showTabs) {
        TabsSheet(
            holder = holder,
            onNew = {
                showTabs = false
                openTab(context, app, HOME_URL)
            },
            onDismiss = { showTabs = false },
        )
    }

    if (showSettings) {
        BrowserSettingsSheet(
            app = app,
            onHome = { webView?.loadUrl(HOME_URL) },
            onReload = { webView?.reload() },
            onApplyJs = { js -> holder.allWebViews.forEach { it.settings.javaScriptEnabled = js; it.reload() } },
            onApplyDesktop = { desktop ->
                // Explicit mobile UA on the way back (not null): a null userAgentString doesn't reliably restore the real
                // mobile default on every OEM build. Also reset the text zoom some desktop CSS leaves oversized.
                holder.allWebViews.forEach {
                    it.settings.userAgentString = if (desktop) DESKTOP_UA else defaultUserAgent(context)
                    it.settings.textZoom = 100
                    it.reload()
                }
            },
            onOpenControlInfo = onOpenControlInfo,
            onDismiss = { showSettings = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabsSheet(holder: BrowserWebViewHolder, onNew: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = SheetBg, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = ScreenPad).padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Tabs  ${holder.tabs.size}/${BrowserWebViewHolder.MAX_TABS}", style = MaterialTheme.typography.titleMedium, color = TextMain, modifier = Modifier.weight(1f))
                AppButton("New tab", onNew, kind = Kind.Primary, compact = true, enabled = holder.tabs.size < BrowserWebViewHolder.MAX_TABS)
            }
            LazyColumn {
                items(holder.tabs.toList(), key = { it.id }) { t ->
                    AppCard(onClick = { holder.switchTo(t.id); onDismiss() }, selected = t.id == holder.activeId) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title.ifEmpty { "New tab" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, color = TextMain)
                                Text(t.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MonoStyle.copy(fontSize = 11.sp, color = TextMuted))
                            }
                            IconButton(onClick = { holder.close(t.id) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Close, "Close tab", tint = TextMuted) }
                        }
                    }
                }
            }
        }
    }
}

/** The tab-count button next to the address bar, shaped like the one in every mobile browser. */
/** Intercept on/off: a round floating button, orange and filled while on, with the number of paused messages. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun InterceptButton(on: Boolean, waiting: Int, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(Modifier.size(48.dp)) {
        Box(
            Modifier.size(48.dp).background(if (on) Accent else Surface1, CircleShape).border(BorderStroke(1.dp, if (on) Accent else TextFaint), CircleShape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (on) Icons.Default.PauseCircle else Icons.Outlined.PauseCircle,
                contentDescription = if (on) "Intercept is on — tap to open it, long-press to turn off" else "Open Intercept (long-press to turn on)",
                tint = if (on) Ground else TextSoft, modifier = Modifier.size(26.dp),
            )
        }
        if (waiting > 0) {
            Box(Modifier.align(Alignment.TopEnd).background(Ground, CircleShape).border(BorderStroke(1.dp, Accent), CircleShape).padding(horizontal = 5.dp)) {
                Text(if (waiting > 99) "99+" else waiting.toString(), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = Accent)
            }
        }
    }
}

@Composable
private fun TabsButton(count: Int, onClick: () -> Unit) {
    Box(modifier = Modifier.size(40.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(22.dp).border(1.5.dp, TextSoft, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(count.toString(), style = MonoStyle.copy(fontSize = 11.sp, color = TextMain)) }
    }
}

@Composable
private fun NavIcon(icon: ImageVector, contentDescription: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp), tint = if (enabled) TextMain else TextFaint)
    }
}
