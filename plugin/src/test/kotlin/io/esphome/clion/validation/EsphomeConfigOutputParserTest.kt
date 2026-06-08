package io.esphome.clion.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parser tests against real `esphome config` output (ESPHome 2026.6.0-dev). */
class EsphomeConfigOutputParserTest {

    // Captured verbatim from `esphome config bad.yaml` on a config with an
    // invalid option and an invalid time value.
    private val realOutput = """
        INFO ESPHome 2026.6.0-dev
        INFO Reading configuration bad.yaml...
        Failed config

        sensor.dht: [source bad.yaml:12]
          platform: dht
          pin: 4
          temperature:
            name: Temp
          humidity:
            name: Humidity

          [bogus_key] is an invalid option for [sensor.dht]. Please check the indentation.
          bogus_key: 123

          Unknown value 'not_a_time', valid options are 'ns', 'nanoseconds', 'ms', 'milliseconds', 's', 'seconds'.
          update_interval: not_a_time
    """.trimIndent()

    @Test
    fun `parses both errors with anchor line and offending keys`() {
        val diagnostics = EsphomeConfigOutputParser.parse(realOutput, "/abs/path/bad.yaml")
        assertEquals(2, diagnostics.size)

        val invalidOption = diagnostics[0]
        assertEquals(12, invalidOption.anchorLine)
        assertEquals("bogus_key", invalidOption.offendingKey)
        assertTrue(invalidOption.message.startsWith("[bogus_key] is an invalid option"))

        val badTime = diagnostics[1]
        assertEquals(12, badTime.anchorLine)
        assertEquals("update_interval", badTime.offendingKey)
        assertTrue(badTime.message.startsWith("Unknown value 'not_a_time'"))
    }

    @Test
    fun `ignores the config dump paragraph and info lines`() {
        val diagnostics = EsphomeConfigOutputParser.parse(realOutput, "bad.yaml")
        // platform/pin/temperature are dump lines, not errors.
        assertTrue(diagnostics.none { it.offendingKey == "platform" || it.offendingKey == "pin" })
    }

    @Test
    fun `filters diagnostics to the target file`() {
        // An error reported against a different file (e.g. an include) is dropped.
        val out = "Failed config\n\nwifi: [source other.yaml:3]\n  ssid: x\n\n  Bad thing happened.\n  ssid: x\n"
        assertTrue(EsphomeConfigOutputParser.parse(out, "device.yaml").isEmpty())
        assertEquals(1, EsphomeConfigOutputParser.parse(out, "other.yaml").size)
    }

    @Test
    fun `parses an error with no config-dump preamble`() {
        // Captured from `esphome config living_room.yaml` — the component fails
        // immediately, so there is no echoed config dump before the error.
        val out = """
            INFO ESPHome 2026.6.0-dev
            INFO Reading configuration living_room.yaml...
            Failed config

            esphome: [source living_room.yaml:11]

              Platform missing. You must include one of the available platform keys: esp32, esp8266.
              name: living-room
        """.trimIndent()
        val diagnostics = EsphomeConfigOutputParser.parse(out, "living_room.yaml")
        assertEquals(1, diagnostics.size)
        assertEquals(11, diagnostics[0].anchorLine)
        assertEquals("name", diagnostics[0].offendingKey)
        assertTrue(diagnostics[0].message.startsWith("Platform missing"))
    }

    @Test
    fun `parses an error interleaved in the config dump without blank separators`() {
        // Captured: a too-short WPA password — the message sits between dump lines.
        val out = """
            Failed config

            wifi: [source examples/living_room.yaml:17]
              ssid: MyNetwork
              WPA password must be at least 8 characters long.
              password: secret
              fast_connect: True
              ap:
                channel: 1
                ssid: Living Room Fallback
        """.trimIndent()
        val diagnostics = EsphomeConfigOutputParser.parse(out, "living_room.yaml")
        assertEquals(1, diagnostics.size)
        assertEquals(17, diagnostics[0].anchorLine)
        assertEquals("password", diagnostics[0].offendingKey)
        assertTrue(diagnostics[0].message.startsWith("WPA password must be at least 8"))
    }

    @Test
    fun `parses a top-level error with no source block and extracts a token`() {
        // Captured: an unknown platform — no `[source …]`, just a top-level line.
        val out = """
            INFO Reading configuration examples/living_room.yaml...
            INFO Unable to import component gpxo.binary_sensor: No module named 'esphome.components.gpxo'
            Failed config
            Platform not found: 'binary_sensor.gpxo'
        """.trimIndent()
        val diagnostics = EsphomeConfigOutputParser.parse(out, "living_room.yaml")
        assertEquals(1, diagnostics.size)
        assertEquals(0, diagnostics[0].anchorLine) // file-level
        assertEquals("gpxo", diagnostics[0].searchToken)
        assertTrue(diagnostics[0].message.startsWith("Platform not found"))
        // The INFO line above must NOT be treated as an error.
        assertTrue(diagnostics.none { it.message.startsWith("Unable to import") })
    }

    @Test
    fun `fragment via root keeps its block errors but drops headerless top-level errors`() {
        // When a fragment is validated through the device root, a top-level error
        // belongs to the root and must not be pinned onto the fragment; a block
        // error reported against the fragment still maps back to it.
        val out = """
            Failed config

            Platform not found: 'binary_sensor.gpxo'

            wifi: [source frag.yaml:3]
              ssid: x

              Bad thing happened.
              ssid: x
        """.trimIndent()

        val withTopLevel = EsphomeConfigOutputParser.parse(out, "frag.yaml", includeTopLevelErrors = true)
        assertTrue(withTopLevel.any { it.message.startsWith("Platform not found") })
        assertTrue(withTopLevel.any { it.message.startsWith("Bad thing happened") })

        val fragmentOnly = EsphomeConfigOutputParser.parse(out, "frag.yaml", includeTopLevelErrors = false)
        assertEquals(1, fragmentOnly.size)
        assertTrue(fragmentOnly[0].message.startsWith("Bad thing happened"))
    }

    @Test
    fun `empty or success output yields no diagnostics`() {
        assertTrue(EsphomeConfigOutputParser.parse("", "x.yaml").isEmpty())
        assertTrue(EsphomeConfigOutputParser.parse("INFO Configuration is valid!", "x.yaml").isEmpty())
    }
}
