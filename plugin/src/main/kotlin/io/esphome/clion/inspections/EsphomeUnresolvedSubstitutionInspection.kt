package io.esphome.clion.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import io.esphome.clion.references.EsphomeSubstitutionSyntax
import io.esphome.clion.services.EsphomeSubstitutions
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Flags an explicit `${name}` substitution whose name is defined nowhere in the
 * file's `!include`/`packages:` graph, offering a near-match quick-fix.
 *
 * Conservative to avoid false positives: only the braced `${...}` form is
 * checked (bare `$name` is too easily a literal `$`); names supplied via
 * `!include … vars:` count as defined; and nothing is flagged when the scope
 * defines no substitutions at all (the config may rely on command-line `-s`).
 * Phase 6 of `docs/roadmap-includes-and-navigation.md`.
 */
class EsphomeUnresolvedSubstitutionInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? YAMLFile ?: return PsiElementVisitor.EMPTY_VISITOR
        val substitutions = EsphomeSubstitutions.getInstance(file.project)
        val virtualFile = file.originalFile.virtualFile

        val known: Set<String> by lazy { virtualFile?.let(substitutions::knownNamesInScope) ?: emptySet() }
        val definedNames: List<String> by lazy {
            virtualFile?.let { substitutions.definitionsInScope(it).keys.toList() } ?: emptyList()
        }

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val scalar = element as? YAMLScalar ?: return
                val text = scalar.text
                if (!text.contains("\${")) return
                if (known.isEmpty()) return // nothing defined anywhere; assume external provisioning

                for (usage in EsphomeSubstitutionSyntax.scan(text)) {
                    if (!usage.braced || usage.name in known) continue
                    val fixes = definedNames.asSequence()
                        .map { it to levenshtein(it, usage.name) }
                        .filter { it.second in 1..MAX_EDITS }
                        .sortedBy { it.second }
                        .take(MAX_SUGGESTIONS)
                        .map { ReplaceSubstitutionNameFix(it.first) as LocalQuickFix }
                        .toList()
                        .toTypedArray()
                    holder.registerProblem(scalar, usage.range, "Unresolved substitution '${usage.name}'", *fixes)
                }
            }
        }
    }

    companion object {
        private const val MAX_EDITS = 2
        private const val MAX_SUGGESTIONS = 3
    }
}

/** Quick-fix: replace an unresolved substitution name with a suggested one. */
private class ReplaceSubstitutionNameFix(private val newName: String) : LocalQuickFix {
    override fun getName(): String = "Change to '$newName'"
    override fun getFamilyName(): String = "Change substitution"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val range = descriptor.textRangeInElement ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile) ?: return
        val base = element.textRange.startOffset
        document.replaceString(base + range.startOffset, base + range.endOffset, newName)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}
