package io.esphome.clion.catalog.lang

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves the language-schema converter resolves `extends` lazily and survives
 * recursion — the two things device-builder's flattening can't do. The synthetic
 * fixture has a recursive widget schema (WIDGETS → obj → OBJBASE → widgets →
 * WIDGETS); a flattener would expand it forever.
 */
class LangSchemaRepositoryTest {

    // demo component: CONFIG_SCHEMA extends BASE and has a recursive widgets: list.
    private val fixture = """
    {
      "demo": {
        "schemas": {
          "CONFIG_SCHEMA": {"type":"schema","schema":{
            "config_vars": {
              "name": {"key":"Optional","type":"string"},
              "widgets": {"is_list":true,"type":"schema","schema":{"extends":["demo.WIDGETS"]}}
            },
            "extends": ["demo.BASE"]
          }},
          "BASE": {"type":"schema","schema":{"config_vars":{"id":{"key":"Optional"}}}},
          "WIDGETS": {"type":"schema","schema":{"config_vars":{
            "obj": {"type":"schema","schema":{"config_vars":{"width":{"key":"Optional"}},"extends":["demo.OBJBASE"]}}
          }}},
          "OBJBASE": {"type":"schema","schema":{"config_vars":{
            "bg_color": {"key":"Optional"},
            "widgets": {"is_list":true,"type":"schema","schema":{"extends":["demo.WIDGETS"]}}
          }}}
        }
      }
    }
    """.trimIndent()

    private fun repo() = LangSchemaRepository(LangSchemaRepository.parseDomains(fixture))

    @Test
    fun `own keys plus extended keys resolve`() {
        val keys = repo().keysAt("demo").keys
        assertTrue(keys.containsAll(setOf("name", "id", "widgets")), "root keys: $keys")
    }

    @Test
    fun `nested widget keys resolve through extends, recursion terminates`() {
        val r = repo()
        assertTrue("obj" in r.keysAt("demo", listOf("widgets")).keys)
        // obj's own width + bg_color from OBJBASE + a nested widgets: from OBJBASE.
        val objKeys = r.keysAt("demo", listOf("widgets", "obj")).keys
        assertTrue(objKeys.containsAll(setOf("width", "bg_color", "widgets")), "obj keys: $objKeys")
        // Recurse one level deeper — the shared WIDGETS ref is cycle-guarded, so
        // this returns obj again instead of looping forever.
        val deep = r.keysAt("demo", listOf("widgets", "obj", "widgets", "obj")).keys
        assertTrue(deep.containsAll(setOf("width", "bg_color")), "deep keys: $deep")
    }

    /**
     * Real-data check against a regenerated language-schema dir, e.g.:
     *   ./gradlew :catalog:test -Dlangschema.dir=/tmp/lvglW --tests '*LangSchema*'
     * Skipped when the property is unset so it never gates CI.
     */
    @Test
    fun `real lvgl schema resolves widgets and widget props`() {
        val dir = System.getProperty("langschema.dir")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val repo = LangSchemaRepository.fromBundle(
            dir.listFiles { f -> f.extension == "json" }!!.map { it.readText() },
        )
        val root = repo.keysAt("lvgl").keys
        assertTrue(root.containsAll(setOf("displays", "widgets", "bg_color")), "lvgl root: ${root.size} keys")
        val widgetTypes = repo.keysAt("lvgl", listOf("widgets")).keys
        assertTrue(widgetTypes.size > 20, "widget types: ${widgetTypes.size}")
        val labelKeys = repo.keysAt("lvgl", listOf("widgets", "label")).keys
        assertTrue(labelKeys.size > 50, "label keys: ${labelKeys.size}")
        println("REAL lvgl: root=${root.size} widgets=${widgetTypes.size} labelProps=${labelKeys.size}")
    }
}
