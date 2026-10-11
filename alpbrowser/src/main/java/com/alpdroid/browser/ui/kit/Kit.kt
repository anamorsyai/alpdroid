package com.alpdroid.browser.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import com.alpdroid.browser.ui.theme.*

/** Horizontal margin shared by every screen, so edges line up from tab to tab. */
val ScreenPad = 18.dp

val MonoStyle = TextStyle(fontFamily = RoninMono, fontSize = 12.sp, lineHeight = 19.sp, color = TextSoft)

/** Screen title with a status line under it ("On · 3 waiting"); [trailing] sits on the right (a switch, a button). */
@Composable
fun ScreenHeader(title: String, status: String? = null, statusOn: Boolean = false, trailing: @Composable (RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = ScreenPad, end = ScreenPad, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = TextMain)
            if (status != null) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (statusOn) Accent else TextFaint))
                    Text(status, style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (trailing != null) trailing()
    }
}

/** A horizontally scrolling row of tab chips: the selected one is filled with the accent. */
@Composable
fun ChipTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = ScreenPad).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Accent else Surface1).clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Text(l, style = MaterialTheme.typography.labelLarge, color = if (on) OnAccent else TextMuted, maxLines = 1) }
        }
    }
}

/** Equal-width segments in a rounded track (Request | Response | History). */
@Composable
fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = ScreenPad).padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp)).background(Surface1).padding(3.dp)) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) SecondaryBg else Color.Transparent).clickable { onSelect(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(l, style = MaterialTheme.typography.labelLarge, color = if (on) TextMain else TextMuted, maxLines = 1) }
        }
    }
}

/** A small on/off chip (Pretty, Raw, Responses): filled with the accent when on. */
@Composable
fun SmallChip(text: String, on: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Accent else Surface1)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(text, style = MaterialTheme.typography.labelMedium, color = if (on) OnAccent else if (enabled) TextMuted else TextFaint, maxLines = 1) }
}

enum class Kind { Primary, Secondary, Danger, Ghost }

@Composable
fun AppButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: Kind = Kind.Secondary, compact: Boolean = false, enabled: Boolean = true) {
    val height = if (compact) 40.dp else 50.dp
    val (bg, fg) = when (kind) {
        Kind.Primary -> Accent to OnAccent
        Kind.Secondary -> SecondaryBg to Color(0xFFE6E3DE)
        Kind.Danger -> DangerBg to DangerText
        Kind.Ghost -> Color.Transparent to TextSoft
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.heightIn(min = height).clip(shape).background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .then(if (kind == Kind.Ghost) Modifier.border(1.dp, FieldBorder, shape) else Modifier)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = if (compact) 10.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall, color = if (enabled) fg else fg.copy(alpha = 0.5f), maxLines = 1, textAlign = TextAlign.Center) }
}

/** A text input: dark well, hairline border, hint inside. [mono] for anything code-shaped. */
@Composable
fun Field(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "",
    singleLine: Boolean = false, minLines: Int = 1, maxLines: Int = if (singleLine) 1 else 12, readOnly: Boolean = false,
    mono: Boolean = true, keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val shape = RoundedCornerShape(10.dp)
    val style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium.copy(color = TextSoft)
    BasicTextField(
        value = value, onValueChange = onValueChange, modifier = modifier.fillMaxWidth(), singleLine = singleLine,
        minLines = minLines, maxLines = maxLines, readOnly = readOnly, textStyle = style, cursorBrush = SolidColor(Accent),
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = 42.dp).background(FieldBg, shape).border(1.dp, FieldBorder, shape).padding(horizontal = 12.dp, vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = style.copy(color = TextFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            }
        },
    )
}

/** A field with a small caption above it, for editors with several inputs (the caption stays when the field is filled). */
@Composable
fun LabeledField(
    label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "",
    singleLine: Boolean = false, minLines: Int = 1, maxLines: Int = if (singleLine) 1 else 12, readOnly: Boolean = false, mono: Boolean = true,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextMuted, modifier = Modifier.padding(bottom = 5.dp, start = 2.dp))
        Field(value, onValueChange, placeholder = placeholder, singleLine = singleLine, minLines = minLines, maxLines = maxLines, readOnly = readOnly, mono = mono)
    }
}

/** A rounded list card; [selected] outlines it in the accent. */
@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, selected: Boolean = false, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.fillMaxWidth().padding(horizontal = ScreenPad, vertical = 4.dp).clip(shape)
            .background(if (selected) AccentSoft else Surface1)
            .then(if (selected) Modifier.border(1.5.dp, Accent, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 13.dp),
    ) { content() }
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPad, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = TextMain)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = OnAccent, checkedTrackColor = Accent, checkedBorderColor = Accent,
                uncheckedThumbColor = TextMuted, uncheckedTrackColor = SecondaryBg, uncheckedBorderColor = SecondaryBg,
            ),
        )
    }
}

@Composable
fun Tag(text: String, color: Color = Accent, width: Dp = 34.dp) {
    Text(text, style = TextStyle(fontFamily = RoninMono, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = color), modifier = Modifier.widthIn(min = width), maxLines = 1)
}

/** Response status as coloured mono text (green 2xx, blue 3xx, red 4xx/5xx). */
@Composable
fun StatusText(status: Int?, modifier: Modifier = Modifier) {
    val (txt, color) = when {
        status == null -> "–" to TextFaint
        status == 0 -> "drop" to TextFaint
        status < 300 -> status.toString() to StatusOk
        status < 400 -> status.toString() to StatusRedirect
        else -> status.toString() to StatusError
    }
    Text(txt, style = TextStyle(fontFamily = RoninMono, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = color), modifier = modifier, maxLines = 1)
}

@Composable
fun MethodText(method: String, modifier: Modifier = Modifier) {
    Text(method, style = TextStyle(fontFamily = RoninMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Method), modifier = modifier, maxLines = 1)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp), color = TextMuted, modifier = modifier.padding(horizontal = ScreenPad).padding(top = 14.dp, bottom = 6.dp))
}

/** Bottom navigation: icon over label, the active destination marked by an accent bar on its top edge. */
@Composable
fun AppNavBar(items: List<Pair<String, ImageVector>>, selected: Int, onSelect: (Int) -> Unit, badge: Map<Int, Int> = emptyMap()) {
    Column(Modifier.fillMaxWidth().background(NavBg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, (label, icon) ->
                val on = i == selected
                Box(Modifier.weight(1f).clickable { onSelect(i) }, contentAlignment = Alignment.TopCenter) {
                    if (on) Box(Modifier.fillMaxWidth(0.56f).height(3.dp).clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)).background(Accent))
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box {
                            Icon(icon, contentDescription = label, tint = if (on) Accent else TextFaint, modifier = Modifier.size(22.dp))
                            val n = badge[i] ?: 0
                            if (n > 0) Box(Modifier.align(Alignment.TopEnd).padding(start = 14.dp).clip(RoundedCornerShape(8.dp)).background(Accent).padding(horizontal = 4.dp)) {
                                Text(n.toString(), style = MaterialTheme.typography.labelSmall, color = OnAccent)
                            }
                        }
                        Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) Accent else TextFaint, maxLines = 1)
                    }
                }
            }
        }
    }
}

