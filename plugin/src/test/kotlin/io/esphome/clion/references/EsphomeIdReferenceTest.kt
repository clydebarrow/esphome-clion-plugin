package io.esphome.clion.references

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Drives id-reference resolution against the vendored catalog and real YAML PSI:
 * a same-file reference, the `provides`-based inheritance case, a reference that
 * crosses an `!include`, and the cases that must not resolve.
 */
class EsphomeIdReferenceTest : BasePlatformTestCase() {

    /** Resolve the reference on the id name in [line], returning the declaration's text value. */
    private fun resolvedNameAt(text: String, line: String, name: String): String? {
        val lineStart = text.indexOf(line)
        require(lineStart >= 0) { "line '$line' not found" }
        val offset = text.indexOf(name, lineStart)
        val reference = myFixture.file.findReferenceAt(offset + 1)
        return (reference?.resolve() as? YAMLScalar)?.textValue
    }

    fun `test output reference resolves to the output id declaration`() {
        val text = """
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
        """.trimIndent()
        myFixture.configureByText("device.yaml", text)
        assertEquals("relay_out", resolvedNameAt(text, "output: relay_out", "relay_out"))
    }

    fun `test reference resolves via provides inheritance`() {
        // ct_clamp's `sensor:` is a use_id(voltage_sampler); adc provides voltage_sampler.
        val text = """
            esphome:
              name: x
            sensor:
              - platform: adc
                id: adc1
                pin: GPIO34
              - platform: ct_clamp
                sensor: adc1
                name: Power
        """.trimIndent()
        myFixture.configureByText("device.yaml", text)
        assertEquals("adc1", resolvedNameAt(text, "sensor: adc1", "adc1"))
    }

    fun `test reference resolves across an include`() {
        myFixture.addFileToProject(
            "p/buses.yaml",
            "i2c:\n  id: bus_a\n  sda: 21\n  scl: 22\n",
        )
        val text = """
            esphome:
              name: x
            packages:
              buses: !include buses.yaml
            sensor:
              - platform: aht10
                i2c_id: bus_a
        """.trimIndent()
        val vf = myFixture.addFileToProject("p/device.yaml", text).virtualFile
        myFixture.configureFromExistingVirtualFile(vf)
        assertEquals("bus_a", resolvedNameAt(text, "i2c_id: bus_a", "bus_a"))
    }

    fun `test id !extend resolves to the package declaration it modifies`() {
        // `id: !extend X` in a config using `packages:` references the X declared
        // in the package (to override it), so it must link to that declaration —
        // not be treated as a new id of its own.
        myFixture.addFileToProject(
            "p/gyro.yaml",
            "binary_sensor:\n  - platform: template\n    id: free_fall_id\n    name: Free Fall\n",
        )
        val text = """
            esphome:
              name: x
            packages:
              gyro: !include gyro.yaml
            binary_sensor:
              - id: !extend free_fall_id
                filters:
                  - delayed_on: 10ms
        """.trimIndent()
        val vf = myFixture.addFileToProject("p/device.yaml", text).virtualFile
        myFixture.configureFromExistingVirtualFile(vf)
        assertEquals("free_fall_id", resolvedNameAt(text, "id: !extend free_fall_id", "free_fall_id"))
    }

    fun `test name-based fallback resolves an id under an unmodeled component`() {
        // lvgl ships no catalog fields, so `display:` here has no references_component;
        // the fallback still links the identifier value to the display id declaration.
        val text = """
            esphome:
              name: x
            display:
              - platform: ili9xxx
                id: sdl_display
            lvgl:
              displays:
                - sdl_display
        """.trimIndent()
        myFixture.configureByText("device.yaml", text)
        assertEquals("sdl_display", resolvedNameAt(text, "- sdl_display", "sdl_display"))
    }

    fun `test templated reference resolves via substitution expansion`() {
        val text = """
            esphome:
              name: x
            substitutions:
              suffix: a
            output:
              - platform: gpio
                id: relay_a
                pin: 4
            light:
              - platform: binary
                name: L
                output: relay_${'$'}{suffix}
        """.trimIndent()
        myFixture.configureByText("device.yaml", text)
        // Click the literal `relay_` part of the templated reference value, which
        // only the id reference covers; it expands to `relay_a` and resolves.
        val offset = text.indexOf("output: relay_") + "output: rel".length
        val resolved = myFixture.file.findReferenceAt(offset)?.resolve() as? YAMLScalar
        assertEquals("relay_a", resolved?.textValue)
    }

    fun `test reference to an undeclared id does not resolve`() {
        val text = """
            esphome:
              name: x
            light:
              - platform: binary
                name: L
                output: missing_output
        """.trimIndent()
        myFixture.configureByText("device.yaml", text)
        assertNull(resolvedNameAt(text, "output: missing_output", "missing_output"))
    }

    fun `test type-mismatched id does not resolve`() {
        // bus_a is an i2c id; an `output:` reference wants an output, so it must not match.
        val text = """
            esphome:
              name: x
            i2c:
              id: bus_a
              sda: 21
              scl: 22
            light:
              - platform: binary
                name: L
                output: bus_a
        """.trimIndent()
        myFixture.configureByText("device.yaml", text)
        assertNull(resolvedNameAt(text, "output: bus_a", "bus_a"))
    }
}
