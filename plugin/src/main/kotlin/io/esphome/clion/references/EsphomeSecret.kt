package io.esphome.clion.references

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import io.esphome.clion.secrets.EsphomeSecretLines
import io.esphome.clion.services.EsphomeIncludeGraph
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLValue

/**
 * Recognises and resolves ESPHome `!secret <name>` references. ESPHome substitutes
 * such a value from a `secrets.yaml` sitting in (or above) the config directory,
 * so navigation follows the same lookup: the nearest `secrets.yaml`/`secrets.yml`
 * at or above the referencing file, within the project.
 *
 * ESPHome also lets a secrets file split its content across another one via a
 * `<<: !include <path>` YAML merge key — the included file's keys merge into the
 * same namespace. This object treats that included file as part of the same
 * secrets file for both lookup ([secretEntries]) and masking classification
 * ([isIncludedBySecretsFile]).
 */
object EsphomeSecret {

    private const val SECRET_TAG = "secret"
    private const val MERGE_KEY = "<<"
    private val SECRETS_FILES = listOf("secrets.yaml", "secrets.yml")

    private fun tagName(value: YAMLValue?): String? = value?.tag?.text?.trim()?.removePrefix("!")

    /** The key named by a `!secret <name>` scalar, or null when it isn't one. */
    fun secretNameOf(scalar: YAMLScalar): String? =
        if (tagName(scalar) == SECRET_TAG) scalar.textValue.takeIf { it.isNotBlank() } else null

    /**
     * The secret name [element] declares, when it is a top-level `YAMLKeyValue`
     * in a file classified as a secrets file — named `secrets.yaml`/
     * `secrets.yml`, declaring the `is_secrets_file` marker, or directly
     * `<<: !include`d by one of those (matching [secretEntries]'s notion of
     * "the same secrets file", so a declaration only reachable that way is
     * still a valid Find Usages target).
     *
     * [element] must be the whole [YAMLKeyValue], not its key leaf: the bundled
     * YAML plugin treats a `YAMLKeyValue` itself as the `PsiNamedElement` for a
     * YAML key (confirmed by logging what a real Cmd-click/Find-Usages
     * invocation actually hands `canFindUsages`/`ReferencesSearch` — a
     * `YAMLKeyValue`, never the bare key token) — the platform resolves that
     * target *before* ever consulting our `TargetElementEvaluator`/
     * `UsageTargetProvider`, so accepting only the leaf silently rejected every
     * real invocation despite working in tests that constructed the leaf by
     * hand. [EsphomeSecretReference.resolve] returns the same `YAMLKeyValue`
     * shape, so usage and declaration sides compare equal.
     */
    fun declaredSecretName(element: PsiElement): String? {
        val keyValue = element as? YAMLKeyValue ?: return null
        val name = keyValue.keyText.takeIf { it.isNotEmpty() && it != MERGE_KEY } ?: return null
        val yaml = element.containingFile as? YAMLFile ?: return null
        if (topLevelMapping(yaml) !== keyValue.parent) return null
        val virtualFile = yaml.originalFile.virtualFile ?: return null
        val project = element.project
        val classified = isSecretsFileByNameOrMarker(virtualFile, yaml) || isIncludedBySecretsFile(project, virtualFile)
        return name.takeIf { classified }
    }

    /** The nearest `secrets.yaml` at or above [from] within the project, or null. */
    fun findSecretsFile(from: VirtualFile, project: Project): VirtualFile? {
        val base = project.basePath
        var dir: VirtualFile? = from.parent
        while (dir != null) {
            for (name in SECRETS_FILES) dir.findChild(name)?.takeIf { it.isValid && !it.isDirectory }?.let { return it }
            if (base != null && dir.path == base) break // don't escape the project
            dir = dir.parent
        }
        return null
    }

    /** Resolve `!secret [name]` (used in [from]) to its `name:` declaration, or null. */
    fun resolveSecret(project: Project, from: VirtualFile, name: String): YAMLKeyValue? {
        val secrets = findSecretsFile(from, project) ?: return null
        return secretEntries(project, secrets)[name]
    }

