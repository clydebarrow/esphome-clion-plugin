package io.esphome.clion.references

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
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
import io.esphome.clion.services.EsphomeCatalogService
import io.esphome.clion.services.EsphomeIds
import io.esphome.clion.services.EsphomeIncludeGraph
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Resolves ESPHome id references (`output: my_output`, `i2c_id: bus`, …) to the
 * `id:` declaration they name, across the file's `!include` graph and filtered
 * by component type. Enables go-to-definition and find-usages; completion is in
 * the completion contributor. Phase 3 of
 * `docs/roadmap-includes-and-navigation.md`.
 */
class EsphomeIdReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLScalar::class.java),
            EsphomeIdReferenceProvider(),
        )
    }
}

private class EsphomeIdReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val scalar = element as? YAMLScalar ?: return PsiReference.EMPTY_ARRAY
        val name = scalar.textValue
        if (name.isEmpty() || name.contains("\${")) return PsiReference.EMPTY_ARRAY
        val repo = EsphomeCatalogService.getInstance().repository
        val referencesComponent = EsphomeIdReferences.referencedComponentOf(repo, scalar)
            ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(
            EsphomeIdReference(scalar, ElementManipulators.getValueTextRange(scalar), referencesComponent),
        )
    }
}

/**
 * A reference from an id-value scalar to the matching `id:` declaration(s).
 * Poly-variant so a duplicate id surfaces every declaration (Phase 5 can flag
 * it). Soft, so an as-yet-unresolved id is not highlighted as an error — that is
 * an inspection's job, with the leniency substitutions/remote packages need.
 */
class EsphomeIdReference(
    scalar: YAMLScalar,
    rangeInElement: TextRange,
    private val referencesComponent: String,
) : PsiPolyVariantReferenceBase<YAMLScalar>(scalar, rangeInElement, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val scalar = element
        val name = scalar.textValue
        if (name.isEmpty()) return ResolveResult.EMPTY_ARRAY
        val virtualFile = scalar.containingFile?.originalFile?.virtualFile ?: return ResolveResult.EMPTY_ARRAY
        val project = scalar.project
        val scope = EsphomeIncludeGraph.getInstance(project).connectedFiles(virtualFile)
        val repo = EsphomeCatalogService.getInstance().repository
        val psiManager = PsiManager.getInstance(project)
        return EsphomeIds.getInstance(project).resolve(name, scope)
            .filter { EsphomeIdReferences.satisfies(repo, it, referencesComponent) }
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
