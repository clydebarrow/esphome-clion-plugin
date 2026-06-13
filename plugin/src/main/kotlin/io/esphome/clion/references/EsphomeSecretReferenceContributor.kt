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
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Makes a `!secret <name>` value navigable: Ctrl-/Cmd-Click (or go-to-definition)
 * jumps to the `name:` declaration in the nearest `secrets.yaml`.
 */
class EsphomeSecretReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLScalar::class.java),
            EsphomeSecretReferenceProvider(),
        )
    }
}

private class EsphomeSecretReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val scalar = element as? YAMLScalar ?: return PsiReference.EMPTY_ARRAY
        val name = EsphomeSecret.secretNameOf(scalar) ?: return PsiReference.EMPTY_ARRAY
        if (name.contains("\${")) return PsiReference.EMPTY_ARRAY // templated — can't resolve statically
        // The name sits after the `!secret ` tag inside the element text; anchor the
        // reference range over it so the link/underline lines up with the name.
        val start = element.text.indexOf(name)
        if (start < 0) return PsiReference.EMPTY_ARRAY
        return arrayOf(EsphomeSecretReference(scalar, TextRange(start, start + name.length), name))
    }
}

/**
 * A soft reference from a `!secret <name>` scalar to its declaration. Soft so a
 * missing `secrets.yaml` (or an as-yet-undefined secret) isn't flagged as an
 * error — navigation simply does nothing until the target exists.
 */
private class EsphomeSecretReference(
    scalar: YAMLScalar,
    rangeInElement: TextRange,
    private val name: String,
) : PsiReferenceBase<YAMLScalar>(scalar, rangeInElement, /* soft = */ true) {

    override fun resolve(): PsiElement? {
        val from = element.containingFile?.originalFile?.virtualFile ?: return null
        val keyValue = EsphomeSecret.resolveSecret(element.project, from, name) ?: return null
        return keyValue.key ?: keyValue
    }
}
