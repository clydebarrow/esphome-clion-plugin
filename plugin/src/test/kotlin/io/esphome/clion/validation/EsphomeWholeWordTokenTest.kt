package io.esphome.clion.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Whole-word token matching used to anchor warnings (e.g. a strapping-pin pin). */
class EsphomeWholeWordTokenTest {

    @Test
    fun `GPIO3 does not match inside GPIO38`() {
        // The bug: a GPIO3 strapping-pin warning highlighted GPIO38.
        assertEquals(-1, EsphomeValidationAnnotator.wholeWordIndex("  number: GPIO38\n", "GPIO3"))
    }

    @Test
    fun `GPIO3 matches a whole-word GPIO3 even when GPIO38 precedes it`() {
        val text = "a: GPIO38\nb: GPIO3\n"
        assertEquals(text.indexOf("GPIO3", text.indexOf("GPIO38") + 1), EsphomeValidationAnnotator.wholeWordIndex(text, "GPIO3"))
    }

    @Test
    fun `a name token does not match as a substring`() {
        assertEquals(-1, EsphomeValidationAnnotator.wholeWordIndex("id: my_sensor", "sensor"))
        assertEquals(4, EsphomeValidationAnnotator.wholeWordIndex("id: sensor", "sensor"))
    }

    @Test
    fun `platform value anchors on the platform line, not an earlier occurrence`() {
        // The bug: "Platform not found: 'cover.apc_proteous'" highlighted the first
        // occurrence (in external_components) instead of the cover platform line.
        val text = """
            external_components:
              - source: github://x/apc_proteous
                components: [apc_proteous]

            cover:
              - platform: apc_proteous
                name: Gate
        """.trimIndent()
        val range = EsphomeValidationAnnotator.platformValueRange(text, "apc_proteous")
        assertNotNull(range)
        // It must be the occurrence on the `- platform:` line, i.e. the last one.
        assertEquals(text.lastIndexOf("apc_proteous"), range!!.first)
        assertEquals("apc_proteous", text.substring(range.first, range.last + 1))
    }

    @Test
    fun `platform value tolerates quotes and a trailing comment`() {
        assertEquals(
            "sdl",
            "  - platform: \"sdl\"  # host build\n".let {
                val r = EsphomeValidationAnnotator.platformValueRange(it, "sdl")!!
                it.substring(r.first, r.last + 1)
            },
        )
    }

    @Test
    fun `platform value returns null when the token is not a platform declaration`() {
        assertNull(EsphomeValidationAnnotator.platformValueRange("components: [apc_proteous]\n", "apc_proteous"))
    }
}
