package io.esphome.clion.references

import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Turns the path argument of an ESPHome `!include` into a navigable file
 * reference: Ctrl-Click opens the included file, completion suggests paths, and
 * moving/renaming the target updates the directive. Resolution is relative to
 * the including file's directory, matching ESPHome.
 *
 * Phase 1 of `docs/roadmap-includes-and-navigation.md`.
 */
class EsphomeIncludeReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLScalar::class.java),
            EsphomeIncludeReferenceProvider(),
        )
    }
}

private class EsphomeIncludeReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val scalar = element as? YAMLScalar ?: return PsiReference.EMPTY_ARRAY
        val path = EsphomeInclude.includePathOf(scalar) ?: return PsiReference.EMPTY_ARRAY

        // Skip what a plain file reference cannot resolve: substitution
        // placeholders (`${...}`) and remote packages (`github://`, any URL).
        if (path.contains("\${") || path.contains("://")) return PsiReference.EMPTY_ARRAY

        // The path sits inside the element text (after the tag, inside quotes);
        // anchor the reference set at its offset so ranges line up.
        val startInElement = element.text.indexOf(path)
        if (startInElement < 0) return PsiReference.EMPTY_ARRAY

        @Suppress("UNCHECKED_CAST")
        return FileReferenceSet(path, element, startInElement, this, true)
            .allReferences as Array<PsiReference>
    }
}
