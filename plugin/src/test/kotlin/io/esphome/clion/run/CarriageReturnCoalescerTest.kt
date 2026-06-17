package io.esphome.clion.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The carry that keeps esptool's in-place progress visible: no emitted chunk may
 * end in a `\r` (the console would render that as a blank line), and a held `\r`
 * is prepended to the stream's next chunk.
 */
class CarriageReturnCoalescerTest {

    private val coalescer = CarriageReturnCoalescer()
    private val stdout = Any()
    private val stderr = Any()

    @Test
    fun `a trailing carriage return is held back and prepended to the next chunk`() {
        // Two esptool-style frames where the read ends on the next frame's leading \r.
        val first = coalescer.feed("\rWriting 50% \r", stdout)
        assertEquals("\rWriting 50% ", first)
        assertFalse("emitted chunk must not end in CR", first.endsWith('\r'))

        val second = coalescer.feed("\rWriting 60% \r", stdout)
        // The held CR is re-prepended, so the overwrite still happens downstream.
        assertEquals("\r\rWriting 60% ", second)
        assertFalse(second.endsWith('\r'))
    }

    @Test
    fun `output without a trailing carriage return passes through unchanged`() {
        assertEquals("compiling...\n", coalescer.feed("compiling...\n", stdout))
        assertEquals("\rWriting 30% ", coalescer.feed("\rWriting 30% ", stdout))
    }

    @Test
    fun `a CRLF split across two reads is reassembled, not blanked`() {
        // "log line\r" + "\nmore" must recombine to a normal CRLF line break.
        assertEquals("log line", coalescer.feed("log line\r", stdout))
        assertEquals("\r\nmore", coalescer.feed("\nmore", stdout))
    }

    @Test
    fun `a lone carriage return emits nothing but is still carried`() {
        assertEquals("", coalescer.feed("\r", stdout))
        assertEquals("\rnext", coalescer.feed("next", stdout))
    }

    @Test
    fun `a carried CR followed by a CR-only chunk never emits a chunk ending in CR`() {
        assertEquals("a", coalescer.feed("a\r", stdout)) // stdout holds a CR
        // carried "\r" + "\r" -> "\r\r" must collapse to "" (not "\r"), still carrying.
        val emitted = coalescer.feed("\r", stdout)
        assertEquals("", emitted)
        assertFalse("a run of trailing CRs must not survive", emitted.endsWith('\r'))
        assertEquals("\rb", coalescer.feed("b", stdout)) // the held CR re-emerges
    }

    @Test
    fun `a carried CR followed by a chunk ending in CRs strips them all`() {
        assertEquals("x", coalescer.feed("x\r", stdout))
        val emitted = coalescer.feed("y\r\r", stdout) // -> "\ry\r\r"
        assertEquals("\ry", emitted)
        assertFalse(emitted.endsWith('\r'))
    }

    @Test
    fun `stdout and stderr carries are independent`() {
        assertEquals("a", coalescer.feed("a\r", stdout)) // stdout holds a CR
        assertEquals("b", coalescer.feed("b", stderr))   // stderr unaffected
        assertEquals("\rc", coalescer.feed("c", stdout)) // stdout's CR re-emerges
    }
}
