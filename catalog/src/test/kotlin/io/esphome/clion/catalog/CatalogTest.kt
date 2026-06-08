package io.esphome.clion.catalog

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the catalog model + loader against real Device Builder
 * `definitions/` fixtures (the actual baked `wifi.json`, `switch.gpio.json`,
 * `i2c.json` from ESPHome schema 2026.5.3), plus a hand-written index.
 */
class CatalogTest {

    private fun definitionsDir(): Path {
        val url = checkNotNull(javaClass.classLoader.getResource("definitions")) {
            "definitions fixtures not found on the test classpath"
        }
        return Path.of(url.toURI())
    }

    private fun repo() = CatalogRepository(definitionsDir())

    @Test
    fun `index parses with schema version and resolved platform ids`() {
        val repo = repo()
        assertEquals("2026.5.3", repo.schemaVersion)
        assertTrue("wifi" in repo.componentIds())
        // The sensor:/platform: discriminator is pre-resolved into its own id.
        assertTrue("sensor.dht" in repo.componentIds())

        val dht = assertNotNull(repo.indexEntry("sensor.dht"))
        assertEquals("sensor", dht.domain)
        assertEquals("dht", dht.platform)
        assertEquals(ComponentCategory.SENSOR, dht.category)

        // A non-platform core component has no platform segment.
        assertNull(assertNotNull(repo.indexEntry("wifi")).platform)
    }

    @Test
    fun `wifi body parses primitive, secure and nested fields`() {
        val wifi = assertNotNull(repo().component("wifi"))
        assertEquals(ComponentCategory.CORE, wifi.category)

        val ssid = assertNotNull(wifi.configEntries.firstOrNull { it.key == "ssid" })
        assertEquals(ConfigEntryType.STRING, ssid.type)

        val password = assertNotNull(wifi.configEntries.firstOrNull { it.key == "password" })
        assertEquals(ConfigEntryType.SECURE_STRING, password.type)

        val ap = assertNotNull(wifi.configEntries.firstOrNull { it.key == "ap" })
        assertEquals(ConfigEntryType.NESTED, ap.type)
        assertTrue(ap.isContainer)
        assertNotNull(ap.configEntries)
    }

    @Test
    fun `nested path resolution walks into containers`() {
        val wifi = assertNotNull(repo().component("wifi"))

        // wifi: -> ap: -> channel:
        val channel = assertNotNull(wifi.entryAtPath(listOf("ap", "channel")))
        assertEquals(ConfigEntryType.INTEGER, channel.type)
        assertEquals(listOf(1.0, 14.0), channel.range)

        // Children offered for completion directly under `ap:`.
        val apChildren = wifi.childEntriesAt(listOf("ap")).map { it.key }
        assertTrue("ssid" in apChildren && "channel" in apChildren)

        // A bogus path resolves to nothing rather than throwing.
        assertNull(wifi.entryAtPath(listOf("ap", "does_not_exist")))
    }

    @Test
    fun `pin and option fields decode with their constraints`() {
        val gpio = assertNotNull(repo().component("switch.gpio"))

        val pin = assertNotNull(gpio.configEntries.firstOrNull { it.key == "pin" })
        assertEquals(ConfigEntryType.PIN, pin.type)
        assertEquals(PinMode.OUTPUT, pin.pinMode)
        assertTrue(pin.required)

        val restoreMode = assertNotNull(gpio.configEntries.firstOrNull { it.key == "restore_mode" })
        val options = assertNotNull(restoreMode.options)
        assertEquals(7, options.size)
    }

    @Test
    fun `unknown enum values coerce instead of throwing`() {
        // A future ConfigEntryType the model does not know yet must not break parsing.
        val body = """
            {
              "id": "future.widget",
              "config_entries": [
                { "key": "mystery", "type": "holographic_input" },
                { "key": "name", "type": "string" }
              ]
            }
        """.trimIndent()
        val entry = EsphomeCatalog.parseComponent(body)
        assertEquals(ConfigEntryType.UNKNOWN, entry.configEntries[0].type)
        assertEquals(ConfigEntryType.STRING, entry.configEntries[1].type)
    }
}
