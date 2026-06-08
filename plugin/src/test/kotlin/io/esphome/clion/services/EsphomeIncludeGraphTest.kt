package io.esphome.clion.services

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Exercises the include-graph service against real YAML files indexed by the
 * fixture: forward/reverse edges resolved relative to each file's directory, and
 * the connected graph that spans nested includes from a device root.
 */
class EsphomeIncludeGraphTest : BasePlatformTestCase() {

    private fun file(path: String, content: String): VirtualFile =
        myFixture.addFileToProject(path, content).virtualFile

    private val graph get() = EsphomeIncludeGraph.getInstance(project)

    fun `test direct includes and includers resolve relative to file directory`() {
        val wifi = file("proj/common/wifi.yaml", "wifi:\n  ssid: x\n")
        val device = file("proj/device.yaml", "esphome:\n  name: x\nwifi: !include common/wifi.yaml\n")

        assertEquals(listOf(wifi), graph.directIncludes(device))
        assertEquals(listOf(device), graph.directIncluders(wifi))
    }

    fun `test same-named file in another directory is not a false includer`() {
        val realWifi = file("proj/common/wifi.yaml", "wifi:\n  ssid: x\n")
        file("other/wifi.yaml", "wifi:\n  ssid: y\n") // same basename, different dir
        val device = file("proj/device.yaml", "esphome:\n  name: x\nwifi: !include common/wifi.yaml\n")

        assertEquals(listOf(device), graph.directIncluders(realWifi))
    }

    fun `test connected files span nested includes from the root`() {
        val sub = file("p/sub.yaml", "binary_sensor:\n  - platform: gpio\n    pin: 4\n")
        val mid = file("p/mid.yaml", "sensor: !include sub.yaml\n")
        val device = file("p/device.yaml", "esphome:\n  name: x\npackages:\n  base: !include mid.yaml\n")

        assertEquals(setOf(device), graph.rootsOf(sub))
        assertEquals(setOf(device, mid, sub), graph.connectedFiles(sub))
        assertEquals(setOf(device, mid, sub), graph.connectedFiles(device))
    }
}
