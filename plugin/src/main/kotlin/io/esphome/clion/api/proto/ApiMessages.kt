package io.esphome.clion.api.proto

import java.util.Locale
import kotlin.math.roundToInt

/** An entity advertised by the device (from a `ListEntities*Response`). */
data class ApiEntity(
    /** fixed32 key, the stable handle the device uses in state updates. */
    val key: Long,
    /** Component type, e.g. `sensor`, `switch`, `binary_sensor`. */
    val type: String,
    /** Friendly name (falls back to object id). */
    val name: String,
    /** Unit of measurement, when the entity has one (sensors). */
    val unit: String,
)

/** A single live state update (`*StateResponse`), already formatted for display. */
data class ApiState(val key: Long, val display: String)

/** Brief device identity from `DeviceInfoResponse`. */
data class ApiDeviceInfo(val name: String, val esphomeVersion: String, val model: String)

/**
 * ESPHome native-API message ids and the encode/decode for the subset the tool
 * window uses. Field numbers track `esphome/components/api/api.proto`.
 *
 * Entity *list* responses all share `object_id=1 / key=2 / name=3`, so one
 * decoder lists every entity type; only *state* responses need per-type
 * decoding, and unknown ones simply leave the entity's state blank.
 */
object ApiMessages {
    // Messages we send / handshake.
    const val HELLO_REQUEST = 1
    const val HELLO_RESPONSE = 2
    const val AUTH_REQUEST = 3 // legacy password auth (pre-2026.1 firmware)
    const val AUTH_RESPONSE = 4
    const val DISCONNECT_REQUEST = 5
    const val DISCONNECT_RESPONSE = 6
    const val PING_REQUEST = 7
    const val PING_RESPONSE = 8
    const val DEVICE_INFO_REQUEST = 9
    const val DEVICE_INFO_RESPONSE = 10
    const val LIST_ENTITIES_REQUEST = 11
    const val LIST_ENTITIES_DONE = 19
    const val SUBSCRIBE_STATES_REQUEST = 20

    /** `ListEntities*Response` id → component type label. */
    val ENTITY_TYPES: Map<Int, String> = mapOf(
        12 to "binary_sensor", 13 to "cover", 14 to "fan", 15 to "light", 16 to "sensor",
        17 to "switch", 18 to "text_sensor", 43 to "camera", 46 to "climate", 49 to "number",
        52 to "select", 55 to "siren", 58 to "lock", 61 to "button", 63 to "media_player",
        94 to "alarm_control_panel", 97 to "text", 100 to "date", 103 to "time", 107 to "event",
        109 to "valve", 112 to "datetime", 116 to "update", 132 to "water_heater",
    )

    private const val SENSOR_LIST = 16

    // State decoding categories, keyed by *StateResponse id.
    private const val BOOL = 0      // on/off (with missing_state at field 3)
    private const val BOOL_NO_MISSING = 1 // on/off, field 3 is not missing_state (switch)
    private const val FLOAT = 2     // numeric, optional unit appended by the table
    private const val TEXT = 3      // string state
    private const val LIGHT = 4     // on/off + brightness

    private val STATE_KINDS: Map<Int, Int> = mapOf(
        21 to BOOL,        // binary_sensor
        26 to BOOL_NO_MISSING, // switch
        24 to LIGHT,       // light
        25 to FLOAT,       // sensor
        50 to FLOAT,       // number
        27 to TEXT,        // text_sensor
        53 to TEXT,        // select
        98 to TEXT,        // text
    )

    /** Whether [type] is a state message we decode (others leave the cell blank). */
    fun isStateResponse(type: Int): Boolean = STATE_KINDS.containsKey(type)

    /** Whether [type] is an entity-list response we list. */
    fun isEntityList(type: Int): Boolean = ENTITY_TYPES.containsKey(type)

    // ---- requests ----

    fun helloRequest(clientInfo: String): ByteArray =
        ProtoWriter().string(1, clientInfo).uint32(2, 1).uint32(3, 12).toByteArray()

    fun authRequest(password: String): ByteArray = ProtoWriter().string(1, password).toByteArray()

