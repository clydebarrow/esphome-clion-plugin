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
import io.esphome.clion.references.EsphomeIdReferences
import io.esphome.clion.services.EsphomeCatalogService
import io.esphome.clion.services.EsphomeIds
import io.esphome.clion.services.EsphomeIncludeGraph
import io.esphome.clion.services.EsphomeSubstitutions
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Flags a *typed* id reference (`output:`, `i2c_id:`, …) whose value names no
 * declaration of a compatible component in the file's `!include` graph, and
 * offers to change it to a near-match. Instant, with a quick-fix — complementary
 * to the `esphome config` validation, which is ground truth but needs a save.
 *
 * High precision by construction: only typed references (the catalog classifies
 * the field) are checked, only when the target class is one we model. Templated
 * references and declarations are resolved through their `${substitution}`
 * values; if the reference or any declaration can't be fully expanded, the check
 * stays quiet rather than risk a false positive.
 * Phase 5 of `docs/roadmap-includes-and-navigation.md`.
 */
class EsphomeUnresolvedIdInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? YAMLFile ?: return PsiElementVisitor.EMPTY_VISITOR
        val repo = EsphomeCatalogService.getInstance().repository
        val substitutions = EsphomeSubstitutions.getInstance(file.project)
        val context: ScopeContext? by lazy { buildContext(file.project, file) }

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val scalar = element as? YAMLScalar ?: return
                val referencesComponent = EsphomeIdReferences.referencedComponentOf(repo, scalar) ?: return
                if (referencesComponent !in repo.referenceableClasses) return
                val raw = scalar.textValue
                if (raw.isEmpty()) return

                val ctx = context ?: return
                val name = if (raw.contains('$')) substitutions.expand(raw, ctx.definitions) else raw
                if (name.contains("\${")) return // undefined substitution in the reference itself
                if (ctx.declarations.any {
                        it.effectiveName == name && EsphomeIdReferences.satisfies(repo, it, referencesComponent)
                    }
                ) {
                    return
                }
                if (ctx.hasUnexpandableDeclaration) return // a templated id we couldn't expand might be the target

                val fixes = ctx.declarations.asSequence()
                    .filter { EsphomeIdReferences.satisfies(repo, it, referencesComponent) }
                    .map { it.effectiveName }
                    .filter { !it.contains('$') }
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
                    "Cannot resolve id reference '$raw' (expected a '$referencesComponent')",
                    *fixes,
                )
            }
        }
    }

    private data class ScopeContext(
        val declarations: List<EsphomeIds.Declaration>,
        val definitions: Map<String, EsphomeSubstitutions.Definition>,
        val hasUnexpandableDeclaration: Boolean,
    )

    private fun buildContext(project: Project, file: PsiFile): ScopeContext? {
        val virtualFile = file.originalFile.virtualFile ?: return null
        val scope = EsphomeIncludeGraph.getInstance(project).connectedFiles(virtualFile)
        val declarations = EsphomeIds.getInstance(project).declarationsIn(scope)
        val definitions = EsphomeSubstitutions.getInstance(project).definitionsIn(scope)
        val hasUnexpandable = declarations.any { it.effectiveName.contains('$') }
        return ScopeContext(declarations, definitions, hasUnexpandable)
    }

    companion object {
        private const val MAX_EDITS = 2
        private const val MAX_SUGGESTIONS = 3
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
