package io.esphome.clion.services

import com.intellij.execution.RunManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import io.esphome.clion.psi.EsphomeYaml
import io.esphome.clion.run.EsphomeRunConfiguration
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Identifies the **top-level device config** a YAML file belongs to.
 *
 * A file is top-level iff nothing `!include`s it — so a shared package like
 * `standard.yaml` is *not* top-level even though it has an `esphome:` block,
 * because devices include it. A package is included by several devices, so its
 * effective root is ambiguous; that is resolved by preferring the file of the
 * **selected run configuration**, then a deterministic fallback.
 *
 * Used to decide what `esphome config` validates, and to derive connection
 * parameters in the right device's substitution scope.
 */
object EsphomeConfigRoots {

    /**
     * The device-root config to interpret [file] in: [file] itself when it is
     * top-level, otherwise the config that includes it — preferring the selected
     * run configuration's file, else the lowest-path standalone root. Null for an
     * orphan fragment (no `esphome:`/`packages:`, included by nothing).
     */
    fun effectiveRoot(project: Project, file: VirtualFile): VirtualFile? {
        val graph = EsphomeIncludeGraph.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        val roots = graph.rootsOf(file)

        // Nothing includes [file] → it is its own root (a real top-level config).
        if (file in roots) {
            return file.takeIf { isStandalone(psiManager, it) }
        }

        // Included → it is a package/fragment; choose among the configs that root it,
        // preferring the selected run configuration's file (matched by path).
        val configRoots = roots.filter { isStandalone(psiManager, it) }
        if (configRoots.isEmpty()) return null
        val runConfigPath = runConfigPath(project)
        // pathsEqual normalizes separators/case, since configPath comes from the run
        // config editor as a presentableUrl (native separators), not VirtualFile.path.
        return runConfigPath?.let { wanted -> configRoots.firstOrNull { FileUtil.pathsEqual(it.path, wanted) } }
            ?: configRoots.minByOrNull { it.path }
    }

    /**
     * Whether [file] is itself the top-level config: a standalone config
     * (`esphome:`/`packages:`) that nothing `!include`s. False for an orphan
     * fragment, which has no includer but isn't a config either.
     */
    fun isTopLevel(project: Project, file: VirtualFile): Boolean =
        effectiveRoot(project, file) == file

    private fun isStandalone(psiManager: PsiManager, file: VirtualFile): Boolean =
        (psiManager.findFile(file) as? YAMLFile)?.let(EsphomeYaml::isStandaloneConfig) == true

    /** The config-file path of the selected ESPHome run configuration, if any. */
    private fun runConfigPath(project: Project): String? =
        (RunManager.getInstance(project).selectedConfiguration?.configuration as? EsphomeRunConfiguration)
            ?.configPath?.trim()?.takeIf { it.isNotEmpty() }
}
