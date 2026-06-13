package io.esphome.clion.documentation

import io.esphome.clion.catalog.ComponentCatalogIndexEntry
import io.esphome.clion.catalog.ConfigEntry
import io.esphome.clion.catalog.ConfigEntryType
import io.esphome.clion.catalog.ConfigValueOption
import io.esphome.clion.psi.EsphomeTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rendering tests (no PSI / IDE). */
class EsphomeDocRendererTest {

    @Test
    fun `renders a field with type, requiredness and markdown description`() {
        val entry = ConfigEntry(
            key = "ssid",
            type = ConfigEntryType.STRING,
            label = "SSID",
            description = "The `WiFi` network name. See [the docs](https://esphome.io/components/wifi).",
            required = true,
            helpLink = "https://esphome.io/components/wifi#configuration-variables",
        )
        val html = EsphomeDocRenderer.render(EsphomeTarget.Field(entry, "wifi"))

        assertTrue(html.contains("<b>ssid</b>"))
        assertTrue(html.contains("string"))
        assertTrue(html.contains("yes")) // Required: yes
        // markdown -> html
        assertTrue(html.contains("<code>WiFi</code>"))
        assertTrue(html.contains("""<a href="https://esphome.io/components/wifi">the docs</a>"""))
        // docs section link
        assertTrue(html.contains("configuration-variables"))
    }

    @Test
    fun `renders enum values and escapes html`() {
        val entry = ConfigEntry(
            key = "mode",
            type = ConfigEntryType.STRING,
            label = "Mode",
            description = "Use <b>care</b> & attention",
            options = listOf(ConfigValueOption("Off", "ALWAYS_OFF"), ConfigValueOption("On", "ALWAYS_ON")),
        )
        val html = EsphomeDocRenderer.render(EsphomeTarget.Field(entry, "switch.gpio"))

        assertTrue(html.contains("ALWAYS_OFF"))
        assertTrue(html.contains("switch.gpio"))
        // raw angle brackets from the description must be escaped, not injected
        assertTrue(html.contains("&lt;b&gt;care&lt;/b&gt;"))
        assertFalse(html.contains("<b>care</b>"))
    }

    @Test
    fun `renders a component`() {
        val index = ComponentCatalogIndexEntry(
            id = "wifi",
            name = "WiFi Component",
            description = "Sets up WiFi connections.",
            docsUrl = "https://esphome.io/components/wifi",
            dependencies = listOf("network"),
        )
        val html = EsphomeDocRenderer.render(EsphomeTarget.Component(index, body = null))

        assertTrue(html.contains("<b>WiFi Component</b>"))
        assertTrue(html.contains("Sets up WiFi connections."))
        assertTrue(html.contains("<code>wifi</code>"))
        assertTrue(html.contains("<code>network</code>")) // dependency
    }

    @Test
    fun `renders a platform domain with its platforms and docs link`() {
        val html = EsphomeDocRenderer.render(EsphomeTarget.Domain("sensor", listOf("dht", "adc", "bme280")))

        assertTrue(html.contains("<b>sensor</b>"))
        assertTrue(html.contains("Platform domain"))
        assertTrue(html.contains("Platforms (3)"))
        assertTrue(html.contains("<code>dht</code>"))
        assertTrue(html.contains("""<a href="https://esphome.io/components/sensor/">"""))
    }
}
