package com.alpdroid.browser.ui.proxy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alpdroid.browser.ui.kit.MonoStyle
import com.alpdroid.browser.ui.kit.ScreenPad
import com.alpdroid.browser.ui.theme.FieldBg

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

/** One piece of a code block, to be used inside a LazyColumn via `items(chunks)`; neighbouring pieces read as one box. */
@Composable
fun MonoChunk(text: String) {
    Text(text, style = MonoStyle, modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPad).background(FieldBg).padding(horizontal = 12.dp))
}
