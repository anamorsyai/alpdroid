package com.alpdroid.browser.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alpdroid.browser.AlpBrowserApp
import com.alpdroid.browser.ui.kit.AppButton
import com.alpdroid.browser.ui.kit.Kind
import com.alpdroid.browser.ui.kit.ScreenPad
import com.alpdroid.browser.ui.kit.SectionLabel
import com.alpdroid.browser.ui.kit.Segmented
import com.alpdroid.browser.ui.kit.ToggleRow
import com.alpdroid.browser.ui.theme.Hairline
import com.alpdroid.browser.ui.theme.SheetBg
import com.alpdroid.browser.ui.theme.TextMain
import com.alpdroid.browser.ui.theme.TextMuted

/**
 * The in-app settings sheet (the ⋮ menu): the browser's own capabilities, phone-sized. Toggles for
 * the ad/tracker blocker, JavaScript, desktop mode, Capture and remote DevTools; the bug-bounty/web-dev mode
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
        onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = SheetBg, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 18.dp).navigationBarsPadding()) {
            Text("Browser settings", style = MaterialTheme.typography.titleMedium, color = TextMain, modifier = Modifier.padding(horizontal = ScreenPad).padding(bottom = 6.dp))

            ToggleRow(
                "Ad & tracker blocker",
                if (adblock) "On · blocked $blocked this session · ${app.adBlocker.size()} domains" else "Off",
                adblock,
            ) { adblock = it; app.setAdblockEnabled(it); if (it) blocked = app.adBlocker.blockedCount.get().toInt() }

            ToggleRow("JavaScript", if (js) "On" else "Off — pages run no scripts", js) { js = it; app.setJsEnabled(it); onApplyJs(it) }

            ToggleRow("Desktop site", if (desktop) "Request the desktop version of pages" else "Mobile pages", desktop) {
                desktop = it; app.setDesktopMode(it); onApplyDesktop(it)
            }

            ToggleRow(
                "Capture traffic (proxy)",
                if (capture) "On — History shows full decrypted requests and responses (a bit slower)" else "Off — direct, fastest browsing; History lists URLs only",
                capture,
            ) { capture = it; app.setCaptureEnabled(it) }

            ToggleRow(
                "Remote DevTools (CDP)",
                if (devtools) "On — any local app can attach a debugger. Turn off when not debugging." else "Off — turn on to let the agent attach Chrome DevTools",
                devtools,
            ) { devtools = it; app.setDevtoolsEnabled(it); android.webkit.WebView.setWebContentsDebuggingEnabled(it) }

            HorizontalDivider(Modifier.padding(horizontal = ScreenPad, vertical = 10.dp), color = Hairline)

            SectionLabel("Mode")
            Segmented(listOf("Bug bounty", "Web dev"), if (mode == "bugbounty") 0 else 1, {
                mode = if (it == 0) "bugbounty" else "webdev"
                app.setMode(mode)
                capture = app.captureEnabled()
            })
            Text(
                if (mode == "bugbounty") "Intercept, match-and-replace rules, scope and full history. Turns Capture on." else "Watch only: console capture and history, no traffic pausing. Turns Capture off.",
                style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.padding(horizontal = ScreenPad).padding(bottom = 4.dp),
            )

            HorizontalDivider(Modifier.padding(horizontal = ScreenPad, vertical = 10.dp), color = Hairline)

            SectionLabel("Clear browsing data")
            Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPad), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton("Cookies", { app.clearBrowserData("cookies") }, Modifier.weight(1f), compact = true)
                AppButton("Cache", { app.clearBrowserData("cache"); onReload() }, Modifier.weight(1f), compact = true)
                AppButton("All", { app.clearBrowserData("all"); onReload() }, Modifier.weight(1f), kind = Kind.Danger, compact = true)
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPad).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton("Home", { onHome(); onDismiss() }, Modifier.weight(1f))
                AppButton("Agent control", { onDismiss(); onOpenControlInfo() }, Modifier.weight(1f), kind = Kind.Primary)
            }
        }
    }
}
