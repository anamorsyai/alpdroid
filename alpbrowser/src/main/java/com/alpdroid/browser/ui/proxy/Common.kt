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
