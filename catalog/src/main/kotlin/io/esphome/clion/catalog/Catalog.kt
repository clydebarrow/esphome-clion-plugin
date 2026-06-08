package io.esphome.clion.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Kotlin model of the Device Builder normalized catalog
 * (`definitions/components.index.json` + `definitions/components/<id>.json`),
 * the data product this plugin consumes for completion, hover and inspections.
 *
 * Field names and semantics mirror device-builder's `models/components.py`
 * (`ComponentCatalogIndexEntry`, `ComponentCatalogEntry`) and
 * `models/common.py` (`ConfigEntry`, `ConfigValueOption`, `RequiredGroup`).
 * See `docs/esphome-data-sources.md` for provenance.
 *
 * Polymorphic ESPHome values (`default_value`, `suggestions`,
 * `depends_on_value`, `platform_defaults`, `translation_params`) are kept as
 * raw [JsonElement] rather than forced into a sealed primitive type — the
 * editor features read them positionally and a faithful round-trip matters more
 * than a typed wrapper at this stage.
 */

/** A single choice for a field that renders as a closed/suggested enum. */
@Serializable
data class ConfigValueOption(
    val label: String,
    val value: String,
)

/** Cross-field "must specify one of these" constraint over sibling [keys]. */
@Serializable
data class RequiredGroup(
    val kind: RequiredGroupKind,
    val keys: List<String> = emptyList(),
)

/**
 * One field in a component's configuration schema — a single YAML key the user
 * can type, fully resolved (discriminators, `extends`, registries already
 * flattened upstream by `sync_components.py`).
 */
@Serializable
data class ConfigEntry(
    // === core ===
    val key: String,
    val type: ConfigEntryType = ConfigEntryType.UNKNOWN,
    val label: String = "",
    val description: String? = null,
    val required: Boolean = false,
    @SerialName("default_value") val defaultValue: JsonElement? = null,
    @SerialName("platform_defaults") val platformDefaults: Map<String, JsonElement>? = null,
    @SerialName("supported_platforms") val supportedPlatforms: List<String> = emptyList(),

    // === value constraints ===
    val options: List<ConfigValueOption>? = null,
    @SerialName("allow_custom_value") val allowCustomValue: Boolean = false,
    /** `[min, max]` for INTEGER / FLOAT entries; null = unbounded. */
    val range: List<Double>? = null,
    /** Display-formatting hint for INTEGER entries; currently only `"hex"`. */
    @SerialName("display_format") val displayFormat: String? = null,
    val registry: String? = null,
    @SerialName("unit_options") val unitOptions: List<String>? = null,
    @SerialName("multi_value") val multiValue: Boolean = false,
    /** Accepts a `!lambda` C++ expression in place of a literal. */
    val templatable: Boolean = false,
    @SerialName("exclusive_group") val exclusiveGroup: String? = null,

    // === featured-component overrides (board presets) ===
    val locked: Boolean = false,
    val suggestions: List<JsonElement>? = null,

    // === conditional visibility ===
    @SerialName("depends_on") val dependsOn: String? = null,
    @SerialName("depends_on_value") val dependsOnValue: JsonElement? = null,
    @SerialName("depends_on_value_not") val dependsOnValueNot: JsonElement? = null,
    @SerialName("depends_on_component") val dependsOnComponent: String? = null,
    @SerialName("references_component") val referencesComponent: String? = null,

    // === pin entries ===
    // NOTE: kept as raw strings (not a PinFeature enum) so an unknown feature
    // from a future ESPHome release decodes cleanly instead of throwing —
    // enum coercion does not apply to list elements in kotlinx.serialization.
    @SerialName("pin_features") val pinFeatures: List<String> = emptyList(),
    @SerialName("pin_mode") val pinMode: PinMode? = null,

    // === presentation ===
    val advanced: Boolean = false,
    val hidden: Boolean = false,
    @SerialName("help_link") val helpLink: String? = null,
    @SerialName("translation_key") val translationKey: String? = null,
    @SerialName("translation_params") val translationParams: JsonElement? = null,
    val group: String? = null,

    // === nesting ===
    @SerialName("config_entries") val configEntries: List<ConfigEntry>? = null,
    @SerialName("required_groups") val requiredGroups: List<RequiredGroup> = emptyList(),
    @SerialName("platform_type") val platformType: String? = null,
) {
    /** True for structural entries that carry no value of their own. */
    val isContainer: Boolean
        get() = type == ConfigEntryType.NESTED || type == ConfigEntryType.MAP

    /** True for layout-only entries the editor should not offer as keys. */
    val isDecoration: Boolean
        get() = type == ConfigEntryType.LABEL ||
            type == ConfigEntryType.DIVIDER ||
            type == ConfigEntryType.ALERT
}

/** Slim catalog entry from `components.index.json` (no per-field tree). */
@Serializable
data class ComponentCatalogIndexEntry(
    val id: String,
    val name: String = "",
    val description: String = "",
    val category: ComponentCategory = ComponentCategory.MISC,
    @SerialName("docs_url") val docsUrl: String = "",
    @SerialName("image_url") val imageUrl: String = "",
    val dependencies: List<String> = emptyList(),
    @SerialName("multi_conf") val multiConf: Boolean = false,
    @SerialName("supported_platforms") val supportedPlatforms: List<String> = emptyList(),
    val provides: List<String> = emptyList(),
) {
    /** Platform domain of an `<domain>.<platform>` id (`sensor.dht` → `sensor`). */
    val domain: String get() = id.substringBefore('.')

    /** Platform name of an `<domain>.<platform>` id (`sensor.dht` → `dht`), or null. */
    val platform: String? get() = id.substringAfter('.', "").ifEmpty { null }
}

/** Top-level shape of `components.index.json`. */
@Serializable
data class ComponentCatalogIndex(
    @SerialName("esphome_schema_version") val esphomeSchemaVersion: String = "",
    val components: List<ComponentCatalogIndexEntry> = emptyList(),
)

/** Full per-component body from `components/<id>.json` (index fields + tree). */
@Serializable
data class ComponentCatalogEntry(
    val id: String,
    val name: String = "",
    val description: String = "",
    val category: ComponentCategory = ComponentCategory.MISC,
    @SerialName("docs_url") val docsUrl: String = "",
    @SerialName("image_url") val imageUrl: String = "",
    val dependencies: List<String> = emptyList(),
    @SerialName("multi_conf") val multiConf: Boolean = false,
    @SerialName("supported_platforms") val supportedPlatforms: List<String> = emptyList(),
    val provides: List<String> = emptyList(),
    @SerialName("config_entries") val configEntries: List<ConfigEntry> = emptyList(),
    @SerialName("required_groups") val requiredGroups: List<RequiredGroup> = emptyList(),
)
