package io.esphome.clion.references

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import io.esphome.clion.services.EsphomeIncludeGraph
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Makes a secrets-file `key:` declaration findable from the *declaration* side:
 * a `ReferencesSearch` over it yields every [EsphomeSecretReference] that
 * resolves to it.
 *
 * Needed for the same reason as [EsphomeIdReferenceSearcher]: our `!secret`
 * references are soft and resolve forward only (usage → declaration), and the
 * default caches-based searcher can't walk that backwards on its own — without
 * this, Find Usages on a declaration reports "no usages found" even though
 * every `!secret <name>` in the project resolves to it.
 *
 * Deliberately does **not** use the project's file index/`GlobalSearchScope`:
 * that only covers files under a registered module content root, and a
 * `secrets.yaml` doesn't have to sit inside one — e.g. a device-config
 * directory opened standalone, or attached alongside an unrelated project in
 * the same IDE window (confirmed by a real report: `GlobalSearchScope.allScope`
 * returned files from a *different*, unrelated attached project, zero from
 * the actual device-config tree). [EsphomeSecret.findSecretsFile] already
 * resolves `!secret` purely by filesystem proximity (walk up from a device
 * config to find the nearest `secrets.yaml`) — matching ESPHome's own
 * resolution, which knows nothing about IDE project structure either. This
 * searcher mirrors that: it walks the filesystem *down* from the declaration
 * file's own directory (and from any file that directly `<<: !include`s it,
 * for a merge-included fragment living in a subdirectory of its own), which
 * is the complete, correct reach of "somewhere a device config could resolve
 * this secret from" — independent of module/content-root registration.
 */
class EsphomeSecretReferenceSearcher :
    QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(/* readAction = */ true) {

    override fun processQuery(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>,
    ) {
        val target = queryParameters.elementToSearch
        if (EsphomeSecret.declaredSecretName(target) == null) return
        val declarationFile = target.containingFile?.originalFile?.virtualFile ?: return
        val project = target.project
        val psiManager = PsiManager.getInstance(project)

        val roots = LinkedHashSet<VirtualFile>()
        declarationFile.parent?.let(roots::add)
        // A merge-included fragment (e.g. secrets/location1.yaml) may live in a
        // subdirectory of where device configs actually are — also walk from
        // whatever directly includes it.
        EsphomeIncludeGraph.getInstance(project).directIncluders(declarationFile).forEach { includer ->
            includer.parent?.let(roots::add)
        }

        val files = LinkedHashSet<VirtualFile>()
        for (root in roots) {
            VfsUtilCore.iterateChildrenRecursively(
                root,
                { file -> !file.isDirectory || !file.name.startsWith(".") }, // skip .git/.idea/.esphome/...
                { file ->
                    val ext = file.extension
                    if (!file.isDirectory && (ext.equals("yaml", true) || ext.equals("yml", true))) files.add(file)
                    true
                },
            )
        }

        for (file in files) {
            try {
                val yaml = psiManager.findFile(file) as? YAMLFile ?: continue
                for (scalar in PsiTreeUtil.findChildrenOfType(yaml, YAMLScalar::class.java)) {
                    if (EsphomeSecret.secretNameOf(scalar) == null) continue
                    for (reference in scalar.references) {
                        if (reference is EsphomeSecretReference && reference.isReferenceTo(target)) {
                            consumer.process(reference)
                        }
                    }
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                // One file's content (a stray YAML construct, a broken merge-include
                // elsewhere, ...) must not silently zero out results in every other
                // file — Find Usages would otherwise report "no usages found" across
                // the whole project because of a single unrelated file.
                thisLogger().warn("Failed to search ${file.path} for secret references", e)
            }
        }
    }
}
