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

    fun `test a line-format diagnostic's column is used directly, with no offending-key search`() {
        val configText = """
            esphome:
              name: test
            time:
              - platform: host
                imezone: UTC extra
        """.trimIndent()
        myFixture.configureByText("device.yaml", configText)

        // Captured shape: `file:line:col: error: message` — column 5 is the `i`
        // of `imezone` on line 5 (1-based), 4 spaces in.
        val output = "device.yaml:5:5: error: [imezone] is an invalid option for [time.host]. Did you mean [timezone]?"
        val diagnostic = EsphomeConfigOutputParser.parse(output, "device.yaml").single()
        assertEquals(5, diagnostic.column)

        val range = EsphomeValidationAnnotator().rangeFor(myFixture.editor.document, diagnostic)
        assertNotNull(range)
        val document = myFixture.editor.document
        val line = document.getLineNumber(range!!.startOffset)
        assertEquals(4, line) // 0-indexed line 4 is `    imezone: UTC extra`
        assertEquals(document.getLineStartOffset(line) + 4, range.startOffset) // column 5 -> offset 4
        // Stops at the end of the `imezone` token — must not sweep in the rest
        // of the line (`: UTC extra`), which has nothing to do with the error.
        assertEquals("imezone", document.getText(range))
    }

    fun `test a line-format diagnostic landing on a quoted value highlights just that value, not the rest of the line`() {
        // Captured against a real `esphome config` run (dev branch with
        // --error-format line): a "could not find action" error's mark lands on
        // the action's *value* node, not its `key:` — ESPHome's own column can
        // point somewhere other than where you'd expect. Highlighting from there
        // to end of line would sweep the whole unrelated log message in with it.
        val configText = """
            esphome:
              name: test
            binary_sensor:
              - platform: template
                id: bs
                on_press:
                  - loggr.log: "Reverting to normal mode" # trailing comment
        """.trimIndent()
        myFixture.configureByText("device.yaml", configText)
        val document = myFixture.editor.document
        val line = configText.lines().indexOfFirst { it.trim().startsWith("- loggr.log:") }
        val column = configText.lines()[line].indexOf('"') + 1 // 1-based

        val output = "device.yaml:${line + 1}:$column: error: Unable to find action with the name 'loggr.log'."
        val diagnostic = EsphomeConfigOutputParser.parse(output, "device.yaml").single()

        val range = EsphomeValidationAnnotator().rangeFor(document, diagnostic)
        assertNotNull(range)
        assertEquals("\"Reverting to normal mode\"", document.getText(range!!))
    }
}
