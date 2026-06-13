package io.esphome.clion.references

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import io.esphome.clion.services.EsphomeIds
import io.esphome.clion.services.EsphomeIncludeGraph
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Makes `id(<name>)` inside a lambda a navigable reference to the `id: <name>`
 * declaration, so Ctrl-/Cmd-Click and find-usages connect a lambda's component
 * accesses to where the id is defined (across the `!include` graph).
 */
class EsphomeLambdaIdReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLScalar::class.java),
            EsphomeLambdaIdReferenceProvider(),
        )
    }
}

private class EsphomeLambdaIdReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val scalar = element as? YAMLScalar ?: return PsiReference.EMPTY_ARRAY
        val calls = EsphomeLambda.idCalls(scalar)
        if (calls.isEmpty()) return PsiReference.EMPTY_ARRAY
        return calls.map { (range, name) -> EsphomeLambdaIdReference(scalar, range, name) }.toTypedArray()
    }
}

/**
 * Reference from one `id(<name>)` occurrence to the declaration(s) named. Untyped
 * (a lambda can access any component) and soft, so an as-yet-unknown id isn't
 * flagged — the unresolved-id inspection owns that, with the substitution leniency.
 */
private class EsphomeLambdaIdReference(
    scalar: YAMLScalar,
    rangeInElement: TextRange,
    private val name: String,
) : PsiPolyVariantReferenceBase<YAMLScalar>(scalar, rangeInElement, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val virtualFile = element.containingFile?.originalFile?.virtualFile ?: return ResolveResult.EMPTY_ARRAY
        val project = element.project
        val scope = EsphomeIncludeGraph.getInstance(project).connectedFiles(virtualFile)
        val psiManager = PsiManager.getInstance(project)
        return EsphomeIds.getInstance(project).resolve(name, scope)
            .mapNotNull { declaration ->
                val file = psiManager.findFile(declaration.file) ?: return@mapNotNull null
                val leaf = file.findElementAt(declaration.offset) ?: return@mapNotNull null
                val target = PsiTreeUtil.getParentOfType(leaf, YAMLScalar::class.java, false) ?: leaf
                PsiElementResolveResult(target)
            }
            .toTypedArray()
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
