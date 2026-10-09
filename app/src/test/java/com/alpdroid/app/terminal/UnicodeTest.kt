package com.alpdroid.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnicodeTest {
    private fun TerminalEmulator.type(s: String) = feed(s.toByteArray(Charsets.UTF_8), s.toByteArray(Charsets.UTF_8).size)
    private fun TerminalEmulator.bytes(vararg b: Int) = feed(ByteArray(b.size) { b[it].toByte() }, b.size)
    private fun TerminalEmulator.row(r: Int) = buildString { rowAt(r).forEach { it.appendTo(this) } }.trimEnd()

    @Test fun widthTablesAreSortedAndDoNotOverlap() {
        for (table in listOf(CharWidth.ZERO, CharWidth.WIDE)) {
            var last = -1
            for (i in table.indices step 2) {
                assertTrue("range ${table[i].toString(16)}..${table[i + 1].toString(16)} is inverted", table[i] <= table[i + 1])
                assertTrue("range ${table[i].toString(16)} overlaps the previous one", table[i] > last)
                last = table[i + 1]
            }
        }
    }

    @Test fun knownWidths() {
        assertEquals(1, CharWidth.of('a'.code))
        assertEquals(1, CharWidth.of('é'.code))
        assertEquals(2, CharWidth.of('世'.code))
        assertEquals(2, CharWidth.of(0x1F680))   // 🚀
        assertEquals(2, CharWidth.of(0x26A1))    // ⚡ (emoji presentation by default)
        assertEquals(2, CharWidth.of(0x2728))    // ✨
        assertEquals(1, CharWidth.of(0x2713))    // ✓ stays one column
        assertEquals(1, CharWidth.of(0x2764))    // ❤ alone is text presentation
        assertEquals(1, CharWidth.of(0x2500))    // ─ box drawing
        assertEquals(0, CharWidth.of(0x0301))    // combining acute
        assertEquals(0, CharWidth.of(0x200D))    // zero-width joiner
        assertEquals(0, CharWidth.of(0xFE0F))    // variation selector 16
    }

    @Test fun anEmojiTakesTwoCellsAndKeepsItsWholeText() {
        val t = TerminalEmulator(3, 10)
        t.type("a🚀b") // a🚀b
        assertEquals("🚀", t.rowAt(0)[1].ext)
        assertTrue(t.rowAt(0)[2].cont)
        assertEquals('b', t.rowAt(0)[3].ch)
        assertEquals(4, t.cursorCol)
        assertEquals("a🚀b", t.row(0))
    }

    @Test fun wideCjkCharactersTakeTwoCells() {
        val t = TerminalEmulator(3, 10)
        t.type("世界x")
        assertEquals('世', t.rowAt(0)[0].ch)
        assertTrue(t.rowAt(0)[1].cont)
        assertEquals('界', t.rowAt(0)[2].ch)
        assertEquals('x', t.rowAt(0)[4].ch)
        assertEquals("世界x", t.row(0))
    }

    @Test fun aWideCharacterThatDoesNotFitMovesToTheNextLine() {
        val t = TerminalEmulator(3, 5)
        t.type("abcd世")
        assertEquals("abcd", t.row(0))
        assertEquals("世", t.row(1))
        assertTrue(t.rowAt(0)[4].wrapped)
        assertEquals(2, t.cursorCol)
        assertEquals(1, t.cursorRow)
    }

    @Test fun combiningMarksJoinTheCharacterBeforeThem() {
        val t = TerminalEmulator(3, 10)
        t.type("éx")
        assertEquals("é", t.rowAt(0)[0].ext)
        assertEquals('x', t.rowAt(0)[1].ch)
        assertEquals(2, t.cursorCol)
    }

    @Test fun aJoinedEmojiFamilyIsOneWideCell() {
        val t = TerminalEmulator(3, 10)
        val family = "👨‍👩‍👧" // 👨‍👩‍👧
        t.type(family + "z")
        assertEquals(family, t.rowAt(0)[0].ext)
        assertTrue(t.rowAt(0)[1].cont)
        assertEquals('z', t.rowAt(0)[2].ch)
        assertEquals(3, t.cursorCol)
    }

    @Test fun aSkinToneModifierJoinsItsEmoji() {
        val t = TerminalEmulator(3, 10)
        t.type("👍🏽x") // 👍🏽x
        assertEquals("👍🏽", t.rowAt(0)[0].ext)
        assertEquals('x', t.rowAt(0)[2].ch)
    }

    @Test fun writingOverTheRightHalfBlanksTheWholeCharacter() {
        val t = TerminalEmulator(3, 10)
        t.type("世")
        t.type("\u001B[1;2Hx") // cursor onto the right half, write x
        assertEquals(' ', t.rowAt(0)[0].ch)
        assertNull(t.rowAt(0)[0].ext)
        assertEquals('x', t.rowAt(0)[1].ch)
        assertFalse(t.rowAt(0)[1].cont)
    }

    @Test fun writingOverTheLeftHalfFreesTheRightHalf() {
        val t = TerminalEmulator(3, 10)
        t.type("世")
        t.type("\r" + "x")
        assertEquals('x', t.rowAt(0)[0].ch)
        assertFalse(t.rowAt(0)[1].cont)
        assertEquals(' ', t.rowAt(0)[1].ch)
    }

    @Test fun erasingHalfAWideCharacterLeavesNoOrphan() {
        val t = TerminalEmulator(3, 10)
        t.type("a世b")
        t.type("\u001B[1;3H\u001B[1X") // erase 1 char at col 3 = the right half
        assertFalse(t.rowAt(0)[2].cont)
        assertEquals(' ', t.rowAt(0)[1].ch)
        assertNull(t.rowAt(0)[1].ext)
    }

    @Test fun selectedTextHasTheEmojiOnce() {
        val t = TerminalEmulator(3, 10)
        t.type("a🚀b")
        assertEquals("a🚀b", t.textInRange(0, 0, 0, 3))
    }

    @Test fun changingOnlyTheEmojiChangesTheContentHash() {
        val a = TerminalEmulator(3, 10); a.type("🚀") // 🚀
        val b = TerminalEmulator(3, 10); b.type("🚁") // another emoji, same high surrogate
        assertNotEquals(a.contentHash(), b.contentHash())
    }

    @Test fun invalidUtf8BecomesReplacementCharacters() {
        val t = TerminalEmulator(3, 20)
        t.bytes(0xC0, 0x80)               // overlong NUL
        t.bytes(0xED, 0xA0, 0x80)         // a UTF-16 surrogate encoded in UTF-8
        t.bytes(0xF4, 0x90, 0x80, 0x80)   // above U+10FFFF
        t.bytes(0xE2, 0x82, 'x'.code)     // truncated euro sign, then x
        val text = t.row(0)
        assertEquals("����x", text)
    }

    @Test fun aSequenceSplitAcrossReadsStillDecodes() {
        val t = TerminalEmulator(3, 10)
        val bytes = "🚀".toByteArray(Charsets.UTF_8)
        t.feed(bytes.copyOfRange(0, 2), 2)
        t.feed(bytes.copyOfRange(2, 4), 2)
        assertEquals("🚀", t.rowAt(0)[0].ext)
    }

    @Test fun scrollDownKeepsContentAndGivesIndependentBlankRows() {
        val t = TerminalEmulator(4, 5)
        t.type("1\r\n2\r\n3\r\n4")
        t.type("\u001B[1T") // scroll down one line: blank row on top, "4" falls off
        assertEquals("", t.row(0))
        assertEquals("1", t.row(1))
        assertEquals("3", t.row(3))
        t.type("\u001B[1;1Hx")
        assertEquals("x", t.row(0))
        assertEquals("1", t.row(1)) // writing the new top row must not alias another row
    }

    @Test fun csiParametersParseLikeBefore() {
        val t = TerminalEmulator(6, 20)
        t.type("\u001B[3;5HX")            // plain numbers
        assertEquals('X', t.rowAt(2)[4].ch)
        t.type("\u001B[;3Hy")             // an empty first field means 0 (-> the default, 1)
        assertEquals('y', t.rowAt(0)[2].ch)
        t.type("\u001B[2;99999999999Hz")  // too large for an Int: treated as 0 -> default column
        assertEquals('z', t.rowAt(1)[0].ch)
        t.type("\u001B[38;2;10;20;30mq")  // truecolor SGR
        assertNotEquals(TerminalColors.DEFAULT_FG, t.rowAt(1)[1].fg)
    }
}
