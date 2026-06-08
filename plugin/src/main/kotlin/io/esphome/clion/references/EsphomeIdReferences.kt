package io.esphome.clion.references

import com.intellij.psi.util.parentOfType
import io.esphome.clion.catalog.CatalogRepository
import io.esphome.clion.catalog.ConfigEntryType
import io.esphome.clion.psi.EsphomeResolution
import io.esphome.clion.psi.EsphomeTarget
import io.esphome.clion.services.EsphomeIds
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Schema-driven recognition and type-matching of ESPHome id references, shared
 * by the id reference provider and the completion contributor. Phase 3 of
 * `docs/roadmap-includes-and-navigation.md`.
 */
object EsphomeIdReferences {

    /**
     * The component *class* an id-reference scalar points at (the catalog's
     * `references_component`), or null when [scalar] is not an id reference — i.e.
     * not a value whose field is `type: id` with a `references_component`. A bare
     * `id:` declaration has no `references_component`, so it returns null here.
     */
    fun referencedComponentOf(repo: CatalogRepository, scalar: YAMLScalar): String? {
        val keyValue = owningKeyValue(scalar) ?: return null
        val field = EsphomeResolution.resolveKey(repo, keyValue) as? EsphomeTarget.Field ?: return null
        val entry = field.entry
        if (entry.type != ConfigEntryType.ID) return null
        return entry.referencesComponent
    }

    /**
     * Whether [declaration]'s component satisfies a reference to
     * [referencesComponent]. A component satisfies its own domain (`i2c`,
     * `sensor`, …) and any base class it `provides` (e.g. `sensor.adc` provides
     * `voltage_sampler`) — matching ESPHome's parent-class inheritance.
     */
    fun satisfies(
        repo: CatalogRepository,
        declaration: EsphomeIds.Declaration,
        referencesComponent: String,
    ): Boolean {
        if (declaration.domain == referencesComponent) return true
        val componentId = declaration.platform?.let { "${declaration.domain}.$it" } ?: declaration.domain
        return referencesComponent in (repo.indexEntry(componentId)?.provides ?: emptyList())
    }

    /** The key/value whose value — a scalar or a list element — is [scalar]. */
    private fun owningKeyValue(scalar: YAMLScalar): YAMLKeyValue? =
        when (val parent = scalar.parent) {
            is YAMLKeyValue -> parent
            is YAMLSequenceItem -> parent.parentOfType<YAMLKeyValue>()
            else -> null
        }
}
