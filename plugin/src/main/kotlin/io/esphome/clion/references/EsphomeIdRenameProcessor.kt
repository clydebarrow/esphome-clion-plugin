package io.esphome.clion.references

import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Renames an ESPHome `id:` declaration and every reference to it across the
 * include graph. References are gathered by the rename framework via
 * `ReferencesSearch` (answered by [EsphomeIdReferenceSearcher]); the declaration
 * scalar isn't a `PsiNamedElement`, so its own text is updated through the YAML
 * scalar manipulator. Registered `order="first"` so it claims id declarations
 * before the bundled YAML processor.
 */
class EsphomeIdRenameProcessor : RenamePsiElementProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean =
        element is YAMLScalar && EsphomeIdReferences.declaredIdName(element) != null

    override fun renameElement(
        element: PsiElement,
        newName: String,
        usages: Array<out UsageInfo>,
        listener: RefactoringElementListener?,
    ) {
        for (usage in usages) {
            usage.reference?.handleElementRename(newName)
        }
        val renamed = if (element is YAMLScalar) {
            ElementManipulators.getManipulator(element)
                ?.handleContentChange(element, ElementManipulators.getValueTextRange(element), newName)
                ?: element
        } else {
            element
        }
        listener?.elementRenamed(renamed)
    }
}
