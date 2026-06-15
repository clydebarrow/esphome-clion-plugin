package io.esphome.clion.inspections

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Inspections: unresolved typed id references (flagged, with a near-match
 * quick-fix, but quiet on resolvable/templated cases) and unused id declarations
 * (only when enabled).
 */
class EsphomeIdInspectionTest : BasePlatformTestCase() {

    private fun descriptions(): List<String> =
        myFixture.doHighlighting().mapNotNull { it.description }

    fun `test unresolved typed id reference is flagged`() {
        myFixture.enableInspections(EsphomeUnresolvedIdInspection())
        myFixture.configureByText(
            "device.yaml",
            """
            esphome:
              name: x
            output:
              - platform: gpio
                id: relay_out
                pin: 4
            light:
              - platform: binary
                name: L
                output: relay_ou
            """.trimIndent(),
        )
        assertTrue(descriptions().any { it.startsWith("Cannot resolve id reference 'relay_ou'") })
    }

    fun `test resolvable reference is not flagged`() {
        myFixture.enableInspections(EsphomeUnresolvedIdInspection())
        myFixture.configureByText(
            "device.yaml",
            """
            esphome:
              name: x
            output:
              - platform: gpio
                id: relay_out
                pin: 4
            light:
              - platform: binary
                name: L
                output: relay_out
            """.trimIndent(),
        )
        assertFalse(descriptions().any { it.contains("Cannot resolve id reference") })
    }

    fun `test action id argument does not shadow the real declaration`() {
        // Regression: `output.set_level: { id: buzzer_out }` etc. are `use_id`
        // references, not declarations. They must not be indexed as `button`-domain
        // declarations that overwrite the real `output`/ledc one (id index is keyed
        // by name), which made `rtttl: output: buzzer_out` falsely "unresolvable".
        myFixture.enableInspections(EsphomeUnresolvedIdInspection())
        myFixture.configureByText(
            "device.yaml",
            """
            esphome:
              name: x
            output:
              - platform: ledc
                id: buzzer_out
                pin: GPIO48
            rtttl:
              output: buzzer_out
            button:
              - platform: template
                id: beep
                on_press:
                  - output.turn_on: buzzer_out
                  - output.ledc.set_frequency:
                      id: buzzer_out
                      frequency: 1kHz
                  - output.set_level:
                      id: buzzer_out
                      level: 50%
            """.trimIndent(),
        )
        assertFalse(descriptions().any { it.contains("Cannot resolve id reference") })
    }

    fun `test quick-fix changes an unresolved reference to a near match`() {
        myFixture.enableInspections(EsphomeUnresolvedIdInspection())
        myFixture.configureByText(
            "device.yaml",
            """
            esphome:
              name: x
            output:
              - platform: gpio
                id: relay_out
                pin: 4
            light:
              - platform: binary
                name: L
                output: relay_ou
            """.trimIndent(),
        )
        val fix = myFixture.getAllQuickFixes().firstOrNull { it.text == "Change to 'relay_out'" }
        assertNotNull("near-match quick-fix offered", fix)
        myFixture.launchAction(fix!!)
        assertTrue(myFixture.file.text.contains("output: relay_out"))
    }

    fun `test reference to an expanded templated id resolves`() {
        myFixture.enableInspections(EsphomeUnresolvedIdInspection())
        myFixture.configureByText(
            "device.yaml",
            """
            esphome:
              name: x
            substitutions:
              suffix: a
            output:
              - platform: gpio
                id: relay_${'$'}{suffix}
                pin: 4
            light:
              - platform: binary
                name: L
                output: relay_a
            """.trimIndent(),
        )
        assertFalse(descriptions().any { it.contains("Cannot resolve id reference") })
    }

    fun `test unexpandable templated declaration suppresses flagging`() {
        // `suffix` is undefined, so the templated id can't be expanded — stay quiet
        // rather than risk flagging a reference that legitimately matches it.
        myFixture.enableInspections(EsphomeUnresolvedIdInspection())
        myFixture.configureByText(
            "device.yaml",
            """
            esphome:
              name: x
            output:
              - platform: gpio
                id: relay_${'$'}{suffix}
                pin: 4
            light:
              - platform: binary
                name: L
                output: relay_x
            """.trimIndent(),
        )
        assertFalse(descriptions().any { it.contains("Cannot resolve id reference") })
    }

    fun `test unused id is flagged when the inspection is enabled`() {
        myFixture.enableInspections(EsphomeUnusedIdInspection())
        myFixture.configureByText(
            "device.yaml",
            """
            esphome:
              name: x
            i2c:
              id: bus0
              sda: 21
              scl: 22
            """.trimIndent(),
        )
        assertTrue(descriptions().any { it.startsWith("ID 'bus0' is never used") })
    }

    fun `test used id is not flagged as unused`() {
        myFixture.enableInspections(EsphomeUnusedIdInspection())
        myFixture.configureByText(
            "device.yaml",
            """
            esphome:
              name: x
            i2c:
              id: bus0
              sda: 21
              scl: 22
            sensor:
              - platform: aht10
                i2c_id: bus0
            """.trimIndent(),
        )
        assertFalse(descriptions().any { it.contains("is never used") })
    }
}
