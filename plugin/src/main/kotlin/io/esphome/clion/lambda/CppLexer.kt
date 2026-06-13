package io.esphome.clion.lambda

/**
 * A tiny, dependency-free C++ tokenizer — just enough to colour ESPHome lambda
 * bodies and run cheap structural checks. It is a *lexer*, not a parser: it knows
 * tokens (keywords, literals, comments, punctuation) but nothing about grammar,
 * types, or semantics. That's deliberate — real C++ analysis would need the
 * generated translation unit, which only exists at `esphome compile`.
 */
object CppLexer {

    enum class Kind { KEYWORD, IDENT, NUMBER, STRING, CHAR, LINE_COMMENT, BLOCK_COMMENT, OP, WHITESPACE, UNTERMINATED }

    /** A token spanning `[start, end)` of the lexed text. */
    data class Token(val kind: Kind, val start: Int, val end: Int)

    val TRIVIA = setOf(Kind.WHITESPACE, Kind.LINE_COMMENT, Kind.BLOCK_COMMENT)

    private val KEYWORDS = setOf(
        "alignas", "alignof", "and", "auto", "bool", "break", "case", "catch", "char", "class", "const",
        "constexpr", "continue", "default", "delete", "do", "double", "else", "enum", "explicit", "extern",
        "false", "float", "for", "friend", "goto", "if", "inline", "int", "long", "mutable", "namespace",
        "new", "not", "nullptr", "operator", "or", "private", "protected", "public", "register", "return",
        "short", "signed", "sizeof", "static", "static_cast", "reinterpret_cast", "const_cast", "dynamic_cast",
        "struct", "switch", "template", "this", "throw", "true", "try", "typedef", "typename", "union",
        "unsigned", "using", "virtual", "void", "volatile", "while",
        // types commonly written in ESPHome lambdas
        "uint8_t", "uint16_t", "uint32_t", "uint64_t", "int8_t", "int16_t", "int32_t", "int64_t",
        "size_t", "std", "String",
    )

    fun tokenize(s: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c.isWhitespace() -> {
                    val start = i
                    while (i < n && s[i].isWhitespace()) i++
                    tokens.add(Token(Kind.WHITESPACE, start, i))
                }
                c == '/' && i + 1 < n && s[i + 1] == '/' -> {
                    val start = i
                    i += 2
                    while (i < n && s[i] != '\n') i++
                    tokens.add(Token(Kind.LINE_COMMENT, start, i))
                }
                c == '/' && i + 1 < n && s[i + 1] == '*' -> {
                    val start = i
                    i += 2
                    var closed = false
                    while (i < n) {
                        if (s[i] == '*' && i + 1 < n && s[i + 1] == '/') { i += 2; closed = true; break }
                        i++
                    }
                    tokens.add(Token(if (closed) Kind.BLOCK_COMMENT else Kind.UNTERMINATED, start, i))
                }
                c == '"' -> { val (end, ok) = scanQuoted(s, i, '"'); tokens.add(Token(if (ok) Kind.STRING else Kind.UNTERMINATED, i, end)); i = end }
                c == '\'' -> { val (end, ok) = scanQuoted(s, i, '\''); tokens.add(Token(if (ok) Kind.CHAR else Kind.UNTERMINATED, i, end)); i = end }
                c.isDigit() || (c == '.' && i + 1 < n && s[i + 1].isDigit()) -> {
                    val start = i
                    i++
                    while (i < n && (s[i].isLetterOrDigit() || s[i] == '.' || s[i] == '_' ||
                            ((s[i] == '+' || s[i] == '-') && (s[i - 1] == 'e' || s[i - 1] == 'E')))
                    ) i++
                    tokens.add(Token(Kind.NUMBER, start, i))
                }
                c == '_' || c.isLetter() -> {
                    val start = i
                    i++
                    while (i < n && (s[i] == '_' || s[i].isLetterOrDigit())) i++
                    tokens.add(Token(if (s.substring(start, i) in KEYWORDS) Kind.KEYWORD else Kind.IDENT, start, i))
                }
                else -> { tokens.add(Token(Kind.OP, i, i + 1)); i++ }
            }
        }
        return tokens
    }

    /** Scan a `"…"`/`'…'` literal; returns the end index and whether it closed on the same line. */
    private fun scanQuoted(s: String, start: Int, quote: Char): Pair<Int, Boolean> {
        var i = start + 1
        val n = s.length
        while (i < n) {
            when (s[i]) {
                '\\' -> { i += 2; continue }
                quote -> return (i + 1) to true
                '\n' -> return i to false
            }
            i++
        }
        return i to false
    }
}
