package io.esphome.clion.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import com.intellij.util.ProcessingContext
import io.esphome.clion.catalog.CatalogRepository
import io.esphome.clion.catalog.ComponentCatalogEntry
import io.esphome.clion.catalog.ConfigEntry
import io.esphome.clion.catalog.ConfigEntryType
import io.esphome.clion.catalog.childEntriesAt
import io.esphome.clion.psi.EsphomeYaml
import io.esphome.clion.references.EsphomeIdReferences
import io.esphome.clion.services.EsphomeCatalogService
import io.esphome.clion.services.EsphomeIds
import io.esphome.clion.services.EsphomeIncludeGraph
import io.esphome.clion.services.EsphomeSubstitutions
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Completion for ESPHome YAML, driven by the vendored component catalog.
 *
 * First slice — handles the well-defined cases:
 *  - top-level keys (platform domains + non-platform components);
 *  - keys inside a known component block, including nested blocks and platform
 *    list items (`sensor: - platform: dht`), resolved via the catalog;
 *  - values: the `platform:` discriminator's options and any field with a
 *    closed `options` enum.
 *
 * Deeper behaviour (registry lists, lambdas, pin pickers, conditional
 * visibility, documentation on hover) is intentionally deferred.
 */
class EsphomeCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(YAMLLanguage.INSTANCE),
            EsphomeCompletionProvider,
        )
    }
}

/** Resolution of the caret context to a catalog component + remaining path. */
private data class Resolved(
    val component: ComponentCatalogEntry?,
    val nestedPath: List<String>,
    /** Set when inside a domain list item that has no `platform:` yet. */
    val domainNeedingPlatform: String?,
)

