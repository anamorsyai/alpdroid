package com.alpdroid.browser.ui.proxy

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.alpdroid.browser.AlpBrowserApp
import com.alpdroid.browser.RuleEngine
import com.alpdroid.browser.ui.kit.AppButton
import com.alpdroid.browser.ui.kit.AppCard
import com.alpdroid.browser.ui.kit.Kind
import com.alpdroid.browser.ui.kit.LabeledField
import com.alpdroid.browser.ui.kit.MonoStyle
import com.alpdroid.browser.ui.kit.ScreenPad
import com.alpdroid.browser.ui.kit.SectionLabel
import com.alpdroid.browser.ui.kit.SmallChip
import com.alpdroid.browser.ui.theme.Accent
import com.alpdroid.browser.ui.theme.OnAccent
import com.alpdroid.browser.ui.theme.SecondaryBg
import com.alpdroid.browser.ui.theme.TextMuted

/** Match & replace: rewrite matching traffic automatically, with no manual breakpoint. Saved across restarts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesTab(app: AlpBrowserApp, onCount: (Int) -> Unit) {
    val engine = app.intercept.rules
    var rules by remember { mutableStateOf(engine.all()) }
    var part by remember { mutableStateOf(RuleEngine.Part.RES_BODY) }
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    val isHeader = part == RuleEngine.Part.REQ_HEADER || part == RuleEngine.Part.RES_HEADER
    fun refresh() { rules = engine.all(); onCount(rules.size) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = ScreenPad)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    RuleEngine.Part.entries.forEach { p ->
                        SmallChip(p.name.replace('_', ' ').lowercase(), on = part == p, onClick = { part = p })
                    }
                }
                LabeledField(if (isHeader) "Find in header values (blank = set a whole header)" else "Find", find, { find = it }, singleLine = true)
                LabeledField(if (isHeader && find.isBlank()) "Header to set, as Name: value" else "Replace with", replace, { replace = it }, singleLine = true)
                LabeledField("Only hosts containing (blank = all)", host, { host = it }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallChip("Regex", on = regex, onClick = { regex = !regex })
                    AppButton(
                        "Add rule", {
                            engine.add(RuleEngine.Rule("r${System.currentTimeMillis()}", part, find, replace, host.trim(), regex, true))
                            find = ""; replace = ""; refresh()
                        },
                        Modifier.weight(1f), kind = Kind.Primary, compact = true,
                        enabled = if (isHeader && find.isBlank()) replace.contains(':') else find.isNotEmpty(),
                    )
                }
                Text(
                    "Rules need the proxy: turn on Capture (Options) so traffic passes through it. Compressed replies are decoded first, so text rules match.",
                    style = MaterialTheme.typography.labelSmall, color = TextMuted, modifier = Modifier.padding(top = 8.dp),
                )
            }
            SectionLabel("Rules")
            if (rules.isEmpty()) Text("No rules yet.", style = MaterialTheme.typography.bodyMedium, color = TextMuted, modifier = Modifier.padding(horizontal = ScreenPad))
        }
        items(rules, key = { it.id }) { r ->
            AppCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            r.part.name.replace('_', ' ').lowercase() + (if (r.hostContains.isNotEmpty()) " · ${r.hostContains}" else "") + (if (r.regex) " · regex" else ""),
                            style = MaterialTheme.typography.labelSmall, color = Accent,
                        )
                        Text(
                            if (r.find.isEmpty()) "set ${r.replace}" else "${r.find}  →  ${r.replace}",
                            style = MonoStyle.copy(fontSize = 11.5.sp), maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = r.enabled, onCheckedChange = { engine.setEnabled(r.id, it); refresh() },
                        colors = SwitchDefaults.colors(checkedThumbColor = OnAccent, checkedTrackColor = Accent, checkedBorderColor = Accent,
                            uncheckedThumbColor = TextMuted, uncheckedTrackColor = SecondaryBg, uncheckedBorderColor = SecondaryBg),
                    )
                    AppButton("Delete", { engine.remove(r.id); refresh() }, Modifier.padding(start = 8.dp), kind = Kind.Danger, compact = true)
                }
            }
        }
        item { Column(Modifier.padding(bottom = 24.dp)) {} }
    }
}
