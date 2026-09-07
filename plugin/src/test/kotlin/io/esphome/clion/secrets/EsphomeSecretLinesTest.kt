package io.esphome.clion.secrets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The value-span parsing that drives secret masking. */
class EsphomeSecretLinesTest {

    private fun value(line: String): String? =
        EsphomeSecretLines.valueColumns(line)?.let { line.substring(it.first, it.last + 1) }

    @Test
    fun `masks the trimmed value after the key`() {
        assertEquals("hunter2", value("wifi_password: hunter2"))
        assertEquals("hunter2", value("wifi_password:    hunter2   "))
        assertEquals("a-very-long-base64==", value("api_key: a-very-long-base64=="))
    }

    @Test
    fun `keeps a value that itself contains a colon`() {
        assertEquals("p@ss:word", value("pw: p@ss:word"))
    }

    @Test
    fun `ignores blank, comment and value-less lines`() {
        assertNull(value(""))
        assertNull(value("   "))
        assertNull(value("# a comment"))
        assertNull(value("  # indented comment"))
        assertNull(value("bare_key:"))
        assertNull(value("mapping:   "))
    }

    @Test
    fun `does not mask an include directive`() {
        assertNull(value("<<: !include ../secrets/location1.yaml"))
        assertNull(value("<<:   !include ../secrets/location1.yaml"))
        assertNull(value("other_key: !include foo.yaml"))
    }

    @Test
    fun `does not mask the is_secrets_file marker line`() {
        assertNull(value("is_secrets_file: true"))
        assertNull(value("is_secrets_file:   true"))
        // Nested/indented occurrences are not the marker key, and are still masked.
        assertEquals("true", value("  is_secrets_file: true"))
    }

    @Test
    fun `recognizes the is_secrets_file marker key`() {
        assertEquals(true, EsphomeSecretLines.declaresSecretsFile("is_secrets_file: true\nwifi_password: hunter2"))
        assertEquals(true, EsphomeSecretLines.declaresSecretsFile("is_secrets_file: \"true\""))
        assertEquals(true, EsphomeSecretLines.declaresSecretsFile("is_secrets_file: TRUE"))
    }

    @Test
    fun `ignores a nested or false is_secrets_file key`() {
        assertEquals(false, EsphomeSecretLines.declaresSecretsFile("wifi_password: hunter2"))
        assertEquals(false, EsphomeSecretLines.declaresSecretsFile("is_secrets_file: false"))
        assertEquals(false, EsphomeSecretLines.declaresSecretsFile("nested:\n  is_secrets_file: true"))
    }

    @Test
    fun `finds a leading front-matter block delimited by dashes`() {
        val lines = listOf("---", "is_secrets_file: true", "description: loc1", "---", "wifi_password: hunter2")
        assertEquals(0..3, EsphomeSecretLines.frontMatterLineRange(lines))
    }

    @Test
    fun `ignores dashes that are not a leading front-matter block`() {
        assertNull(EsphomeSecretLines.frontMatterLineRange(emptyList()))
        assertNull(EsphomeSecretLines.frontMatterLineRange(listOf("wifi_password: hunter2")))
        // Opening `---` with no closing `---` is not a front-matter block.
        assertNull(EsphomeSecretLines.frontMatterLineRange(listOf("---", "wifi_password: hunter2")))
        // A `---` that isn't the very first line doesn't start a front-matter block.
        assertNull(EsphomeSecretLines.frontMatterLineRange(listOf("wifi_password: hunter2", "---", "api_key: x", "---")))
    }
}
