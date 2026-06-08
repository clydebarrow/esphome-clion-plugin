package io.esphome.clion.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.FileBasedIndex
import io.esphome.clion.index.EsphomeIdIndex
import io.esphome.clion.index.IdDeclaration

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

    /** A located id declaration: [IdDeclaration] plus the file it lives in. */
    data class Declaration(
        val name: String,
        val file: VirtualFile,
        val offset: Int,
        val domain: String,
        val platform: String?,
    )

    /** Every id declared across [scope]. */
    fun declarationsIn(scope: Collection<VirtualFile>): List<Declaration> {
        val index = FileBasedIndex.getInstance()
        val result = ArrayList<Declaration>()
        for (file in scope) {
            val data: Map<String, IdDeclaration> = index.getFileData(EsphomeIdIndex.NAME, file, project)
            for ((name, declaration) in data) {
                result.add(Declaration(name, file, declaration.offset, declaration.domain, declaration.platform))
            }
        }
        return result
    }

    /**
     * Declarations of [name] within [scope]. Usually one; more than one means a
     * duplicate-id error (which Phase 5 can flag).
     */
    fun resolve(name: String, scope: Collection<VirtualFile>): List<Declaration> =
        declarationsIn(scope).filter { it.name == name }

    companion object {
        fun getInstance(project: Project): EsphomeIds = project.service()
    }
}
