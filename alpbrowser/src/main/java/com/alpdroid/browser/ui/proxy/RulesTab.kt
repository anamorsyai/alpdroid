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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alpdroid.browser.AlpBrowserApp
import com.alpdroid.browser.RuleEngine
import com.alpdroid.browser.ui.theme.RoninMono

/** Match & replace: rewrite matching traffic automatically, with no manual breakpoint. Saved across restarts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesTab(app: AlpBrowserApp) {
    val engine = app.intercept.rules
    var rules by remember { mutableStateOf(engine.all()) }
    var part by remember { mutableStateOf(RuleEngine.Part.RES_BODY) }
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    val isHeader = part == RuleEngine.Part.REQ_HEADER || part == RuleEngine.Part.RES_HEADER
    fun refresh() { rules = engine.all() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        item {
            Text("New rule", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuleEngine.Part.entries.forEach { p ->
                    FilterChip(selected = part == p, onClick = { part = p }, label = { Text(p.name.replace('_', ' ').lowercase()) })
                }
            }
            MonoField(find, { find = it }, if (isHeader) "Find in header values (blank = set a whole header)" else "Find", singleLine = true)
            MonoField(replace, { replace = it }, if (isHeader && find.isBlank()) "Header to set, as Name: value" else "Replace with", singleLine = true)
            MonoField(host, { host = it }, "Only hosts containing (blank = all)", singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = regex, onClick = { regex = !regex }, label = { Text("Regex") })
                Button(
                    enabled = if (isHeader && find.isBlank()) replace.contains(':') else find.isNotEmpty(),
                    onClick = {
                        engine.add(RuleEngine.Rule("r${System.currentTimeMillis()}", part, find, replace, host.trim(), regex, true))
                        find = ""; replace = ""; refresh()
                    },
                ) { Text("Add rule") }
            }
            Text(
                "Rules need the proxy: turn on Capture (or Intercept) so traffic passes through it. Compressed replies are decoded first, so text rules match.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (rules.isEmpty()) Text("No rules yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(rules, key = { it.id }) { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.part.name.replace('_', ' ').lowercase() + (if (r.hostContains.isNotEmpty()) " · ${r.hostContains}" else "") + (if (r.regex) " · regex" else ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    Text(if (r.find.isEmpty()) "set ${r.replace}" else "${r.find}  →  ${r.replace}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = RoninMono), maxLines = 3)
                }
                Switch(checked = r.enabled, onCheckedChange = { engine.setEnabled(r.id, it); refresh() })
                TextButton(onClick = { engine.remove(r.id); refresh() }) { Text("Delete") }
            }
            HorizontalDivider()
        }
    }
}
