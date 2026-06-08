package io.esphome.clion.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Primitive value type of a [ConfigEntry]. Drives editor behaviour: the base
 * completion/inspection control, what literals are valid, and how hover
 * documentation is rendered.
 *
 * Mirrors `ConfigEntryType` in device-builder's `models/common.py`. Unknown
 * wire values coerce to [UNKNOWN] (see [EsphomeCatalog.json]) so a newer
 * ESPHome release can add a type without breaking parsing.
 */
@Serializable
enum class ConfigEntryType {
    @SerialName("string") STRING,
    @SerialName("secure_string") SECURE_STRING,
    @SerialName("integer") INTEGER,
    @SerialName("float") FLOAT,
    @SerialName("boolean") BOOLEAN,
    @SerialName("pin") PIN,
    @SerialName("time_period") TIME_PERIOD,
    @SerialName("float_with_unit") FLOAT_WITH_UNIT,
    @SerialName("icon") ICON,
    @SerialName("id") ID,
    @SerialName("trigger") TRIGGER,
    @SerialName("color") COLOR,
    @SerialName("mac_address") MAC_ADDRESS,
    @SerialName("lambda") LAMBDA,
    @SerialName("json") JSON,
    @SerialName("nested") NESTED,
    @SerialName("map") MAP,
    @SerialName("registry_list") REGISTRY_LIST,
    @SerialName("label") LABEL,
    @SerialName("divider") DIVIDER,
    @SerialName("alert") ALERT,
    @SerialName("unknown") UNKNOWN,
}

/**
 * Group a component is filed under in the catalog. For platform components the
 * category equals the platform domain (`sensor`, `switch`, …). Unknown values
 * coerce to [MISC].
 */
@Serializable
enum class ComponentCategory {
    @SerialName("sensor") SENSOR,
    @SerialName("binary_sensor") BINARY_SENSOR,
    @SerialName("switch") SWITCH,
    @SerialName("light") LIGHT,
    @SerialName("fan") FAN,
    @SerialName("cover") COVER,
    @SerialName("climate") CLIMATE,
    @SerialName("button") BUTTON,
    @SerialName("number") NUMBER,
    @SerialName("select") SELECT,
    @SerialName("text") TEXT,
    @SerialName("text_sensor") TEXT_SENSOR,
    @SerialName("lock") LOCK,
    @SerialName("valve") VALVE,
    @SerialName("media_player") MEDIA_PLAYER,
    @SerialName("speaker") SPEAKER,
    @SerialName("microphone") MICROPHONE,
    @SerialName("camera") CAMERA,
    @SerialName("display") DISPLAY,
    @SerialName("touchscreen") TOUCHSCREEN,
    @SerialName("output") OUTPUT,
    @SerialName("datetime") DATETIME,
    @SerialName("event") EVENT,
    @SerialName("update") UPDATE,
    @SerialName("alarm_control_panel") ALARM,
    @SerialName("core") CORE,
    @SerialName("bus") BUS,
    @SerialName("automation") AUTOMATION,
    @SerialName("ota") OTA,
    @SerialName("time") TIME,
    @SerialName("audio_adc") AUDIO_ADC,
    @SerialName("audio_dac") AUDIO_DAC,
    @SerialName("canbus") CANBUS,
    @SerialName("infrared") INFRARED,
    @SerialName("media_source") MEDIA_SOURCE,
    @SerialName("one_wire") ONE_WIRE,
    @SerialName("packet_transport") PACKET_TRANSPORT,
    @SerialName("stepper") STEPPER,
    @SerialName("water_heater") WATER_HEATER,
    @SerialName("featured") FEATURED,
    @SerialName("misc") MISC,
}

/** Direction a GPIO pin will be used in (constrains pin pickers/inspections). */
@Serializable
enum class PinMode {
    @SerialName("input") INPUT,
    @SerialName("output") OUTPUT,
    @SerialName("input_output") INPUT_OUTPUT,
    @SerialName("unknown") UNKNOWN,
}

/**
 * Cross-field cardinality constraint over a group of sibling keys. Mirrors the
 * four `cv.has_*_one_key` validators upstream ESPHome exposes.
 */
@Serializable
enum class RequiredGroupKind {
    @SerialName("exactly_one") EXACTLY_ONE,
    @SerialName("at_least_one") AT_LEAST_ONE,
    @SerialName("at_most_one") AT_MOST_ONE,
    @SerialName("none_or_all") NONE_OR_ALL,
}
