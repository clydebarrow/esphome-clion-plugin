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
}
