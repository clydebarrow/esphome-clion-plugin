package io.esphome.clion.references

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** `!secret <name>` resolves to its declaration in the nearest secrets.yaml. */
class EsphomeSecretReferenceTest : BasePlatformTestCase() {

    fun `test secret use resolves to the secrets file declaration`() {
        myFixture.addFileToProject(
            "secrets.yaml",
            "wifi_password: hunter2\napi_key: abc123\n",
        )
        val text = """
            esphome:
              name: x
            wifi:
              ssid: Home
              password: !secret wifi_password
        """.trimIndent()
        myFixture.configureByText("device.yaml", text)

        val offset = text.indexOf("wifi_password", text.indexOf("!secret"))
        val resolved = myFixture.file.findReferenceAt(offset + 1)?.resolve()
        assertNotNull("!secret should resolve", resolved)
        assertEquals("secrets.yaml", resolved!!.containingFile.name)
        assertTrue(resolved.text.contains("wifi_password"))
    }

    fun `test unknown secret does not resolve`() {
        myFixture.addFileToProject("secrets.yaml", "wifi_password: hunter2\n")
        val text = """
            esphome:
              name: x
            wifi:
              password: !secret nonexistent
        """.trimIndent()
        myFixture.configureByText("device.yaml", text)

        val offset = text.indexOf("nonexistent")
        assertNull(myFixture.file.findReferenceAt(offset + 1)?.resolve())
    }
}
