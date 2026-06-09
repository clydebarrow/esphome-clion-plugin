package io.esphome.clion.catalog

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Parsing entry points and a repository over a vendored copy of Device
 * Builder's `definitions/` directory.
 *
 * The repository is deliberately lazy: it reads `components.index.json` up
 * front (cheap, drives list/search/filter) and loads a component's
 * `config_entries` body only when first asked for — matching how the catalog is
 * split upstream and how a completion contributor uses it (index for the
 * top-level key list, body on demand when the caret descends into a block).
 */
object EsphomeCatalog {
    /**
     * Lenient, forward-compatible decoder.
     *
     * - [Json.ignoreUnknownKeys]: a newer ESPHome release may add fields we do
     *   not model yet — ignore them rather than fail.
     * - [Json.coerceInputValues]: an unknown enum value (e.g. a new
     *   [ConfigEntryType]) coerces to the property default (`UNKNOWN`/`MISC`)
     *   instead of throwing.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    fun parseIndex(text: String): ComponentCatalogIndex = json.decodeFromString(text)

    fun parseComponent(text: String): ComponentCatalogEntry = json.decodeFromString(text)

    fun parseAutomations(text: String): AutomationsIndex = json.decodeFromString(text)
}

/**
 * Reads catalog files by path relative to a `definitions/` root, e.g.
 * `"components.index.json"` or `"components/wifi.json"`. Returns null when the
 * file is absent. Abstracts over filesystem vs classpath so the same
 * [CatalogRepository] works in tests (a temp dir) and inside the plugin
 * (bundled resources).
 */
fun interface CatalogSource {
    fun read(relativePath: String): String?
}

/** [CatalogSource] backed by a filesystem `definitions/` directory. */
class FileCatalogSource(private val root: Path) : CatalogSource {
    override fun read(relativePath: String): String? {
        val file = root.resolve(relativePath)
        return if (Files.exists(file)) Files.readString(file) else null
    }
}

/**
 * Resolve the [ConfigEntry] reached by walking [path] (a sequence of YAML keys)
 * from this component's top-level entries. Descends through NESTED containers
 * and MAP value templates. Returns null if the path does not resolve.
 *
 * Example: `wifi` component, path `["ap", "channel"]` → the AP channel entry.
 */
fun ComponentCatalogEntry.entryAtPath(path: List<String>): ConfigEntry? {
    if (path.isEmpty()) return null
    var entries: List<ConfigEntry>? = configEntries
    var current: ConfigEntry? = null
    for (key in path) {
        val next = entries?.firstOrNull { it.key == key } ?: return null
        current = next
        entries = next.configEntries
    }
    return current
}

/** The config entries that may legally appear directly under [path]. */
fun ComponentCatalogEntry.childEntriesAt(path: List<String>): List<ConfigEntry> {
    if (path.isEmpty()) return configEntries
    return entryAtPath(path)?.configEntries ?: emptyList()
}

/**
 * Lazy view over a vendored `definitions/` source. Beyond raw lookup it exposes
 * the derived views a completion engine needs: the set of legal top-level YAML
 * keys, the platform domains, and the platforms available under each domain.
 */
class CatalogRepository(private val source: CatalogSource) {

    /** Convenience for a filesystem-backed catalog (tests, local dirs). */
    constructor(definitionsDir: Path) : this(FileCatalogSource(definitionsDir))

    val index: ComponentCatalogIndex by lazy {
        val text = source.read(INDEX_FILE)
            ?: error("ESPHome catalog index not found: $INDEX_FILE")
        EsphomeCatalog.parseIndex(text)
    }

    private val bodyCache = HashMap<String, ComponentCatalogEntry?>()

    val schemaVersion: String get() = index.esphomeSchemaVersion

    /** All catalog ids, e.g. `wifi`, `i2c`, `sensor.dht`, `switch.gpio`. */
    fun componentIds(): List<String> = index.components.map { it.id }

    fun indexEntry(id: String): ComponentCatalogIndexEntry? =
        index.components.firstOrNull { it.id == id }

    /** Load (and cache) a component body; null if no body file exists. */
    fun component(id: String): ComponentCatalogEntry? = bodyCache.getOrPut(id) {
        source.read("$BODIES_DIR/$id.json")?.let(EsphomeCatalog::parseComponent)
    }

    /** Platform domains — the umbrella keys like `sensor`, `switch`, `light`. */
    val domains: Set<String> by lazy {
        index.components.mapNotNull { if (it.platform != null) it.domain else null }.toSet()
    }

    fun isDomain(key: String): Boolean = key in domains

    /**
     * Every class an id reference (`references_component`) can target: the
     * top-level component keys an id can be declared under (platform domains and
     * non-platform components), plus every base class a component `provides`
     * (e.g. `voltage_sampler`). An inspection uses this to avoid flagging a
     * reference whose target class we simply don't model.
     */
    val referenceableClasses: Set<String> by lazy {
        (topLevelKeys.asSequence() + index.components.asSequence().flatMap { it.provides.asSequence() }).toSet()
    }

    /** Platform names available under a domain (`sensor` → `dht`, `adc`, …). */
    fun platformsFor(domain: String): List<String> =
        index.components.filter { it.domain == domain && it.platform != null }
            .mapNotNull { it.platform }
            .sorted()

    /**
     * Keys legal at the top level of an ESPHome config: every platform domain
     * plus every non-platform component (`wifi`, `api`, `i2c`, `esphome`, …).
     */
    val topLevelKeys: Set<String> by lazy {
        val nonPlatform = index.components.filter { it.platform == null }.map { it.id }
        (nonPlatform.asSequence() + domains.asSequence()).toSortedSet()
    }

    /** Triggers (`on_*`), or empty when no automations index is present. */
    private val automations: AutomationsIndex by lazy {
        source.read(AUTOMATIONS_INDEX_FILE)?.let(EsphomeCatalog::parseAutomations) ?: AutomationsIndex()
    }

    /**
     * Triggers grouped by the context they apply to — a platform domain or
     * component id (`touchscreen`, `sensor`, `remote_receiver`), or `esphome`
     * for device-level triggers (`on_boot`). Deduped by trigger key, since the
     * same `on_touch` is registered once per platform.
     */
    private val triggersByContext: Map<String, List<TriggerEntry>> by lazy {
        val byContext = HashMap<String, LinkedHashMap<String, TriggerEntry>>()
        for (trigger in automations.triggers) {
            val contexts = trigger.appliesTo.ifEmpty {
                if (trigger.isDeviceLevel) listOf(DEVICE_LEVEL_CONTEXT) else emptyList()
            }
            for (context in contexts) {
                byContext.getOrPut(context) { LinkedHashMap() }.putIfAbsent(trigger.key, trigger)
            }
        }
        byContext.mapValues { (_, triggers) -> triggers.values.toList() }
    }

    /** Triggers offered at the root of any of [contextKeys] (a domain/component id). */
    fun triggersFor(contextKeys: Collection<String>): List<TriggerEntry> =
        contextKeys.asSequence()
            .flatMap { (triggersByContext[it] ?: emptyList()).asSequence() }
            .distinctBy { it.key }
            .toList()

    companion object {
        const val INDEX_FILE = "components.index.json"
        const val AUTOMATIONS_INDEX_FILE = "automations.index.json"
        const val BODIES_DIR = "components"
        /** Top-level component under which device-level triggers (`on_boot`, …) live. */
        private const val DEVICE_LEVEL_CONTEXT = "esphome"
    }
}
