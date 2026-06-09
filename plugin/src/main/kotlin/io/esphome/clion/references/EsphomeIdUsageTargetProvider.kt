package io.esphome.clion.references

import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageTargetProvider
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Makes an `id:` declaration a Find Usages *target* when the caret sits on it.
 *
 * A YAML scalar isn't a `PsiNamedElement`, so `TargetElementUtil` can't offer it
 * as a target on its own — without this, invoking Find Usages on a declaration
 * reports "Cannot search for usages from this location". Wrapping the scalar as a
 * usage target routes the search through [EsphomeIdFindUsagesHandlerFactory] and
 * [EsphomeIdReferenceSearcher]. Composable (multiple providers allowed), so the
 * bundled YAML plugin is unaffected.
 */
class EsphomeIdUsageTargetProvider : UsageTargetProvider {

    override fun getTargets(editor: Editor, file: PsiFile): Array<UsageTarget>? {
        val scalar = PsiTreeUtil.getParentOfType(
            file.findElementAt(editor.caretModel.offset),
            YAMLScalar::class.java,
            false,
        ) ?: return null
        if (EsphomeIdReferences.declaredIdName(scalar) == null) return null
        // (scalar, true) — the single-arg PsiElement2UsageTargetAdapter(PsiElement)
        // is scheduled for removal; the (element, requiresPsiElement=true) overload
        // is the supported replacement and behaves identically here.
        return arrayOf(PsiElement2UsageTargetAdapter(scalar, true))
    }
}
