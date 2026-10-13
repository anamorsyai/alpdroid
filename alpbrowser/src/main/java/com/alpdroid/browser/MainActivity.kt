package com.alpdroid.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.alpdroid.browser.ui.browser.BrowserScreen
import com.alpdroid.browser.ui.kit.AppButton
import com.alpdroid.browser.ui.kit.AppNavBar
import com.alpdroid.browser.ui.kit.Kind
import com.alpdroid.browser.ui.proxy.ExtensionsScreen
import com.alpdroid.browser.ui.proxy.ProxyScreen
import com.alpdroid.browser.ui.proxy.RepeaterScreen
import com.alpdroid.browser.ui.theme.Ground
import com.alpdroid.browser.ui.theme.RoninMono
import com.alpdroid.browser.ui.theme.RoninTheme
import com.alpdroid.browser.ui.theme.SheetBg
import com.alpdroid.browser.ui.theme.TextMain
import com.alpdroid.browser.ui.theme.TextSoft

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AlpBrowserApp
        setContent {
            RoninTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Ground) {
                    var showControl by remember { mutableStateOf(false) }
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    val held by app.intercept.pendingFlow.collectAsState()
                    Scaffold(
                        containerColor = Ground,
                        bottomBar = {
                            AppNavBar(
                                items = listOf(
                                    "Browser" to Icons.Default.Language,
                                    "Proxy" to Icons.Default.Security,
                                    "Repeater" to Icons.Default.Repeat,
                                    "Extensions" to Icons.Default.Extension,
                                ),
                                selected = tab, onSelect = { tab = it }, badge = mapOf(1 to held.size),
                            )
                        },
                    ) { padding ->
                        val m = Modifier.padding(padding)
                        when (tab) {
                            0 -> Box(m) { BrowserScreen(onOpenControlInfo = { showControl = true }, onOpenRepeater = { tab = 2 }) }
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
        appendLine(if (app.openLocalAccess()) "Token:        not needed on this phone (Open local access is on)" else "Token:        ${app.controlToken}")
        appendLine()
        appendLine("From the AlpDroid agent (inside Alpine):")
        if (!app.openLocalAccess()) appendLine("  export ALPBROWSER_TOKEN=${app.controlToken}")
        appendLine("  browserctl ping")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SheetBg,
        confirmButton = { AppButton("Copy token", { clipboard.setText(AnnotatedString(app.controlToken)); onDismiss() }, kind = Kind.Primary, compact = true) },
        dismissButton = { AppButton("Close", onDismiss, kind = Kind.Ghost, compact = true) },
        title = { Text("Agent control", style = MaterialTheme.typography.titleMedium, color = TextMain) },
        text = {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Text(info, style = MaterialTheme.typography.bodySmall.copy(fontFamily = RoninMono), color = TextSoft)
            }
        },
    )
}
