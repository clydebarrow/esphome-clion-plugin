package io.esphome.clion.validation

import org.junit.Assert.assertEquals
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
}
