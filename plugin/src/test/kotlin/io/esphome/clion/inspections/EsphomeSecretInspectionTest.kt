package io.esphome.clion.inspections

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The unresolved-secret inspection: flags typos, stays quiet otherwise. */
class EsphomeSecretInspectionTest : BasePlatformTestCase() {

    private fun descriptions(): List<String> =
        myFixture.doHighlighting().mapNotNull { it.description }

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(EsphomeUnresolvedSecretInspection())
    }

    fun `test misspelt secret name is flagged`() {
        myFixture.addFileToProject("secrets.yaml", "wifi_password: hunter2\n")
        myFixture.configureByText(
            "device.yaml",
            "wifi:\n  password: !secret wifi_passwrd\n",
        )
        assertTrue(descriptions().any { it == "Unresolved secret 'wifi_passwrd'" })
    }

    fun `test defined secret is not flagged`() {
        myFixture.addFileToProject("secrets.yaml", "wifi_password: hunter2\n")
        myFixture.configureByText(
            "device.yaml",
            "wifi:\n  password: !secret wifi_password\n",
        )
        assertFalse(descriptions().any { it.startsWith("Unresolved secret") })
    }

    fun `test secret defined only in a merge-included file is not flagged`() {
        myFixture.addFileToProject("secrets/location1.yaml", "project_name: my-project\n")
        myFixture.addFileToProject("secrets.yaml", "<<: !include secrets/location1.yaml\n")
        myFixture.configureByText(
            "device.yaml",
            "esphome:\n  project:\n    name: !secret project_name\n",
        )
        assertFalse(descriptions().any { it.startsWith("Unresolved secret") })
    }

    fun `test no secrets file at all means no flagging`() {
        myFixture.configureByText("device.yaml", "wifi:\n  password: !secret wifi_password\n")
        assertFalse(descriptions().any { it.startsWith("Unresolved secret") })
    }

    fun `test templated secret name is not checked`() {
        myFixture.addFileToProject("secrets.yaml", "wifi_password: hunter2\n")
        myFixture.configureByText(
            "device.yaml",
            "substitutions:\n  key: wifi\nwifi:\n  password: !secret \${key}_password\n",
        )
        assertFalse(descriptions().any { it.startsWith("Unresolved secret") })
    }

    fun `test near-match quick-fix is offered`() {
        myFixture.addFileToProject("secrets.yaml", "wifi_password: hunter2\n")
        myFixture.configureByText(
            "device.yaml",
            "wifi:\n  password: !secret wifi_passwrd\n",
        )
        val fix = myFixture.getAllQuickFixes().firstOrNull { it.text == "Change to 'wifi_password'" }
        assertNotNull(fix)
        myFixture.launchAction(fix!!)
        assertTrue(myFixture.file.text.contains("!secret wifi_password"))
    }
}
