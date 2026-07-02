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
    /** Home-Assistant device class (e.g. `temperature`, `motion`), for icon choice. */
    val deviceClass: String = "",
    /** Sensor decimal places to show; -1 when unknown/not a sensor. */
    val accuracyDecimals: Int = -1,
    /** The choices a `select` offers; empty for other types. */
    val options: List<String> = emptyList(),
    /** A `number`'s range and step (all 0 when not a number / unbounded). */
    val numberMin: Float = 0f,
    val numberMax: Float = 0f,
    val numberStep: Float = 0f,
)

/** A single live state update (`*StateResponse`), already formatted for display. */
data class ApiState(
    val key: Long,
    val display: String,
    /** On/off for toggleable entities (switch/light/fan/binary), else null. */
    val active: Boolean? = null,
    /** Raw numeric value for sensor/number states, so the row can apply accuracy. */
    val number: Float? = null,
)

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
    private const val BINARY_SENSOR_LIST = 12
    private const val SELECT_LIST = 52
    private const val NUMBER_LIST = 49

    // Command messages (UI → device).
    const val LIGHT_COMMAND = 32
    const val FAN_COMMAND = 31
    const val SWITCH_COMMAND = 33
    const val COVER_COMMAND = 30
    const val BUTTON_COMMAND = 62
    const val SELECT_COMMAND = 54
    const val LOCK_COMMAND = 60
    const val VALVE_COMMAND = 111
    const val NUMBER_COMMAND = 51
    const val TEXT_COMMAND = 99

    // State decoding categories, keyed by *StateResponse id.
    private const val BOOL = 0      // on/off (with missing_state at field 3)
    private const val BOOL_NO_MISSING = 1 // on/off, field 3 is not missing_state (switch)
    private const val FLOAT = 2     // numeric, optional unit appended by the table
    private const val TEXT = 3      // string state
    private const val LIGHT = 4     // on/off + brightness
    private const val EVENT = 5     // momentary trigger carrying an event_type
    private const val COVER = 6     // position (field 3 float) + current_operation (field 5)
    private const val LOCK = 7      // LockState enum (field 2 varint)
    private const val VALVE = 8     // position (field 2 float) + current_operation (field 3)

    private val STATE_KINDS: Map<Int, Int> = mapOf(
        21 to BOOL,            // binary_sensor
        26 to BOOL_NO_MISSING, // switch
        23 to BOOL_NO_MISSING, // fan (field 3 is `oscillating`, not missing_state)
        24 to LIGHT,           // light
        22 to COVER,           // cover
        25 to FLOAT,           // sensor
        50 to FLOAT,           // number
        27 to TEXT,            // text_sensor
        53 to TEXT,            // select
        98 to TEXT,            // text
        59 to LOCK,            // lock
        110 to VALVE,          // valve
        108 to EVENT,          // event (event_type at field 2; fires, no resting state)
    )

    /** Whether [type] is a state message we decode (others leave the cell blank). */
    fun isStateResponse(type: Int): Boolean = STATE_KINDS.containsKey(type)

    /** Whether [type] is an entity-list response we list. */
    fun isEntityList(type: Int): Boolean = ENTITY_TYPES.containsKey(type)

    /** Entity component types the tool window drives with an on-off toggle. */
    fun isControllable(type: String): Boolean = type in CONTROLLABLE
    private val CONTROLLABLE = setOf("switch", "light", "fan", "lock")

    // ---- requests ----

    fun helloRequest(clientInfo: String): ByteArray =
        ProtoWriter().string(1, clientInfo).uint32(2, API_VERSION_MAJOR).uint32(3, API_VERSION_MINOR).toByteArray()

    // Advertised native-API version. 1.14 is the current floor: below it the
    // device logs an "outdated API" warning. The only behavioural difference for
    // us is that a 1.14+ device omits the per-entity `object_id` (the client is
    // expected to derive it from the name) — we only use `object_id` as a
    // display fallback, and `name`/`key` are still sent, so decoding is unaffected.
    private const val API_VERSION_MAJOR = 1
    private const val API_VERSION_MINOR = 14

    fun authRequest(password: String): ByteArray = ProtoWriter().string(1, password).toByteArray()

    val EMPTY: ByteArray = ByteArray(0)

    // ---- commands ----

    /** The (message type, payload) to set toggleable [type] entity [key] to [on], or null. */
    fun toggleCommand(type: String, key: Long, on: Boolean): Pair<Int, ByteArray>? = when (type) {
        "switch" -> SWITCH_COMMAND to ProtoWriter().fixed32(1, key).bool(2, on).toByteArray()
        // light/fan: has_state(2)=true, state(3)=on
        "light" -> LIGHT_COMMAND to ProtoWriter().fixed32(1, key).bool(2, true).bool(3, on).toByteArray()
        "fan" -> FAN_COMMAND to ProtoWriter().fixed32(1, key).bool(2, true).bool(3, on).toByteArray()
        // lock: LockCommand command(2) = LOCK(1) / UNLOCK(0, the proto3 default, so omitted).
        "lock" -> LOCK_COMMAND to ProtoWriter().fixed32(1, key).uint32(2, if (on) 1 else 0).toByteArray()
        else -> null
    }

    /** The (message type, payload) to press button [key]. */
    fun buttonCommand(key: Long): Pair<Int, ByteArray> =
        BUTTON_COMMAND to ProtoWriter().fixed32(1, key).toByteArray()

    /** The (message type, payload) to set select [key] to [option] (SelectCommandRequest: key=1, state=2). */
    fun selectCommand(key: Long, option: String): Pair<Int, ByteArray> =
        SELECT_COMMAND to ProtoWriter().fixed32(1, key).string(2, option).toByteArray()

    /** Cover actions the tool window offers. */
    enum class CoverAction { OPEN, CLOSE, STOP }

    /**
     * The (message type, payload) to drive cover [key]. Open/Close use a position
     * command (has_position=1, position=1.0/0.0) — which ESPHome maps to open/close
     * even for non-positional covers — and Stop uses the stop flag. Matches how
     * Home Assistant / aioesphomeapi drive covers (no deprecated legacy_command).
     */
    fun coverCommand(key: Long, action: CoverAction): Pair<Int, ByteArray> {
        val w = ProtoWriter().fixed32(1, key)
        when (action) {
            // has_position (field 4), position (field 5)
            CoverAction.OPEN -> w.bool(4, true).float(5, 1.0f)
            CoverAction.CLOSE -> w.bool(4, true).float(5, 0.0f)
            CoverAction.STOP -> w.bool(8, true) // stop (field 8)
        }
        return COVER_COMMAND to w.toByteArray()
    }

    /**
     * The (message type, payload) to drive valve [key] — the cover pattern with
     * valve field numbers (has_position=2, position=3, stop=4).
     */
    fun valveCommand(key: Long, action: CoverAction): Pair<Int, ByteArray> {
        val w = ProtoWriter().fixed32(1, key)
        when (action) {
            CoverAction.OPEN -> w.bool(2, true).float(3, 1.0f)
            CoverAction.CLOSE -> w.bool(2, true).float(3, 0.0f)
            CoverAction.STOP -> w.bool(4, true)
        }
        return VALVE_COMMAND to w.toByteArray()
    }

    /** The (message type, payload) to set number [key] to [value] (NumberCommandRequest: key=1, state=2 float). */
    fun numberCommand(key: Long, value: Float): Pair<Int, ByteArray> =
        NUMBER_COMMAND to ProtoWriter().fixed32(1, key).float(2, value).toByteArray()

    /** The (message type, payload) to set text [key] to [value] (TextCommandRequest: key=1, state=2 string). */
    fun textCommand(key: Long, value: String): Pair<Int, ByteArray> =
        TEXT_COMMAND to ProtoWriter().fixed32(1, key).string(2, value).toByteArray()

    // ---- decoding ----

    fun decodeEntity(listId: Int, payload: ByteArray): ApiEntity {
        val r = ProtoReader(payload)
        var objectId = ""
        var key = 0L
        var name = ""
        var unit = ""
        var deviceClass = ""
        var accuracyDecimals = -1
        val options = mutableListOf<String>()
        var numberMin = 0f
        var numberMax = 0f
        var numberStep = 0f
        while (r.hasMore()) {
            val tag = r.readTag()
            val field = r.fieldOf(tag)
            val wire = r.wireOf(tag)
            when {
                field == 1 && wire == 2 -> objectId = r.readString()
                field == 2 && wire == 5 -> key = r.readFixed32()
                field == 3 && wire == 2 -> name = r.readString()
                field == 6 && wire == 2 && listId == SENSOR_LIST -> unit = r.readString()
                field == 6 && wire == 2 && listId == SELECT_LIST -> options.add(r.readString())
                field == 7 && wire == 0 && listId == SENSOR_LIST -> accuracyDecimals = r.readVarint().toInt()
                // Number: min_value=6, max_value=7, step=8 (floats), unit=11.
                field == 6 && wire == 5 && listId == NUMBER_LIST -> numberMin = r.readFloat()
                field == 7 && wire == 5 && listId == NUMBER_LIST -> numberMax = r.readFloat()
                field == 8 && wire == 5 && listId == NUMBER_LIST -> numberStep = r.readFloat()
                field == 11 && wire == 2 && listId == NUMBER_LIST -> unit = r.readString()
                // device_class lives at different fields per type; read the ones that drive icons.
                field == 9 && wire == 2 && listId == SENSOR_LIST -> deviceClass = r.readString()
                field == 5 && wire == 2 && listId == BINARY_SENSOR_LIST -> deviceClass = r.readString()
                else -> r.skip(wire)
            }
        }
        return ApiEntity(
            key, ENTITY_TYPES[listId] ?: "unknown", name.ifEmpty { objectId }, unit, deviceClass,
            accuracyDecimals, options, numberMin, numberMax, numberStep,
        )
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
        var brightness = 0f // also the cover position (both are field 3, float)
        var operation = 0   // cover current_operation (field 5) / valve (3) / lock state (field 2)
        while (r.hasMore()) {
            val tag = r.readTag()
            val field = r.fieldOf(tag)
            val wire = r.wireOf(tag)
            when {
                field == 1 && wire == 5 -> key = r.readFixed32()
                field == 2 && wire == 0 && kind == LOCK -> operation = r.readVarint().toInt() // LockState enum
                field == 2 && wire == 0 -> bool = r.readBool()
                field == 2 && wire == 5 -> f = r.readFloat() // sensor/number/valve position
                field == 2 && wire == 2 -> s = r.readString()
                field == 3 && wire == 0 && kind == VALVE -> operation = r.readVarint().toInt() // valve current_operation
                field == 3 && wire == 0 && kind != BOOL_NO_MISSING -> missing = r.readBool()
                field == 3 && wire == 5 -> brightness = r.readFloat()
                field == 5 && wire == 0 && kind == COVER -> operation = r.readVarint().toInt()
                else -> r.skip(wire)
            }
        }
        val display = when (kind) {
            BOOL, BOOL_NO_MISSING -> if (missing) DASH else if (bool) "on" else "off"
            LIGHT -> if (!bool) "off" else if (brightness > 0f) "on ${(brightness * 100).roundToInt()}%" else "on"
            COVER -> positionDisplay(brightness, operation)
            VALVE -> positionDisplay(f, operation)
            LOCK -> lockDisplay(operation)
            FLOAT -> if (missing) DASH else formatFloat(f)
            TEXT -> if (missing) DASH else s
            EVENT -> s.ifEmpty { "triggered" }
            else -> return null
        }
        val active: Boolean? = when (kind) {
            BOOL, BOOL_NO_MISSING -> if (missing) null else bool
            LIGHT -> bool
            // Lock toggle reflects locked(1)=on / unlocked(2)=off; transitional/jammed leave it as-is.
            LOCK -> when (operation) { 1 -> true; 2 -> false; else -> null }
            else -> null
        }
        val number: Float? = if (kind == FLOAT && !missing) f else null
        return ApiState(key, display, active, number)
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

    /**
     * Cover/valve state text from position (0..1) and current_operation (1=opening,
     * 2=closing). Always leads with the position percentage so it is visible even
     * when fully open/closed, tagging the endpoints for clarity.
     */
    private fun positionDisplay(position: Float, operation: Int): String {
        val pct = (position * 100).roundToInt()
        return when (operation) {
            1 -> "opening $pct%"
            2 -> "closing $pct%"
            else -> when {
                pct >= 100 -> "100% (open)"
                pct <= 0 -> "0% (closed)"
                else -> "$pct% open"
            }
        }
    }

    /** Lock state text from the LockState enum (1=locked, 2=unlocked, 3=jammed, 4=locking, 5=unlocking). */
    private fun lockDisplay(state: Int): String = when (state) {
        1 -> "locked"
        2 -> "unlocked"
        3 -> "jammed"
        4 -> "locking"
        5 -> "unlocking"
        else -> DASH
    }

    private const val DASH = "—" // em dash for "no state yet"

    private fun formatFloat(f: Float): String {
        if (f.isNaN()) return "NaN"
        if (f == f.toLong().toFloat()) return f.toLong().toString()
        return "%.4f".format(Locale.ROOT, f).trimEnd('0').trimEnd('.')
    }
}
