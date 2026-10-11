package com.alpdroid.browser.ui.proxy

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alpdroid.browser.AlpBrowserApp
import com.alpdroid.browser.ExtensionStore
import com.alpdroid.browser.UserScript
import com.alpdroid.browser.ui.kit.AppButton
import com.alpdroid.browser.ui.kit.AppCard
import com.alpdroid.browser.ui.kit.Field
import com.alpdroid.browser.ui.kit.Kind
import com.alpdroid.browser.ui.kit.ScreenHeader
import com.alpdroid.browser.ui.kit.ScreenPad
import com.alpdroid.browser.ui.theme.Accent
import com.alpdroid.browser.ui.theme.OnAccent
import com.alpdroid.browser.ui.theme.SecondaryBg
import com.alpdroid.browser.ui.theme.SheetBg
import com.alpdroid.browser.ui.theme.TextMain
import com.alpdroid.browser.ui.theme.TextMuted
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Extensions: install userscripts (paste, URL or file), switch them on/off, update or remove them. They persist. */
@Composable
fun ExtensionsScreen(app: AlpBrowserApp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scripts by remember { mutableStateOf(app.extensions.list()) }
    var message by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<String?>(null) } // "paste" | "url"

    fun reload() { scripts = app.extensions.list(); app.extensionInjector.refresh() }

    fun report(r: ExtensionStore.InstallResult) {
        message = when (r) {
            is ExtensionStore.InstallResult.Installed -> (if (r.replaced) "Updated " else "Installed ") + r.script.name + " — reload the page to apply."
            is ExtensionStore.InstallResult.Rejected -> r.reason
        }
        reload()
    }

    fun installFromUrl(url: String) {
        scope.launch {
            message = "Downloading…"
            val r = withContext(Dispatchers.IO) {
                runCatching { app.extensions.install(fetchText(url), url) }
                    .getOrElse { ExtensionStore.InstallResult.Rejected("Could not download: ${it.message}") }
            }
            report(r)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot read file")
                    app.extensions.install(String(bytes, Charsets.UTF_8))
                }.getOrElse { ExtensionStore.InstallResult.Rejected("Could not read file: ${it.message}") }
            }
            report(r)
        }
    }

    Column(modifier.fillMaxSize()) {
        ScreenHeader("Extensions", status = if (scripts.isEmpty()) "None installed" else "${scripts.size} installed · ${scripts.count { it.enabled }} on", statusOn = scripts.any { it.enabled })
        Text(
            "Userscripts (.user.js, the Tampermonkey format). They stay installed and enabled until you remove them, and run on every page that matches. Chrome (.crx) and Firefox (.xpi) packages can't run in Android's WebView.",
            style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.padding(horizontal = ScreenPad).padding(bottom = 12.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPad), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Paste", { dialog = "paste" }, Modifier.weight(1f), kind = Kind.Primary, compact = true)
            AppButton("From URL", { dialog = "url" }, Modifier.weight(1f), compact = true)
            AppButton("From file", { picker.launch(arrayOf("*/*")) }, Modifier.weight(1f), compact = true)
        }
        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall, color = Accent, modifier = Modifier.padding(horizontal = ScreenPad).padding(top = 10.dp))
        LazyColumn(Modifier.weight(1f).padding(top = 10.dp)) {
            if (scripts.isEmpty()) item { Text("No extensions installed yet.", style = MaterialTheme.typography.bodyMedium, color = TextMuted, modifier = Modifier.padding(horizontal = ScreenPad, vertical = 8.dp)) }
            items(scripts, key = { it.id }) { s ->
                ScriptCard(
                    s,
                    onToggle = { app.extensions.setEnabled(s.id, it); reload() },
                    onRemove = { app.extensions.remove(s.id); message = "Removed ${s.name}"; reload() },
                    onUpdate = s.sourceUrl?.let { u -> { installFromUrl(u) } },
                )
            }
        }
    }

    when (dialog) {
        "paste" -> TextInputDialog("Paste a userscript", "// ==UserScript==\n…", multiline = true, onDismiss = { dialog = null }) { text ->
            dialog = null
            scope.launch { report(withContext(Dispatchers.IO) { app.extensions.install(text) }) }
        }
        "url" -> TextInputDialog("Install from URL", "https://…/script.user.js", multiline = false, onDismiss = { dialog = null }) { url ->
            dialog = null
            installFromUrl(url.trim())
        }
    }
}

@Composable
private fun ScriptCard(s: UserScript, onToggle: (Boolean) -> Unit, onRemove: () -> Unit, onUpdate: (() -> Unit)?) {
    AppCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(s.name + if (s.version.isNotEmpty()) "  v${s.version}" else "", style = MaterialTheme.typography.titleSmall, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (s.description.isNotEmpty()) Text(s.description, style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (s.matches.isEmpty()) "all pages" else s.matches.take(2).joinToString(", ") + if (s.matches.size > 2) " …" else "") + " · ${s.runAt}",
                        style = MaterialTheme.typography.labelSmall, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = s.enabled, onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedThumbColor = OnAccent, checkedTrackColor = Accent, checkedBorderColor = Accent,
                        uncheckedThumbColor = TextMuted, uncheckedTrackColor = SecondaryBg, uncheckedBorderColor = SecondaryBg),
                )
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onUpdate != null) AppButton("Update", onUpdate, kind = Kind.Ghost, compact = true)
                AppButton("Remove", onRemove, kind = Kind.Danger, compact = true)
            }
        }
    }
}

@Composable
private fun TextInputDialog(title: String, hint: String, multiline: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SheetBg,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, color = TextMain) },
        text = { Field(text, { text = it }, placeholder = hint, singleLine = !multiline, minLines = if (multiline) 8 else 1) },
        confirmButton = { AppButton("Install", { onConfirm(text) }, kind = Kind.Primary, compact = true, enabled = text.isNotBlank()) },
        dismissButton = { AppButton("Cancel", onDismiss, kind = Kind.Ghost, compact = true) },
    )
}

/** Downloads a script directly (not through the intercepting proxy), http(s) only, capped in size. */
private fun fetchText(url: String): String {
    val lower = url.lowercase()
    require(lower.startsWith("http://") || lower.startsWith("https://")) { "URL must start with http:// or https://" }
    val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 20_000 }
    try {
        require(conn.responseCode in 200..299) { "server answered ${conn.responseCode}" }
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        conn.inputStream.use { input ->
            while (true) {
                val n = input.read(buf); if (n < 0) break
                out.write(buf, 0, n)
                require(out.size() <= ExtensionStore.MAX_SCRIPT_BYTES) { "file is too large" }
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    } finally { conn.disconnect() }
}
