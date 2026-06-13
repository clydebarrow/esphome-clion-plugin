package io.esphome.clion.lambda

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.esphome.clion.references.EsphomeLambda
import io.esphome.clion.services.EsphomeIncludeGraph
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Lightweight C++ awareness for ESPHome lambda bodies: lexer-based syntax
 * highlighting (keywords, literals, comments) plus a few reliable structural
 * checks (unbalanced brackets, unterminated literals, a likely-missing trailing
 * `;`). Works in every IDE — it needs no C++ plugin — at the cost of being
 * lexical only: no type/identifier/semantic analysis (see [LambdaChecks]).
 */
class EsphomeLambdaAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val scalar = element as? YAMLScalar ?: return
        if (!EsphomeLambda.isLambda(scalar)) return
        val file = scalar.containingFile as? YAMLFile ?: return
        if (!EsphomeIncludeGraph.getInstance(file.project).isEsphomeConfigContext(file)) return

        val source = lambdaSource(scalar) ?: return
        if (source.text.isBlank()) return
        val tokens = CppLexer.tokenize(source.text)

        for (token in tokens) {
            highlightKey(token.kind)?.let { key ->
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(source.documentRange(token.start, token.end))
                    .textAttributes(key)
                    .create()
            }
        }

        for (problem in LambdaChecks.problems(source.text, tokens)) {
            val severity = if (problem.severity == LambdaChecks.Severity.ERROR) HighlightSeverity.ERROR else HighlightSeverity.WARNING
            // Underline the whole offending line, not just the one token — a
            // single-character squiggle is easy to miss.
            holder.newAnnotation(severity, problem.message)
                .range(source.lineRange(problem.start, problem.end))
                .create()
        }
    }

    private fun highlightKey(kind: CppLexer.Kind): TextAttributesKey? = when (kind) {
        CppLexer.Kind.KEYWORD -> DefaultLanguageHighlighterColors.KEYWORD
        CppLexer.Kind.NUMBER -> DefaultLanguageHighlighterColors.NUMBER
        CppLexer.Kind.STRING, CppLexer.Kind.CHAR -> DefaultLanguageHighlighterColors.STRING
        CppLexer.Kind.LINE_COMMENT -> DefaultLanguageHighlighterColors.LINE_COMMENT
        CppLexer.Kind.BLOCK_COMMENT -> DefaultLanguageHighlighterColors.BLOCK_COMMENT
        else -> null
    }

    /**
     * The lambda's C++ text with a per-character map back to document offsets.
     * Built via the scalar's [com.intellij.psi.LiteralTextEscaper] — the platform
     * mechanism language injection uses — so a block scalar's `|-` header and
     * per-line indentation are excluded and quotes/escapes are handled, while
     * highlight/error ranges still land on the right characters.
     */
    private class Source(val text: String, private val offsets: IntArray) {
        fun documentRange(start: Int, end: Int): TextRange =
            TextRange(offsets[start], offsets[end - 1] + 1)

        /**
         * [documentRange] widened to the full text line(s) spanning `[start, end)`,
         * with surrounding whitespace trimmed — so a problem underlines its whole
         * statement/line instead of one character.
         */
        fun lineRange(start: Int, end: Int): TextRange {
            var s = start
            while (s > 0 && text[s - 1] != '\n') s--
            var e = end
            while (e < text.length && text[e] != '\n') e++
            while (s < e && text[s].isWhitespace()) s++
            while (e > s && text[e - 1].isWhitespace()) e--
            return if (s < e) documentRange(s, e) else documentRange(start, end)
        }
    }

    private fun lambdaSource(scalar: YAMLScalar): Source? {
        val escaper = scalar.createLiteralTextEscaper()
        val relevant = escaper.relevantTextRange
        val decoded = StringBuilder()
        if (!escaper.decode(relevant, decoded) || decoded.isEmpty()) return null
        val base = scalar.textRange.startOffset
        val offsets = IntArray(decoded.length) { i ->
            val inHost = escaper.getOffsetInHost(i, relevant)
            base + (if (inHost >= 0) inHost else relevant.endOffset)
        }
        return Source(decoded.toString(), offsets)
    }
}
