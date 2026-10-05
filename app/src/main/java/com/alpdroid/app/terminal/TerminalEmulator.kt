package com.alpdroid.app.terminal

import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * One character cell: glyph plus the SGR attributes it was drawn with.
 *
 * [fg]/[bg] are the resolved RGB actually drawn — the fast path [TerminalView] reads every
 * frame. [fgKind]/[bgKind] remember *how* that color was chosen ([KIND_DEFAULT], an ANSI16
 * index 0-15, or [KIND_FIXED] for an explicit 256-color/truecolor SGR that isn't theme-relative
 * at all) so [TerminalEmulator.applyPalette] can re-resolve every cell already on screen against
 * a newly chosen theme — without this, switching themes mid-session only affected text written
 * *after* the switch, leaving old scrollback/screen content stuck in whatever theme was active
 * when it was written (a visibly "mixed themes" screen).
 */
/** Bytes fed to the emulator per lock acquisition (see TerminalEmulator.feed). */
private const val FEED_SLICE = 4096
private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
private const val FNV_PRIME = 1099511628211L          // 0x100000001b3

data class Cell(
    var ch: Char = ' ',
    var fg: Int = TerminalColors.DEFAULT_FG,
    var bg: Int = TerminalColors.DEFAULT_BG,
    var fgKind: Int = KIND_DEFAULT,
    var bgKind: Int = KIND_DEFAULT,
    var bold: Boolean = false,
    var underline: Boolean = false,
    var reverse: Boolean = false,
    /** Set on the LAST cell of a row when the program's output auto-wrapped past it onto the next
     *  row (a genuine soft wrap), so a resize can rejoin exactly those rows — instead of guessing
     *  from whether the row happens to end in a non-blank character, which failed for any wrapped
     *  row that ended on a space (most `ls -l`/column output) and left text broken at its old
     *  width after the terminal got wider. */
    var wrapped: Boolean = false,
) {
    companion object {
        const val KIND_DEFAULT = -1
        const val KIND_FIXED = -2
        // 0..15: an index into TerminalColors.ANSI16
    }
}

private fun blankRow(cols: Int, fg: Int, bg: Int, fgKind: Int = Cell.KIND_DEFAULT, bgKind: Int = Cell.KIND_DEFAULT): Array<Cell> =
    Array(cols) { Cell(fg = fg, bg = bg, fgKind = fgKind, bgKind = bgKind) }

/**
 * A byte-stream-in, screen-grid-out VT100/ANSI terminal emulator — the model half of a
 * terminal; [com.alpdroid.app.terminal.TerminalView] is the (dumb) rendering half. Handles
 * the common subset real-world shell usage needs: cursor movement, scrolling with a scroll
 * region, insert/delete char/line, SGR colors (16/256/truecolor), the primary+alternate screen
 * switch full-screen programs use, and DSR cursor-position queries. Deliberately does not
 * reflow line-wraps on resize, and does not implement terminfo-level esoterica (sixel, DECSLRM,
 * true rectangular copy) — those would need many times this file's size for marginal benefit
 * on a phone-sized shell.
 */
