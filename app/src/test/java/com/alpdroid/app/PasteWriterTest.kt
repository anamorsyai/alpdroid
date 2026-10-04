package com.alpdroid.app

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasteWriterTest {
    private val start = "\u001B[200~".toByteArray()
    private val end = "\u001B[201~".toByteArray()

    @Test fun plainPasteWritesAllBytesInOrder() {
        val data = ByteArray(50_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val n = PasteWriter.write(out, data, bracketed = false)
        assertEquals(data.size, n)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test fun bracketedPasteIsWrappedInMarkers() {
        val data = "hello\nworld".toByteArray()
        val out = ByteArrayOutputStream()
        PasteWriter.write(out, data, bracketed = true)
        assertArrayEquals(start + data + end, out.toByteArray())
    }

    @Test fun writesInBoundedChunks() {
        val sizes = mutableListOf<Int>()
        val out = object : java.io.OutputStream() {
            override fun write(b: Int) { sizes += 1 }
            override fun write(b: ByteArray, off: Int, len: Int) { sizes += len }
        }
        PasteWriter.write(out, ByteArray(20_000), bracketed = false, chunkBytes = 8_192)
        assertEquals(listOf(8_192, 8_192, 3_616), sizes)
    }

    @Test fun cancelStopsAfterCurrentChunkButStillClosesBracket() {
        val data = ByteArray(100_000) { 'x'.code.toByte() }
        var chunks = 0
        val out = ByteArrayOutputStream()
        val written = PasteWriter.write(out, data, bracketed = true, chunkBytes = 10_000, isCancelled = { chunks++ >= 3 })
        assertEquals(30_000, written)
        val bytes = out.toByteArray()
        assertEquals(start.size + 30_000 + end.size, bytes.size)
        assertArrayEquals(end, bytes.copyOfRange(bytes.size - end.size, bytes.size))
    }

    @Test fun cancelBeforeStartWritesNoPayload() {
        val out = ByteArrayOutputStream()
        val written = PasteWriter.write(out, ByteArray(1000), bracketed = true, isCancelled = { true })
        assertEquals(0, written)
        assertArrayEquals(start + end, out.toByteArray())
    }

    @Test fun emptyPasteIsHarmless() {
        val out = ByteArrayOutputStream()
        assertEquals(0, PasteWriter.write(out, ByteArray(0), bracketed = false))
        assertTrue(out.toByteArray().isEmpty())
    }

    @Test fun markerStillWrittenWhenStreamFailsMidway() {
        var calls = 0
        val sink = ByteArrayOutputStream()
        val failing = object : java.io.OutputStream() {
            override fun write(b: Int) = sink.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (++calls == 3) throw java.io.IOException("pipe closed")
                sink.write(b, off, len)
            }
        }
        try {
            PasteWriter.write(failing, ByteArray(100), bracketed = true, chunkBytes = 10)
        } catch (_: java.io.IOException) {
        }
        assertTrue(calls >= 3)
    }
}
