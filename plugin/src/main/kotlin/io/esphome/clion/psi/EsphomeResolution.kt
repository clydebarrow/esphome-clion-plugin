package io.esphome.clion.psi

import com.intellij.psi.util.parentOfType
import io.esphome.clion.catalog.CatalogRepository
import io.esphome.clion.catalog.ComponentCatalogEntry
import io.esphome.clion.catalog.ComponentCatalogIndexEntry
import io.esphome.clion.catalog.ConfigEntry
import io.esphome.clion.catalog.childEntriesAt
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/** What an existing YAML key resolves to in the catalog. */
sealed interface EsphomeTarget {
    /** A top-level component block (`wifi:`, `i2c:`). */
    data class Component(
        val index: ComponentCatalogIndexEntry,
        val body: ComponentCatalogEntry?,
    ) : EsphomeTarget

    /** A field within a component (`ssid`, `ap.channel`, `restore_mode`). */
    data class Field(val entry: ConfigEntry, val componentId: String) : EsphomeTarget
}

/**
 * Resolves an *existing* YAML key to its catalog target. Shared by the
 * documentation provider (and usable by future inspections / go-to-definition).
 * Distinct from completion, which resolves the *container* a new key goes into.
 */
object EsphomeResolution {

    fun resolveKey(repo: CatalogRepository, keyValue: YAMLKeyValue): EsphomeTarget? {
        val key = keyValue.keyText.ifEmpty { return null }
        val path = EsphomeYaml.pathOfMapping(keyValue.parentOfType<YAMLMapping>())

        if (path.isEmpty()) {
            // Top-level. A platform domain (`sensor:`) is a category, not a single
            // component, so it has no one doc target.
            if (repo.isDomain(key)) return null
            val index = repo.indexEntry(key) ?: return null
            return EsphomeTarget.Component(index, repo.component(key))
        }

        val first = path.first()
        val componentId: String
        val nestedPath: List<String>
        if (repo.isDomain(first)) {
            val platform = EsphomeYaml.platformOf(keyValue) ?: return null
            componentId = "$first.$platform"
            nestedPath = path.drop(1)
        } else {
            componentId = first
            nestedPath = path.drop(1)
        }

        val component = repo.component(componentId) ?: return null
        val entry = component.childEntriesAt(nestedPath).firstOrNull { it.key == key } ?: return null
        return EsphomeTarget.Field(entry, componentId)
    }
}
