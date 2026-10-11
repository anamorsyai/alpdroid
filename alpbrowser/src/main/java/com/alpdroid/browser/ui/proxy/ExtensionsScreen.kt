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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.alpdroid.browser.AlpBrowserApp
import com.alpdroid.browser.ExtensionStore
import com.alpdroid.browser.UserScript
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

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Extensions", style = MaterialTheme.typography.titleMedium)
        Text(
            "Add userscripts (.user.js — the Tampermonkey format). They stay installed and enabled until you remove them, " +
                "and run on every page that matches. Chrome (.crx) and Firefox (.xpi) packages can't run in Android's WebView.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { dialog = "paste" }) { Text("Paste") }
            OutlinedButton(onClick = { dialog = "url" }) { Text("From URL") }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("From file") }
        }
        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 6.dp))
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        if (scripts.isEmpty()) {
            Text("No extensions installed yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn {
            items(scripts, key = { it.id }) { s ->
                ScriptRow(
                    s,
                    onToggle = { app.extensions.setEnabled(s.id, it); reload() },
                    onRemove = { app.extensions.remove(s.id); message = "Removed ${s.name}"; reload() },
                    onUpdate = s.sourceUrl?.let { u -> { installFromUrl(u) } },
                )
                HorizontalDivider()
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
private fun ScriptRow(s: UserScript, onToggle: (Boolean) -> Unit, onRemove: () -> Unit, onUpdate: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name + if (s.version.isNotEmpty()) "  v${s.version}" else "", style = MaterialTheme.typography.titleSmall)
                if (s.description.isNotEmpty()) Text(s.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    (if (s.matches.isEmpty()) "all pages" else s.matches.take(3).joinToString(", ") + if (s.matches.size > 3) " …" else "") + " · ${s.runAt}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                )
            }
            Switch(checked = s.enabled, onCheckedChange = onToggle)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onRemove) { Text("Remove") }
            if (onUpdate != null) TextButton(onClick = onUpdate) { Text("Update") }
        }
    }
}

@Composable
private fun TextInputDialog(title: String, hint: String, multiline: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { MonoField(text, { text = it }, hint, singleLine = !multiline, minLines = if (multiline) 8 else 1) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text("Install") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
