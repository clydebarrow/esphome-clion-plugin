package io.esphome.clion.api

import io.esphome.clion.api.proto.ApiMessages
import io.esphome.clion.api.proto.ProtoReader
import io.esphome.clion.api.proto.ProtoWriter
import org.junit.Assert.assertArrayEquals
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
        assertEquals(null, ApiMessages.decodeState(47 /* climate — not rendered */, fixed32(1, 1)))
    }

    @Test
    fun `cover state reflects position and operation`() {
        // CoverStateResponse (22): key=1, position=3 (float), current_operation=5.
        // Position percentage is always shown, tagged at the endpoints.
        assertEquals("100% (open)", ApiMessages.decodeState(22, fixed32(1, 1) + floatf(3, 1.0f))!!.display)
        assertEquals("0% (closed)", ApiMessages.decodeState(22, fixed32(1, 1) + floatf(3, 0.0f))!!.display)
        assertEquals("50% open", ApiMessages.decodeState(22, fixed32(1, 1) + floatf(3, 0.5f))!!.display)
        // current_operation overrides the resting position: 1=opening, 2=closing.
        assertEquals("opening 40%", ApiMessages.decodeState(22, fixed32(1, 1) + floatf(3, 0.4f) + varintf(5, 1))!!.display)
        assertEquals("closing 40%", ApiMessages.decodeState(22, fixed32(1, 1) + floatf(3, 0.4f) + varintf(5, 2))!!.display)
    }

    @Test
    fun `valve state reflects position and operation`() {
        // ValveStateResponse (110): key=1, position=2 (float), current_operation=3.
        assertEquals("100% (open)", ApiMessages.decodeState(110, fixed32(1, 1) + floatf(2, 1.0f))!!.display)
        assertEquals("0% (closed)", ApiMessages.decodeState(110, fixed32(1, 1) + floatf(2, 0.0f))!!.display)
        assertEquals("30% open", ApiMessages.decodeState(110, fixed32(1, 1) + floatf(2, 0.3f))!!.display)
        assertEquals("opening 30%", ApiMessages.decodeState(110, fixed32(1, 1) + floatf(2, 0.3f) + varintf(3, 1))!!.display)
        assertEquals("closing 30%", ApiMessages.decodeState(110, fixed32(1, 1) + floatf(2, 0.3f) + varintf(3, 2))!!.display)
    }

    @Test
    fun `lock state maps the enum and carries locked as the active flag`() {
        // LockStateResponse (59): key=1, state=2 (LockState enum).
        assertEquals("locked", ApiMessages.decodeState(59, fixed32(1, 1) + varintf(2, 1))!!.display)
        assertEquals("unlocked", ApiMessages.decodeState(59, fixed32(1, 1) + varintf(2, 2))!!.display)
        assertEquals("jammed", ApiMessages.decodeState(59, fixed32(1, 1) + varintf(2, 3))!!.display)
        assertEquals(true, ApiMessages.decodeState(59, fixed32(1, 1) + varintf(2, 1))!!.active)
        assertEquals(false, ApiMessages.decodeState(59, fixed32(1, 1) + varintf(2, 2))!!.active)
        assertEquals(null, ApiMessages.decodeState(59, fixed32(1, 1) + varintf(2, 3))!!.active) // jammed
    }

    @Test
    fun `number entity decodes its range, step and unit`() {
        // ListEntitiesNumberResponse (49): key=2, name=3, min=6, max=7, step=8, unit=11.
        val e = ApiMessages.decodeEntity(
            49,
            str(1, "brightness") + fixed32(2, 3) + str(3, "Brightness") +
                floatf(6, 0f) + floatf(7, 255f) + floatf(8, 5f) + str(11, "lx"),
        )
        assertEquals("number", e.type)
        assertEquals(0f, e.numberMin, 0f)
        assertEquals(255f, e.numberMax, 0f)
        assertEquals(5f, e.numberStep, 0f)
        assertEquals("lx", e.unit)
    }

    @Test
    fun `lock command locks with command=1 and unlocks by omitting it`() {
        val lock = ApiMessages.toggleCommand("lock", 6L, true)!!
        assertEquals(ApiMessages.LOCK_COMMAND, lock.first)
        assertArrayEquals(fixed32(1, 6) + varintf(2, 1), lock.second)
        // UNLOCK is the proto3 default (0) — omitted, key only.
        assertArrayEquals(fixed32(1, 6), ApiMessages.toggleCommand("lock", 6L, false)!!.second)
    }

    @Test
    fun `valve command encodes open, close, and stop`() {
        // has_position(2)=true + position(3)=1.0/0.0; stop(4)=true.
        val open = ApiMessages.valveCommand(7L, ApiMessages.CoverAction.OPEN)
        assertEquals(ApiMessages.VALVE_COMMAND, open.first)
        assertArrayEquals(fixed32(1, 7) + boolf(2, true) + floatf(3, 1.0f), open.second)
        assertArrayEquals(fixed32(1, 7) + boolf(2, true) + floatf(3, 0.0f), ApiMessages.valveCommand(7L, ApiMessages.CoverAction.CLOSE).second)
        assertArrayEquals(fixed32(1, 7) + boolf(4, true), ApiMessages.valveCommand(7L, ApiMessages.CoverAction.STOP).second)
    }

    @Test
    fun `number command encodes key and value`() {
        val (type, payload) = ApiMessages.numberCommand(3L, 42.5f)
        assertEquals(ApiMessages.NUMBER_COMMAND, type)
        assertArrayEquals(fixed32(1, 3) + floatf(2, 42.5f), payload)
    }

    @Test
    fun `text command encodes key and value`() {
        val (type, payload) = ApiMessages.textCommand(3L, "hello")
        assertEquals(ApiMessages.TEXT_COMMAND, type)
        assertArrayEquals(fixed32(1, 3) + str(2, "hello"), payload)
    }

    @Test
    fun `event state surfaces the event_type`() {
        // EventResponse (108): key=1, event_type=2.
        val s = ApiMessages.decodeState(108, fixed32(1, 3) + str(2, "single_click"))!!
        assertEquals("single_click", s.display)
        assertEquals(3L, s.key)
        // A type-less fire still reads as a trigger, not blank.
        assertEquals("triggered", ApiMessages.decodeState(108, fixed32(1, 3))!!.display)
    }

    // --- commands ---

    @Test
    fun `switch command encodes key and state, omitting a false state`() {
        val on = ApiMessages.toggleCommand("switch", 0x0A0B0C0DL, true)!!
        assertEquals(ApiMessages.SWITCH_COMMAND, on.first)
        assertArrayEquals(fixed32(1, 0x0A0B0C0D) + boolf(2, true), on.second)
        val off = ApiMessages.toggleCommand("switch", 0x0A0B0C0DL, false)!!
        assertArrayEquals(fixed32(1, 0x0A0B0C0D), off.second) // false omitted
    }

    @Test
    fun `light command sets has_state plus state`() {
        val on = ApiMessages.toggleCommand("light", 7L, true)!!
        assertEquals(ApiMessages.LIGHT_COMMAND, on.first)
        assertArrayEquals(fixed32(1, 7) + boolf(2, true) + boolf(3, true), on.second)
        // off: has_state true, state omitted (false)
        assertArrayEquals(fixed32(1, 7) + boolf(2, true), ApiMessages.toggleCommand("light", 7L, false)!!.second)
    }

    @Test
    fun `button command is just the key`() {
        val (type, payload) = ApiMessages.buttonCommand(9L)
        assertEquals(ApiMessages.BUTTON_COMMAND, type)
        assertArrayEquals(fixed32(1, 9), payload)
    }

    @Test
    fun `non-controllable type has no toggle command`() {
        assertEquals(null, ApiMessages.toggleCommand("sensor", 1L, true))
    }

    @Test
    fun `cover command encodes open, close, and stop`() {
        // Open/Close: has_position(4)=true + position(5)=1.0/0.0.
        val open = ApiMessages.coverCommand(4L, ApiMessages.CoverAction.OPEN)
        assertEquals(ApiMessages.COVER_COMMAND, open.first)
        assertArrayEquals(fixed32(1, 4) + boolf(4, true) + floatf(5, 1.0f), open.second)
        val close = ApiMessages.coverCommand(4L, ApiMessages.CoverAction.CLOSE)
        assertArrayEquals(fixed32(1, 4) + boolf(4, true) + floatf(5, 0.0f), close.second)
        // Stop: stop(8)=true only.
        val stop = ApiMessages.coverCommand(4L, ApiMessages.CoverAction.STOP)
        assertArrayEquals(fixed32(1, 4) + boolf(8, true), stop.second)
    }

    // --- state active flag + device class ---

    @Test
    fun `state carries the on-off flag for toggleables`() {
        assertEquals(true, ApiMessages.decodeState(26, fixed32(1, 1) + boolf(2, true))!!.active) // switch
        assertEquals(false, ApiMessages.decodeState(21, fixed32(1, 1) + boolf(2, false))!!.active) // binary
        assertEquals(null, ApiMessages.decodeState(21, fixed32(1, 1) + boolf(3, true))!!.active) // missing
        assertEquals(null, ApiMessages.decodeState(25, fixed32(1, 1) + floatf(2, 1f))!!.active) // sensor
    }

    @Test
    fun `sensor entity decodes accuracy_decimals and state carries the raw number`() {
        val e = ApiMessages.decodeEntity(16, str(1, "t") + fixed32(2, 1) + str(3, "Temp") + str(6, "°C") + varintf(7, 2))
        assertEquals(2, e.accuracyDecimals)
        val s = ApiMessages.decodeState(25, fixed32(1, 1) + floatf(2, 23.456f))!!
        assertEquals(23.456f, s.number!!, 1e-4f)
    }

    @Test
    fun `fan state decodes on-off and ignores oscillating at field 3`() {
        // FanStateResponse: key, state(2 bool), oscillating(3 bool) — not missing_state.
        val s = ApiMessages.decodeState(23, fixed32(1, 1) + boolf(2, true) + boolf(3, true))!!
        assertEquals("on", s.display)
        assertEquals(true, s.active)
    }

    @Test
    fun `entity decodes device class for icon choice`() {
        val sensor = ApiMessages.decodeEntity(16, str(1, "t") + fixed32(2, 1) + str(3, "Temp") + str(9, "temperature"))
        assertEquals("temperature", sensor.deviceClass)
        val binary = ApiMessages.decodeEntity(12, str(1, "m") + fixed32(2, 2) + str(3, "Motion") + str(5, "motion"))
        assertEquals("motion", binary.deviceClass)
    }

    @Test
    fun `select entity decodes its options`() {
        // ListEntitiesSelectResponse (52): object_id=1, key=2, name=3, repeated options=6.
        val e = ApiMessages.decodeEntity(
            52,
            str(1, "mode") + fixed32(2, 5) + str(3, "Mode") + str(6, "Off") + str(6, "Low") + str(6, "High"),
        )
        assertEquals("select", e.type)
        assertEquals(listOf("Off", "Low", "High"), e.options)
    }

    @Test
    fun `select command encodes key and chosen option`() {
        val (type, payload) = ApiMessages.selectCommand(5L, "Low")
        assertEquals(ApiMessages.SELECT_COMMAND, type)
        assertArrayEquals(fixed32(1, 5) + str(2, "Low"), payload)
    }

    @Test
    fun `hello response yields the device name`() {
        assertEquals("living-room", ApiMessages.decodeHelloResponse(str(3, "esphome 2026") + str(4, "living-room")))
    }
}