    val EMPTY: ByteArray = ByteArray(0)

    // ---- decoding ----

    fun decodeEntity(listId: Int, payload: ByteArray): ApiEntity {
        val r = ProtoReader(payload)
        var objectId = ""
        var key = 0L
        var name = ""
        var unit = ""
        while (r.hasMore()) {
            val tag = r.readTag()
            val field = r.fieldOf(tag)
            val wire = r.wireOf(tag)
            when {
                field == 1 && wire == 2 -> objectId = r.readString()
                field == 2 && wire == 5 -> key = r.readFixed32()
                field == 3 && wire == 2 -> name = r.readString()
                field == 6 && wire == 2 && listId == SENSOR_LIST -> unit = r.readString()
                else -> r.skip(wire)
            }
        }
        return ApiEntity(key, ENTITY_TYPES[listId] ?: "unknown", name.ifEmpty { objectId }, unit)
    }

    /** Decode a state message into a display string, or null for types we don't render. */
    fun decodeState(stateId: Int, payload: ByteArray): ApiState? {
        val kind = STATE_KINDS[stateId] ?: return null
        val r = ProtoReader(payload)
        var key = 0L
        var bool = false
        var f = 0f
        var s = ""
        var missing = false
        var brightness = 0f
        while (r.hasMore()) {
            val tag = r.readTag()
            val field = r.fieldOf(tag)
            val wire = r.wireOf(tag)
            when {
                field == 1 && wire == 5 -> key = r.readFixed32()
                field == 2 && wire == 0 -> bool = r.readBool()
                field == 2 && wire == 5 -> f = r.readFloat()
                field == 2 && wire == 2 -> s = r.readString()
                field == 3 && wire == 0 && kind != BOOL_NO_MISSING -> missing = r.readBool()
                field == 3 && wire == 5 -> brightness = r.readFloat()
                else -> r.skip(wire)
            }
        }
        val display = when (kind) {
            BOOL, BOOL_NO_MISSING -> if (missing) DASH else if (bool) "on" else "off"
            LIGHT -> if (!bool) "off" else if (brightness > 0f) "on ${(brightness * 100).roundToInt()}%" else "on"
            FLOAT -> if (missing) DASH else formatFloat(f)
            TEXT -> if (missing) DASH else s
            else -> return null
        }
        return ApiState(key, display)
    }

    fun decodeHelloResponse(payload: ByteArray): String {
        val r = ProtoReader(payload)
        var name = ""
        var server = ""
        while (r.hasMore()) {
            val tag = r.readTag()
            val field = r.fieldOf(tag)
            val wire = r.wireOf(tag)
            when {
                field == 3 && wire == 2 -> server = r.readString()
                field == 4 && wire == 2 -> name = r.readString()
                else -> r.skip(wire)
            }
        }
        return name.ifEmpty { server }
    }

    fun decodeAuthInvalid(payload: ByteArray): Boolean {
        val r = ProtoReader(payload)
        while (r.hasMore()) {
            val tag = r.readTag()
            if (r.fieldOf(tag) == 1 && r.wireOf(tag) == 0) return r.readBool()
            r.skip(r.wireOf(tag))
        }
        return false
    }

    fun decodeDeviceInfo(payload: ByteArray): ApiDeviceInfo {
        val r = ProtoReader(payload)
        var name = ""
        var version = ""
        var model = ""
        while (r.hasMore()) {
            val tag = r.readTag()
            val field = r.fieldOf(tag)
            val wire = r.wireOf(tag)
            when {
                field == 2 && wire == 2 -> name = r.readString()
                field == 4 && wire == 2 -> version = r.readString()
                field == 6 && wire == 2 -> model = r.readString()
                else -> r.skip(wire)
            }
        }
        return ApiDeviceInfo(name, version, model)
    }

    private const val DASH = "—" // em dash for "no state yet"

    private fun formatFloat(f: Float): String {
        if (f.isNaN()) return "NaN"
        if (f == f.toLong().toFloat()) return f.toLong().toString()
        return "%.4f".format(Locale.ROOT, f).trimEnd('0').trimEnd('.')
    }
}
