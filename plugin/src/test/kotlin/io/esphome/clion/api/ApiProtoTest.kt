package io.esphome.clion.api

import io.esphome.clion.api.proto.ApiMessages
import io.esphome.clion.api.proto.ProtoReader
import io.esphome.clion.api.proto.ProtoWriter
import org.junit.Assert.assertEquals
import org.junit.Test

/** The hand-rolled protobuf codec and ESPHome message decoding from raw bytes. */
class ApiProtoTest {

    // --- tiny field encoders to build fixtures the way a device would ---
    private fun tag(field: Int, wire: Int) = ProtoWriter.varint(((field shl 3) or wire).toLong())
    private fun str(field: Int, s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return tag(field, 2) + ProtoWriter.varint(b.size.toLong()) + b
    }
    private fun fixed32(field: Int, v: Int): ByteArray =
        tag(field, 5) + byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte())
    private fun floatf(field: Int, f: Float) = fixed32(field, f.toRawBits())
    private fun boolf(field: Int, v: Boolean) = tag(field, 0) + ProtoWriter.varint(if (v) 1 else 0)
    private fun varintf(field: Int, v: Long) = tag(field, 0) + ProtoWriter.varint(v)

    @Test
    fun `writer and reader round-trip strings and varints`() {
        val bytes = ProtoWriter().string(1, "Home Assistant").uint32(2, 1).uint32(3, 300).toByteArray()
        val r = ProtoReader(bytes)
        assertEquals(1 shl 3 or 2, r.readTag()); assertEquals("Home Assistant", r.readString())
        assertEquals(2 shl 3 or 0, r.readTag()); assertEquals(1L, r.readVarint())
        assertEquals(3 shl 3 or 0, r.readTag()); assertEquals(300L, r.readVarint())
        assertEquals(false, r.hasMore())
    }

    @Test
    fun `reader decodes fixed32 little-endian and float`() {
        val r = ProtoReader(fixed32(1, 0x12345678) + floatf(2, 23.5f))
        r.readTag(); assertEquals(0x12345678L, r.readFixed32())
        r.readTag(); assertEquals(23.5f, r.readFloat(), 0f)
    }

    @Test
    fun `sensor entity decodes name, key and unit`() {
        val payload = str(1, "living_temp") + fixed32(2, 0x0A0B0C0D) + str(3, "Living Temp") + str(6, "°C")
        val e = ApiMessages.decodeEntity(16, payload)
        assertEquals(0x0A0B0C0DL, e.key)
        assertEquals("sensor", e.type)
        assertEquals("Living Temp", e.name)
        assertEquals("°C", e.unit)
    }

    @Test
    fun `entity falls back to object id when name is empty`() {
        val e = ApiMessages.decodeEntity(17, str(1, "relay") + fixed32(2, 7))
        assertEquals("switch", e.type)
        assertEquals("relay", e.name)
    }

    @Test
    fun `sensor state formats the float and honors missing_state`() {
        assertEquals("23.5", ApiMessages.decodeState(25, fixed32(1, 5) + floatf(2, 23.5f))!!.display)
        assertEquals("18", ApiMessages.decodeState(25, fixed32(1, 5) + floatf(2, 18.0f))!!.display)
        assertEquals("—", ApiMessages.decodeState(25, fixed32(1, 5) + floatf(2, 0f) + boolf(3, true))!!.display)
    }

    @Test
    fun `binary sensor maps to on or off`() {
        assertEquals("on", ApiMessages.decodeState(21, fixed32(1, 1) + boolf(2, true))!!.display)
        assertEquals("off", ApiMessages.decodeState(21, fixed32(1, 1) + boolf(2, false))!!.display)
    }

    @Test
    fun `switch device_id at field 3 is not mistaken for missing_state`() {
        // SwitchStateResponse: key=1, state=2, device_id=3 (NOT missing_state).
        val s = ApiMessages.decodeState(26, fixed32(1, 1) + boolf(2, true) + varintf(3, 4))
        assertEquals("on", s!!.display)
    }

    @Test
    fun `light shows brightness when on`() {
        assertEquals("on 50%", ApiMessages.decodeState(24, fixed32(1, 1) + boolf(2, true) + floatf(3, 0.5f))!!.display)
        assertEquals("off", ApiMessages.decodeState(24, fixed32(1, 1) + boolf(2, false))!!.display)
    }

    @Test
    fun `unknown state response is not decoded`() {
        assertEquals(null, ApiMessages.decodeState(22 /* cover */, fixed32(1, 1)))
    }

    @Test
    fun `hello response yields the device name`() {
        assertEquals("living-room", ApiMessages.decodeHelloResponse(str(3, "esphome 2026") + str(4, "living-room")))
    }
}