class TerminalEmulator(
    rows: Int,
    cols: Int,
    private val maxScrollback: Int = 2000,
    /** Called for DSR-style queries that require writing bytes back to the pty (e.g. cursor position). */
    private val respond: (String) -> Unit = {},
    onBell: () -> Unit = {},
) {
    /** Called on a BEL byte (0x07) — a CLI agent or a build finishing is exactly what this is
     *  for. A `var`, not baked in at construction like [respond] above: [respond] only ever
     *  writes bytes back to the pty (process-level, outlives any one Activity instance just fine),
     *  but this one calls back into Activity-owned UI (a notification, a toast) — whichever
     *  MainActivity instance owns the tab when it's first created would otherwise stay wired in
     *  forever, including after the system destroys and recreates that Activity (backgrounding,
     *  a config change) and a *different* instance is the one actually on screen. Re-set by
     *  rebindTabOutputs() alongside TerminalTab.onOutput, the same pattern that field already uses. */
    var onBell: () -> Unit = onBell
    // Volatile: mutated under @Synchronized on the PTY reader thread, read directly on the UI
    // thread (blink runnable, cursor draw, touch handling) — without visibility guarantees the
    // UI can spin on stale geometry after a resize.
    @Volatile var rows: Int = max(1, rows)
        private set
    @Volatile var cols: Int = max(1, cols)
        private set

    @Volatile var cursorRow = 0
        private set
    @Volatile var cursorCol = 0
        private set
    @Volatile var cursorVisible = true
        private set

    /** Bumped on every mutation; [com.alpdroid.app.terminal.TerminalView] compares this to know when to redraw. */
    @Volatile var generation = 0L
        private set

    private var screen: MutableList<Array<Cell>> = MutableList(rows) { blankRow(cols, TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG) }
    private var altScreen: MutableList<Array<Cell>>? = null
    // Non-null exactly while the alt screen (1049/1047/47) is the one showing: holds the primary
    // screen's grid and cursor position so leaving the alt screen restores the real prompt/output
    // that was there before a full-screen app started, instead of a blank grid. Previously nothing
    // saved this at all — switchAltScreen() below just overwrote `screen` in both directions, so
    // exiting any full-screen app (opencode, vim, less) always left an empty primary screen, and a
    // resize taken while alt-screen was up ran the primary-only reflow/scrollback logic on what was
    // actually the alt buffer, corrupting both the on-screen alt content and the primary scrollback
    // with the alt screen's own rows.
    private var primaryScreen: MutableList<Array<Cell>>? = null
    private var primaryCursorRow = 0
    private var primaryCursorCol = 0
    private val scrollback = ArrayDeque<Array<Cell>>()

    /** True while a full-screen program (opencode, vim, htop, less) has the alt screen up — see
     *  TerminalView, which uses this to decide whether a keyboard show/hide should send this
     *  session a real resize (safe and expected here: such a program redraws itself completely on
     *  the SIGWINCH that causes, unlike a plain shell) instead of just shifting rendering upward. */
    val inAltScreen: Boolean
        @Synchronized get() = primaryScreen != null

    /** How many of the newest [scrollback] rows exist only because a keyboard-toggle-driven
     *  shrink temporarily displaced them, and are therefore still eligible to be pulled back onto
     *  the main screen the next time it grows — see resizePrimaryScreen() and eraseInDisplay(). */
    private var pendingRestoreCount = 0

    private var topMargin = 0
    private var bottomMargin = rows - 1

    private var curFg = TerminalColors.DEFAULT_FG
    private var curBg = TerminalColors.DEFAULT_BG
    private var curFgKind = Cell.KIND_DEFAULT
    private var curBgKind = Cell.KIND_DEFAULT
    private var curBold = false
    private var curUnderline = false
    private var curReverse = false

    private var savedRow = 0
    private var savedCol = 0

    var applicationCursorKeys = false
        private set

    /** Mode 2004 — set by any readline-based program (bash, python's REPL, and every interactive
     *  CLI agent tool this app exists to run) that wants pasted text delivered as one wrapped
     *  block (ESC[200~...ESC[201~) instead of raw bytes. Without a caller honoring this, pasting
     *  multi-line text (a whole prompt pasted into an agent chat, in particular) had every
     *  embedded newline read back as a literal Enter keypress — submitting each line as its own
     *  separate, incomplete command/message instead of landing as one block of editable text. */
    var bracketedPasteEnabled = false
        private set

    /** DECAWM (mode 7, default on in every real terminal) — a program drawing something it never
     *  wants wrapped onto a new line (a fixed-width status bar, a progress indicator) can turn
     *  this off for exactly that. */
    private var autoWrapEnabled = true

    /** 0 = off, else the mode number currently enabled (1000/1002/1003) — see [setMode]. A tap on
     *  the terminal while this is nonzero should be reported to the program as a mouse click
     *  ([mouseClickSequence]) instead of TerminalView's own default of opening the soft keyboard;
     *  without this, any full-screen TUI that expects clickable elements (opencode's own
     *  collapsible sections, an `fzf --bind`-style picker, mouse-aware vim/less) never receives
     *  them, and every tap on it just pops the keyboard up instead. */
    var mouseReportingMode = 0
        private set

    /** Mode 1006 (SGR extended mouse coordinates) — without it, coordinates beyond ~223 can't be
     *  represented in the legacy X10 click encoding (which packs col/row into single bytes offset
     *  by 32), so a wide/tall terminal would silently corrupt or clip the reported position. */
    var mouseSgrMode = false
        private set

    /** Builds the escape sequence for a single mouse button press or release at the given
     *  zero-based cell, or null if the program hasn't asked for click reporting ([mouseReportingMode]
     *  == 0). `button` 0 is the left button, matching a plain tap. */
    fun mouseClickSequence(row: Int, col: Int, button: Int = 0, pressed: Boolean = true): String? {
        if (mouseReportingMode == 0) return null
        val r = row + 1
        val c = col + 1
        return if (mouseSgrMode) {
            "\u001B[<$button;$c;$r${if (pressed) "M" else "m"}"
        } else {
            // Legacy X10 encoding: three bytes after "\x1B[M" — button (32 = release, +32 offset
            // otherwise), column, row, each offset by 32 and clamped to a single byte so a huge
            // grid can't produce a value outside the encodable range instead of a garbled report.
            val buttonByte = (32 + (if (pressed) button else 3)).coerceIn(32, 255)
            val colByte = (32 + c).coerceIn(32, 255)
            val rowByte = (32 + r).coerceIn(32, 255)
            "\u001B[M${buttonByte.toChar()}${colByte.toChar()}${rowByte.toChar()}"
        }
    }

    // --- Parser state ---
    private enum class State { NORMAL, ESCAPE, CSI, OSC, CHARSET }
    private var state = State.NORMAL
    private val csiBuf = StringBuilder()
    private val utf8Pending = ArrayList<Int>(4)

    // Deferred callbacks: feed() runs under the emulator lock on the PTY reader thread, but
    // onBell/onAltScreenChanged touch Activity UI (binder IPC, view ops) and respond() writes
    // the pty pipe (blocks when full) — invoking any of them with the lock held stalls every
    // other emulator reader and risks lock-order inversion. Sites below only record; feed()
    // invokes after unlocking. Single-reader-thread design (one PtySession stdout pump per
    // session), so no extra synchronization on these three.
    private var pendingBells = 0
    private var pendingAlt: Boolean? = null
    private var pendingResponses: MutableList<String>? = null

    private fun queueResponse(text: String) {
        (pendingResponses ?: mutableListOf<String>().also { pendingResponses = it }).add(text)
    }
    // OSC has no buffer (only its terminator is watched) — without a length cap a binary dump
    // containing ESC ] with no BEL/ESC\ would wedge the parser in OSC state indefinitely.
    private var oscLen = 0
    private var oscLimit = 1024

    fun feed(buf: ByteArray, len: Int) {
        // Clamp: a caller passing len > buf.size would otherwise throw inside the reader thread.
        val n = len.coerceIn(0, buf.size)
        // Sliced: the reader hands over up to 32KB at a time, and holding the emulator lock for
        // the whole chunk made the UI thread's renderSnapshot() wait out all of it under heavy
        // output (visible as scroll jank). Releasing between slices lets a frame slip in.
        var off = 0
        while (off < n) {
            val end = minOf(n, off + FEED_SLICE)
            synchronized(this) {
                for (i in off until end) processByte(buf[i].toInt() and 0xFF)
                generation++
            }
            off = end
        }
        // Outside the lock (see fields above). Bells coalesce: one notification per chunk, not
        // per BEL byte — `yes $'\a'` must not notification-spam.
        if (pendingBells > 0) { pendingBells = 0; onBell() }
        pendingAlt?.let { pendingAlt = null; onAltScreenChanged?.invoke(it) }
        pendingResponses?.let { pendingResponses = null; it.forEach { respond(it) } }
    }

    /** Client-side only — clears the view without touching the shell process (the "Clear" context-menu action). */
    /** Frees memory under pressure: drops the OLDEST scrollback rows until at most [keep] remain (the
     *  visible screen is untouched). Returns how many rows were released. */
    @Synchronized
    fun trimScrollback(keep: Int): Int {
        var removed = 0
        val limit = keep.coerceAtLeast(0)
        while (scrollback.size > limit) { scrollback.removeFirst(); removed++ }
        if (pendingRestoreCount > scrollback.size) pendingRestoreCount = scrollback.size
        if (removed > 0) generation++
        return removed
    }

    @Synchronized
    fun clearAll() {
        for (r in 0 until rows) screen[r] = blankRow(cols, TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG)
        scrollback.clear()
        cursorRow = 0
        cursorCol = 0
        generation++
    }

    /** Scrollback + current screen as plain text, trailing spaces trimmed per line — for the "Copy all" context-menu action. */
    @Synchronized
    fun fullText(): String = buildString {
        for (i in 0 until scrollback.size) { append(rowText(scrollback.elementAt(i))); append('\n') }
        for (r in 0 until rows) { append(rowText(screen[r])); append('\n') }
    }

    /**
     * Last [maxLines] lines only, built directly from the tail — the agent screen endpoint
     * polled this via fullText().trimEnd().lines().takeLast().joinToString(), materializing
     * the whole ~400KB scrollback plus two throwaway copies per request. Must hold the
     * monitor: runs on the agent-bridge pool thread while feed() mutates the grid on the
     * reader thread.
     */
    @Synchronized
    fun tailText(maxLines: Int): String {
        val take = maxLines.coerceIn(1, 5000)
        val total = scrollback.size + rows
        val from = (total - take).coerceAtLeast(0)
        return buildString {
            for (i in from until total) {
                val row = if (i < scrollback.size) scrollback.elementAt(i) else screen[i - scrollback.size]
                append(rowText(row).trimEnd())
                if (i < total - 1) append('\n')
            }
        }.trimEnd()
    }

    private fun rowText(row: Array<Cell>): String = buildString { row.forEach { append(it.ch) } }.trimEnd()

    /** Combined-row indices (see [combinedRow]) whose text contains [query], case-insensitive —
     *  a plain linear scan; only run on an explicit search action, never per-frame, so an O(n)
     *  pass over a couple thousand scrollback lines is cheap enough not to need an index. */
    @Synchronized
    fun findRows(query: String): List<Int> {
        if (query.isEmpty()) return emptyList()
        // No per-row lowercase copies: regionMatches compares case-insensitively
        // straight off the cells. A 2000-row scan used to allocate 3 strings per row.
        val matches = mutableListOf<Int>()
        for (i in 0 until combinedRowCount()) {
            if (rowContains(combinedRow(i), query)) matches.add(i)
        }
        return matches
    }

    private fun rowContains(row: Array<Cell>, query: String): Boolean {
        if (query.length > row.size) return false
        outer@ for (start in 0..row.size - query.length) {
            for (j in query.indices) {
                val a = row[start + j].ch
                val b = query[j]
                if (a != b && a.lowercaseChar() != b.lowercaseChar()) continue@outer
            }
            return true
        }
        return false
    }

    /** Scrollback (oldest first) followed by the current screen, addressed as one continuous
     *  0-based sequence — what text selection needs: a single coordinate space a touch position
     *  maps into directly, regardless of how much of it happens to be scrolled off-screen. */
    @Synchronized
    fun combinedRowCount(): Int = scrollback.size + rows

    @Synchronized
    fun combinedRow(index: Int): Array<Cell> =
        if (index < scrollback.size) scrollback.elementAt(index) else screen[index - scrollback.size]

    /**
     * Plain text for the inclusive range from ([startRow],[startCol]) to ([endRow],[endCol]) in
     * combined-row coordinates — normalizes reversed selections (dragged upward/leftward) itself,
     * so the caller doesn't need to know which endpoint came first.
     */
    @Synchronized
    fun textInRange(startRow: Int, startCol: Int, endRow: Int, endCol: Int): String {
        var r1 = startRow; var c1 = startCol; var r2 = endRow; var c2 = endCol
        if (r1 > r2 || (r1 == r2 && c1 > c2)) {
            val tr = r1; val tc = c1; r1 = r2; c1 = c2; r2 = tr; c2 = tc
        }
        r1 = r1.coerceIn(0, combinedRowCount() - 1)
        r2 = r2.coerceIn(0, combinedRowCount() - 1)
        return buildString {
            for (r in r1..r2) {
                val row = combinedRow(r)
                val fromCol = if (r == r1) c1.coerceIn(0, row.size) else 0
                val toCol = if (r == r2) c2.coerceIn(0, row.size - 1) else row.size - 1
                val seg = StringBuilder()
                for (c in fromCol..toCol) seg.append(row[c].ch)
                // A row that soft-wrapped into the next continues the same logical line: no newline, and its
                // trailing cell is real text rather than padding.
                if (r != r2 && toCol == row.size - 1 && row.isNotEmpty() && row.last().wrapped) {
                    append(seg)
                } else {
                    append(seg.toString().trimEnd())
                    if (r != r2) append('\n')
                }
            }
        }.trimEnd()
    }

    @Synchronized
    fun resize(newRows: Int, newCols: Int) {
        // A 0 (or negative) dimension arrives transiently from layout passes before the view
        // has a size — accepting it would set rows/cols to 0 and make every coerceIn(0, rows-1)
        // below throw, killing the session over a meaningless intermediate measurement.
        if (newRows < 1 || newCols < 1) return
        if (newRows == rows && newCols == cols) return
        // The primary screen treats scrollback+screen as one continuous buffer across a resize:
        // shrinking (the soft keyboard opening, which shrinks the view under adjustResize) pushes
        // whatever no longer fits at the top into scrollback instead of just discarding it, and
        // growing back pulls those same rows back out to refill the top instead of padding with
        // blank lines. Previously every keyboard show/hide silently deleted a chunk of visible
        // history outright — exactly "terminal output disappearing one line at a time". The
        // alt-screen (full-screen apps: vim, htop, less) doesn't get this treatment since those
        // redraw themselves entirely on resize (via SIGWINCH) and were never reading scrollback.
        if (primaryScreen != null) {
            // `screen`/cursorRow/cursorCol currently belong to the ALT buffer, not the primary one
            // — resizing them with the primary-only logic below would push/pull the alt screen's
            // own rows into the primary scrollback (permanently mixing a full-screen app's output
            // into ordinary shell history) while leaving the actually-saved primary screen sized
            // for whatever dimensions it had before this resize. Swap the primary buffer back in
            // just long enough to run the normal resize logic against it, save the result, then
            // resize the alt buffer on its own with the plain grid resize it always used — this is
            // exactly what let a resize taken mid-full-screen-app (the keyboard opening/closing
            // while opencode/vim/less was up) corrupt both buffers at once and made old primary
            // scrollback lines bleed into the redrawn alt-screen content.
            val altBuf = screen
            val altCursorRow = cursorRow
            val altCursorCol = cursorCol
            screen = primaryScreen!!
            cursorRow = primaryCursorRow
            cursorCol = primaryCursorCol
            resizePrimaryBuffer(newRows, newCols)
            primaryScreen = screen
            primaryCursorRow = cursorRow
            primaryCursorCol = cursorCol
            screen = resizeGrid(altBuf, newRows, newCols)
            altScreen = screen
            cursorRow = altCursorRow.coerceIn(0, newRows - 1)
            cursorCol = altCursorCol.coerceIn(0, newCols - 1)
        } else {
            resizePrimaryBuffer(newRows, newCols)
            altScreen = altScreen?.let { resizeGrid(it, newRows, newCols) }
        }
        rows = newRows
        cols = newCols
        topMargin = 0
        bottomMargin = newRows - 1
        generation++
    }

    /** Resizes whatever is currently in `screen`/`scrollback`/`cursorRow`/`cursorCol` using the
     *  primary-screen reflow/row-shift logic — used directly for an ordinary resize, and with the
     *  primary buffer temporarily swapped into those same fields for a resize taken while the alt
     *  screen is the one actually showing (see [resize]). */
    private fun resizePrimaryBuffer(newRows: Int, newCols: Int) {
        if (newCols != cols) {
            // A column-count change (rotating the phone, a big enough pinch-zoom) needs actual
            // reflow, not just the row-preserving shift above — otherwise every wrapped line's
            // text stays chopped at the OLD column boundaries, visibly mangled at the new width.
            // No per-row wrap flag is tracked (that would mean threading a parallel structure
            // through every single line-mutating operation in this file for a phone-only,
            // rotate-mid-command edge case), so this uses the same heuristic several historic
            // terminal emulators without explicit wrap tracking have used: a row that's filled
            // edge-to-edge is treated as continuing onto the next one. Correct for the
            // overwhelmingly common case (a shell wrapping long output); the rare cost is
            // occasionally joining two coincidentally full-width, actually-separate lines.
            // Wrapped in a fallback to the old row-shift behavior since a resize must never
            // crash the session outright — reflow touches every scrollback row at once and a
            // missed edge case here shouldn't take the whole terminal down with it.
            val reflowResult = runCatching { reflow(newRows, newCols) }
            reflowResult.onFailure {
                // reflow() only ever mutates scrollback/screen in its last few lines, once every
                // index it needs (screenStart, newCursorAbsRow, ...) has already been computed
                // into local variables — an exception here means it failed *before* touching
                // either field, so falling back to the plain row-shift path below operates on
                // still-intact state, not a partially-rewritten one. Logging it is what makes a
                // real occurrence of this (a pathological screen/cursor state this heuristic
                // wasn't designed for) diagnosable instead of silently looking like ordinary
                // scrollback loss with nothing to explain why.
                Log.w("AlpDroid/Terminal", "reflow($newRows, $newCols) failed; falling back to row-shift resize", it)
            }
            if (reflowResult.isFailure) {
                cursorRow = resizePrimaryScreen(newRows, newCols).coerceIn(0, newRows - 1)
            }
        } else {
            cursorRow = resizePrimaryScreen(newRows, newCols).coerceIn(0, newRows - 1)
        }
        cursorCol = min(cursorCol, newCols - 1)
    }

    private fun reflow(newRows: Int, newCols: Int) {
        val allRows = ArrayList<Array<Cell>>(scrollback.size + screen.size)
        allRows.addAll(scrollback)
        allRows.addAll(screen)
        val cursorAbsRow = scrollback.size + cursorRow

        // Trim pure trailing blank rows beyond the last real content or the cursor's own row.
        // Any ordinary, mostly-empty session already has a full screen's worth of untouched blank
        // rows below wherever the cursor is — without this, every one of those gets carried
        // forward as its own separate logical line (a blank row never counts as "full", so each
        // one ends its own line rather than joining the next) and faithfully reproduced on every
        // resize. Repeated zooming in and out on the same session compounds that: each grow pads
        // fresh blanks on top of blanks that were already there from the last one, so the visible
        // gap between real content only ever grows, never shrinks back down.
        var lastContentRow = -1
        for (i in allRows.indices.reversed()) {
            if (allRows[i].any { it.ch != ' ' }) { lastContentRow = i; break }
        }
        val lastMeaningful = max(lastContentRow, cursorAbsRow)
        val trimmedRows = if (lastMeaningful + 1 < allRows.size) allRows.subList(0, lastMeaningful + 1) else allRows

        // Flatten into logical lines: a row filled edge-to-edge joins onto the next one.
        val logicalLines = ArrayList<ArrayList<Cell>>()
        var current = ArrayList<Cell>()
        var cursorLogicalLine = 0
        var cursorLogicalOffset = 0
        for ((idx, row) in trimmedRows.withIndex()) {
            if (idx == cursorAbsRow) {
                cursorLogicalLine = logicalLines.size
                cursorLogicalOffset = current.size + cursorCol.coerceIn(0, row.size)
            }
            current.addAll(row.toList())
            val full = row.isNotEmpty() && row.last().wrapped
            if (!full) {
                logicalLines.add(current)
                current = ArrayList()
            }
        }
        if (current.isNotEmpty() || logicalLines.isEmpty()) logicalLines.add(current)

        // Re-chunk each logical line at the new width, trimming trailing blank cells off the
        // end first so re-wrapping doesn't pad every line out to a full multiple of newCols.
        val newAllRows = ArrayList<Array<Cell>>()
        var newCursorAbsRow = 0
        var newCursorCol = 0
        for ((lineIdx, line) in logicalLines.withIndex()) {
            var end = line.size
            while (end > 0 && line[end - 1].ch == ' ') end--
            val trimmed = line.subList(0, end)
            if (trimmed.isEmpty()) {
                newAllRows.add(blankRow(newCols, TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG))
                if (lineIdx == cursorLogicalLine) { newCursorAbsRow = newAllRows.size - 1; newCursorCol = 0 }
                continue
            }
            var pos = 0
            while (pos < trimmed.size) {
                val chunkEnd = min(pos + newCols, trimmed.size)
                val rowArr = Array(newCols) { c -> if (pos + c < chunkEnd) trimmed[pos + c].copy(wrapped = false) else Cell(fg = TerminalColors.DEFAULT_FG, bg = TerminalColors.DEFAULT_BG) }
                if (pos + newCols < trimmed.size) rowArr[newCols - 1].wrapped = true
                newAllRows.add(rowArr)
                if (lineIdx == cursorLogicalLine) {
                    if (cursorLogicalOffset in pos until (pos + newCols)) {
                        newCursorAbsRow = newAllRows.size - 1
                        newCursorCol = cursorLogicalOffset - pos
                    } else if (cursorLogicalOffset >= trimmed.size && pos + newCols >= trimmed.size) {
                        // Cursor sits right after the last real character (the common case for
                        // an active prompt) — what would otherwise be trailing blank space that
                        // just got trimmed away. Pin it to the end of this, the line's last chunk.
                        newCursorAbsRow = newAllRows.size - 1
                        newCursorCol = (trimmed.size - pos).coerceIn(0, newCols - 1)
                    }
                }
                pos += newCols
            }
        }

        val screenStart = max(0, newAllRows.size - newRows)
        scrollback.clear()
        for (r in newAllRows.subList(0, screenStart)) scrollback.addLast(r)
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
        val newScreenRows = newAllRows.subList(screenStart, newAllRows.size).toMutableList()
        // Growing to a row count bigger than the trimmed real content only ever needs filler
        // *after* it now (trailing blank rows were cut above, so there's nothing meaningful left
        // to push down out of the way) — padding at the bottom keeps the cursor's own row exactly
        // where reflowing it landed, matching how resizePrimaryScreen (the row-count-only path)
        // already pads a grow, instead of shifting real content down by however much was added.
        while (newScreenRows.size < newRows) newScreenRows.add(blankRow(newCols, TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG))
        screen = newScreenRows

        cursorRow = (newCursorAbsRow - screenStart).coerceIn(0, newRows - 1)
        cursorCol = newCursorCol.coerceIn(0, newCols - 1)
    }

    /** Returns the new cursor row directly (not a shift) — a shrink that pushed rows above an
     *  already-clamped cursor and then grew back would otherwise compound the clamp into a
     *  second, larger error, which is what produced the blank gap between prompts after toggling
     *  the keyboard on a mostly-empty screen (cursor near row 0, nothing pushed to preserve it). */
    private fun resizePrimaryScreen(newRows: Int, newCols: Int): Int {
        val resizedOld = screen.map { resizeRow(it, newCols) }
        return when {
            newRows < resizedOld.size -> {
                // Push only as many rows above the cursor as are actually needed to keep the
                // cursor's own row inside the new, smaller screen — not unconditionally forcing it
                // all the way down to the last row. A short or freshly-opened session should behave
                // like an ordinary terminal: the cursor starts at the top and only settles at the
                // bottom once real typing has actually scrolled it there (lineFeed()'s own
                // scrollUp() already does exactly that on its own) — resize() has no business
                // getting ahead of that. If the cursor already fits (cursorRow <= newRows - 1),
                // needed is 0 and the cursor's row — and everything above it — stays exactly where
                // it visually was; only the still-blank rows below it get trimmed. A full/busy
                // screen (cursor already at/near the bottom from real usage) still lands on the
                // last row, same as before, since needed then equals cursorRow anyway.
                val totalToRemove = resizedOld.size - newRows
                val needed = max(0, cursorRow - (newRows - 1))
                val pushCount = min(totalToRemove, needed)
                for (i in 0 until pushCount) scrollback.addLast(resizedOld[i])
                while (scrollback.size > maxScrollback) scrollback.removeFirst()
                // Tracks specifically how many of the newest scrollback rows exist only because a
                // shrink temporarily displaced them, as opposed to genuine history that scrolled
                // off naturally (lineFeed's own scrollUp() never touches this) — see the grow
                // branch below for why the distinction matters.
                pendingRestoreCount += pushCount
                val dropFromBottom = totalToRemove - pushCount
                screen = resizedOld.subList(pushCount, resizedOld.size - dropFromBottom).toMutableList()
                cursorRow - pushCount
            }
            newRows > resizedOld.size -> {
                val deficit = newRows - resizedOld.size
                // Only pulls back rows that are still marked as pending from an earlier shrink —
                // never more than that, even if plain (unrelated, already-scrolled-off) history
                // sits underneath them in scrollback. Without this cap, growing back would just
                // pull from scrollback.size regardless of *why* those rows were there, so a
                // `clear` typed while shrunk (which only wipes the currently-visible rows, same as
                // every real terminal — scrollback survives a clear on purpose) got silently
                // undone the moment the keyboard closed and pulled the pre-clear content back onto
                // the main screen: exactly "clear didn't work" from the outside, even though
                // nothing was technically lost. eraseInDisplay()'s full-clear branch zeroes this
                // out for the same reason.
                val pullCount = minOf(deficit, pendingRestoreCount, scrollback.size)
                val pulled = ArrayList<Array<Cell>>(pullCount)
                repeat(pullCount) { pulled.add(0, scrollback.removeLast()) }
                pendingRestoreCount -= pullCount
                // Any remaining deficit pads the bottom, not the top — those rows were never
                // pushed to scrollback (only real-history rows above the cursor are), so there's
                // nothing to restore there; padding the top instead would shove the cursor's row
                // down away from where its content actually still is.
                val bottomPad = List(deficit - pullCount) { blankRow(newCols, TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG) }
                screen = (pulled + resizedOld + bottomPad).toMutableList()
                cursorRow + pullCount
            }
            else -> {
                screen = resizedOld.toMutableList()
                cursorRow
            }
        }
    }

    private fun resizeRow(row: Array<Cell>, newCols: Int): Array<Cell> =
        Array(newCols) { c -> row.getOrNull(c)?.copy() ?: Cell(fg = TerminalColors.DEFAULT_FG, bg = TerminalColors.DEFAULT_BG) }

    /** Plain truncate/pad, no scrollback interaction — used only for the alt-screen, which a
     *  full-screen program redraws entirely on its own SIGWINCH handler and never reads
     *  scrollback from anyway. */
    private fun resizeGrid(old: MutableList<Array<Cell>>, newRows: Int, newCols: Int): MutableList<Array<Cell>> {
        return MutableList(newRows) { r ->
            val src = old.getOrNull(r)
            Array(newCols) { c -> src?.getOrNull(c)?.copy() ?: Cell(fg = TerminalColors.DEFAULT_FG, bg = TerminalColors.DEFAULT_BG) }
        }
    }

    // These three are read from TerminalView.onDraw() on the UI thread every single frame while
    // feed() runs on the pty-reader thread, mutating the very same screen/scrollback collections
    // (neither ArrayList nor Kotlin's ArrayDeque is thread-safe) — without @Synchronized here to
    // match feed()'s own lock, a high-output command (a big recursive listing, anything reading
    // a large/virtual file) makes the two threads far more likely to actually collide mid-mutation,
    // surfacing as a crash that looks "random"/command-specific but is really just a race whose
    // odds scale with output volume, not anything particular about the command itself.
    /** Snapshot for rendering: scrollback (oldest first) is NOT included — the view asks for it separately via [scrollbackRow]. */
    @Synchronized
    fun rowAt(index: Int): Array<Cell> = screen[index]

    /** How far down from [cursorRow] the same visible block of content continues, stopping at the
     *  first fully blank row — used by TerminalView's keyboard-avoidance shift instead of trusting
     *  [cursorRow] alone. A plain shell prompt has nothing below the cursor worth protecting, so
     *  this returns exactly cursorRow for that case. But a full-screen TUI box (opencode's input
     *  box, which has its own status line and a hint row directly under the actual input line)
     *  often draws a few more non-blank rows right below wherever the cursor happens to sit, and
     *  those are just as much "the part currently worth keeping above the keyboard" as the cursor's
     *  own row. Deliberately bounded to a *contiguous* run starting at cursorRow rather than a
     *  whole-screen scan for the lowest non-blank row anywhere: a full-screen app's own static
     *  footer (a version string, a status bar) sitting far below the actual UI with blank rows in
     *  between would otherwise always win that scan, dragging the shift down to clear a footer
     *  nobody's about to type into while leaving the real input box hidden above it — an actual
     *  regression this replaces after being tried first. */
    @Synchronized
    fun contentBottomRow(): Int {
        var r = cursorRow
        while (r + 1 < rows && screen[r + 1].any { it.ch != ' ' }) r++
        return r
    }

    @Synchronized
    fun scrollbackSize(): Int = scrollback.size

    @Synchronized
    fun scrollbackRow(indexFromOldest: Int): Array<Cell> = scrollback.elementAt(indexFromOldest)

    /** One consistent picture of the grid for a single frame. onDraw() reads rows, scrollback
     *  size and individual rows through separate calls today; feed() on the pty-reader thread
     *  can mutate the grid between any two of them (a resize shrinking the grid mid-frame),
     *  turning a valid index captured a line earlier into an IndexOutOfBounds crash. Taking
     *  every reference under this one lock makes a frame atomic against feed()/resize(). */
    data class RenderSnapshot(
        val rows: Int,
        val cols: Int,
        val scrollbackSize: Int,
        val screenRows: List<Array<Cell>>,
        /** Only the tail of the scrollback that this frame can actually show (empty when not
         *  scrolled back); entry 0 is absolute scrollback index [scrollbackBase]. */
        val scrollbackRows: List<Array<Cell>>,
        val scrollbackBase: Int,
        val cursorRow: Int,
        val cursorCol: Int,
        val cursorVisible: Boolean,
        /** [contentHash] of the grid at the moment of the snapshot (taken under the same lock), so a
         *  repaint can tell later whether anything it would show has changed since. */
        val contentHash: Long = 0L,
    )

    // NOTE: intentionally shallow — the row lists are copied but Cells are shared with the
    // live grid. A deep copy per frame (200k+ cells of scrollback at 60fps) is prohibitive;
    // torn glyph/style reads from the PTY thread are cosmetic-only (array bounds come from the
    // same snapshot, so no crash), and geometry fields are @Volatile. Callers needing a stable
    // text scan (search, selection) copy row text under the emulator lock instead.
    @Synchronized
    fun renderSnapshot(scrollOffset: Int = 0): RenderSnapshot {
        // Copying the whole scrollback (up to maxScrollback rows) on every frame was the bulk of
        // per-frame allocation under heavy output. A frame scrolled back by scrollOffset shows
        // scrollback rows [size - scrollOffset, size - scrollOffset + min(scrollOffset, rows));
        // while following the tail (offset 0) it shows none.
        val sbSize = scrollback.size
        val base = (sbSize - scrollOffset).coerceIn(0, sbSize)
        val end = (sbSize - scrollOffset + minOf(scrollOffset, rows)).coerceIn(base, sbSize)
        return RenderSnapshot(
            rows = rows,
            cols = cols,
            scrollbackSize = sbSize,
            screenRows = screen.toList(),
            scrollbackRows = if (scrollOffset <= 0 || end == base) emptyList() else scrollback.subList(base, end).toList(),
            scrollbackBase = base,
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            cursorVisible = cursorVisible,
            contentHash = contentHash(),
        )
    }

    /**
     * 64-bit digest of everything the visible grid would paint: every cell's character and colours and
     * bold/underline/reverse, the cursor, and the size. Equal digests mean a repaint would produce an
     * identical frame — TUIs often rewrite the same content, and skipping those repaints is free
     * heat saved. Content-based on purpose: hooking every place that mutates a cell would be easy to
     * get subtly wrong (a missed hook is a stale screen), while this costs ~5k cells of arithmetic.
     */
    @Synchronized
    fun contentHash(): Long {
        var h = FNV_OFFSET
        for (row in screen) {
            for (c in row) {
                var v = c.ch.code.toLong() or ((c.fg.toLong() and 0xFFFFFFFFL) shl 16)
                if (c.bold) v = v xor (1L shl 52)
                if (c.underline) v = v xor (1L shl 53)
                if (c.reverse) v = v xor (1L shl 54)
                h = (h xor v) * FNV_PRIME
                h = (h xor (c.bg.toLong() and 0xFFFFFFFFL)) * FNV_PRIME
            }
        }
        h = (h xor ((cursorRow.toLong() shl 20) or cursorCol.toLong() or (if (cursorVisible) 1L shl 40 else 0L))) * FNV_PRIME
        h = (h xor ((rows.toLong() shl 16) or cols.toLong())) * FNV_PRIME
        return h
    }

    // --- Byte-level parsing -------------------------------------------------------------

    private fun processByte(b: Int) {
        when (state) {
            State.NORMAL -> processNormal(b)
            State.ESCAPE -> processEscape(b)
            State.CSI -> processCsi(b)
            State.OSC -> processOsc(b)
            State.CHARSET -> state = State.NORMAL // consume the one designator byte, ignore it
        }
    }

    private fun processNormal(b: Int) {
        // Any byte below 0x80 arriving while a multi-byte UTF-8 sequence is only partially
        // received means that sequence was truncated — an ESC starting a new escape sequence
        // mid-character, or plain ASCII interrupting one, are both bytes < 0x80 and were routed
        // here directly (bypassing feedUtf8() entirely), which used to leave those stray pending
        // bytes sitting around forever, silently prepended onto the *next* genuine multi-byte
        // sequence's own bytes and corrupting whatever character that produced.
        if (utf8Pending.isNotEmpty() && b < 0x80) {
            utf8Pending.clear()
            putChar('�')
        }
        when {
            b == 0x1B -> { state = State.ESCAPE; csiBuf.clear() }
            b == 0x07 -> pendingBells++
            b == 0x08 -> cursorCol = max(0, min(cursorCol, cols - 1) - 1)
            b == 0x09 -> { cursorCol = min(cols - 1, ((cursorCol / 8) + 1) * 8) }
            b == 0x0A -> lineFeed()
            b == 0x0D -> cursorCol = 0
            b < 0x20 -> {} // ignore other C0 controls
            b < 0x80 -> putChar(b.toChar())
            else -> feedUtf8(b)
        }
    }

    private var utf8PendingExpected = 0

    private fun feedUtf8(b: Int) {
        if (utf8Pending.isEmpty()) {
            utf8PendingExpected = when {
                b and 0xE0 == 0xC0 -> 2
                b and 0xF0 == 0xE0 -> 3
                b and 0xF8 == 0xF0 -> 4
                else -> { putChar('�'); return } // stray continuation byte with no lead
            }
            utf8Pending.add(b)
            return
        }
        if (b and 0xC0 != 0x80) {
            // Not a valid continuation byte (10xxxxxx) — the sequence in progress was truncated
            // (output cut off mid-character at a buffer boundary, or a program emitting raw bytes
            // without properly encoding them) and this byte is actually the start of the NEXT,
            // unrelated thing. Discard what was pending and reprocess this byte completely fresh
            // instead of feeding it into flushUtf8() as a bogus "continuation," which corrupted
            // whatever character came out the other end.
            utf8Pending.clear()
            putChar('�')
            processNormal(b)
            return
        }
        utf8Pending.add(b)
        if (utf8Pending.size >= utf8PendingExpected) flushUtf8()
    }

    private fun flushUtf8() {
        val bytes = ByteArray(utf8Pending.size) { utf8Pending[it].toByte() }
        utf8Pending.clear()
        val decoded = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        putChar(decoded?.firstOrNull() ?: '�')
    }

    private fun processEscape(b: Int) {
        val c = b.toChar()
        when (c) {
            '[' -> { state = State.CSI; csiBuf.clear() }
            ']' -> { state = State.OSC; csiBuf.clear(); oscLen = 0; oscLimit = 1024 }
            // DCS / SOS / PM / APC strings (tmux passthrough, sixel, kitty graphics): ended by ST, payload
            // swallowed instead of printed as text.
            'P', 'X', '^', '_' -> { state = State.OSC; csiBuf.clear(); oscLen = 0; oscLimit = 65536 }
            '7' -> { savedRow = cursorRow; savedCol = cursorCol; state = State.NORMAL }
            '8' -> { cursorRow = min(savedRow, rows - 1); cursorCol = min(savedCol, cols - 1); state = State.NORMAL }
            'c' -> { resetHard(); state = State.NORMAL }
            'D' -> { lineFeed(); state = State.NORMAL }
            'E' -> { cursorCol = 0; lineFeed(); state = State.NORMAL }
            'M' -> { reverseIndex(); state = State.NORMAL }
            '(', ')', '*', '+' -> state = State.CHARSET
            else -> state = State.NORMAL
        }
    }

    private fun processOsc(b: Int) {
        // BEL alone terminates outright. ESC is the *first* half of the standard "ST" terminator
        // (ESC \) — many modern programs (tmux/fish/nvim's own title-setting, OSC 8 hyperlinks:
        // `ls --hyperlink`, gcc/clang/rustc's own diagnostics) use ST rather than BEL. Re-entering
        // ESCAPE state (not straight to NORMAL) lets the following backslash actually get
        // consumed as part of the terminator via processEscape()'s own unmatched-byte fallback,
        // instead of falling through to processNormal() and being printed as a literal stray "\"
        // in front of the next line of real output.
        if (b == 0x07) state = State.NORMAL
        else if (b == 0x1B) state = State.ESCAPE
        // Length cap: an unterminated OSC (binary dump through the terminal) must not wedge the
        // parser in this state indefinitely, swallowing all later output until some far-off BEL.
        else if (++oscLen > oscLimit) { state = State.NORMAL; oscLen = 0 }
    }

    private fun processCsi(b: Int) {
        if (b in 0x30..0x3F || b in 0x20..0x2F) {
            // Capped: a malformed/hostile stream of parameter bytes with no final byte
            // (a stuck program, a binary file catted to the terminal) would otherwise grow
            // this buffer without bound until the process ran out of memory.
            if (csiBuf.length < 256) csiBuf.append(b.toChar()) else state = State.NORMAL
            return
        }
        if (b in 0x40..0x7E) {
            // State first: a dispatch that throws must not leave the parser stuck in CSI (the next
            // printable byte would then be swallowed as a bogus final byte).
            state = State.NORMAL
            dispatchCsi(b.toChar(), csiBuf.toString())
            return
        }
        state = State.NORMAL // malformed; bail out rather than hang in CSI forever
    }

    private fun dispatchCsi(final: Char, raw: String) {
        // "?", "<", ">", "=" are all private-parameter leader bytes (ECMA-48's 0x3C-0x3F range),
        // not just "?" — "<"/">"/"=" are exactly how the kitty keyboard protocol's push/pop/query
        // (CSI > 1 u / CSI < u / CSI ? u — sent by fish 4, neovim, helix, and any other program
        // that speaks it) and DECSET-family variants are framed. Treating only "?" as private used
        // to let a ">"/"<"/"="-prefixed sequence fall through to this file's own *unrelated* plain
        // handler for the same final byte — CSI > 1 u hit the plain 'u' case (restore cursor),
        // silently relocating the cursor, and CSI > 4;1 m got applied as SGR bold. None of what
        // these prefixes actually request is implemented here, so recognizing and ignoring them is
        // the correct behavior — not "run the plain-sequence handler because the final byte
        // happens to coincide."
        val prefixChar = raw.firstOrNull()?.takeIf { it in "?<>=" }
        if (prefixChar != null && prefixChar != '?') return
        val private = prefixChar == '?'
        val body = if (private) raw.substring(1) else raw
        val params = if (final == 'm' && !private) parseSgrParams(body) else body.split(";").map { it.toIntOrNull() ?: 0 }
        // Clamp: an unbounded count (e.g. 2147483647) would overflow cursor arithmetic to a
        // negative row/col and crash on the next screen[] access.
        fun p(i: Int, default: Int = 0) = params.getOrNull(i)?.takeIf { it != 0 }?.coerceIn(0, 9999) ?: default

        when (final) {
            'A' -> cursorRow = max(topMargin, cursorRow - max(1, p(0, 1)))
            'B' -> cursorRow = min(bottomMargin, cursorRow + max(1, p(0, 1)))
            'C' -> cursorCol = min(cols - 1, cursorCol + max(1, p(0, 1)))
            'D' -> cursorCol = max(0, min(cursorCol, cols - 1) - max(1, p(0, 1)))
            'H', 'f' -> {
                cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (p(1, 1) - 1).coerceIn(0, cols - 1)
            }
            'G' -> cursorCol = (p(0, 1) - 1).coerceIn(0, cols - 1)
            'd' -> cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
            'J' -> eraseInDisplay(p(0, 0))
            'K' -> eraseInLine(p(0, 0))
            '@' -> insertChars(max(1, p(0, 1)))
            'P' -> deleteChars(max(1, p(0, 1)))
            'L' -> insertLines(max(1, p(0, 1)))
            'M' -> deleteLines(max(1, p(0, 1)))
            'X' -> eraseChars(max(1, p(0, 1)))
            'S' -> scrollUp(max(1, p(0, 1)))
            'T' -> scrollDown(max(1, p(0, 1)))
            'r' -> {
                topMargin = (p(0, 1) - 1).coerceIn(0, rows - 1)
                bottomMargin = (if (params.size > 1 && params[1] != 0) params[1] else rows).coerceIn(1, rows) - 1
                if (topMargin > bottomMargin) { topMargin = 0; bottomMargin = rows - 1 }
                cursorRow = 0; cursorCol = 0 // DECSTBM homes the cursor
            }
            'b' -> repeat(min(max(1, p(0, 1)), cols * rows)) { putChar(lastPrinted) } // REP
            // Primary device attributes: "VT220-ish, with colour" — answered so programs that probe at
            // startup don't sit waiting for their timeout.
            'c' -> if (p(0, 0) == 0) queueResponse("\u001B[?62;c")
            'm' -> applySgr(params)
            'h' -> setMode(private, params, true)
            'l' -> setMode(private, params, false)
            's' -> { savedRow = cursorRow; savedCol = cursorCol }
            'u' -> { cursorRow = min(savedRow, rows - 1); cursorCol = min(savedCol, cols - 1) }
            'n' -> if (p(0, 0) == 6) queueResponse("\u001B[${cursorRow + 1};${min(cursorCol, cols - 1) + 1}R")
            else -> {} // unhandled final byte: ignore rather than crash
        }
    }

    private fun setMode(private: Boolean, params: List<Int>, enable: Boolean) {
        if (!private) return
        for (mode in params) {
            when (mode) {
                25 -> cursorVisible = enable
                1 -> applicationCursorKeys = enable
                2004 -> bracketedPasteEnabled = enable
                // DECAWM — a program drawing a fixed-width status/progress element that doesn't
                // want it wrapping onto a new line if it happens to be wider than the terminal
                // (many full-screen/status-line tools disable this deliberately) explicitly
                // requests it turned off here; previously untracked entirely, so it was silently
                // ignored and every such program always got hard-wrapped regardless of what it
                // asked for.
                7 -> autoWrapEnabled = enable
                1049 -> switchAltScreen(enable, clear = true)
                1047, 47 -> switchAltScreen(enable, clear = false)
                // Mouse click reporting (1000: press/release, 1002: +drag, 1003: +plain motion) —
                // a full-screen TUI (opencode, htop, a mouse-aware vim/less) that turns this on
                // wants taps translated into click escape sequences sent to it, not swallowed by
                // this app's own "tap the terminal to open the keyboard" gesture. Only one of these
                // three is ever active at a time per the spec; disabling whichever one is currently
                // on (by any of the three numbers — real programs always disable with the same
                // number they enabled) is what setting it back to 0 means. 1002/1003 aren't given
                // any drag/motion behavior beyond what a plain tap already provides — real click
                // reporting is enough to fix "tapping inside the app opens the keyboard instead of
                // clicking" without tracking every finger movement as synthetic mouse motion.
                1000, 1002, 1003 -> mouseReportingMode = if (enable) mode else 0
                1006 -> mouseSgrMode = enable
                else -> {} // 12 cursor blink, etc: tracked nowhere, harmless to ignore
            }
        }
    }

    /** Fires the moment [switchAltScreen] actually changes state (not on a redundant/nested
     *  enable or disable) — TerminalView uses this to restore the shell's real terminal size right
     *  away when a full-screen program exits, rather than waiting for its next draw frame to
     *  notice via [inAltScreen] and then debouncing another ~150ms on top of that. That gap used to
     *  be a real, reproducible race: typing a command immediately after leaving a full-screen app
     *  (before the delayed resize actually landed) executed at whatever the temporarily-reduced
     *  keyboard-driven row count still was, and if that command was "exit" — ending the shell right
     *  as the pending resize came due — the reader thread's EOF handling and the resize's own
     *  onGridSize()/session.resize() call could interleave against a tab already being torn down,
     *  which looked exactly like the tab hanging instead of closing.
     *
     *  @Volatile — set from the main thread (TerminalView's emulator setter) but read and invoked
     *  from the pty reader thread (inside feed()/switchAltScreen(), same as TerminalTab's own
     *  onOutput/onExit callbacks) — without it, a plain field has no guarantee the reader thread
     *  ever observes the assignment promptly, or at all, since nothing else here forces a memory
     *  barrier between "the main thread wired this callback" and "the reader thread checks it". */
    @Volatile var onAltScreenChanged: ((Boolean) -> Unit)? = null

    private fun switchAltScreen(enable: Boolean, clear: Boolean) {
        if (enable) {
            if (primaryScreen != null) return // already showing the alt screen; ignore a nested/duplicate enable
            primaryScreen = screen
            primaryCursorRow = cursorRow
            primaryCursorCol = cursorCol
            // 1049 always starts blank; 1047/47 preserve. Reusing a stale buffer showed the
            // previous fullscreen app's content on the next enter.
            if (clear || altScreen == null) altScreen = MutableList(rows) { blankRow(cols, TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG) }
            screen = altScreen!!
            cursorRow = 0
            cursorCol = 0
        } else {
            val savedPrimary = primaryScreen ?: return // not currently in the alt screen; ignore
            altScreen = screen
            screen = savedPrimary
            primaryScreen = null
            cursorRow = primaryCursorRow.coerceIn(0, rows - 1)
            cursorCol = primaryCursorCol.coerceIn(0, cols - 1)
        }
        // Deferred: invoked by feed() after unlocking (see pendingAlt).
        pendingAlt = enable
    }

    /** SGR parameters with ':' sub-parameters (ITU T.416 colours `38:2::r:g:b`, underline styles `4:3`)
     *  flattened into the classic ';' form. Unknown colon forms are dropped — they used to parse as 0,
     *  i.e. a full attribute reset. */
    private fun parseSgrParams(body: String): List<Int> {
        val out = ArrayList<Int>()
        for (tok in body.split(';')) {
            if (':' !in tok) { out.add(tok.toIntOrNull() ?: 0); continue }
            val sub = tok.split(':')
            val head = sub[0].toIntOrNull() ?: 0
            when (head) {
                38, 48, 58 -> when (sub.getOrNull(1)?.toIntOrNull()) {
                    5 -> { out.add(head); out.add(5); out.add(sub.getOrNull(2)?.toIntOrNull() ?: 0) }
                    2 -> {
                        val rgb = sub.drop(if (sub.size >= 6) 3 else 2).map { it.toIntOrNull() ?: 0 }
                        if (rgb.size >= 3) { out.add(head); out.add(2); out.addAll(rgb.take(3)) }
                    }
                }
                4 -> out.add(if ((sub.getOrNull(1)?.toIntOrNull() ?: 1) == 0) 24 else 4)
                else -> {}
            }
        }
        return out
    }

    private fun applySgr(paramsIn: List<Int>) {
        val params = if (paramsIn.isEmpty()) listOf(0) else paramsIn
        var i = 0
        while (i < params.size) {
            when (val p = params[i]) {
                0 -> {
                    curFg = TerminalColors.DEFAULT_FG; curBg = TerminalColors.DEFAULT_BG
                    curFgKind = Cell.KIND_DEFAULT; curBgKind = Cell.KIND_DEFAULT
                    curBold = false; curUnderline = false; curReverse = false
                }
                1 -> curBold = true
                4 -> curUnderline = true
                7 -> curReverse = true
                22 -> curBold = false
                24 -> curUnderline = false
                27 -> curReverse = false
                in 30..37 -> { curFg = TerminalColors.ANSI16[p - 30]; curFgKind = p - 30 }
                39 -> { curFg = TerminalColors.DEFAULT_FG; curFgKind = Cell.KIND_DEFAULT }
                in 40..47 -> { curBg = TerminalColors.ANSI16[p - 40]; curBgKind = p - 40 }
                49 -> { curBg = TerminalColors.DEFAULT_BG; curBgKind = Cell.KIND_DEFAULT }
                in 90..97 -> { curFg = TerminalColors.ANSI16[p - 90 + 8]; curFgKind = p - 90 + 8 }
                in 100..107 -> { curBg = TerminalColors.ANSI16[p - 100 + 8]; curBgKind = p - 100 + 8 }
                58 -> { // underline colour: not drawn, but its operands must not be read as attributes
                    if (params.getOrNull(i + 1) == 5) i += 2 else if (params.getOrNull(i + 1) == 2) i += 4
                }
                38, 48 -> {
                    val isFg = p == 38
                    if (params.getOrNull(i + 1) == 5 && i + 2 < params.size) {
                        val color = TerminalColors.ansi256(params[i + 2])
                        if (isFg) { curFg = color; curFgKind = Cell.KIND_FIXED } else { curBg = color; curBgKind = Cell.KIND_FIXED }
                        i += 2
                    } else if (params.getOrNull(i + 1) == 2 && i + 4 < params.size) {
                        val color = TerminalColors.rgb(params[i + 2], params[i + 3], params[i + 4])
                        if (isFg) { curFg = color; curFgKind = Cell.KIND_FIXED } else { curBg = color; curBgKind = Cell.KIND_FIXED }
                        i += 4
                    }
                }
                else -> {}
            }
            i++
        }
    }

    // --- Screen mutation -----------------------------------------------------------------

    private var lastPrinted = ' '

    private fun putChar(c: Char) {
        lastPrinted = c
        // Guard: transient out-of-range cursor (resize race, hostile CSI) must clamp, not crash.
        // cursorCol == cols is the legitimate wrap-pending state (set by cursorCol++ below), so
        // the upper bound stays cols, not cols - 1 — the wrap branch right below handles it.
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols)
        if (cursorCol >= cols) {
            if (!autoWrapEnabled) {
                // Overwrite the last column in place instead of wrapping — standard terminal
                // behavior with DECAWM off, for a program that explicitly disabled wrap because it
                // never wants what it draws to spill onto a new line.
                cursorCol = cols - 1
            } else {
                screen[cursorRow][cols - 1].wrapped = true
                cursorCol = 0
                lineFeed()
            }
        }
        val cell = screen[cursorRow][cursorCol]
        cell.wrapped = false
        cell.ch = c
        cell.fg = curFg
        cell.bg = curBg
        cell.fgKind = curFgKind
        cell.bgKind = curBgKind
        cell.bold = curBold
        cell.underline = curUnderline
        cell.reverse = curReverse
        cursorCol++
    }

    private fun lineFeed() {
        if (cursorRow == bottomMargin) scrollUp(1) else cursorRow = min(rows - 1, cursorRow + 1)
    }

    private fun reverseIndex() {
        if (cursorRow == topMargin) scrollDown(1) else cursorRow = max(0, cursorRow - 1)
    }

    private fun scrollUp(n: Int) {
        repeat(min(n, bottomMargin - topMargin + 1).coerceAtLeast(0)) {
            val row = screen.removeAt(topMargin)
            // Only lines leaving the top of the *whole primary screen* are history. Alt-screen programs
            // (vim, less, htop) and partial scroll regions just recycle the row — they used to fill the
            // shell's scrollback with screen fragments.
            if (topMargin == 0 && bottomMargin == rows - 1 && primaryScreen == null) {
                scrollback.addLast(row)
                // When full, the evicted oldest row becomes the new blank bottom row instead of garbage.
                var recycled: Array<Cell>? = null
                while (scrollback.size > maxScrollback) recycled = scrollback.removeFirst()
                screen.add(bottomMargin, if (recycled != null && recycled.size == cols) clearRow(recycled) else blankRow(cols, TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG))
            } else {
                screen.add(bottomMargin, clearRow(row))
            }
        }
    }

    private fun clearRow(r: Array<Cell>): Array<Cell> {
        for (it in r) {
            it.ch = ' '
            it.wrapped = false
            it.fg = TerminalColors.DEFAULT_FG; it.bg = TerminalColors.DEFAULT_BG
            it.fgKind = Cell.KIND_DEFAULT; it.bgKind = Cell.KIND_DEFAULT
            it.bold = false; it.underline = false; it.reverse = false
        }
        return r
    }

    private fun scrollDown(n: Int) {
        repeat(min(n, bottomMargin - topMargin + 1).coerceAtLeast(0)) {
            screen.removeAt(bottomMargin)
            screen.add(topMargin, blankRow(cols, TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG))
        }
    }

    private fun insertLines(n: Int) {
        if (cursorRow < topMargin || cursorRow > bottomMargin) return
        repeat(min(n, bottomMargin - cursorRow + 1).coerceAtLeast(0)) {
            screen.removeAt(bottomMargin)
            screen.add(cursorRow, blankRow(cols, TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG))
        }
    }

    private fun deleteLines(n: Int) {
        if (cursorRow < topMargin || cursorRow > bottomMargin) return
        if (screen.getOrNull(cursorRow) == null || screen.getOrNull(bottomMargin) == null) return
        repeat(min(n, bottomMargin - cursorRow + 1).coerceAtLeast(0)) {
            screen.removeAt(cursorRow)
            screen.add(bottomMargin, blankRow(cols, TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG))
        }
    }

    private fun insertChars(n: Int) {
        // Stale cursorRow (resize racing the reader thread) must not throw here — feed()
        // runs off-UI and an IndexOutOfBounds would kill the session's reader thread.
        val row = screen.getOrNull(cursorRow) ?: return
        val count = min(n, cols - cursorCol)
        // Copy: Cells are mutable and putChar edits in place — sharing one instance across
        // two columns would make a later write to one visibly rewrite the other.
        for (i in cols - 1 downTo cursorCol + count) row[i] = row[i - count].copy()
        for (i in cursorCol until cursorCol + count) row[i] = Cell(fg = curFg, bg = curBg, fgKind = curFgKind, bgKind = curBgKind)
    }

    private fun deleteChars(n: Int) {
        val row = screen.getOrNull(cursorRow) ?: return
        val count = min(n, cols - cursorCol)
        for (i in cursorCol until cols - count) row[i] = row[i + count].copy()
        for (i in cols - count until cols) row[i] = Cell(fg = curFg, bg = curBg, fgKind = curFgKind, bgKind = curBgKind)
    }

    private fun eraseChars(n: Int) {
        val row = screen.getOrNull(cursorRow) ?: return
        for (i in cursorCol until min(cols, cursorCol + n)) row[i] = Cell(fg = curFg, bg = curBg, fgKind = curFgKind, bgKind = curBgKind)
    }

    private fun eraseInLine(mode: Int) {
        val row = screen.getOrNull(cursorRow) ?: return
        // cursorCol == cols is the wrap-pending state: the cursor is on the last cell.
        val col = min(cursorCol, cols - 1)
        val range = when (mode) {
            0 -> col until cols
            1 -> 0..col
            else -> 0 until cols
        }
        for (i in range) row[i] = Cell(fg = curFg, bg = curBg, fgKind = curFgKind, bgKind = curBgKind)
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> { eraseInLine(0); for (r in cursorRow + 1 until rows) screen[r] = blankRow(cols, curFg, curBg, curFgKind, curBgKind) }
            1 -> { eraseInLine(1); for (r in 0 until cursorRow) screen[r] = blankRow(cols, curFg, curBg, curFgKind, curBgKind) }
            // ESC[3J clears the scrollback buffer (xterm); previously fell into the full-clear
            // branch, so scrollback survived printf '\e[3J'.
            3 -> { scrollback.clear(); pendingRestoreCount = 0 }
            else -> for (r in 0 until rows) screen[r] = blankRow(cols, curFg, curBg, curFgKind, curBgKind)
        }
        // The `clear` utility commonly sends cursor-home followed by a *bare* ESC[J (mode 0, erase
        // from cursor down) rather than ESC[2J — with the cursor sitting at row 0 col 0 right after
        // the home, mode 0 wipes the same rows mode 2 would, but takes the `0 ->` branch above, not
        // the explicit full-clear one. Checking the actual result rather than the mode number is
        // what catches that: whenever an erase leaves the whole visible screen blank, however it got
        // there, any scrollback rows still pending from a keyboard-driven shrink are no longer
        // "current" history — without resetting this, closing the keyboard right after a `clear`
        // pulled that pre-clear content straight back onto the main screen, undoing the clear.
        // Skip the scan entirely when nothing is pending: its only effect is resetting
        // pendingRestoreCount, so a zero value makes the full-grid walk pure waste (this runs
        // on every ESC[J, including hostile spam).
        if (pendingRestoreCount != 0 && screen.all { row -> row.all { it.ch == ' ' } }) pendingRestoreCount = 0
    }

    private fun resetHard() {
        for (r in 0 until rows) screen[r] = blankRow(cols, TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG)
        pendingRestoreCount = 0
        scrollback.clear()
        // ESC c is a full reset: leave the alt screen too, or a fullscreen app's grid (and its
        // stale size expectations) survives a reset that was supposed to clear everything.
        if (primaryScreen != null) {
            screen = primaryScreen!!
            primaryScreen = null
            pendingAlt = false
        }
        altScreen = null
        cursorRow = 0; cursorCol = 0
        curFg = TerminalColors.DEFAULT_FG; curBg = TerminalColors.DEFAULT_BG
        curFgKind = Cell.KIND_DEFAULT; curBgKind = Cell.KIND_DEFAULT
        curBold = false; curUnderline = false; curReverse = false
        topMargin = 0; bottomMargin = rows - 1
        cursorVisible = true
        bracketedPasteEnabled = false
        autoWrapEnabled = true
        mouseReportingMode = 0
        mouseSgrMode = false
    }

    /**
     * Re-resolves every cell's displayed [Cell.fg]/[Cell.bg] — across the current screen, the
     * alternate screen (if a full-screen program has it active), and the whole scrollback —
     * against whatever theme is active *right now*, using each cell's remembered [Cell.fgKind]/
     * [Cell.bgKind]. Called once, right after [TerminalColors.applyTheme], so a theme switch
     * repaints everything already on screen instead of only text written from that point on.
     * Cells from an explicit 256-color/truecolor SGR ([Cell.KIND_FIXED]) are untouched — they
     * were never theme-relative in the first place.
     */
    @Synchronized
    fun applyPalette() {
        fun resolve(kind: Int): Int? = when {
            kind == Cell.KIND_DEFAULT -> null // caller decides fg vs bg default
            kind == Cell.KIND_FIXED -> null // leave as-is
            else -> TerminalColors.ANSI16.getOrNull(kind)
        }
        fun recolor(row: Array<Cell>) {
            for (cell in row) {
                when (cell.fgKind) {
                    Cell.KIND_DEFAULT -> cell.fg = TerminalColors.DEFAULT_FG
                    Cell.KIND_FIXED -> {}
                    else -> resolve(cell.fgKind)?.let { cell.fg = it }
                }
                when (cell.bgKind) {
                    Cell.KIND_DEFAULT -> cell.bg = TerminalColors.DEFAULT_BG
                    Cell.KIND_FIXED -> {}
                    else -> resolve(cell.bgKind)?.let { cell.bg = it }
                }
            }
        }
        for (row in screen) recolor(row)
        altScreen?.forEach { recolor(it) }
        // While a full-screen app has the alt screen up, the primary screen sits in this field
        // instead of `screen` (see switchAltScreen()) — missing it here meant a theme change made
        // while inside opencode/vim/less looked applied, but the ordinary shell content it would
        // return to on exit stayed in the old theme's colors until something else touched it.
        primaryScreen?.forEach { recolor(it) }
        for (i in 0 until scrollback.size) recolor(scrollback.elementAt(i))
        curFg = when (curFgKind) { Cell.KIND_DEFAULT -> TerminalColors.DEFAULT_FG; Cell.KIND_FIXED -> curFg; else -> TerminalColors.ANSI16.getOrElse(curFgKind) { curFg } }
        curBg = when (curBgKind) { Cell.KIND_DEFAULT -> TerminalColors.DEFAULT_BG; Cell.KIND_FIXED -> curBg; else -> TerminalColors.ANSI16.getOrElse(curBgKind) { curBg } }
        generation++
    }
}
