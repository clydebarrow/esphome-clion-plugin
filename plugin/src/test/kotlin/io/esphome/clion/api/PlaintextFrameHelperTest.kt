package io.esphome.clion.api

import io.esphome.clion.api.proto.ProtoWriter
import io.esphome.clion.api.transport.FrameHelper
import io.esphome.clion.api.transport.PlaintextFrameHelper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Plaintext framing: `0x00 | varint len | varint type | payload` round-trips. */
class PlaintextFrameHelperTest {

    @Test
    fun `write then read recovers type and payload`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val wire = ByteArrayOutputStream()
        PlaintextFrameHelper(ByteArrayInputStream(ByteArray(0)), wire).writeMessage(42, payload)

        // Header is the 0x00 marker, then len=5, then type=42 (all single-byte varints here).
        assertEquals(0x00, wire.toByteArray()[0].toInt())

        val read = PlaintextFrameHelper(ByteArrayInputStream(wire.toByteArray()), ByteArrayOutputStream()).readMessage()
        assertEquals(42, read.type)
        assertArrayEquals(payload, read.payload)
    }

    @Test
    fun `large payload length encodes as multi-byte varint and round-trips`() {
        val payload = ByteArray(300) { (it % 7).toByte() }
        val wire = ByteArrayOutputStream()
        PlaintextFrameHelper(ByteArrayInputStream(ByteArray(0)), wire).writeMessage(16, payload)
        val read = PlaintextFrameHelper(ByteArrayInputStream(wire.toByteArray()), ByteArrayOutputStream()).readMessage()
        assertEquals(16, read.type)
        assertArrayEquals(payload, read.payload)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an over-length frame is rejected before allocating`() {
        val bad = ByteArrayOutputStream()
        bad.write(0x00)
        bad.write(ProtoWriter.varint(FrameHelper.MAX_PAYLOAD.toLong() + 1))
        bad.write(ProtoWriter.varint(1))
        PlaintextFrameHelper(ByteArrayInputStream(bad.toByteArray()), ByteArrayOutputStream()).readMessage()
    }
}
