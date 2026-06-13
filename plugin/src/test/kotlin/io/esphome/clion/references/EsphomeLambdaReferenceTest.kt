package io.esphome.clion.references

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLScalar

/** `id(<name>)` inside a lambda resolves to its `id:` declaration. */
class EsphomeLambdaReferenceTest : BasePlatformTestCase() {

    fun `test id call in a lambda resolves to the declaration`() {
        val text = """
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
                      id(relay_sw).turn_on();
        """.trimIndent()
        myFixture.configureByText("device.yaml", text)

        val offset = text.indexOf("relay_sw", text.indexOf("id(relay_sw"))
        val resolved = myFixture.file.findReferenceAt(offset + 1)?.resolve()
        assertEquals("relay_sw", (resolved as? YAMLScalar)?.textValue)
    }

    fun `test unknown id in a lambda does not resolve`() {
        val text = """
            esphome:
              name: x
            button:
              - platform: template
                name: B
                on_press:
                  - lambda: |-
                      id(nope).turn_on();
        """.trimIndent()
        myFixture.configureByText("device.yaml", text)

        val offset = text.indexOf("nope")
        assertNull(myFixture.file.findReferenceAt(offset + 1)?.resolve())
    }
}
