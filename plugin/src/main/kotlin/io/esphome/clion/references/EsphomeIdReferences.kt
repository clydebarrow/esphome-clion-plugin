package io.esphome.clion.references

import com.intellij.psi.util.parentOfType
import io.esphome.clion.catalog.CatalogRepository
import io.esphome.clion.catalog.ConfigEntryType
import io.esphome.clion.psi.EsphomeResolution
import io.esphome.clion.psi.EsphomeTarget
import io.esphome.clion.psi.EsphomeYaml
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

    /**
     * Whether [scalar] is a plausible *untyped* id reference — an identifier-like
     * value (not the `id:` declaration itself) under some key. Used for the
     * catalog-less fallback: some components (notably `lvgl`) ship no schema, so
     * `references_component` can't classify their id uses. We still let an
     * identifier value that names a declaration in scope navigate, just without
     * type filtering.
     */
    fun isPotentialIdReference(scalar: YAMLScalar): Boolean {
        val keyValue = owningKeyValue(scalar) ?: return false
        // A plain `id:` is a declaration, but `id: !extend X` / `id: !remove X`
        // reference the X declared in a package — so those navigate like a use.
        if (keyValue.keyText == ID_KEY) {
            return EsphomeYaml.isMergeTaggedId(scalar) && scalar.textValue.matches(ID_TOKEN)
        }
        return scalar.textValue.matches(ID_TOKEN)
    }

    /**
     * If [scalar] is the value of an `id:` declaration, the declared id name;
     * otherwise null. Used by Find Usages and Rename to recognise the target. An
     * `id: !extend`/`!remove` is a *reference* to a package declaration, not a
     * declaration itself, so it is excluded.
     */
    fun declaredIdName(scalar: YAMLScalar): String? {
        val keyValue = scalar.parent as? YAMLKeyValue ?: return null
        if (keyValue.keyText != ID_KEY || keyValue.value !== scalar) return null
        if (EsphomeYaml.isMergeTaggedId(scalar)) return null
        return scalar.textValue.takeIf { it.isNotEmpty() && !it.contains("\${") && it.matches(ID_TOKEN) }
    }

    /**
     * The key/value whose **value** — a scalar or a list element — is [scalar].
     * Returns null when [scalar] is a key (so config keys never get treated as id
     * references) or anything other than a value.
     */
    private fun owningKeyValue(scalar: YAMLScalar): YAMLKeyValue? =
        when (val parent = scalar.parent) {
            is YAMLKeyValue -> parent.takeIf { it.value === scalar }
            is YAMLSequenceItem -> parent.parentOfType<YAMLKeyValue>()
            else -> null
        }

    private const val ID_KEY = "id"
    private val ID_TOKEN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
}
