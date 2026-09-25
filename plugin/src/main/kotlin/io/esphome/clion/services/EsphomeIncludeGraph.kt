package io.esphome.clion.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
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
     *
     * The index only covers files under a registered module content root, and a
     * device-config directory doesn't have to be one (opened standalone as a
     * CLion "empty module", or attached next to an unrelated project) — and it
     * is unavailable in dumb mode. In either case candidates come from a
     * bounded filesystem walk near [file] instead, so ids, Find Usages and the
     * Structure view still see the whole include graph.
     */
    fun directIncluders(file: VirtualFile): List<VirtualFile> {
        val dumb = DumbService.isDumb(project)
        val inContent = ProjectFileIndex.getInstance(project).isInContent(file)
        val indexed = if (!dumb && inContent) {
            FileBasedIndex.getInstance()
                .getContainingFiles(EsphomeIncludeIndex.NAME, file.name, GlobalSearchScope.allScope(project))
                .filter { it != file && directIncludes(it).contains(file) }
        } else {
            emptyList()
        }
        if (indexed.isNotEmpty()) return indexed
        // Nothing from the index: it may simply not cover these files, so look on
        // disk before concluding [file] is a top-level config.
        val nearby = nearbyIncludeCandidates(file).filter { it != file && directIncludes(it).contains(file) }
        return nearby
    }

    /**
     * YAML files near [file] whose text mentions its name: everything below its
     * own directory, plus the sibling directories one level up (a shared
     * `../standard.yaml` is included from sibling device folders). Bounded, and
     * skips hidden/build directories, so a config sitting in a huge tree can't
     * turn this into a whole-disk scan.
     */
    private fun nearbyIncludeCandidates(file: VirtualFile): List<VirtualFile> {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return nearbyYamlFilesMentioning(file)
        return CachedValuesManager.getCachedValue(psi) {
            CachedValueProvider.Result.create(
                nearbyYamlFilesMentioning(file),
                PsiModificationTracker.getInstance(project),
                VirtualFileManager.getInstance(),
            )
        }
    }

    private fun nearbyYamlFilesMentioning(file: VirtualFile): List<VirtualFile> {
        val dir = file.parent ?: return emptyList()
        val psiManager = PsiManager.getInstance(project)
        val found = LinkedHashSet<VirtualFile>()
        var budget = WALK_BUDGET
        fun walk(current: VirtualFile, depth: Int) {
            if (budget <= 0) return
            for (child in current.children) {
                if (--budget <= 0) return
                if (child.isDirectory) {
                    if (depth > 0 && !child.name.startsWith(".") && child.name !in SKIPPED_DIRS) walk(child, depth - 1)
                } else if (child.extension.let { it == "yaml" || it == "yml" }) {
                    val yaml = psiManager.findFile(child) as? YAMLFile ?: continue
                    if (yaml.text.contains(file.name)) found.add(child)
                }
            }
        }
        walk(dir, OWN_DIR_DEPTH)
        dir.parent?.let { walk(it, SIBLING_DIR_DEPTH) }
        return found.toList()
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
     * The *downward* include closure of [root]: [root] plus every file it
     * `!include`s, transitively. Unlike [connectedFiles] this does not walk up to
     * other roots, so a shared package's siblings (other devices that include it)
     * are excluded — the right scope for resolving one device's substitutions.
     */
    fun includeClosure(root: VirtualFile): Set<VirtualFile> {
        val all = LinkedHashSet<VirtualFile>()
        val queue = ArrayDeque<VirtualFile>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!all.add(current)) continue
            queue.addAll(directIncludes(current))
        }
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
        private const val WALK_BUDGET = 20_000
        private const val OWN_DIR_DEPTH = 4
        private const val SIBLING_DIR_DEPTH = 2
        private val SKIPPED_DIRS = setOf("build", "node_modules", "venv", "__pycache__", "target", "out")

        fun getInstance(project: Project): EsphomeIncludeGraph = project.service()
    }
}
