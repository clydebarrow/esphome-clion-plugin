package io.esphome.clion.validation

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Phase 4 target selection: an `!include`d fragment is validated through the
 * device root that includes it, while a device root and an orphan fragment are
 * handled directly. (The full [EsphomeValidationAnnotator.collectInformation]
 * also requires a local-filesystem file, which the light test fixture is not, so
 * the root-selection logic is exercised via `includingRoot`.)
 */
class EsphomeValidationTargetTest : BasePlatformTestCase() {

    private val annotator = EsphomeValidationAnnotator()

    fun `test includingRoot picks the device root for a fragment`() {
        val device = myFixture.addFileToProject(
            "p/device.yaml",
            "esphome:\n  name: x\nwifi: !include wifi.yaml\n",
        ).virtualFile
        val wifi = myFixture.addFileToProject("p/wifi.yaml", "wifi:\n  ssid: x\n").virtualFile

        assertEquals(device, annotator.includingRoot(project, wifi))
    }

    fun `test includingRoot follows a nested include chain to the root`() {
        val sub = myFixture.addFileToProject("p/sub.yaml", "binary_sensor:\n  - platform: gpio\n").virtualFile
        myFixture.addFileToProject("p/mid.yaml", "sensor: !include sub.yaml\n")
        val device = myFixture.addFileToProject(
            "p/device.yaml",
            "esphome:\n  name: x\npackages:\n  base: !include mid.yaml\n",
        ).virtualFile

        assertEquals(device, annotator.includingRoot(project, sub))
    }

    fun `test includingRoot is null for an orphan fragment`() {
        val orphan = myFixture.addFileToProject("p/orphan.yaml", "sensor:\n  - platform: dht\n").virtualFile
        assertNull(annotator.includingRoot(project, orphan))
    }

    fun `test includingRoot is null when the includer is not a device root`() {
        val inner = myFixture.addFileToProject("p/inner.yaml", "ssid: x\n").virtualFile
        // wifi.yaml includes inner.yaml but has no top-level esphome:, so it is not a device.
        myFixture.addFileToProject("p/wifi.yaml", "wifi: !include inner.yaml\n")

        assertNull(annotator.includingRoot(project, inner))
    }
}
