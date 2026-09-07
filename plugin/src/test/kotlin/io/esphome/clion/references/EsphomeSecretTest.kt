package io.esphome.clion.references

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** A file `<<: !include`d by a secrets file is itself treated as one. */
class EsphomeSecretTest : BasePlatformTestCase() {

    fun `test file merge-included from secrets_yaml is recognized as a secrets file`() {
        val included = myFixture.addFileToProject("secrets/location1.yaml", "wifi_password: hunter2\n").virtualFile
        myFixture.addFileToProject(
            "secrets.yaml",
            "<<: !include secrets/location1.yaml\napi_key: abc123\n",
        )
        assertTrue(EsphomeSecret.isIncludedBySecretsFile(project, included))
    }

    fun `test file merge-included from an is_secrets_file-marked file is recognized`() {
        val included = myFixture.addFileToProject("secrets/location1.yaml", "wifi_password: hunter2\n").virtualFile
        myFixture.addFileToProject(
            "my_secrets.yaml",
            "is_secrets_file: true\n<<: !include secrets/location1.yaml\n",
        )
        assertTrue(EsphomeSecret.isIncludedBySecretsFile(project, included))
    }

    fun `test a file only regularly included (not via the merge key) is not recognized`() {
        val included = myFixture.addFileToProject("wifi.yaml", "ssid: Home\n").virtualFile
        myFixture.addFileToProject(
            "secrets.yaml",
            "wifi_block: !include wifi.yaml\napi_key: abc123\n",
        )
        assertFalse(EsphomeSecret.isIncludedBySecretsFile(project, included))
    }

    fun `test a file included from a non-secrets file is not recognized`() {
        val included = myFixture.addFileToProject("secrets/location1.yaml", "wifi_password: hunter2\n").virtualFile
        myFixture.addFileToProject(
            "device.yaml",
            "<<: !include secrets/location1.yaml\n",
        )
        assertFalse(EsphomeSecret.isIncludedBySecretsFile(project, included))
    }
}
