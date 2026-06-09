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

    fun `test boolean field values are suggested`() {
        val items = complete("esphome:\n  name: x\ni2c:\n  scan: <caret>\n")
        assertContainsElements(items, "true", "false")
    }

    /**
     * Triggers (`on_*`) come from the automations index, not a component's
     * config_entries, and are offered at the component's root — here a
     * `touchscreen` platform item — keyed on the domain.
     */
    fun `test triggers are suggested at a component root`() {
        val items = complete("esphome:\n  name: x\ntouchscreen:\n  - platform: gt911\n    <caret>\n")
        assertContainsElements(items, "on_touch", "on_release", "on_update")
    }

    /**
     * The `lvgl:` root is the active-screen obj, so its obj-level event triggers
     * are valid there. They come from the (overlaid) automations index.
     */
    fun `test lvgl root triggers are suggested`() {
        val items = complete("esphome:\n  name: x\nlvgl:\n  <caret>\n")
        assertContainsElements(items, "on_boot", "on_all_events")
    }

    /**
     * lvgl is driven by the raw language schema (recursive widget tree, extends).
     * These exercise the language-schema path: root keys, widget types under the
     * `widgets:` list, and a widget's resolved properties.
     */
    fun `test lvgl root keys from language schema`() {
        val items = complete("esphome:\n  name: x\nlvgl:\n  <caret>\n")
        assertContainsElements(items, "displays", "widgets", "bg_color")
    }

    fun `test lvgl widget types under widgets`() {
        val items = complete("esphome:\n  name: x\nlvgl:\n  widgets:\n    - <caret>\n")
        assertContainsElements(items, "obj", "label", "button")
    }

    fun `test lvgl widget properties resolve through extends`() {
        val items = complete("esphome:\n  name: x\nlvgl:\n  widgets:\n    - label:\n        <caret>\n")
        assertContainsElements(items, "align", "bg_color")
    }

    /** In an automation list (under a trigger `on_*`), keys are action names. */
    fun `test actions are suggested in an automation list`() {
        val items = complete(
            "esphome:\n  name: x\nbinary_sensor:\n  - platform: gpio\n    pin: 1\n    on_press:\n      - <caret>\n",
        )
        if (!items.containsAll(listOf("delay", "logger.log"))) error("got: $items")
        assertContainsElements(items, "delay", "logger.log", "switch.turn_on")
    }

    fun `test non-esphome yaml is ignored`() {
        val items = complete("foo:\n  bar: 1\n<caret>\n")
        assertDoesntContain(items, "wifi", "sensor")
    }

    /**
     * The bundled YAML plugin's word-completion fallback otherwise pads the list
     * with words scraped from the file — in-use ids, sibling keys, values — that
     * are irrelevant to the current key position. Completion is driven from the
     * catalog, so those must not appear alongside the real i2c keys.
     */
    /**
     * A config whose `esphome:` block comes from a package has a top-level
     * `packages:` key but no `esphome:` of its own. It must still be recognised
     * as an ESPHome file so completion works — otherwise only the bundled YAML
     * word-completion runs (the reported "no suggestions" / wrong list).
     */
    fun `test packages-based config without top-level esphome is recognized`() {
        val items = complete(
            "substitutions:\n  name: x\npackages:\n  base: !include base.yaml\ni2c:\n  scl: 14\n  <caret>\n",
        )
        assertContainsElements(items, "scan", "frequency", "sda")
    }

    fun `test bundled yaml word-completion noise is suppressed`() {
        val items = complete(
            "esphome:\n  name: x\nwifi:\n  ssid: foo\ni2c:\n  id: bus_a\n  scl: 14\n  s<caret>\n",
        )
        assertContainsElements(items, "scan", "sda")
        // bus_a (an in-use id), ssid (a sibling key), esphome (an in-use key).
        assertDoesntContain(items, "bus_a", "ssid", "esphome")
    }
}
