package com.alpdroid.browser.ui.proxy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.alpdroid.browser.ui.theme.RoninMono

/** A monospace text field used by the raw request/response editors. */
@Composable
fun MonoField(
    value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, singleLine: Boolean = false, minLines: Int = 1, readOnly: Boolean = false,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) },
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        singleLine = singleLine, minLines = minLines, maxLines = if (singleLine) 1 else 14, readOnly = readOnly,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = RoninMono),
        shape = RoundedCornerShape(12.dp),
    )
}

/** Horizontal margin shared by every screen, so edges line up from tab to tab. */
val ScreenPadding = 16.dp

/** A small section heading used above groups of controls. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 14.dp, bottom = 4.dp))
}

/** A tappable rounded row for lists (held messages, history, rules), so every list looks the same. */
@Composable
fun ListCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) { Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) { content() } }
}

@Composable
fun StatusChip(status: Int?) {
    val color = when {
        status == null || status == 0 -> MaterialTheme.colorScheme.outline
        status < 300 -> MaterialTheme.colorScheme.primary
        status < 400 -> MaterialTheme.colorScheme.tertiary
        status < 500 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }
    androidx.compose.material3.Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.16f)) {
        Text(
            if (status == null) "–" else if (status == 0) "dropped" else status.toString(),
            color = color, style = MaterialTheme.typography.labelMedium.copy(fontFamily = RoninMono),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * Rendering of one HTTP message, capped so a huge body cannot freeze the UI. With [pretty], a recognised body (JSON,
 * HTML/XML, form post) is laid out readably; otherwise, and for anything unrecognised, the body is shown as it is.
 */
fun rawMessage(firstLine: String, headers: List<Pair<String, String>>, body: ByteArray?, pretty: Boolean = false): String {
    val shown = (if (pretty) com.alpdroid.browser.BodyFormat.pretty(headers, body) else null)
        ?: com.alpdroid.browser.RawHttp.bodyToText(body) ?: "[binary body, ${body?.size ?: 0} bytes]"
    val clipped = if (shown.length > 2_000_000) shown.take(2_000_000) + "\n[… truncated for display]" else shown
    return buildString {
        appendLine(firstLine)
        headers.forEach { appendLine("${it.first}: ${it.second}") }
        appendLine()
        append(clipped)
    }
}

/**
 * Splits [text] into pieces of roughly [size] characters at line breaks. Shown as separate lazy list items so a
 * multi-hundred-KB response is laid out a screenful at a time — one giant Text composable takes seconds on the
 * main thread and gets the app reported as "not responding". Beyond [maxChars] the rest is not rendered (copy it).
 */
fun chunkText(text: String, size: Int = 1500, maxChars: Int = 2_000_000): List<String> {
    val shown = if (text.length > maxChars) text.substring(0, maxChars) + "\n[… ${text.length - maxChars} more characters not shown — use Copy]" else text
    if (shown.length <= size) return listOf(shown)
    val out = ArrayList<String>(shown.length / size + 1)
    var i = 0
    while (i < shown.length) {
        var end = minOf(i + size, shown.length)
        if (end < shown.length) {
            val nl = shown.lastIndexOf('\n', end)
            if (nl > i) end = nl + 1
        }
        out.add(shown.substring(i, end))
        i = end
    }
    return out
}

/** A monospace block of chunks, to be used inside a LazyColumn via `items(chunks)`. */
@Composable
fun MonoChunk(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = RoninMono), modifier = Modifier.fillMaxWidth())
}
