package com.alpdroid.browser.ui.proxy

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
        shape = RoundedCornerShape(10.dp),
    )
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
    Text(
        if (status == null) "…" else if (status == 0) "dropped" else status.toString(),
        color = color, style = MaterialTheme.typography.labelMedium.copy(fontFamily = RoninMono),
    )
}

/** Raw, copy-friendly rendering of one HTTP message, capped so a huge body cannot freeze the UI. */
fun rawMessage(firstLine: String, headers: List<Pair<String, String>>, body: ByteArray?): String {
    val text = com.alpdroid.browser.RawHttp.bodyToText(body) ?: "[binary body, ${body?.size ?: 0} bytes]"
    val clipped = if (text.length > 200_000) text.take(200_000) + "\n[… truncated for display]" else text
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
