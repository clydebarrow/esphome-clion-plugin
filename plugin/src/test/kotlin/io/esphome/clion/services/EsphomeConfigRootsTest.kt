package io.esphome.clion.services

import com.intellij.execution.RunManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.esphome.clion.run.EsphomeRunConfiguration
import io.esphome.clion.run.EsphomeRunConfigurationType

/**
 * Top-level-config identification: a file is top-level iff nothing `!include`s it
 * (so a shared package with an `esphome:` block is *not* top-level), and the
 * ambiguous root of a shared package is resolved via the selected run config.
 */
class EsphomeConfigRootsTest : BasePlatformTestCase() {

    fun `test a fragment resolves to the device root that includes it`() {
        val device = myFixture.addFileToProject(
            "p/device.yaml",
            "esphome:\n  name: x\nwifi: !include wifi.yaml\n",
        ).virtualFile
        val wifi = myFixture.addFileToProject("p/wifi.yaml", "wifi:\n  ssid: x\n").virtualFile
        assertEquals(device, EsphomeConfigRoots.effectiveRoot(project, wifi))
    }

    fun `test a nested include chain resolves to the root`() {
        val sub = myFixture.addFileToProject("p/sub.yaml", "binary_sensor:\n  - platform: gpio\n").virtualFile
        myFixture.addFileToProject("p/mid.yaml", "sensor: !include sub.yaml\n")
        val device = myFixture.addFileToProject(
            "p/device.yaml",
            "esphome:\n  name: x\npackages:\n  base: !include mid.yaml\n",
        ).virtualFile
        assertEquals(device, EsphomeConfigRoots.effectiveRoot(project, sub))
    }

    fun `test a top-level config is its own root`() {
        val device = myFixture.addFileToProject("p/device.yaml", "esphome:\n  name: x\n").virtualFile
        assertEquals(device, EsphomeConfigRoots.effectiveRoot(project, device))
        assertTrue(EsphomeConfigRoots.isTopLevel(project, device))
    }

    fun `test an included package is not top-level even with an esphome block`() {
        val pkg = myFixture.addFileToProject("p/standard.yaml", "esphome:\n  name: \${name}\n").virtualFile
        val device = myFixture.addFileToProject(
            "p/device.yaml",
            "substitutions:\n  name: dev1\npackages:\n  std: !include standard.yaml\n",
        ).virtualFile
        // standard.yaml has an esphome: block but is included, so it's a package.
        assertFalse(EsphomeConfigRoots.isTopLevel(project, pkg))
        assertEquals(device, EsphomeConfigRoots.effectiveRoot(project, pkg))
    }

    fun `test an orphan fragment has no root`() {
        val orphan = myFixture.addFileToProject("p/orphan.yaml", "sensor:\n  - platform: dht\n").virtualFile
        assertNull(EsphomeConfigRoots.effectiveRoot(project, orphan))
    }

    fun `test a shared package prefers the selected run configuration's device`() {
        val pkg = myFixture.addFileToProject("p/standard.yaml", "esphome:\n  name: \${name}\n").virtualFile
        myFixture.addFileToProject(
            "p/dev-a.yaml",
            "substitutions:\n  name: a\npackages:\n  std: !include standard.yaml\n",
        )
        val devB = myFixture.addFileToProject(
            "p/dev-b.yaml",
            "substitutions:\n  name: b\npackages:\n  std: !include standard.yaml\n",
        ).virtualFile

        // Select a run config pointing at dev-b; the package should resolve to it.
        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration("b", EsphomeRunConfigurationType().configurationFactories[0])
        (settings.configuration as EsphomeRunConfiguration).configPath = devB.path
        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings

        assertEquals(devB, EsphomeConfigRoots.effectiveRoot(project, pkg))
    }
}
