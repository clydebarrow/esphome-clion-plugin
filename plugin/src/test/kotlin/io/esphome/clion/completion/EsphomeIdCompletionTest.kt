package io.esphome.clion.completion

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Completion in id-reference value positions: only in-scope, type-compatible ids
 * are offered.
 */
class EsphomeIdCompletionTest : BasePlatformTestCase() {

    private fun complete(text: String): List<String> {
        myFixture.configureByText("device.yaml", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings ?: emptyList()
    }

    fun `test output reference offers matching output ids only`() {
        val items = complete(
            """
            esphome:
              name: x
            output:
              - platform: gpio
                id: relay_out
                pin: 4
            i2c:
              id: bus_a
              sda: 21
              scl: 22
            light:
              - platform: binary
                name: L
                output: <caret>
            """.trimIndent(),
        )
        assertContainsElements(items, "relay_out")
        assertDoesntContain(items, "bus_a") // an i2c id is not an output
    }

    fun `test i2c_id reference offers the i2c bus id`() {
        val items = complete(
            """
            esphome:
              name: x
            i2c:
              id: bus_a
              sda: 21
              scl: 22
            sensor:
              - platform: aht10
                i2c_id: <caret>
            """.trimIndent(),
        )
        assertContainsElements(items, "bus_a")
    }

    fun `test domain action shorthand offers only ids of that domain`() {
        val items = complete(
            """
            esphome:
              name: x
            switch:
              - platform: gpio
                id: relay_sw
                pin: 4
            sensor:
              - platform: template
                id: my_temp
                on_value:
                  - switch.turn_on: <caret>
            """.trimIndent(),
        )
        assertContainsElements(items, "relay_sw")
        assertDoesntContain(items, "my_temp") // a sensor is not a switch
    }

    fun `test lambda id call offers in-scope ids`() {
        val items = complete(
            """
            esphome:
              name: x
            switch:
              - platform: gpio
                id: relay_sw
                pin: 4
            button:
              - platform: template
                name: B
                on_press:
                  - lambda: |-
                      id(<caret>
            """.trimIndent(),
        )
        assertContainsElements(items, "relay_sw")
    }

    fun `test component update shorthand offers any in-scope id`() {
        val items = complete(
            """
            esphome:
              name: x
            switch:
              - platform: gpio
                id: relay_sw
                pin: 4
            sensor:
              - platform: template
                id: my_temp
                on_value:
                  - component.update: <caret>
            """.trimIndent(),
        )
        // component.* references any component by id, regardless of type.
        assertContainsElements(items, "relay_sw", "my_temp")
    }
}
