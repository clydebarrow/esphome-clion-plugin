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
