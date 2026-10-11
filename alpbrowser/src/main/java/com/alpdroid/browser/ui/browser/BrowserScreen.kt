package com.alpdroid.browser.ui.browser

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.alpdroid.browser.AlpBrowserApp
import com.alpdroid.browser.BrowserTab
import com.alpdroid.browser.BrowserWebViewHolder
import com.alpdroid.browser.ui.theme.RoninMono
import com.alpdroid.proxy.BrowserCommand

/**
 * The browser surface, sized for a phone: one compact toolbar (back / forward / address / tabs / menu), a thin load
 * bar, and the page. Tabs live in [BrowserWebViewHolder] on the Application so they survive recomposition and switching
 * screens. While Capture or Intercept is on, traffic is pointed at the in-process MITM proxy (see
 * [AlpBrowserApp.applyProxyMode]); otherwise the WebView talks to the network directly, which is what keeps plain
 * browsing as fast as any WebView.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(onOpenControlInfo: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AlpBrowserApp
    val holder = app.browserWebViewHolder

    val tab = holder.active
    if (tab == null) { Box(Modifier.fillMaxSize()); return }

    var showSettings by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }

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

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                NavIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", enabled = tab.canGoBack) { webView?.goBack() }
                NavIcon(Icons.AutoMirrored.Filled.ArrowForward, "Forward", enabled = tab.canGoForward) { webView?.goForward() }
                OutlinedTextField(
                    value = urlField,
                    onValueChange = { urlField = it; editing = true },
                    modifier = Modifier.weight(1f).height(50.dp).padding(horizontal = 4.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = RoninMono),
                    shape = RoundedCornerShape(25.dp),
                    leadingIcon = {
                        Icon(
                            if (secure) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = if (secure) "Secure" else "Not secure",
                            tint = if (secure) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { webView?.reload() }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Refresh, "Reload", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        editing = false
                        webView?.loadUrl(normalizeUrl(urlField))
                    }),
                )
                TabsButton(holder.tabs.size) { showTabs = true }
                NavIcon(Icons.Default.MoreVert, "Menu") { showSettings = true }
            }
        }

        if (tab.progress in 1..99) {
            LinearProgressIndicator(
                progress = { tab.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface,
            )
        }

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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tabs (${holder.tabs.size}/${BrowserWebViewHolder.MAX_TABS})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Button(onClick = onNew, enabled = holder.tabs.size < BrowserWebViewHolder.MAX_TABS) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Text(" New tab")
                }
            }
            LazyColumn(Modifier.padding(top = 8.dp)) {
                items(holder.tabs.toList(), key = { it.id }) { t ->
                    TabRow(t, active = t.id == holder.activeId, onSelect = { holder.switchTo(t.id); onDismiss() }, onClose = { holder.close(t.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TabRow(t: BrowserTab, active: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                t.title.ifEmpty { "New tab" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(t.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall.copy(fontFamily = RoninMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close tab") }
    }
}

/** The tab-count button next to the address bar, shaped like the one in every mobile browser. */
@Composable
private fun TabsButton(count: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp).padding(8.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface), modifier = Modifier.size(22.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Text(count.toString(), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun NavIcon(icon: ImageVector, contentDescription: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(
            icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        )
    }
}
