package com.alpdroid.browser.ui.proxy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.alpdroid.browser.ui.kit.Kind
import com.alpdroid.browser.ui.kit.AppButton
import com.alpdroid.browser.ui.theme.TextMuted
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
        ?: com.alpdroid.browser.RawHttp.displayText(headers, body) ?: "[binary body, ${body?.size ?: 0} bytes]"
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
    return chunkBounds(shown, size).map { (s, e) -> shown.substring(s, e) }
}

/** The same pieces as [chunkText], but coloured: [text] is highlighted once, then cut at the same line breaks. */
fun chunkHighlighted(text: String, size: Int = 1500, maxChars: Int = 2_000_000): List<androidx.compose.ui.text.AnnotatedString> {
    val shown = if (text.length > maxChars) text.substring(0, maxChars) + "\n[… ${text.length - maxChars} more characters not shown — use Copy]" else text
    val coloured = HttpHighlight.annotate(shown)
    return chunkBounds(shown, size).map { (s, e) -> coloured.subSequence(s, e) }
}

internal fun chunkBounds(shown: String, size: Int): List<Pair<Int, Int>> {
    if (shown.length <= size) return listOf(0 to shown.length)
    val out = ArrayList<Pair<Int, Int>>(shown.length / size + 1)
    var i = 0
    while (i < shown.length) {
        var end = minOf(i + size, shown.length)
        if (end < shown.length) {
            val nl = shown.lastIndexOf('\n', end)
            if (nl > i) end = nl + 1
        }
        out.add(i to end)
        i = end
    }
    return out
}

/** A coloured piece (see [chunkHighlighted]). */
@Composable
fun MonoChunk(text: androidx.compose.ui.text.AnnotatedString) {
    Text(text, style = MonoStyle, modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPad).background(FieldBg).padding(horizontal = 12.dp))
}

/** One piece of a code block, to be used inside a LazyColumn via `items(chunks)`; neighbouring pieces read as one box. */
@Composable
fun MonoChunk(text: String) {
    Text(text, style = MonoStyle, modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPad).background(FieldBg).padding(horizontal = 12.dp))
}


/**
 * A body that is too big (or not safe) to put in an edit field, shown read-only instead of being left out: the text a screenful
 * at a time (a "Show more" button adds more), with Copy for all of it. Binary bodies say so. Use inside a scrolling Column.
 */
@Composable
fun BodyPreview(headers: List<Pair<String, String>>, body: ByteArray?, note: String) {
    val clipboard = LocalClipboardManager.current
    val text = remember(body) { com.alpdroid.browser.RawHttp.displayText(headers, body) }
    val size = body?.size ?: 0
    Text(
        if (text == null) "Binary body ($size bytes) — forwarded unchanged." else "Body ($size bytes) — $note",
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.padding(bottom = 8.dp),
    )
    if (text == null) return
    val chunks by produceState(emptyList<String>(), text) { value = withContext(Dispatchers.Default) { chunkText(text) } }
    var visible by remember(body) { mutableIntStateOf(PREVIEW_CHUNKS) }
    Column(Modifier.fillMaxWidth()) { chunks.take(visible).forEach { MonoChunk(AnnotatedString(it)) } }
    Row(Modifier.padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (chunks.size > visible) AppButton("Show more", { visible += PREVIEW_CHUNKS }, kind = Kind.Ghost, compact = true)
        AppButton("Copy body", { clipboard.setText(AnnotatedString(text)) }, kind = Kind.Ghost, compact = true)
    }
}

private const val PREVIEW_CHUNKS = 40
