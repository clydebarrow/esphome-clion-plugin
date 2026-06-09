package io.esphome.clion.references

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext
import io.esphome.clion.services.EsphomeSubstitutions
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Turns each `${name}` / `$name` usage in a scalar into a navigable reference to
 * its `substitutions:` definition (Ctrl/Cmd-click, find-usages of the key).
 * Soft, so an as-yet-undefined name isn't error-highlighted — that is the
 * unresolved-substitution inspection's job.
 */
class EsphomeSubstitutionReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLScalar::class.java),
            EsphomeSubstitutionReferenceProvider(),
        )
    }
}

private class EsphomeSubstitutionReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val text = element.text
        if (!text.contains('$')) return PsiReference.EMPTY_ARRAY
        val scalar = element as? YAMLScalar ?: return PsiReference.EMPTY_ARRAY
        return EsphomeSubstitutionSyntax.scan(text)
            .map { EsphomeSubstitutionReference(scalar, it.range, it.name) }
            .toTypedArray()
    }
}

/** A reference from a `${name}` usage to its `substitutions:` definition key. */
class EsphomeSubstitutionReference(
    scalar: YAMLScalar,
    rangeInElement: TextRange,
    private val name: String,
) : PsiReferenceBase<YAMLScalar>(scalar, rangeInElement, /* soft = */ true) {

    override fun resolve(): PsiElement? {
        val virtualFile = element.containingFile?.originalFile?.virtualFile ?: return null
        return EsphomeSubstitutions.getInstance(element.project).resolve(name, virtualFile)?.keyValue
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
