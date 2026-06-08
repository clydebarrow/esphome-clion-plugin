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
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

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
        val file = position.containingFile as? YAMLFile ?: return
        if (!EsphomeYaml.isEsphomeFile(file)) return

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

        val component = resolved.component ?: return
        component.childEntriesAt(resolved.nestedPath).asSequence()
            .filter { !it.isDecoration && it.key !in present }
            .forEach { result.addElement(entryLookup(it)) }
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
            .forEach { declaration ->
                val type = declaration.platform?.let { "${declaration.domain}.$it" } ?: declaration.domain
                result.addElement(LookupElementBuilder.create(declaration.name).withTypeText(type))
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
}
