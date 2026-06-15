package io.esphome.clion.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import io.esphome.clion.index.EsphomeIdIndex
import io.esphome.clion.index.IdDeclaration
import io.esphome.clion.psi.EsphomeYaml
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Queries the [EsphomeIdIndex] for `id:` declarations within a resolution scope
 * (usually [EsphomeIncludeGraph.connectedFiles]). The basis for Phase 3 id
 * navigation/completion. Phase 2 of
 * `docs/roadmap-includes-and-navigation.md`.
 *
 * Reads the index and must be called under a read action.
 */
@Service(Service.Level.PROJECT)
class EsphomeIds(private val project: Project) {

    /**
     * A located id declaration. [name] is the raw text (which may be templated,
     * e.g. `${prefix}_relay`); [effectiveName] is it with substitutions expanded
     * — what references actually resolve against. They are equal for plain ids.
     */
    data class Declaration(
        val name: String,
        val effectiveName: String,
        val file: VirtualFile,
        val offset: Int,
        val domain: String,
        val platform: String?,
    )

    /**
     * Every id declared across [scope], with templated names expanded. Reads the
     * [EsphomeIdIndex]; when that yields nothing it falls back to a direct PSI
     * scan, so a config opened *outside the project's content roots* (where the
     * file isn't indexed) still resolves its own ids — completion and navigation
     * both go through here.
     */
    fun declarationsIn(scope: Collection<VirtualFile>): List<Declaration> =
        declarationsFromIndex(scope).ifEmpty { declarationsByPsi(scope) }

    private fun declarationsFromIndex(scope: Collection<VirtualFile>): List<Declaration> {
        val index = FileBasedIndex.getInstance()
        val substitutions = EsphomeSubstitutions.getInstance(project)
        val definitions by lazy { substitutions.definitionsIn(scope) }
        val result = ArrayList<Declaration>()
        for (file in scope) {
            val data: Map<String, IdDeclaration> = index.getFileData(EsphomeIdIndex.NAME, file, project)
            for ((name, declaration) in data) {
                val effective = if (name.contains('$')) substitutions.expand(name, definitions) else name
                result.add(
                    Declaration(name, effective, file, declaration.offset, declaration.domain, declaration.platform),
                )
            }
        }
        return result
    }

    /**
     * Declarations matching [name] within [scope], comparing against the
     * expanded ([Declaration.effectiveName]) form. [name] should already be
     * expanded by the caller. Usually one; more than one means a duplicate-id
     * error (which Phase 5 can flag).
     */
    fun resolve(name: String, scope: Collection<VirtualFile>): List<Declaration> =
        declarationsIn(scope).filter { it.effectiveName == name }

    /**
     * Like [declarationsFromIndex] but reading each file's PSI instead of the
     * index — the fallback for un-indexed (out-of-content-root) files.
     */
    internal fun declarationsByPsi(scope: Collection<VirtualFile>): List<Declaration> {
        val psiManager = PsiManager.getInstance(project)
        val substitutions = EsphomeSubstitutions.getInstance(project)
        val definitions by lazy { substitutions.definitionsIn(scope) }
        val result = ArrayList<Declaration>()
        for (file in scope) {
            val yaml = psiManager.findFile(file) as? YAMLFile ?: continue
            for (keyValue in PsiTreeUtil.findChildrenOfType(yaml, YAMLKeyValue::class.java)) {
                if (!EsphomeYaml.isDeclarationId(keyValue)) continue
                val value = keyValue.value as? YAMLScalar ?: continue
                val name = value.textValue.ifEmpty { continue }
                val parentMapping = keyValue.parent as? YAMLMapping ?: continue
                val domain = EsphomeYaml.pathOfMapping(parentMapping).firstOrNull() ?: continue
                val platform = EsphomeYaml.platformOf(keyValue)
                val effective = if (name.contains('$')) substitutions.expand(name, definitions) else name
                result.add(Declaration(name, effective, file, value.textRange.startOffset, domain, platform))
            }
        }
        return result
    }

    companion object {
        fun getInstance(project: Project): EsphomeIds = project.service()
    }
}
