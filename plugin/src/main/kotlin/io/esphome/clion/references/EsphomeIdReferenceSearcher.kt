package io.esphome.clion.references

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import io.esphome.clion.services.EsphomeIncludeGraph
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Makes `id:` declarations findable from the *declaration* side: a
 * `ReferencesSearch` over an id declaration scalar yields every
 * [EsphomeIdReference] that resolves to it, across the file's `!include` graph.
 *
 * This is the keystone for Find Usages and Rename — our references are soft and
 * resolve forward (reference → declaration), but the default caches-based
 * searcher can't walk that backwards because a YAML scalar isn't a
 * `PsiNamedElement`. Phase 3 follow-up (`docs/roadmap-includes-and-navigation.md`).
 */
class EsphomeIdReferenceSearcher :
    QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(/* readAction = */ true) {

    override fun processQuery(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>,
    ) {
        val target = queryParameters.elementToSearch as? YAMLScalar ?: return
        val name = EsphomeIdReferences.declaredIdName(target) ?: return
        val project = target.project
        val virtualFile = target.containingFile?.originalFile?.virtualFile ?: return
        val psiManager = PsiManager.getInstance(project)

        for (file in EsphomeIncludeGraph.getInstance(project).connectedFiles(virtualFile)) {
            val yaml = psiManager.findFile(file) as? YAMLFile ?: continue
            for (scalar in PsiTreeUtil.findChildrenOfType(yaml, YAMLScalar::class.java)) {
                if (scalar.textValue != name) continue
                for (reference in scalar.references) {
                    if (reference is EsphomeIdReference && reference.isReferenceTo(target)) {
                        consumer.process(reference)
                    }
                }
            }
        }
    }
}
