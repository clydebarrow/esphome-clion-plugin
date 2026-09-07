package io.esphome.clion.references

import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Makes an `id:` declaration or a secrets-file `key:` declaration a *named
 * element* under the caret, so `TargetElementUtil` recognises it. That gives
 * the "Go To Declaration or Usages" gesture (Cmd-click / Cmd-B) the standard
 * behaviour on a declaration — showing its usages — matching other languages.
 *
 * This is a per-language singleton extension point (only one evaluator applies
 * per language), so every kind of ESPHome declaration we want targetable has to
 * be claimed here rather than in a second registration. Everything else returns
 * null so the platform's default named-element walk still applies (YAML keys
 * etc. stay targetable). YAML registers no evaluator of its own, so this adds
 * rather than overrides.
 *
 * An id declaration is a scalar *value* (`id: name`), wrapped in a `YAMLScalar`
 * composite — [element] must be walked up to find it. A secret declaration is
 * the *key* side (`name: value`); the bundled YAML plugin already treats the
 * whole `YAMLKeyValue` as a `PsiNamedElement` there (confirmed by logging what
 * a real Cmd-click actually resolves to), so [EsphomeSecret.declaredSecretName]
 * expects that shape too — walk up to it the same way as the id case.
 */
class EsphomeTargetElementEvaluator : TargetElementEvaluatorEx2() {

    override fun getNamedElement(element: PsiElement): PsiElement? {
        val scalar = PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java, false)
        if (scalar != null && EsphomeIdReferences.declaredIdName(scalar) != null) return scalar
        val keyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false)
        if (keyValue != null && EsphomeSecret.declaredSecretName(keyValue) != null) return keyValue
        return null
    }
}
