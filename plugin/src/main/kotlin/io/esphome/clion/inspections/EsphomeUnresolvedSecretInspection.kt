package io.esphome.clion.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import io.esphome.clion.references.EsphomeSecret
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Flags a `!secret <name>` value that names no key in the nearest secrets file
 * (or the file it directly `<<: !include`s — see [EsphomeSecret.secretEntries]) —
 * instant, with a quick-fix suggesting a near-match. Complementary to
 * `esphome config` validation, which is ground truth but needs a save.
 *
 * Conservative to avoid false positives: templated names (`${...}`) are never
 * checked, and nothing is flagged when no secrets file can be found at all
 * (the project may provision secrets some other way this plugin doesn't see).
 */
class EsphomeUnresolvedSecretInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? YAMLFile ?: return PsiElementVisitor.EMPTY_VISITOR
        val virtualFile = file.originalFile.virtualFile ?: return PsiElementVisitor.EMPTY_VISITOR
        val project = file.project
        val entries: Map<String, YAMLKeyValue>? by lazy {
            EsphomeSecret.findSecretsFile(virtualFile, project)?.let { EsphomeSecret.secretEntries(project, it) }
        }

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val scalar = element as? YAMLScalar ?: return
                val name = EsphomeSecret.secretNameOf(scalar) ?: return
                if (name.contains("\${")) return // templated — can't check statically

                val known = entries ?: return // no secrets file found; don't guess
                if (name in known.keys) return

                val start = scalar.text.indexOf(name)
                if (start < 0) return
                val range = TextRange(start, start + name.length)

                val fixes = known.keys.asSequence()
                    .map { it to levenshtein(it, name) }
                    .filter { it.second in 1..MAX_EDITS }
                    .sortedBy { it.second }
                    .take(MAX_SUGGESTIONS)
                    .map { ChangeSecretNameFix(it.first) as LocalQuickFix }
                    .toList()
                    .toTypedArray()

                holder.registerProblem(scalar, range, "Unresolved secret '$name'", *fixes)
            }
        }
    }

    companion object {
        private const val MAX_EDITS = 2
        private const val MAX_SUGGESTIONS = 3
    }
}

/** Quick-fix: replace an unresolved secret name with a suggested existing one. */
private class ChangeSecretNameFix(private val newName: String) : LocalQuickFix {
    override fun getName(): String = "Change to '$newName'"
    override fun getFamilyName(): String = "Change secret name"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val range = descriptor.textRangeInElement ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile) ?: return
        val base = element.textRange.startOffset
        document.replaceString(base + range.startOffset, base + range.endOffset, newName)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}