    /**
     * Every top-level `key: value` in [file], merged with the file it directly
     * pulls in via a `<<: !include <path>` merge key (ESPHome's way of splitting
     * secrets across multiple files) — so a lookup against the top-level secrets
     * file also finds a key declared only in the included one. A key already
     * present (locally, or from the local mapping taking precedence per YAML
     * merge-key semantics) is not overridden by the included file's version.
     */
    fun secretEntries(project: Project, file: VirtualFile): Map<String, YAMLKeyValue> {
        val yaml = PsiManager.getInstance(project).findFile(file) as? YAMLFile ?: return emptyMap()
        val mapping = topLevelMapping(yaml) ?: return emptyMap()
        val result = LinkedHashMap(ownEntries(mapping))
        val includedYaml = mergeIncludeTarget(file, mapping)
            ?.let { PsiManager.getInstance(project).findFile(it) as? YAMLFile }
        includedYaml?.let(::topLevelMapping)?.let(::ownEntries)?.forEach { (key, value) -> result.putIfAbsent(key, value) }
        return result
    }

    /**
     * Whether [file] is directly `<<: !include`d — the merge-key form ESPHome
     * uses to split secrets across files — by some other file that is itself a
     * secrets file (named `secrets.yaml`/`secrets.yml`, or declaring the
     * top-level `is_secrets_file: true` marker). Lets a fragment pulled into a
     * secrets file this way be masked and cross-referenced the same way as the
     * top-level file, without needing its own marker.
     */
    fun isIncludedBySecretsFile(project: Project, file: VirtualFile): Boolean {
        val psiManager = PsiManager.getInstance(project)
        return EsphomeIncludeGraph.getInstance(project).directIncluders(file).any { includer ->
            val includerYaml = psiManager.findFile(includer) as? YAMLFile ?: return@any false
            val mapping = topLevelMapping(includerYaml) ?: return@any false
            mergeIncludeTarget(includer, mapping) == file && isSecretsFileByNameOrMarker(includer, includerYaml)
        }
    }

    private fun isSecretsFileByNameOrMarker(file: VirtualFile, yaml: YAMLFile): Boolean =
        SECRETS_FILES.any { file.name.equals(it, true) } || EsphomeSecretLines.declaresSecretsFile(yaml.text)

    /**
     * The mapping holding this secrets file's actual entries. A leading Jekyll-
     * style front-matter block (`---` metadata `---`, see
     * [EsphomeSecretLines.frontMatterLineRange]) makes the file a *multi-document*
     * YAML stream — the front matter is its own document, and the real content is
     * the one after it — so the last document (not the first) is the one with the
     * secrets, whether or not front matter is present (a file without any is a
     * single-document stream, where first and last are the same document).
     */
    private fun topLevelMapping(yaml: YAMLFile): YAMLMapping? =
        yaml.documents.lastOrNull()?.topLevelValue as? YAMLMapping

    /** [mapping]'s own top-level entries, excluding the `<<` merge key itself. */
    private fun ownEntries(mapping: YAMLMapping): Map<String, YAMLKeyValue> {
        val result = LinkedHashMap<String, YAMLKeyValue>()
        for (keyValue in mapping.keyValues) {
            val key = keyValue.keyText.takeIf { it.isNotEmpty() && it != MERGE_KEY } ?: continue
            result.putIfAbsent(key, keyValue)
        }
        return result
    }

    /**
     * The file a top-level `<<: !include <path>` merge key in [mapping] points
     * at (resolved relative to [file]'s directory), or null if there is none.
     */
    private fun mergeIncludeTarget(file: VirtualFile, mapping: YAMLMapping): VirtualFile? {
        val scalar = mapping.keyValues.firstOrNull { it.keyText == MERGE_KEY }?.value as? YAMLScalar ?: return null
        val path = EsphomeInclude.includePathOf(scalar) ?: return null
        if (path.contains("\${") || path.contains("://")) return null
        return file.parent?.findFileByRelativePath(path)
    }
}
