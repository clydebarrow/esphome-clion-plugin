package io.esphome.clion.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import io.esphome.clion.index.EsphomeIncludeIndex
import io.esphome.clion.psi.EsphomeYaml
import io.esphome.clion.references.EsphomeInclude
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Resolves the ESPHome `!include` graph: forward edges (what a file includes),
 * reverse edges (who includes a file, via [EsphomeIncludeIndex]), the device
 * roots above a fragment, and the connected graph a file belongs to — its
 * cross-file resolution scope. Phase 2 of
 * `docs/roadmap-includes-and-navigation.md`.
 *
 * All methods read the PSI/index and must be called under a read action.
 */
@Service(Service.Level.PROJECT)
class EsphomeIncludeGraph(private val project: Project) {

    /** Files [file] directly `!include`s, resolved relative to its directory. */
    fun directIncludes(file: VirtualFile): List<VirtualFile> {
        val dir = file.parent ?: return emptyList()
        val yaml = PsiManager.getInstance(project).findFile(file) as? YAMLFile ?: return emptyList()
        val targets = LinkedHashSet<VirtualFile>()
        for (scalar in PsiTreeUtil.findChildrenOfType(yaml, YAMLScalar::class.java)) {
            val path = EsphomeInclude.includePathOf(scalar) ?: continue
            if (path.contains("\${") || path.contains("://")) continue
            dir.findFileByRelativePath(path)?.let(targets::add)
        }
        return targets.toList()
    }

    /**
     * Files that directly `!include` [file]. The index narrows candidates by
     * basename; each is re-resolved precisely so a same-named file in another
     * directory is not a false positive.
     */
    fun directIncluders(file: VirtualFile): List<VirtualFile> {
        val candidates = FileBasedIndex.getInstance()
            .getContainingFiles(EsphomeIncludeIndex.NAME, file.name, GlobalSearchScope.allScope(project))
        return candidates.filter { it != file && directIncludes(it).contains(file) }
    }

    /**
     * Device roots reachable upward from [file] (topmost files with no
     * includer). A fragment included by several devices yields all of them;
     * an orphan fragment yields itself.
     */
    fun rootsOf(file: VirtualFile): Set<VirtualFile> {
        val roots = LinkedHashSet<VirtualFile>()
        val visited = HashSet<VirtualFile>()
        val queue = ArrayDeque<VirtualFile>().apply { add(file) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            val includers = directIncluders(current)
            if (includers.isEmpty()) roots.add(current) else queue.addAll(includers)
        }
        return roots
    }

    /**
     * The whole connected include graph [file] belongs to: every file reachable
     * by going up to the device roots and back down through their includes. This
     * is the scope in which ESPHome ids resolve.
     */
    fun connectedFiles(file: VirtualFile): Set<VirtualFile> {
        val all = LinkedHashSet<VirtualFile>()
        val queue = ArrayDeque(rootsOf(file))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!all.add(current)) continue
            queue.addAll(directIncludes(current))
        }
        all.add(file)
        return all
    }

    /**
     * True if [file] is an ESPHome config we should offer editor features
     * (completion, documentation) for: a standalone config itself — top-level
     * `esphome:` *or* `packages:` (a package commonly supplies the `esphome:`
     * block, so the main file has none of its own) — or a fragment included,
     * directly or transitively, by such a config. The standalone check is cheap
     * and short-circuits, so only included fragments pay for the reverse walk.
     */
    fun isEsphomeConfigContext(file: YAMLFile): Boolean {
        if (EsphomeYaml.isStandaloneConfig(file)) return true
        val vfile = file.originalFile.virtualFile ?: return false
        val psiManager = PsiManager.getInstance(project)
        return rootsOf(vfile).any { root ->
            root != vfile &&
                (psiManager.findFile(root) as? YAMLFile)?.let(EsphomeYaml::isStandaloneConfig) == true
        }
    }

    companion object {
        fun getInstance(project: Project): EsphomeIncludeGraph = project.service()
    }
}