private object EsphomeCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val position = parameters.position
        // Inside a `${...}` → substitution names (works in fragments too, so this
        // runs before the esphome-file gate).
        if (addSubstitutionCompletions(parameters, position, result)) {
            result.stopHere()
            return
        }

        val file = position.containingFile as? YAMLFile ?: return
        // Recognise configs whose `esphome:` comes from a package (top-level
        // `packages:`, no own `esphome:`) and fragments included by one — not
        // just files with a literal top-level `esphome:` key.
        if (!EsphomeIncludeGraph.getInstance(file.project).isEsphomeConfigContext(file)) return

        // We drive completion structurally from the catalog, so suppress the
        // bundled YAML plugin's word-completion fallback — otherwise it pads the
        // list with in-use ids, sibling keys, and other words scraped from the
        // file that are irrelevant to the current key/value position.
        result.stopHere()

        val repo = EsphomeCatalogService.getInstance().repository
        val keyValue = position.parentOfType<YAMLKeyValue>()
        val inValueSubtree = keyValue?.value?.let { PsiTreeUtil.isAncestor(it, position, false) } == true

        // A genuine value position has its value on the key's line (`key: val`).
        // When the "value" is on a following line, the parser has mis-attached a
        // half-typed child key as the parent's scalar value — treat as a key.
        if (inValueSubtree && EsphomeYaml.isValueOnKeyLine(keyValue!!)) {
            addValueCompletions(repo, position, keyValue, result)
        } else {
            addKeyCompletions(repo, position, keyValue, nascentChildOf = inValueSubtree, result = result)
        }
    }

    // --- substitution completion -------------------------------------------

    /**
     * When the caret sits inside an unclosed `${…` token, offer the substitution
     * names in scope (with their values as type text). Returns true when the
     * caret is in that context, so normal key/value completion is skipped.
     */
    private fun addSubstitutionCompletions(
        parameters: CompletionParameters,
        position: PsiElement,
        result: CompletionResultSet,
    ): Boolean {
        val scalar = position.parentOfType<YAMLScalar>() ?: return false
        val textToCaret = scalar.text.substring(
            0,
            (parameters.offset - scalar.textRange.startOffset).coerceIn(0, scalar.text.length),
        )
        val open = textToCaret.lastIndexOf("\${")
        if (open < 0 || textToCaret.indexOf('}', open) >= 0) return false
        val partial = textToCaret.substring(open + 2).trimStart()
        if (!partial.matches(IDENTIFIER_PREFIX)) return false

        val virtualFile = position.containingFile.originalFile.virtualFile ?: return true
        val definitions = EsphomeSubstitutions.getInstance(position.project).definitionsInScope(virtualFile)
        val prefixed = result.withPrefixMatcher(partial)
        for ((name, definition) in definitions) {
            var element = LookupElementBuilder.create(name)
            (definition.keyValue.value as? YAMLScalar)?.textValue?.let { element = element.withTypeText(it) }
            prefixed.addElement(element)
        }
        return true
    }

    // --- key completion ----------------------------------------------------

    private fun addKeyCompletions(
        repo: CatalogRepository,
        position: PsiElement,
        keyValueBeingTyped: YAMLKeyValue?,
        nascentChildOf: Boolean,
        result: CompletionResultSet,
    ) {
        val path: List<String>
        val present: Set<String>
        if (nascentChildOf && keyValueBeingTyped != null) {
            // The new key belongs to the block introduced by `keyValueBeingTyped`,
            // which has no real child mapping yet — so its path includes that key
            // and nothing is "already present".
            path = EsphomeYaml.pathOfMapping(keyValueBeingTyped.parentOfType<YAMLMapping>()) +
                keyValueBeingTyped.keyText
            present = emptySet()
        } else {
            val container = keyValueBeingTyped?.parentOfType<YAMLMapping>()
                ?: position.parentOfType<YAMLMapping>()
            path = EsphomeYaml.pathOfMapping(container)
            present = presentKeys(container)
        }

        // Inside an automation list — a sequence under a trigger (`on_*`) or a
        // `then:`/`else:` — the keys are action names (`lvgl.widget.update`,
        // `switch.turn_on`). Actions are global, so offer them all.
        path.lastOrNull()?.let { listKey ->
            if (listKey.startsWith("on_") || listKey == "then" || listKey == "else") {
                repo.actions.asSequence()
                    .filter { it.id !in present }
                    .forEach { result.addElement(keyLookup(it.id, it.name.ifBlank { null })) }
                return
            }
        }

        if (path.isEmpty()) {
            repo.topLevelKeys.asSequence()
                .filter { it !in present }
                .forEach { key ->
                    val description = repo.indexEntry(key)?.description?.takeIf { it.isNotBlank() }
                    result.addElement(keyLookup(key, description))
                }
            return
        }

        val resolved = resolve(repo, path, position)
        if (resolved.domainNeedingPlatform != null) {
            if (EsphomeYaml.PLATFORM_KEY !in present) {
                result.addElement(
                    keyLookup(EsphomeYaml.PLATFORM_KEY, "Selects the ${resolved.domainNeedingPlatform} platform"),
                )
            }
            return
        }

        // Component field keys (when a body exists — some components are
        // schema-less in the catalog, but may still carry triggers below).
        resolved.component?.childEntriesAt(resolved.nestedPath)?.asSequence()
            ?.filter { !it.isDecoration && it.key !in present }
            ?.forEach { result.addElement(entryLookup(it)) }

        // Triggers (on_*) live in the automations index, not config_entries, and
        // belong at a component's root (a direct child of `i2c:` or a
        // `- platform:` item). Offer them keyed on the domain/component id even
        // when the component body is absent.
        if (resolved.nestedPath.isEmpty()) {
            repo.triggersFor(listOf(path.first())).asSequence()
                .filter { it.key !in present }
                .forEach { result.addElement(keyLookup(it.key, it.name.ifBlank { null })) }
        }
    }

    // --- value completion --------------------------------------------------

    private fun addValueCompletions(
        repo: CatalogRepository,
        position: PsiElement,
        keyValue: YAMLKeyValue,
        result: CompletionResultSet,
    ) {
        val container = keyValue.parentOfType<YAMLMapping>()
        val path = EsphomeYaml.pathOfMapping(container)

        // `platform:` value → the platforms available under the domain.
        if (keyValue.keyText == EsphomeYaml.PLATFORM_KEY && path.isNotEmpty() && repo.isDomain(path[0])) {
            repo.platformsFor(path[0]).forEach { result.addElement(LookupElementBuilder.create(it)) }
            return
        }

        // Any field with a closed/suggested enum → its option values.
        val resolved = resolve(repo, path, position)
        val component = resolved.component ?: return
        val entry = component.childEntriesAt(resolved.nestedPath)
            .firstOrNull { it.key == keyValue.keyText } ?: return
        entry.options?.forEach { option ->
            result.addElement(LookupElementBuilder.create(option.value).withTypeText(option.label))
        }

        // Boolean fields carry no `options`; offer the literals.
        if (entry.type == ConfigEntryType.BOOLEAN) {
            for (value in listOf("true", "false")) {
                result.addElement(LookupElementBuilder.create(value).withTypeText("boolean"))
            }
        }

        // An id-reference field → in-scope ids whose component type matches.
        if (entry.type == ConfigEntryType.ID) {
            entry.referencesComponent?.let { addIdReferenceCompletions(repo, position, it, result) }
        }
    }

    /** Offer ids declared anywhere in the file's include graph that satisfy [referencesComponent]. */
    private fun addIdReferenceCompletions(
        repo: CatalogRepository,
        position: PsiElement,
        referencesComponent: String,
        result: CompletionResultSet,
    ) {
        val virtualFile = position.containingFile.originalFile.virtualFile ?: return
        val project = position.project
        val scope = EsphomeIncludeGraph.getInstance(project).connectedFiles(virtualFile)
        EsphomeIds.getInstance(project).declarationsIn(scope)
            .filter { EsphomeIdReferences.satisfies(repo, it, referencesComponent) }
            .filter { !it.effectiveName.contains('$') } // skip ids we couldn't fully expand
            .forEach { declaration ->
                val type = declaration.platform?.let { "${declaration.domain}.$it" } ?: declaration.domain
                result.addElement(LookupElementBuilder.create(declaration.effectiveName).withTypeText(type))
            }
    }

    // --- shared ------------------------------------------------------------

    /**
     * Map a container path to the catalog component whose fields apply, plus
     * the remaining nested path within it. For a platform domain we read the
     * sibling `platform:` to pick `<domain>.<platform>`.
     */
    private fun resolve(repo: CatalogRepository, path: List<String>, position: PsiElement): Resolved {
        val first = path.firstOrNull() ?: return Resolved(null, emptyList(), null)
        if (repo.isDomain(first)) {
            val platform = EsphomeYaml.platformOf(position)
                ?: return Resolved(null, emptyList(), first)
            return Resolved(repo.component("$first.$platform"), path.drop(1), null)
        }
        return Resolved(repo.component(first), path.drop(1), null)
    }

    private fun presentKeys(mapping: YAMLMapping?): Set<String> =
        mapping?.keyValues?.mapNotNull { it.keyText.takeIf(String::isNotEmpty) }?.toSet() ?: emptySet()

    private fun keyLookup(key: String, description: String?): LookupElementBuilder {
        val element = LookupElementBuilder.create(key)
        return if (description != null) element.withTypeText(description.singleLine(), true) else element
    }

    private fun entryLookup(entry: ConfigEntry): LookupElementBuilder {
        val tail = buildString {
            if (entry.required) append("  required")
            entry.description?.takeIf { it.isNotBlank() }?.let { append("  ").append(it.singleLine()) }
        }
        var element = LookupElementBuilder.create(entry.key).withTypeText(entry.type.name.lowercase())
        if (tail.isNotEmpty()) element = element.withTailText(tail, true)
        if (entry.required) element = element.bold()
        return element
    }

    private fun String.singleLine(): String =
        replace('\n', ' ').let { if (it.length > 80) it.take(77) + "…" else it }

    private val IDENTIFIER_PREFIX = Regex("[A-Za-z0-9_]*")
}
