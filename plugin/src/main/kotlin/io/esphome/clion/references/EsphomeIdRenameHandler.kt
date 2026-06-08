package io.esphome.clion.references

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameHandler
import com.intellij.refactoring.rename.RenameProcessor
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Lets Rename be invoked directly on an `id:` declaration. A YAML scalar isn't a
 * `PsiNamedElement`, so `TargetElementUtil` won't offer it as a rename target on
 * its own; this handler recognises the declaration at the caret and drives the
 * rename through [EsphomeIdRenameProcessor]. Renaming *from a reference* already
 * works via the standard handler (it resolves to the declaration), so this only
 * claims the declaration case and leaves everything else to the platform.
 */
class EsphomeIdRenameHandler : RenameHandler {

    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean =
        declarationAtCaret(dataContext) != null

    override fun isRenaming(dataContext: DataContext): Boolean = isAvailableOnDataContext(dataContext)

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext) {
        val declaration = declarationAtCaret(dataContext) ?: return
        val newName = PsiElementRenameHandler.DEFAULT_NAME.getData(dataContext)
        if (newName != null) {
            RenameProcessor(project, declaration, newName, false, false).run()
        } else {
            PsiElementRenameHandler.rename(declaration, project, declaration, editor)
        }
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext) {
        // Only invoked with an explicit element set; we drive rename from the caret instead.
        invoke(project, null, null, dataContext)
    }

    private fun declarationAtCaret(dataContext: DataContext): YAMLScalar? {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return null
        val scalar = PsiTreeUtil.getParentOfType(
            file.findElementAt(editor.caretModel.offset),
            YAMLScalar::class.java,
            false,
        ) ?: return null
        return scalar.takeIf { EsphomeIdReferences.declaredIdName(it) != null }
    }
}
