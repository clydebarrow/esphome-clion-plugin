package io.esphome.clion.references

import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageTargetProvider
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Makes a secrets-file `key:` declaration a Find Usages *target* when the caret
 * sits on it. Belt-and-braces: the bundled YAML plugin already treats a
 * `YAMLKeyValue` as a `PsiNamedElement`, so the platform's own default handling
 * typically finds it without this — but this covers any invocation path where
 * that default doesn't kick in. Composable (multiple providers allowed), so
 * the bundled YAML plugin and [EsphomeIdUsageTargetProvider] are unaffected.
 */
class EsphomeSecretUsageTargetProvider : UsageTargetProvider {

    override fun getTargets(editor: Editor, file: PsiFile): Array<UsageTarget>? {
        val element = file.findElementAt(editor.caretModel.offset) ?: return null
        val keyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false) ?: return null
        if (EsphomeSecret.declaredSecretName(keyValue) == null) return null
        return arrayOf(PsiElement2UsageTargetAdapter(keyValue, true))
    }
}
