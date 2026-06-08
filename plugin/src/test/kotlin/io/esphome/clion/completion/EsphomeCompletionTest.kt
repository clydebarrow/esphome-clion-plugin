package io.esphome.clion.completion

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Drives the completion contributor against real YAML PSI (the bundled YAML
 * plugin is on the test classpath, and the plugin's own `plugin.xml` registers
 * the contributor). Covers the key context shapes that differ in the parse
 * tree: top-level keys, keys in a block mapping (where a half-typed key is
 * mis-parsed as the parent's scalar value), keys in a platform list item, and
 * the two value cases (`platform:` and a closed enum).
 */
class EsphomeCompletionTest : BasePlatformTestCase() {

    private fun complete(text: String): List<String> {
        myFixture.configureByText("device.yaml", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings ?: emptyList()
    }

    fun `test top-level keys are suggested`() {
        val items = complete("esphome:\n  name: x\n<caret>\n")
        assertContainsElements(items, "wifi", "sensor", "switch", "i2c")
    }

    fun `test keys inside a component block`() {
        val items = complete("esphome:\n  name: x\ni2c:\n  <caret>\n")
        assertContainsElements(items, "sda", "scl", "frequency")
    }

    fun `test keys inside a nested block`() {
        val items = complete("esphome:\n  name: x\nwifi:\n  ap:\n    <caret>\n")
        assertContainsElements(items, "ssid", "channel", "ap_timeout")
    }

    fun `test keys inside a platform item`() {
        val items = complete("esphome:\n  name: x\nswitch:\n  - platform: gpio\n    <caret>\n")
        assertContainsElements(items, "name", "pin", "restore_mode")
    }

    fun `test platform value is suggested`() {
        val items = complete("esphome:\n  name: x\nsensor:\n  - platform: <caret>\n")
        assertContainsElements(items, "dht")
    }

    fun `test enum option values are suggested`() {
        val items = complete("esphome:\n  name: x\nswitch:\n  - platform: gpio\n    restore_mode: <caret>\n")
        assertContainsElements(items, "RESTORE_DEFAULT_OFF")
    }

    fun `test non-esphome yaml is ignored`() {
        val items = complete("foo:\n  bar: 1\n<caret>\n")
        assertDoesntContain(items, "wifi", "sensor")
    }
}
