package com.alpdroid.browser.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alpdroid.browser.AlpBrowserApp

/**
 * The in-app settings sheet (the ⋮ menu): the browser's own capabilities, phone-sized. Toggles for
 * the ad/tracker blocker, JavaScript, desktop mode and remote DevTools; the bug-bounty/web-dev mode
 * switch; clear-browsing-data actions; and a shortcut to the agent-control token. Values persist via
 * [AlpBrowserApp]; the live-WebView side effects (reload on JS/UA change) are applied by the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserSettingsSheet(
    app: AlpBrowserApp,
    onHome: () -> Unit,
    onReload: () -> Unit,
    onApplyJs: (Boolean) -> Unit,
    onApplyDesktop: (Boolean) -> Unit,
    onOpenControlInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var adblock by remember { mutableStateOf(app.adblockEnabled()) }
    var js by remember { mutableStateOf(app.jsEnabled()) }
    var desktop by remember { mutableStateOf(app.desktopMode()) }
    var devtools by remember { mutableStateOf(app.devtoolsEnabled()) }
    var capture by remember { mutableStateOf(app.captureEnabled()) }
    var mode by remember { mutableStateOf(app.mode()) }
    var blocked by remember { mutableIntStateOf(app.adBlocker.blockedCount.get().toInt()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Browser settings", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp))

            ToggleRow(
                title = "Ad & tracker blocker",
                subtitle = if (adblock) "On · blocked $blocked this session · ${app.adBlocker.size()} domains"
                else "Off",
                checked = adblock,
            ) { adblock = it; app.setAdblockEnabled(it); if (it) blocked = app.adBlocker.blockedCount.get().toInt() }

            ToggleRow("JavaScript", if (js) "On" else "Off — pages run no scripts", js) {
                js = it; app.setJsEnabled(it); onApplyJs(it)
            }

            ToggleRow("Desktop site", if (desktop) "Request the desktop version of pages" else "Mobile pages", desktop) {
                desktop = it; app.setDesktopMode(it); onApplyDesktop(it)
            }

            ToggleRow(
                title = "Capture traffic (proxy)",
                subtitle = if (capture) "On — History shows full decrypted requests and responses (a bit slower)"
                else "Off — direct, fastest browsing; History lists URLs only",
                checked = capture,
            ) { capture = it; app.setCaptureEnabled(it) }

            ToggleRow(
                title = "Remote DevTools (CDP)",
                subtitle = if (devtools) "On — any local app can attach a debugger. Turn off when not debugging."
                else "Off — turn on to let the agent attach Chrome DevTools",
                checked = devtools,
            ) { devtools = it; app.setDevtoolsEnabled(it); android.webkit.WebView.setWebContentsDebuggingEnabled(it) }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)

            Text("Mode", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeButton("Bug bounty", mode == "bugbounty", Modifier.weight(1f)) { mode = "bugbounty"; app.setMode("bugbounty") }
                ModeButton("Web dev", mode == "webdev", Modifier.weight(1f)) { mode = "webdev"; app.setMode("webdev") }
            }
            Text(
                if (mode == "bugbounty") "Intercept, match-and-replace rules, scope and full history are active."
                else "Watch only: console capture and history, no traffic pausing.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)

            Text("Clear browsing data", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { app.clearBrowserData("cookies") }, modifier = Modifier.weight(1f)) { Text("Cookies") }
                OutlinedButton(onClick = { app.clearBrowserData("cache"); onReload() }, modifier = Modifier.weight(1f)) { Text("Cache") }
                OutlinedButton(onClick = { app.clearBrowserData("all"); onReload() }, modifier = Modifier.weight(1f)) { Text("All") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onHome(); onDismiss() }, modifier = Modifier.weight(1f)) { Text("Home") }
                Button(onClick = { onDismiss(); onOpenControlInfo() }, modifier = Modifier.weight(1f)) { Text("Agent control") }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(10.dp)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text(label) }
    }
}
