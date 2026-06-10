package io.esphome.clion.api

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Connection-target derivation from a config (and its package include). */
class EsphomeApiTargetTest : BasePlatformTestCase() {

    fun `test derives host, port, key from use_address and api block`() {
        val file = myFixture.configureByText(
            "device.yaml",
            """
            esphome:
              name: bedroom
            wifi:
              use_address: 10.0.0.42
            api:
              port: 6052
              encryption:
                key: c2VjcmV0a2V5
            """.trimIndent(),
        )
        val t = EsphomeApiTarget.forFile(project, file.virtualFile)
        assertEquals("10.0.0.42", t.host)
        assertEquals(6052, t.port)
        assertEquals("c2VjcmV0a2V5", t.encryptionKey)
        assertTrue(t.hasApi)
    }

    fun `test falls back to name dot local and default port`() {
        val file = myFixture.configureByText(
            "device.yaml",
            """
            esphome:
              name: porch
            api:
            """.trimIndent(),
        )
        val t = EsphomeApiTarget.forFile(project, file.virtualFile)
        assertEquals("porch.local", t.host)
        assertEquals(6053, t.port)
        assertTrue(t.hasApi)
    }

    fun `test expands substitutions in the device name`() {
        val file = myFixture.configureByText(
            "device.yaml",
            """
            substitutions:
              dev: kitchen
            esphome:
              name: ${'$'}{dev}
            api:
            """.trimIndent(),
        )
        assertEquals("kitchen.local", EsphomeApiTarget.forFile(project, file.virtualFile).host)
    }

    fun `test reads api and name from an included package`() {
        myFixture.addFileToProject(
            "common/base.yaml",
            "esphome:\n  name: attic\napi:\n  encryption:\n    key: YWJjZGVm\n",
        )
        val device = myFixture.addFileToProject(
            "common/device.yaml",
            "packages:\n  base: !include base.yaml\nsensor:\n  - platform: template\n    id: x\n",
        )
        myFixture.configureFromExistingVirtualFile(device.virtualFile)
        val t = EsphomeApiTarget.forFile(project, device.virtualFile)
        assertEquals("attic.local", t.host)
        assertEquals("YWJjZGVm", t.encryptionKey)
        assertTrue(t.hasApi)
    }

    fun `test no api block is reported`() {
        val file = myFixture.configureByText("device.yaml", "esphome:\n  name: x\n")
        val t = EsphomeApiTarget.forFile(project, file.virtualFile)
        assertFalse(t.hasApi)
        assertEquals("x.local", t.host)
    }
}
