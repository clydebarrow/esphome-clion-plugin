package io.esphome.clion.services

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Exercises the id index/service: a `platform:` id captures its domain and
 * platform, a top-level component id has no platform, and an id declared in an
 * included fragment is found within the device's connected scope.
 */
class EsphomeIdsTest : BasePlatformTestCase() {

    private fun file(path: String, content: String): VirtualFile =
        myFixture.addFileToProject(path, content).virtualFile

    private val ids get() = EsphomeIds.getInstance(project)
    private val graph get() = EsphomeIncludeGraph.getInstance(project)

    fun `test platform id captures domain and platform`() {
        val device = file(
            "p/d.yaml",
            "esphome:\n  name: x\nsensor:\n  - platform: dht\n    id: temp\n    pin: 4\n",
        )
        val decls = ids.resolve("temp", listOf(device))
        assertEquals(1, decls.size)
        assertEquals("sensor", decls[0].domain)
        assertEquals("dht", decls[0].platform)
        assertEquals(device, decls[0].file)
    }

    fun `test top-level component id has no platform`() {
        val device = file("p/d.yaml", "esphome:\n  name: x\ni2c:\n  id: bus\n  sda: 21\n")
        val decls = ids.resolve("bus", listOf(device))
        assertEquals(1, decls.size)
        assertEquals("i2c", decls[0].domain)
        assertNull(decls[0].platform)
    }

    fun `test id declared in an included fragment is found in the connected scope`() {
        val frag = file("p/frag.yaml", "i2c:\n  id: bus\n  sda: 21\n")
        val device = file("p/device.yaml", "esphome:\n  name: x\npackages:\n  base: !include frag.yaml\n")

        val scope = graph.connectedFiles(device)
        val decls = ids.resolve("bus", scope)
        assertEquals(1, decls.size)
        assertEquals(frag, decls[0].file)
        assertEquals("i2c", decls[0].domain)
    }

    fun `test templated id is indexed and expands to its effective name`() {
        val device = file(
            "p/d.yaml",
            "substitutions:\n  bus: i2c0\nesphome:\n  name: x\ni2c:\n  id: \${bus}_bus\n  sda: 21\n",
        )
        val decl = ids.declarationsIn(listOf(device)).single { it.domain == "i2c" }
        assertEquals("\${bus}_bus", decl.name)
        assertEquals("i2c0_bus", decl.effectiveName)
    }

    fun `test reference resolves to a templated declaration by effective name`() {
        // The declaration is templated; a literal reference to its expanded name resolves.
        val device = file(
            "p/d.yaml",
            "substitutions:\n  bus: i2c0\ni2c:\n  id: \${bus}_bus\n  sda: 21\n  scl: 22\n",
        )
        val decls = ids.resolve("i2c0_bus", listOf(device))
        assertEquals(1, decls.size)
        assertEquals("i2c", decls[0].domain)
    }
}
