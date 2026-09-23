package io.esphome.clion.validation

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [EsphomeValidationAnnotator.rangeFor] picks the source line to underline for
 * a parsed [EsphomeDiagnostic]. These drive it against real captured
 * `esphome config` output where the error is anchored on a whole enclosing
 * block (not the specific offending line) — the case that can otherwise
 * highlight an unrelated declaration of the same id instead of the actual
 * reference.
 */
class EsphomeValidationAnnotatorTest : BasePlatformTestCase() {

    fun `test id-type-mismatch error anchored on the whole lvgl block finds the nested reference, not the declaration`() {
        // Captured from `esphome config` on a config where an lvgl switch's id is
        // used where a binary_sensor is required: ESPHome anchors the error on
        // the whole `lvgl:` block (line 7 below), not the specific `on_click`
        // action several lines further down. A naive first-match-after-anchor
        // search finds the switch's own `id: maintenance_mode` declaration
        // (line 9) before it reaches the actual reference (line 13).
        val configText = """
            esphome:
              name: test
            binary_sensor:
              - platform: template
                id: dummy_sensor
                name: "Dummy"
            lvgl:
              displays: sdl0
              widgets:
                - switch:
                    id: maintenance_mode
                - button:
                    on_click:
                      - binary_sensor.template.publish:
                          id: maintenance_mode
                          state: true
        """.trimIndent()
        myFixture.configureByText("device.yaml", configText)

        val output = """
            Failed config

            lvgl: [source device.yaml:7]
              - displays:
                  - sdl0
                widgets:
                  - switch:
                      id: maintenance_mode
                  - button:
                      on_click:
                        - then:
                            - binary_sensor.template.publish:

                                ID 'maintenance_mode' of type lv_switch_t doesn't inherit from binary_sensor::BinarySensor. Please double check your ID is pointing to the correct value.
                                id: maintenance_mode
                                state: True
        """.trimIndent()
        val diagnostic = EsphomeConfigOutputParser.parse(output, "device.yaml").single()
        assertEquals("binary_sensor.template.publish", diagnostic.parentKey)

        val range = EsphomeValidationAnnotator().rangeFor(myFixture.editor.document, diagnostic)
        assertNotNull(range)
        val line = myFixture.editor.document.getLineNumber(range!!.startOffset)
        // 0-indexed line 12 is `id: maintenance_mode` under `binary_sensor.template.publish:`
        // (the reference); line 10 is the switch's own declaration.
        val declarationLine = configText.lines().indexOfFirst { it.trim() == "- switch:" } + 1
        val referenceLine = configText.lines().indexOfLast { it.trim() == "id: maintenance_mode" }
        assertTrue("must not land on the declaration line", line != declarationLine)
        assertEquals("must land on the actual reference line", referenceLine, line)
    }
}
