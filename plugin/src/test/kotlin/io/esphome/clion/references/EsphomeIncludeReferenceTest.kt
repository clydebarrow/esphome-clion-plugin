package io.esphome.clion.references

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Drives the `!include` file reference against real YAML PSI (the bundled YAML
 * plugin is on the test classpath, and the plugin's `plugin.xml` registers the
 * contributor). Covers the scalar and block `!include` forms, relative-path
 * resolution, and the cases that must NOT get a reference.
 */
class EsphomeIncludeReferenceTest : BasePlatformTestCase() {

    /** Resolve the reference sitting on the file-name portion of [path]. */
    private fun resolveIncludeTarget(mainFile: PsiFile, path: String): PsiFile? {
        val fileName = path.substringAfterLast('/')
        val offset = mainFile.text.indexOf(fileName)
        require(offset >= 0) { "path '$path' not found in file text" }
        val reference = mainFile.findReferenceAt(offset + fileName.length / 2)
        return reference?.resolve() as? PsiFile
    }

    fun `test scalar include resolves to the file`() {
        myFixture.addFileToProject("common/wifi.yaml", "wifi:\n  ssid: Home\n")
        val main = myFixture.configureByText(
            "device.yaml",
            "esphome:\n  name: x\nwifi: !include common/wifi.yaml\n",
        )
        val target = resolveIncludeTarget(main, "common/wifi.yaml")
        assertNotNull("include should resolve", target)
        assertEquals("wifi.yaml", target!!.name)
    }

    fun `test block-form include resolves via the file key`() {
        myFixture.addFileToProject("sensors/temp.yaml", "- platform: dht\n  pin: 4\n")
        val main = myFixture.configureByText(
            "device.yaml",
            "esphome:\n  name: x\nsensor: !include\n  file: sensors/temp.yaml\n  vars:\n    name: Temp\n",
        )
        val target = resolveIncludeTarget(main, "sensors/temp.yaml")
        assertNotNull("block-form include should resolve", target)
        assertEquals("temp.yaml", target!!.name)
    }

    fun `test unresolved include path yields a reference that does not resolve`() {
        val main = myFixture.configureByText(
            "device.yaml",
            "esphome:\n  name: x\nwifi: !include common/missing.yaml\n",
        )
        val offset = main.text.indexOf("missing.yaml")
        val reference = main.findReferenceAt(offset + 1)
        assertNotNull("a file reference is still contributed", reference)
        assertNull("but it does not resolve to anything", reference!!.resolve())
    }

    fun `test plain scalar without include tag gets no file reference`() {
        val main = myFixture.configureByText(
            "device.yaml",
            "esphome:\n  name: device.yaml\n",
        )
        val offset = main.text.indexOf("device.yaml", startIndex = "esphome:\n  name: ".length)
        val reference = main.findReferenceAt(offset + 1)
        // The `name:` value is not an !include, so our provider contributes nothing.
        assertNull(reference)
    }

    fun `test substitution placeholder in include path is skipped`() {
        val main = myFixture.configureByText(
            "device.yaml",
            "esphome:\n  name: x\nwifi: !include \${board}/wifi.yaml\n",
        )
        val offset = main.text.indexOf("wifi.yaml")
        val reference = main.findReferenceAt(offset + 1)
        assertNull("substitution paths are not turned into file references", reference)
    }
}
