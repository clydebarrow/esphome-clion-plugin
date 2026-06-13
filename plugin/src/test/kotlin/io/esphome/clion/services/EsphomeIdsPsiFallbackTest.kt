package io.esphome.clion.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The PSI-scan fallback used when a config is opened outside the project's
 * content roots (so the file-based index doesn't cover it). It must find the
 * same `id:` declarations the index would — including a font id referenced by an
 * lvgl widget, the reported case.
 */
class EsphomeIdsPsiFallbackTest : BasePlatformTestCase() {

    fun `test PSI scan finds font and component ids with their domains`() {
        val file = myFixture.configureByText(
            "reterminal.yaml",
            """
            esphome:
              name: x
            font:
              - file: "gfonts://Roboto"
                id: font_date
                size: 28
            sensor:
              - platform: template
                id: my_temp
            lvgl:
              pages:
                - id: main_page
                  widgets:
                    - label:
                        id: lbl_date
                        text_font: font_date
            """.trimIndent(),
        ).virtualFile

        val ids = EsphomeIds.getInstance(project).declarationsByPsi(listOf(file))
        val byName = ids.associateBy { it.effectiveName }

        assertEquals("font", byName["font_date"]?.domain)
        assertEquals("sensor", byName["my_temp"]?.domain)
        assertEquals("lvgl", byName["main_page"]?.domain)
        assertEquals("lvgl", byName["lbl_date"]?.domain)
        // text_font's value is a use, not a declaration — not collected.
        assertEquals(setOf("font_date", "my_temp", "main_page", "lbl_date"), byName.keys)
    }
}
