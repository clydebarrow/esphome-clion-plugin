package io.esphome.clion.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.esphome.clion.catalog.lang.LangSchemaRepository

/**
 * Application-level holder for the raw-language-schema [LangSchemaRepository],
 * used for components device-builder's flat catalog can't represent — currently
 * just **lvgl**, whose widget schemas are deeply recursive (a widget contains
 * widgets) and whose `extends`-heavy styles flatten to ~15 MB. Keeping the
 * `extends` refs and resolving them lazily keeps lvgl at ~1.8 MB and makes the
 * recursion a non-issue. Everything else still comes from [EsphomeCatalogService].
 *
 * The bundled schema is a stopgap (generated from the patched esphome
 * `lvgl-schema` branch) until an esphome-schema release carries the fix.
 */
@Service
class EsphomeLangSchemaService {

    val repository: LangSchemaRepository by lazy {
        val texts = FILES.mapNotNull { name ->
            javaClass.getResourceAsStream("$RESOURCE_ROOT/$name")
                ?.use { it.reader(Charsets.UTF_8).readText() }
        }
        LangSchemaRepository.fromBundle(texts)
    }

    /** Components driven by the language schema rather than the flat catalog. */
    fun handles(componentId: String): Boolean = componentId in LANG_COMPONENTS

    companion object {
        private const val RESOURCE_ROOT = "/esphome/langschema"
        // lvgl.json carries lvgl + its platforms; esphome.json supplies the
        // `core.*` schemas lvgl extends (COMPONENT_SCHEMA, time periods, …).
        private val FILES = listOf("lvgl.json", "esphome.json")
        private val LANG_COMPONENTS = setOf("lvgl")

        fun getInstance(): EsphomeLangSchemaService = service()
    }
}
