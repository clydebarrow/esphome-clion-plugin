package io.esphome.clion.structureview

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLFile

/**
 * The Structure window for an ESPHome config: a `sensor:`/`binary_sensor:`/…
 * list entry should show its `id`/`name`/`platform` instead of the bundled
 * YAML view's generic "Sequence item".
 */
class EsphomeStructureViewElementTest : BasePlatformTestCase() {

    private fun rootChildren(labelPath: List<String>): List<EsphomeStructureViewElement> {
        var level = listOf(EsphomeStructureViewElement(myFixture.file))
        for (label in labelPath) {
            level = level.single().getChildrenBase()
                .filterIsInstance<EsphomeStructureViewElement>()
                .filter { it.presentableText == label }
        }
        return level.single().getChildrenBase().filterIsInstance<EsphomeStructureViewElement>()
    }

    fun `test a sensor entry is labelled by its id, not "Sequence item"`() {
        myFixture.configureByText(
            "device.yaml",
            """
            esphome:
              name: x
            binary_sensor:
              - platform: analog_threshold
                id: blower_running
                name: "Blower Running"
                sensor_id: blower_current
                threshold: 50
            """.trimIndent(),
        )
        val entries = rootChildren(listOf("binary_sensor"))
        assertEquals(1, entries.size)
        assertEquals("blower_running", entries.single().presentableText)
        assertEquals("analog_threshold", entries.single().locationString)
    }

    fun `test an entry with no id falls back to name then platform`() {
        myFixture.configureByText(
            "device.yaml",
            """
            esphome:
              name: x
            switch:
              - platform: template
                name: "No Id Here"
              - platform: gpio
                pin: 4
            """.trimIndent(),
        )
        val entries = rootChildren(listOf("switch"))
        assertEquals(2, entries.size)
        assertEquals("No Id Here", entries[0].presentableText)
        assertEquals("gpio", entries[1].presentableText)
    }

    fun `test file structure builder is offered for an esphome config`() {
        myFixture.configureByText("device.yaml", "esphome:\n  name: x\n")
        val builder = EsphomeStructureViewFactory().getStructureViewBuilder(myFixture.file as YAMLFile)
        assertNotNull(builder)
    }

    fun `test file structure builder is not offered for a plain non-esphome yaml file`() {
        myFixture.configureByText("plain.yaml", "foo:\n  bar: baz\n")
        val builder = EsphomeStructureViewFactory().getStructureViewBuilder(myFixture.file as YAMLFile)
        assertNull(builder)
    }
}
