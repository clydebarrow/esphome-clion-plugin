package io.esphome.clion.references

import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Makes an `id:` declaration a *named element* under the caret, so
 * `TargetElementUtil` recognises it. That gives the "Go To Declaration or
 * Usages" gesture (Cmd-click / Cmd-B) the standard behaviour on a declaration —
 * showing its usages — matching other languages.
 *
 * Only id declarations are claimed; everything else returns null so the
 * platform's default named-element walk still applies (YAML keys etc. stay
 * targetable). YAML registers no evaluator of its own, so this adds rather than
 * overrides.
 */
class EsphomeTargetElementEvaluator : TargetElementEvaluatorEx2() {

    override fun getNamedElement(element: PsiElement): PsiElement? {
        val scalar = PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java, false) ?: return null
        return scalar.takeIf { EsphomeIdReferences.declaredIdName(it) != null }
    }
}
