package com.alpdroid.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {
    private fun TerminalEmulator.type(s: String) = feed(s.toByteArray(Charsets.UTF_8), s.toByteArray(Charsets.UTF_8).size)

    private fun TerminalEmulator.rowText(r: Int) = rowAt(r).map { it.ch }.joinToString("").trimEnd()

    @Test fun plainTextAdvancesCursor() {
        val t = TerminalEmulator(5, 20)
        t.type("hello")
        assertEquals("hello", t.rowText(0))
        assertEquals(5, t.cursorCol)
        assertEquals(0, t.cursorRow)
    }

    @Test fun crlfMovesToNextLine() {
        val t = TerminalEmulator(5, 20)
        t.type("a\r\nb")
        assertEquals("a", t.rowText(0))
        assertEquals("b", t.rowText(1))
        assertEquals(1, t.cursorRow)
    }

    @Test fun cursorPositionSequence() {
        val t = TerminalEmulator(6, 20)
        t.type("\u001B[3;5HX")
        assertEquals('X', t.rowAt(2)[4].ch)
    }

    @Test fun sgrColorAndBold() {
        val t = TerminalEmulator(3, 10)
        t.type("\u001B[31;1mR\u001B[0mN")
        assertNotEquals(TerminalColors.DEFAULT_FG, t.rowAt(0)[0].fg)
        assertTrue(t.rowAt(0)[0].bold)
        assertEquals(TerminalColors.DEFAULT_FG, t.rowAt(0)[1].fg)
        assertFalse(t.rowAt(0)[1].bold)
    }

    @Test fun eraseDisplayClearsScreen() {
        val t = TerminalEmulator(3, 10)
        t.type("abc\u001B[2J")
        assertEquals("", t.rowText(0))
    }

    @Test fun autoWrapContinuesOnNextRow() {
        val t = TerminalEmulator(4, 5)
        t.type("abcdefg")
        assertEquals("abcde", t.rowText(0))
        assertEquals("fg", t.rowText(1))
    }

    @Test fun scrollbackGrowsAndIsCapped() {
        val t = TerminalEmulator(5, 10, maxScrollback = 10)
        repeat(100) { t.type("line $it\r\n") }
        assertTrue(t.scrollbackSize() > 0)
        assertTrue(t.scrollbackSize() <= 10)
    }

    @Test fun utf8SplitAcrossFeedsIsDecoded() {
        val t = TerminalEmulator(3, 10)
        val bytes = "é".toByteArray(Charsets.UTF_8)
        t.feed(byteArrayOf(bytes[0]), 1)
        t.feed(byteArrayOf(bytes[1]), 1)
        assertEquals('é', t.rowAt(0)[0].ch)
    }

    @Test fun cursorPositionReportIsAnswered() {
        val replies = mutableListOf<String>()
        val t = TerminalEmulator(5, 10, respond = { replies += it })
        t.type("ab\u001B[6n")
        assertEquals(listOf("\u001B[1;3R"), replies)
    }

    @Test fun bracketedPasteModeToggles() {
        val t = TerminalEmulator(3, 10)
        assertFalse(t.bracketedPasteEnabled)
        t.type("\u001B[?2004h")
        assertTrue(t.bracketedPasteEnabled)
        t.type("\u001B[?2004l")
        assertFalse(t.bracketedPasteEnabled)
    }

    @Test fun altScreenRestoresPrimaryContent() {
        val t = TerminalEmulator(4, 10)
        t.type("keep")
        t.type("\u001B[?1049h")
        assertTrue(t.inAltScreen)
        t.type("alt")
        t.type("\u001B[?1049l")
        assertFalse(t.inAltScreen)
        assertEquals("keep", t.rowText(0))
    }

    @Test fun resizeKeepsGeometryConsistent() {
        val t = TerminalEmulator(5, 20)
        t.type("hello world")
        t.resize(8, 12)
        assertEquals(8, t.rows)
        assertEquals(12, t.cols)
        assertTrue(t.cursorRow in 0 until 8)
        assertTrue(t.cursorCol in 0..12)
    }

    @Test fun renderSnapshotCopiesOnlyVisibleScrollback() {
        val t = TerminalEmulator(4, 10, maxScrollback = 50)
        repeat(30) { t.type("row $it\r\n") }
        val size = t.scrollbackSize()
        assertTrue(size > 8)

        val live = t.renderSnapshot(0)
        assertTrue(live.scrollbackRows.isEmpty())
        assertEquals(size, live.scrollbackSize)

        val scrolled = t.renderSnapshot(3)
        assertEquals(3, scrolled.scrollbackRows.size)
        assertEquals(size - 3, scrolled.scrollbackBase)

        // Scrolled further than one screen: only a screen's worth of rows is ever drawn.
        val far = t.renderSnapshot(10)
        assertEquals(4, far.scrollbackRows.size)
        assertEquals(size - 10, far.scrollbackBase)
    }

    @Test fun feedInSlicesProducesSameResultAsOneShot() {
        val big = StringBuilder().apply { repeat(2000) { append("0123456789abcdef\r\n") } }.toString()
        val a = TerminalEmulator(10, 40, maxScrollback = 5000)
        val b = TerminalEmulator(10, 40, maxScrollback = 5000)
        a.type(big)
        val bytes = big.toByteArray()
        var i = 0
        while (i < bytes.size) { val n = minOf(7, bytes.size - i); b.feed(bytes.copyOfRange(i, i + n), n); i += n }
        assertEquals(a.fullText(), b.fullText())
    }

    @Test fun trimScrollbackDropsOldestRowsOnly() {
        val t = TerminalEmulator(4, 10, maxScrollback = 500)
        repeat(100) { t.type("row $it\r\n") }
        val before = t.scrollbackSize()
        assertTrue(before > 50)
        val newest = t.scrollbackRow(before - 1).map { it.ch }.joinToString("").trimEnd()

        val removed = t.trimScrollback(20)
        assertEquals(before - 20, removed)
        assertEquals(20, t.scrollbackSize())
        // The newest rows (the ones just above the screen) are the ones kept.
        assertEquals(newest, t.scrollbackRow(19).map { it.ch }.joinToString("").trimEnd())
        // Visible screen untouched; trimming further than what exists is harmless.
        assertEquals(0, t.trimScrollback(100))
        assertEquals(4, t.rows)
    }

    @Test fun contentHashIsStableForIdenticalContent() {
        val a = TerminalEmulator(5, 20)
        val b = TerminalEmulator(5, 20)
        a.type("hello\u001B[31m world")
        b.type("hello\u001B[31m world")
        assertEquals(a.contentHash(), b.contentHash())
        // Rewriting the same text in place leaves the frame identical.
        val before = a.contentHash()
        a.type("\u001B[0m\u001B[1;1Hhello\u001B[31m world")
        assertEquals(before, a.contentHash())
    }

    @Test fun contentHashChangesWithAnythingVisible() {
        val t = TerminalEmulator(5, 20)
        t.type("abc")
        val base = t.contentHash()
        t.type("d")
        val afterChar = t.contentHash()
        assertNotEquals(base, afterChar)
        t.type("\u001B[1;1H") // cursor only
        assertNotEquals(afterChar, t.contentHash())
        val atHome = t.contentHash()
        t.type("\u001B[1m\u001B[1;1Ha") // same char, now bold
        assertNotEquals(atHome, t.contentHash())
        t.type("\u001B[?25l") // cursor hidden
        val hidden = t.contentHash()
        t.type("\u001B[?25h")
        assertNotEquals(hidden, t.contentHash())
    }

    @Test fun contentHashSeesColourAndSize() {
        val a = TerminalEmulator(5, 20); val b = TerminalEmulator(5, 20)
        a.type("x"); b.type("\u001B[44mx")
        assertNotEquals(a.contentHash(), b.contentHash())
        val c = TerminalEmulator(5, 20); val d = TerminalEmulator(6, 20)
        assertNotEquals(c.contentHash(), d.contentHash())
    }

    @Test fun snapshotCarriesTheHashItWasTakenWith() {
        val t = TerminalEmulator(5, 20)
        t.type("abc")
        assertEquals(t.contentHash(), t.renderSnapshot().contentHash)
    }

    @Test fun eraseToStartAtWrapPendingCursorDoesNotThrowOrStickInCsi() {
        val t = TerminalEmulator(3, 5)
        t.type("abcde")            // cursor is now wrap-pending (col == cols)
        t.type("\u001B[1K")        // used to throw ArrayIndexOutOfBounds and stay in CSI state
        t.type("X")                // must print as text (wrapping to the next row), not be eaten as a CSI final byte
        assertEquals("X", t.rowText(1))
    }

    @Test fun eraseInDisplayAtWrapPendingCursorWorks() {
        val t = TerminalEmulator(3, 5)
        t.type("abcde\u001B[1J")
        assertEquals("", t.rowText(0))
    }

    @Test fun backspaceFromWrapPendingMovesOntoPenultimateCell() {
        val t = TerminalEmulator(3, 5)
        t.type("abcde\b")
        assertEquals(3, t.cursorCol)
    }

    @Test fun altScreenScrollingDoesNotFillShellScrollback() {
        val t = TerminalEmulator(4, 10)
        t.type("\u001B[?1049h")
        repeat(30) { t.type("row $it\r\n") }
        t.type("\u001B[?1049l")
        assertEquals(0, t.scrollbackSize())
    }

    @Test fun partialScrollRegionDoesNotFillScrollback() {
        val t = TerminalEmulator(6, 10)
        t.type("\u001B[1;3r")      // region rows 1-3 only, top margin 0
        t.type("\u001B[3;1H")
        repeat(10) { t.type("x\r\n") }
        assertEquals(0, t.scrollbackSize())
    }

    @Test fun fullScrollbackRecyclesRowsWithoutLeakingOldText() {
        val t = TerminalEmulator(3, 6, maxScrollback = 2)
        repeat(20) { t.type("line$it\r\n") }
        assertEquals(2, t.scrollbackSize())
        // the newest blank row at the bottom must be clean even though it reuses an evicted row
        assertEquals("", t.rowText(2))
        assertEquals("line19", t.rowText(1))
    }

    @Test fun colonSgrSubparametersDoNotResetAttributes() {
        val t = TerminalEmulator(3, 20)
        t.type("\u001B[1m\u001B[4:3mA")
        val cell = t.rowAt(0)[0]
        assertTrue(cell.bold)          // curly underline must not wipe bold
        assertTrue(cell.underline)
        t.type("\u001B[38:2::10:20:30mB")
        assertEquals(TerminalColors.rgb(10, 20, 30), t.rowAt(0)[1].fg)
        assertTrue(t.rowAt(0)[1].bold)
    }

    @Test fun underlineColourOperandsAreNotReadAsAttributes() {
        val t = TerminalEmulator(3, 20)
        t.type("\u001B[1;58;2;1;2;3mA")   // r,g,b = 1,2,3 used to set bold again / etc.
        val c = t.rowAt(0)[0]
        assertTrue(c.bold)
        assertFalse(c.reverse)             // would be set if '7' were misread; here 1,2,3 -> bold only
    }

    @Test fun stringSequencesAreSwallowedNotPrinted() {
        val t = TerminalEmulator(3, 30)
        t.type("a\u001BPq#0;2;0;0;0\u001B\\b")
        assertEquals("ab", t.rowText(0))
        t.type("\u001B_Gi=1;payload\u001B\\c")
        assertEquals("abc", t.rowText(0))
    }

    @Test fun repeatPrecedingCharacter() {
        val t = TerminalEmulator(3, 20)
        t.type("x\u001B[4b")
        assertEquals("xxxxx", t.rowText(0))
    }

    @Test fun primaryDeviceAttributesAreAnswered() {
        val replies = mutableListOf<String>()
        val t = TerminalEmulator(3, 10, respond = { replies += it })
        t.type("\u001B[c")
        assertEquals(listOf("\u001B[?62;c"), replies)
    }

    @Test fun setScrollRegionHomesCursor() {
        val t = TerminalEmulator(6, 10)
        t.type("\u001B[4;5Hxy\u001B[2;5r")
        assertEquals(0, t.cursorRow)
        assertEquals(0, t.cursorCol)
    }

    @Test fun selectionAcrossSoftWrappedRowsHasNoNewlineOrPadding() {
        val t = TerminalEmulator(4, 5)
        t.type("abcdefgh")                 // wraps: "abcde" / "fgh"
        assertEquals("abcdefgh", t.textInRange(0, 0, 1, 4))
        t.type("\r\nxy\r\nz")
        assertEquals("abcdefgh\nxy", t.textInRange(0, 0, 2, 4))
    }

    @Test fun tailTextCanJoinSoftWrappedLines() {
        val t = TerminalEmulator(4, 10)
        t.type("server password abcdefghijklmnop\r\nnext")   // wraps across rows
        assertTrue(t.tailText(10).contains("server pas\nsword"))   // default: rows separated
        val joined = t.tailText(10, joinWrapped = true)
        assertTrue(joined, joined.contains("server password abcdefghijklmnop"))
        assertTrue(joined, joined.endsWith("next"))
    }

    /** Ink / Claude Code style redraw: erase the previous frame with ESC[2K + ESC[1A per line, then ESC[G, and print the new frame. */
    private fun inkErase(lines: Int): String = buildString {
        for (i in 0 until lines) { append("\u001B[2K"); if (i < lines - 1) append("\u001B[1A") }
        append("\u001B[G")
    }

    @Test fun inkStyleRedrawLeavesNoLeftoverLines() {
        val cols = 40
        val t = TerminalEmulator(20, cols)
        t.type("root@alpdroid:~# claude\r\n")
        val full = "─".repeat(cols)                   // a full-width border: leaves the cursor wrap-pending
        val frame1 = listOf(full, "Accessing workspace:", "", "/root", "", "  > No, exit", "    Yes, I trust this folder", "Enter to confirm")
        val frame2 = listOf(full, "Accessing workspace:", "", "/root", "", "    No, exit", "  > Yes, I trust this folder", "Enter to confirm")
        t.type(frame1.joinToString("\r\n") + "\r\n")
        t.type(inkErase(frame1.size + 1) + frame2.joinToString("\r\n") + "\r\n")
        val rows = (0 until 20).map { t.rowText(it) }.filter { it.isNotEmpty() }
        assertEquals((listOf("root@alpdroid:~# claude") + frame2).filter { it.isNotEmpty() }, rows)
    }

    @Test fun cursorNextAndPreviousLineAndHorizontalPositioning() {
        val t = TerminalEmulator(6, 20)
        t.type("abc\u001B[2Edef")        // CNL: two lines down, column 1
        assertEquals(2, t.cursorRow); assertEquals("def", t.rowText(2))
        t.type("\u001B[1Fxyz")           // CPL: one line up, column 1
        assertEquals("xyz", t.rowText(1))
        t.type("\u001B[10`Q")            // HPA: column 10
        assertEquals('Q', t.rowAt(1)[9].ch)
    }

    @Test fun privateSequencesDoNotRunTheirPlainCounterparts() {
        val t = TerminalEmulator(10, 40)
        t.type("\u001B[3;5H")         // cursor to row 3, col 5
        t.type("\u001B7")             // save it
        t.type("\u001B[8;20H")        // somewhere else
        // `CSI ? u` is the kitty-keyboard query modern TUIs (Claude Code) send at startup. It used to be handled as
        // `CSI u` (restore cursor), which jumped the cursor back to the saved position.
        t.type("\u001B[?u")
        assertEquals(7, t.cursorRow)
        assertEquals(19, t.cursorCol)
        t.type("\u001B[?1s\u001B[?5l")  // other private forms must not act as plain `s` (save) either
        t.type("\u001B8")
        assertEquals(2, t.cursorRow)
        assertEquals(4, t.cursorCol)
    }

    @Test fun kittyQueryIsNotAnswered() {
        val replies = mutableListOf<String>()
        val t = TerminalEmulator(5, 20, respond = { replies += it })
        t.type("\u001B[?u\u001B[>0q")
        assertEquals(emptyList<String>(), replies)   // answering would claim kitty-protocol support
    }
}
