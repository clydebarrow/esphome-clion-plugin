package io.esphome.clion.documentation

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Drives the documentation provider end-to-end against real YAML PSI. */
class EsphomeDocumentationProviderTest : BasePlatformTestCase() {

    private val provider = EsphomeDocumentationProvider()

    private fun docAt(text: String): String? {
        myFixture.configureByText("device.yaml", text)
        val context = myFixture.file.findElementAt(myFixture.caretOffset)
        val target = provider.getCustomDocumentationElement(
            myFixture.editor, myFixture.file, context, myFixture.caretOffset,
        )
        return provider.generateDoc(target, context)
    }

    fun `test doc for a top-level component key`() {
        val doc = docAt("esphome:\n  name: x\nwi<caret>fi:\n  ssid: y\n")
        assertNotNull(doc)
        assertTrue(doc!!.contains("WiFi") || doc.contains("wifi"))
        assertTrue(doc.contains("esphome.io/components/wifi"))
    }

    fun `test doc for a field key`() {
        val doc = docAt("esphome:\n  name: x\nwifi:\n  ss<caret>id: y\n")
        assertNotNull(doc)
        assertTrue(doc!!.contains("<b>ssid</b>"))
        assertTrue(doc.contains("SSID") || doc.contains("network"))
    }

    fun `test doc for a nested field key`() {
        val doc = docAt("esphome:\n  name: x\nwifi:\n  ap:\n    chan<caret>nel: 6\n")
        assertNotNull(doc)
        assertTrue(doc!!.contains("<b>channel</b>"))
    }

    fun `test doc for a field inside a platform item`() {
        val doc = docAt("esphome:\n  name: x\nswitch:\n  - platform: gpio\n    restore_<caret>mode: RESTORE_DEFAULT_OFF\n")
        assertNotNull(doc)
        assertTrue(doc!!.contains("<b>restore_mode</b>"))
    }

    fun `test no doc for a platform domain key`() {
        // `sensor:` is a category, not a single component.
        assertNull(docAt("esphome:\n  name: x\nsen<caret>sor:\n  - platform: dht\n    pin: 4\n"))
    }
}
