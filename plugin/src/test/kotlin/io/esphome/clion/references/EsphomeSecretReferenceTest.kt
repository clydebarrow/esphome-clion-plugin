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

    fun `test secret defined only in a merge-included file resolves`() {
        myFixture.addFileToProject("secrets/location1.yaml", "wifi_password: hunter2\n")
        myFixture.addFileToProject(
            "secrets.yaml",
            "<<: !include secrets/location1.yaml\napi_key: abc123\n",
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
        assertNotNull("!secret should resolve into the merge-included file", resolved)
        assertEquals("location1.yaml", resolved!!.containingFile.name)
    }

    fun `test secret resolves into an included file that has a front-matter block`() {
        // The included file uses the plugin's own `is_secrets_file` front-matter
        // convention (--- ... ---), which makes it a multi-document YAML stream —
        // the actual secrets must still be found in the document after it.
        myFixture.addFileToProject(
            "secrets/location1.yaml",
            "---\nis_secrets_file: true\n---\nproject_name: my-project\n",
        )
        myFixture.addFileToProject(
            "secrets.yaml",
            "<<: !include secrets/location1.yaml\napi_key: abc123\n",
        )
        val text = """
            esphome:
              project:
                name: !secret project_name
        """.trimIndent()
        myFixture.configureByText("device.yaml", text)

        val offset = text.indexOf("project_name", text.indexOf("!secret"))
        val resolved = myFixture.file.findReferenceAt(offset + 1)?.resolve()
        assertNotNull("!secret should resolve past the front-matter document", resolved)
        assertEquals("location1.yaml", resolved!!.containingFile.name)
    }

    fun `test secret resolves when the top-level secrets_yaml itself has a front-matter block`() {
        myFixture.addFileToProject(
            "secrets.yaml",
            "---\nis_secrets_file: true\n---\nwifi_password: hunter2\n",
        )
        val text = """
            wifi:
              password: !secret wifi_password
        """.trimIndent()
        myFixture.configureByText("device.yaml", text)

        val offset = text.indexOf("wifi_password", text.indexOf("!secret"))
        val resolved = myFixture.file.findReferenceAt(offset + 1)?.resolve()
        assertNotNull("!secret should resolve past secrets.yaml's own front-matter document", resolved)
    }

    fun `test a key defined directly in secrets_yaml takes precedence over the included file`() {
        myFixture.addFileToProject("secrets/location1.yaml", "wifi_password: from-include\n")
        myFixture.addFileToProject(
            "secrets.yaml",
            "<<: !include secrets/location1.yaml\nwifi_password: from-top-level\n",
        )
        val text = """
            esphome:
              name: x
            wifi:
              password: !secret wifi_password
        """.trimIndent()
        myFixture.configureByText("device.yaml", text)

        val offset = text.indexOf("wifi_password", text.indexOf("!secret"))
        val resolved = myFixture.file.findReferenceAt(offset + 1)?.resolve()
        assertEquals("secrets.yaml", resolved?.containingFile?.name)
    }
}
