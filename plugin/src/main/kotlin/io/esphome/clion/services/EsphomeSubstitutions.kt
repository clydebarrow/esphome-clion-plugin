package io.esphome.clion.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import io.esphome.clion.psi.EsphomeYaml
import io.esphome.clion.references.EsphomeSubstitutionSyntax
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Resolves ESPHome `${name}` substitutions: collects the top-level
 * `substitutions:` definitions visible across a file's `!include`/`packages:`
 * graph, and resolves a name to its definition or its (recursively expanded)
 * value. The basis for substitution navigation, completion and inspection.
 *
 * Reads PSI/index under a read action. Package/`vars:` override precedence is
 * approximated as first-seen-wins over [EsphomeIncludeGraph.connectedFiles]
 * order (roots first) — enough to navigate to a definition.
 */
@Service(Service.Level.PROJECT)
class EsphomeSubstitutions(private val project: Project) {

    /** A `substitutions:` entry: its name, the key/value PSI, and the file. */
    data class Definition(val name: String, val keyValue: YAMLKeyValue, val file: VirtualFile)

    /** All substitutions visible from [file], keyed by name (first definition wins). */
    fun definitionsInScope(file: VirtualFile): Map<String, Definition> {
        val result = LinkedHashMap<String, Definition>()
        val psiManager = PsiManager.getInstance(project)
        for (vf in EsphomeIncludeGraph.getInstance(project).connectedFiles(file)) {
            val yaml = psiManager.findFile(vf) as? YAMLFile ?: continue
            for (document in yaml.documents) {
                val block = EsphomeYaml.topLevelMapping(document)
                    ?.getKeyValueByKey(EsphomeYaml.SUBSTITUTIONS_KEY)
                    ?.value as? YAMLMapping ?: continue
                for (keyValue in block.keyValues) {
                    val name = keyValue.keyText
                    if (name.isEmpty()) continue
                    result.putIfAbsent(name, Definition(name, keyValue, vf))
                }
            }
        }
        return result
    }

    /** The definition of [name] visible from [file], or null. */
    fun resolve(name: String, file: VirtualFile): Definition? = definitionsInScope(file)[name]

    /**
     * Every substitution name that could be defined for [file]: the
     * `substitutions:` definitions plus any `!include … vars:` keys in scope.
     * Used by the inspection to avoid flagging a name supplied as an include var
     * (which we don't bind to a single definition) as unresolved.
     */
    fun knownNamesInScope(file: VirtualFile): Set<String> {
        val names = HashSet(definitionsInScope(file).keys)
        val psiManager = PsiManager.getInstance(project)
        for (vf in EsphomeIncludeGraph.getInstance(project).connectedFiles(file)) {
            val yaml = psiManager.findFile(vf) as? YAMLFile ?: continue
            for (keyValue in com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(yaml, YAMLKeyValue::class.java)) {
                if (keyValue.keyText != VARS_KEY) continue
                (keyValue.value as? YAMLMapping)?.keyValues
                    ?.forEach { v -> v.keyText.takeIf(String::isNotEmpty)?.let(names::add) }
            }
        }
        return names
    }

    /** The value of [name], with any nested `${...}` expanded; null if undefined. */
    fun valueOf(name: String, file: VirtualFile): String? =
        expand(name, definitionsInScope(file), HashSet())

    private fun expand(name: String, defs: Map<String, Definition>, seen: MutableSet<String>): String? {
        if (!seen.add(name)) return null // cycle
        val raw = (defs[name]?.keyValue?.value as? YAMLScalar)?.textValue ?: return null
        return EsphomeSubstitutionSyntax.PATTERN.replace(raw) { match ->
            val inner = match.groupValues[1].ifEmpty { match.groupValues[2] }
            expand(inner, defs, seen) ?: match.value
        }
    }

    companion object {
        private const val VARS_KEY = "vars"

        fun getInstance(project: Project): EsphomeSubstitutions = project.service()
    }
}
