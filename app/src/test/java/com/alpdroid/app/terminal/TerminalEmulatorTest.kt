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
}
