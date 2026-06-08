package io.esphome.clion.catalog

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The derived views the completion contributor relies on: top-level key set,
 * platform domains, and per-domain platforms. Fixture index contains `wifi`
 * (core), `i2c` (bus), `switch.gpio`, `sensor.dht`.
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
        // wifi + i2c (non-platform) and switch + sensor (domains).
        assertEquals(setOf("wifi", "i2c", "switch", "sensor"), repo().topLevelKeys)
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
