package io.esphome.clion.run

import org.junit.Assert.assertEquals
import org.junit.Test

/** The version field maps to valid `pip install` arguments (e.g. `dev` is not `==dev`). */
class EsphomeVenvTest {

    @Test
    fun `version field maps to pip requirement`() {
        assertEquals(listOf("esphome"), EsphomeVenv.pipRequirement(""))
        assertEquals(listOf("esphome"), EsphomeVenv.pipRequirement("latest"))
        assertEquals(listOf("--pre", "esphome"), EsphomeVenv.pipRequirement("beta"))
        assertEquals(listOf("esphome==2025.7.0"), EsphomeVenv.pipRequirement("2025.7.0"))
        // pre-release versions are still valid specifiers.
        assertEquals(listOf("esphome==2025.7.0b1"), EsphomeVenv.pipRequirement("2025.7.0b1"))
        // a branch/tag name installs from git (a bare `==dev` is invalid pip).
        assertEquals(
            listOf("git+https://github.com/esphome/esphome.git@dev"),
            EsphomeVenv.pipRequirement("dev"),
        )
    }
}
