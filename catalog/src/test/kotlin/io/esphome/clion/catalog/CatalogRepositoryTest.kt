package io.esphome.clion.catalog

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The derived views the completion contributor relies on: top-level key set,
 * platform domains, and per-domain platforms. Fixture index contains `wifi`
 * (core), `i2c` (bus), `switch.gpio`, `sensor.dht`, `lvgl` (misc).
 */
class CatalogRepositoryTest {

    private fun repo(): CatalogRepository {
        val url = checkNotNull(javaClass.classLoader.getResource("definitions"))
        return CatalogRepository(Path.of(url.toURI()))
    }

    @Test
    fun `domains are derived from platform ids only`() {
        val repo = repo()
        assertEquals(setOf("switch", "sensor"), repo.domains)
        assertTrue(repo.isDomain("sensor"))
        // A non-platform component is not a domain.
        assertFalse(repo.isDomain("wifi"))
    }

    @Test
    fun `top-level keys are non-platform components plus domains`() {
        // wifi + i2c + lvgl (non-platform) and switch + sensor (domains).
        assertEquals(setOf("wifi", "i2c", "lvgl", "switch", "sensor"), repo().topLevelKeys)
    }

    /**
     * lvgl is the stress case for the catalog: upstream its schema is generated
     * from `extends` chains (the base obj style/state schema reused across every
     * widget/part/state), which device-builder flattens into a config_entries
     * list before we vendor it. This mirrors the shipped lvgl body (the build's
     * `overlayCatalog` slice) and asserts it exposes its top-level fields with the
     * right types, so completion/validation of the `lvgl:` block actually works.
     *
     * Note `displays`' `references_component` is supplied by the overlay: lvgl
     * declares it via a custom `display_schema` validator the upstream schema
     * dumper can't see through, so device-builder emits a bare `id` with no target
     * class. The overlay restores the `display` target so id navigation works.
     */
    @Test
    fun `lvgl exposes flattened top-level fields with resolved types`() {
        val lvgl = checkNotNull(repo().component("lvgl")) { "lvgl body missing" }
        val byKey = checkNotNull(lvgl.configEntries).associateBy { it.key }

        // displays is a typed id reference to display ids — type ID *and*
        // references_component "display" together drive id navigation/completion.
        val displays = byKey.getValue("displays")
        assertEquals(ConfigEntryType.ID, displays.type)
        assertEquals("display", displays.referencesComponent)
        assertEquals(ConfigEntryType.STRING, byKey.getValue("bg_color").type)
        assertEquals(ConfigEntryType.BOOLEAN, byKey.getValue("full_refresh").type)
        // color_depth resolved its `one_of(16)` into an options list.
        assertTrue(byKey.getValue("color_depth").options?.isNotEmpty() == true)

        // style_definitions is a nested block whose children resolved through the
        // FULL_STYLE_SCHEMA `extends` — reachable via the nested-path API.
        assertEquals(ConfigEntryType.NESTED, byKey.getValue("style_definitions").type)
        val childKeys = lvgl.childEntriesAt(listOf("style_definitions")).map { it.key }
        assertTrue("border_width" in childKeys, "style child keys: $childKeys")
    }

    @Test
    fun `platforms are listed under their domain`() {
        val repo = repo()
        assertEquals(listOf("dht"), repo.platformsFor("sensor"))
        assertEquals(listOf("gpio"), repo.platformsFor("switch"))
        assertTrue(repo.platformsFor("wifi").isEmpty())
    }

    @Test
    fun `classpath-style source is interchangeable with file source`() {
        // Same data, read through a custom CatalogSource instead of a Path.
        val fileRepo = repo()
        val proxied = CatalogRepository(
            CatalogSource { rel -> FileCatalogSource(definitionsDir()).read(rel) },
        )
        assertEquals(fileRepo.topLevelKeys, proxied.topLevelKeys)
        assertEquals(fileRepo.schemaVersion, proxied.schemaVersion)
    }

    private fun definitionsDir(): Path =
        Path.of(checkNotNull(javaClass.classLoader.getResource("definitions")).toURI())
}
