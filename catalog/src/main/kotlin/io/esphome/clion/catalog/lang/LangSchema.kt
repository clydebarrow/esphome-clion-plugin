package io.esphome.clion.catalog.lang

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * PROTOTYPE: consume ESPHome's raw language schema (`script/build_language_schema.py`
 * output) directly, instead of device-builder's pre-flattened catalog.
 *
 * The language schema keeps `extends` as references between named schemas
 * (`lvgl.STYLE_SCHEMA`, `core.COMPONENT_SCHEMA`), so a component's effective keys
 * are its own `config_vars` plus those of everything it extends, resolved on
 * demand. We deliberately do *not* flatten: that's what blows lvgl up to ~15 MB
 * in device-builder's output and makes recursive widget schemas impossible.
 * Resolving lazily keeps storage at the schema's own size and turns the
 * recursion into a non-issue — a cycle guard stops at the shared named schema.
 */

/** One node in the schema tree — a `config_var` or a named schema. */
@Serializable
class LangNode(
    /** "schema" | "enum" | "typed" | "boolean" | "integer" | … or absent (opaque leaf). */
    val type: String? = null,
    /** YAML key requiredness marker: "Optional" | "Required" | "GeneratedID". */
    val key: String? = null,
    @SerialName("is_list") val isList: Boolean = false,
    /**
     * Polymorphic in the source: a `{config_vars, extends}` object for
     * `type == "schema"`, but a bare `true` for some leaves (e.g. pins). Kept raw
     * and decoded to a [LangSchemaBody] only when it's an object — see [body].
     */
    val schema: JsonElement? = null,
    /** Present when [type] == "enum": value -> null map. */
    val values: Map<String, JsonElement?>? = null,
    /** Present when [type] == "typed": discriminated sub-schemas. */
    val types: Map<String, LangNode>? = null,
    val default: JsonElement? = null,
    @SerialName("docs_url") val docsUrl: String? = null,
)

/** A schema body: its own keys plus the named schemas it extends. */
@Serializable
class LangSchemaBody(
    @SerialName("config_vars") val configVars: Map<String, LangNode> = emptyMap(),
    val extends: List<String> = emptyList(),
)

/** A domain entry (`lvgl`, `lvgl.binary_sensor`, `core`, …) in the bundle. */
@Serializable
class LangDomain(
    val schemas: Map<String, LangNode> = emptyMap(),
)

/**
 * Resolves keys/values from a parsed language-schema bundle without flattening.
 * [domains] maps domain id → its node (built from the per-domain JSON files).
 */
class LangSchemaRepository(private val domains: Map<String, LangDomain>) {

    /** Named schemas keyed `"<domain>.<SchemaName>"` — the targets of `extends`. */
    private val named: Map<String, LangNode> = buildMap {
        for ((domain, body) in domains) for ((name, node) in body.schemas) put("$domain.$name", node)
    }

    /** The keys (and their nodes) legal directly under [path] within [componentId]. */
    fun keysAt(componentId: String, path: List<String> = emptyList()): Map<String, LangNode> {
        var body = named["$componentId.${CONFIG_SCHEMA}"]?.body() ?: return emptyMap()
        var vars = resolveVars(body)
        for (segment in path) {
            // Descend through the matched key's nested schema.
            body = vars[segment]?.body() ?: return emptyMap()
            vars = resolveVars(body)
        }
        return vars
    }

    /** A node's schema body, when its `schema` is an object (not a pin's `true`). */
    private fun LangNode.body(): LangSchemaBody? =
        (schema as? JsonObject)?.let { json.decodeFromJsonElement<LangSchemaBody>(it) }

    /**
     * A schema's effective config_vars: those of every `extends` target (depth
     * first, so the schema's own keys win) plus its own. [seen] guards cycles —
     * recursive widget schemas reference a shared named schema, so we stop the
     * first time a ref repeats instead of expanding forever.
     */
    private fun resolveVars(
        body: LangSchemaBody,
        seen: MutableSet<String> = HashSet(),
    ): Map<String, LangNode> {
        val out = LinkedHashMap<String, LangNode>()
        for (ref in body.extends) {
            if (!seen.add(ref)) continue
            named[ref]?.body()?.let { out.putAll(resolveVars(it, seen)) }
        }
        out.putAll(body.configVars)
        return out
    }

    companion object {
        private const val CONFIG_SCHEMA = "CONFIG_SCHEMA"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Parse one per-domain language-schema file (e.g. `lvgl.json`). */
        fun parseDomains(text: String): Map<String, LangDomain> = json.decodeFromString(text)

        /** Build a repository from many per-domain files (the schema bundle). */
        fun fromBundle(files: Iterable<String>): LangSchemaRepository =
            LangSchemaRepository(buildMap { files.forEach { putAll(parseDomains(it)) } })
    }
}
