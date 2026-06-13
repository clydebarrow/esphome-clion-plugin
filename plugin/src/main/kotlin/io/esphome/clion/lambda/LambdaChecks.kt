package io.esphome.clion.lambda

/**
 * Cheap, low-false-positive structural checks over a lexed lambda body. Pure
 * (text-coordinate problems, no PSI), so it's unit-tested directly; the annotator
 * maps the ranges back to the document.
 *
 * Only checks that a lexer can do *reliably* are here — unbalanced
 * brackets, unterminated literals, and a missing trailing `;`. Deeper checks
 * (per-statement semicolons, type/identifier resolution, whether a `return` is
 * required) need a real parser / the generated translation unit and would
 * produce false positives, so they're intentionally omitted.
 */
object LambdaChecks {

    enum class Severity { ERROR, WARNING }

    data class Problem(val start: Int, val end: Int, val severity: Severity, val message: String)

    private val PAIRS = mapOf(')' to '(', ']' to '[', '}' to '{')

    fun problems(text: String, tokens: List<CppLexer.Token>): List<Problem> {
        val out = ArrayList<Problem>()

        // Unterminated string/char/block comment (the lexer flags these).
        tokens.filter { it.kind == CppLexer.Kind.UNTERMINATED }
            .forEach { out.add(Problem(it.start, it.end, Severity.ERROR, "Unterminated string or comment")) }

        // Bracket balance — '(' '[' '{'. Angle brackets are left out (ambiguous
        // with comparison/shift). Brackets inside strings/comments aren't OP tokens.
        //
        // On a closer, if the top opener doesn't match it but a matching opener sits
        // deeper, the openers above it were never closed — report *those* (at their
        // own line, e.g. the line with the missing ')'), rather than blaming the
        // closer. A closer with no matching opener at all is a stray bracket.
        val openers = ArrayDeque<CppLexer.Token>()
        for (token in tokens) {
            if (token.kind != CppLexer.Kind.OP) continue
            when (val ch = text[token.start]) {
                '(', '[', '{' -> openers.addLast(token)
                ')', ']', '}' -> {
                    val wanted = PAIRS[ch]
                    if (openers.any { text[it.start] == wanted }) {
                        while (text[openers.last().start] != wanted) {
                            val unclosed = openers.removeLast()
                            out.add(Problem(unclosed.start, unclosed.end, Severity.ERROR, "Unclosed '${text[unclosed.start]}'"))
                        }
                        openers.removeLast() // matched
                    } else {
                        out.add(Problem(token.start, token.end, Severity.ERROR, "Unbalanced '$ch'"))
                    }
                }
            }
        }
        openers.forEach { out.add(Problem(it.start, it.end, Severity.ERROR, "Unclosed '${text[it.start]}'")) }

        // The body should end with a complete statement (`;` or a `}` block). If
        // the last real token is anything else, a trailing semicolon is likely
        // missing — the most common ESPHome lambda mistake.
        val last = tokens.lastOrNull { it.kind !in CppLexer.TRIVIA }
        if (last != null && last.kind != CppLexer.Kind.UNTERMINATED) {
            val ends = last.kind == CppLexer.Kind.OP && (text[last.start] == ';' || text[last.start] == '}')
            if (!ends) {
                out.add(Problem(last.start, last.end, Severity.WARNING, "Lambda may be missing a ';' (each statement ends with one)"))
            }
        }
        return out
    }
}
