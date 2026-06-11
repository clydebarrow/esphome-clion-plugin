package io.esphome.clion.api.toolwindow

import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import com.intellij.util.ui.UIUtil
import javax.swing.Icon

/**
 * Maps an entity's component type / device class to a bundled (curated MDI)
 * icon, recolored to the IDE's label foreground so it adapts to the theme. The
 * device class wins when known (e.g. a `temperature` sensor), else the type, with
 * a generic fallback.
 */
object EntityIcons {

    /**
     * Icon for an entity, recolored to the IDE label foreground. A binary_sensor
     * shows a filled circle when on and an unfilled circle when off ([active]);
     * everything else picks by device class then type.
     */
    fun iconFor(type: String, deviceClass: String, active: Boolean? = null): Icon {
        val name = when (type) {
            "binary_sensor" -> if (active == true) "circle" else "circle-outline"
            else -> DEVICE_CLASS_ICONS[deviceClass] ?: TYPE_ICONS[type] ?: FALLBACK
        }
        val base = IconLoader.getIcon("/icons/esphome/$name.svg", EntityIcons::class.java)
        return IconUtil.colorize(base, UIUtil.getLabelForeground())
    }

    /** Every bundled icon name referenced — for a resource-presence test. */
    internal fun iconResourceNames(): Set<String> =
        TYPE_ICONS.values.toSet() + DEVICE_CLASS_ICONS.values.toSet() + setOf(FALLBACK, "circle", "circle-outline")

    private const val FALLBACK = "shape-outline"

    private val TYPE_ICONS = mapOf(
        "light" to "lightbulb",
        "switch" to "toggle-switch",
        "fan" to "fan",
        "sensor" to "gauge",
        "binary_sensor" to "checkbox-marked-circle",
        "button" to "gesture-tap-button",
        "text_sensor" to "text-box",
        "text" to "text-box",
        "number" to "numeric",
        "select" to "form-select",
        "cover" to "window-shutter",
        "lock" to "lock",
        "climate" to "thermostat",
        "media_player" to "speaker",
        "camera" to "camera",
        "valve" to "valve",
    )

    private val DEVICE_CLASS_ICONS = mapOf(
        "temperature" to "thermometer",
        "humidity" to "water-percent",
        "moisture" to "water",
        "power" to "flash",
        "energy" to "lightning-bolt",
        "voltage" to "sine-wave",
        "frequency" to "sine-wave",
        "current" to "current-ac",
        "pressure" to "gauge-low",
        "illuminance" to "brightness-5",
        "signal_strength" to "wifi",
        "connectivity" to "wifi",
        "battery" to "battery",
        "timestamp" to "clock-outline",
        "carbon_dioxide" to "molecule-co2",
        "pm25" to "air-filter",
        "motion" to "motion-sensor",
        "occupancy" to "account",
        "presence" to "account",
        "door" to "door",
        "garage_door" to "garage",
        "window" to "window-closed",
        "problem" to "alert-circle",
        "smoke" to "smoke-detector",
        "lock" to "lock",
    )
}
