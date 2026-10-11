package com.alpdroid.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.alpdroid.browser.ui.proxy.ExtensionsScreen
import com.alpdroid.browser.ui.proxy.ProxyScreen
import com.alpdroid.browser.ui.proxy.RepeaterScreen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.alpdroid.browser.ui.browser.BrowserScreen
import com.alpdroid.browser.ui.theme.RoninMono
import com.alpdroid.browser.ui.theme.RoninTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AlpBrowserApp
        setContent {
            RoninTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var showControl by remember { mutableStateOf(false) }
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    val held by app.intercept.pendingFlow.collectAsState()
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        bottomBar = {
                            NavigationBar {
                                val items = listOf(
                                    Triple("Browser", Icons.Default.Language, 0),
                                    Triple("Proxy", Icons.Default.Security, 1),
                                    Triple("Repeater", Icons.Default.Repeat, 2),
                                    Triple("Extensions", Icons.Default.Extension, 3),
                                )
                                items.forEach { (label, icon, i) ->
                                    NavigationBarItem(
                                        selected = tab == i, onClick = { tab = i },
                                        icon = {
                                            if (i == 1 && held.isNotEmpty()) BadgedBox(badge = { Badge { Text(held.size.toString()) } }) { Icon(icon, label) }
                                            else Icon(icon, label)
                                        },
                                        label = { Text(label) },
                                    )
                                }
                            }
                        },
                    ) { padding ->
                        val m = Modifier.padding(padding)
                        when (tab) {
                            0 -> Box(m) { BrowserScreen(proxyPort = AlpBrowserApp.PROXY_PORT, onOpenControlInfo = { showControl = true }) }
                            1 -> ProxyScreen(app, onSendToRepeater = { tab = 2 }, modifier = m)
                            2 -> RepeaterScreen(app, modifier = m)
                            else -> ExtensionsScreen(app, modifier = m)
                        }
                    }
                    if (showControl) ControlInfoDialog(app) { showControl = false }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ControlInfoDialog(app: AlpBrowserApp, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val info = buildString {
        appendLine("Control API:  http://127.0.0.1:${AlpBrowserApp.CONTROL_PORT}")
        appendLine("Proxy:        127.0.0.1:${AlpBrowserApp.PROXY_PORT}")
        appendLine("Token:        ${app.controlToken}")
        appendLine()
        appendLine("From the AlpDroid agent (inside Alpine):")
        appendLine("  export ALPBROWSER_TOKEN=${app.controlToken}")
        appendLine("  browserctl ping")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(app.controlToken)); onDismiss() }) { Text("Copy token") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Agent control") },
        text = {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Text(info, style = MaterialTheme.typography.bodySmall.copy(fontFamily = RoninMono),
                    color = MaterialTheme.colorScheme.onSurface)
            }
        },
    )
}
