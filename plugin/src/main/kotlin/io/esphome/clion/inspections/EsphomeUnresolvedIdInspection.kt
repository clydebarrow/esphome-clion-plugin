package io.esphome.clion.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.esphome.clion.references.EsphomeIdReferences
import io.esphome.clion.services.EsphomeCatalogService
import io.esphome.clion.services.EsphomeIds
import io.esphome.clion.services.EsphomeIncludeGraph
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Flags a *typed* id reference (`output:`, `i2c_id:`, …) whose value names no
 * declaration of a compatible component in the file's `!include` graph, and
 * offers to change it to a near-match. Instant, with a quick-fix — complementary
 * to the `esphome config` validation, which is ground truth but needs a save.
 *
 * High precision by construction: only typed references (the catalog classifies
 * the field) are checked, only when the target class is one we model, and not at
 * all when the scope builds ids from `${substitutions}` (where a literal
 * reference may legitimately match a templated declaration we can't index).
 * Phase 5 of `docs/roadmap-includes-and-navigation.md`.
 */
class EsphomeUnresolvedIdInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? YAMLFile ?: return PsiElementVisitor.EMPTY_VISITOR
        val repo = EsphomeCatalogService.getInstance().repository
        val context: ScopeContext? by lazy { buildContext(file.project, file) }

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val scalar = element as? YAMLScalar ?: return
                val referencesComponent = EsphomeIdReferences.referencedComponentOf(repo, scalar) ?: return
                if (referencesComponent !in repo.referenceableClasses) return
                val name = scalar.textValue
                if (name.isEmpty() || name.contains("\${")) return

                val ctx = context ?: return
                if (ctx.hasTemplatedIds) return // can't be certain; stay quiet
                if (ctx.declarations.any { it.name == name && EsphomeIdReferences.satisfies(repo, it, referencesComponent) }) {
                    return
                }

                val fixes = ctx.declarations.asSequence()
                    .filter { EsphomeIdReferences.satisfies(repo, it, referencesComponent) }
                    .map { it.name }
                    .distinct()
                    .map { it to levenshtein(it, name) }
                    .filter { it.second in 1..MAX_EDITS }
                    .sortedBy { it.second }
                    .take(MAX_SUGGESTIONS)
                    .map { ChangeIdReferenceFix(it.first) as LocalQuickFix }
                    .toList()
                    .toTypedArray()

                holder.registerProblem(
                    scalar,
                    "Cannot resolve id reference '$name' (expected a '$referencesComponent')",
                    *fixes,
                )
            }
        }
    }

    private data class ScopeContext(val declarations: List<EsphomeIds.Declaration>, val hasTemplatedIds: Boolean)

    private fun buildContext(project: Project, file: PsiFile): ScopeContext? {
        val virtualFile = file.originalFile.virtualFile ?: return null
        val scope = EsphomeIncludeGraph.getInstance(project).connectedFiles(virtualFile)
        val declarations = EsphomeIds.getInstance(project).declarationsIn(scope)
        val psiManager = PsiManager.getInstance(project)
        val templated = scope.any { vf -> psiManager.findFile(vf)?.text?.let(TEMPLATED_ID::containsMatchIn) == true }
        return ScopeContext(declarations, templated)
    }

    companion object {
        private const val MAX_EDITS = 2
        private const val MAX_SUGGESTIONS = 3
        private val TEMPLATED_ID = Regex("""(?m)^\s*id:\s*\S*\$\{""")
    }
}

/** Quick-fix: replace an unresolved id reference with a suggested existing id. */
private class ChangeIdReferenceFix(private val newName: String) : LocalQuickFix {
    override fun getName(): String = "Change to '$newName'"
    override fun getFamilyName(): String = "Change id reference"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val scalar = descriptor.psiElement as? YAMLScalar ?: return
        ElementManipulators.getManipulator(scalar)
            ?.handleContentChange(scalar, ElementManipulators.getValueTextRange(scalar), newName)
    }
}

/** Bounded Levenshtein distance, enough to rank typo suggestions. */
private fun levenshtein(a: String, b: String): Int {
    val prev = IntArray(b.length + 1) { it }
    val curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
        }
        prev.indices.forEach { prev[it] = curr[it] }
    }
    return prev[b.length]
}
