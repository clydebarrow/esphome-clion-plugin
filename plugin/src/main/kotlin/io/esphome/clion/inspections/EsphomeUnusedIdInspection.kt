package io.esphome.clion.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.esphome.clion.references.EsphomeIdReferences
import io.esphome.clion.services.EsphomeIncludeGraph
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Flags an `id:` declaration whose name appears nowhere else in the `!include`
 * graph. Uses a whole-word text scan of the connected files (not just resolved
 * references), so an id used only inside a `!lambda` (`id(x)`) still counts as
 * used.
 *
 * **Disabled by default**: ESPHome ids are frequently referenced only
 * externally — by Home Assistant, the native API, or the web server — so an id
 * unused *within the YAML* is often intentional. Opt in via Settings | Editor |
 * Inspections. Phase 5 of `docs/roadmap-includes-and-navigation.md`.
 */
class EsphomeUnusedIdInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? YAMLFile ?: return PsiElementVisitor.EMPTY_VISITOR
        val scopeText: String by lazy { scopeText(file.project, file) }

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val scalar = element as? YAMLScalar ?: return
                val name = EsphomeIdReferences.declaredIdName(scalar) ?: return
                if (wholeWordCount(scopeText, name) > 1) return // the declaration itself is one
                holder.registerProblem(
                    scalar,
                    "ID '$name' is never used in this configuration",
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                )
            }
        }
    }

    private fun scopeText(project: Project, file: PsiFile): String {
        val virtualFile = file.originalFile.virtualFile ?: return file.text
        val scope = EsphomeIncludeGraph.getInstance(project).connectedFiles(virtualFile)
        val psiManager = PsiManager.getInstance(project)
        return scope.mapNotNull { psiManager.findFile(it)?.text }.joinToString("\n")
    }

    private fun wholeWordCount(text: String, word: String): Int =
        Regex("(?<![A-Za-z0-9_])${Regex.escape(word)}(?![A-Za-z0-9_])").findAll(text).count()
}
