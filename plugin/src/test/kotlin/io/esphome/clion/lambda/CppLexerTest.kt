package io.esphome.clion.lambda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CppLexerTest {

    private fun kindsOf(text: String, code: String): List<CppLexer.Kind> =
        CppLexer.tokenize(text).filter { text.substring(it.start, it.end) == code }.map { it.kind }

    @Test
    fun `classifies keywords, numbers, strings, chars and comments`() {
        val src = """return id(x).state > 3.5f ? "on" : 'c'; // done"""
        val tokens = CppLexer.tokenize(src)
        assertEquals(listOf(CppLexer.Kind.KEYWORD), kindsOf(src, "return"))
        assertEquals(listOf(CppLexer.Kind.IDENT), kindsOf(src, "id"))
        assertEquals(listOf(CppLexer.Kind.NUMBER), kindsOf(src, "3.5f"))
        assertTrue(tokens.any { it.kind == CppLexer.Kind.STRING && src.substring(it.start, it.end) == "\"on\"" })
        assertTrue(tokens.any { it.kind == CppLexer.Kind.CHAR && src.substring(it.start, it.end) == "'c'" })
        assertTrue(tokens.any { it.kind == CppLexer.Kind.LINE_COMMENT })
    }

    @Test
    fun `a string brace is not punctuation and an escaped quote does not end the string`() {
        val src = """log("a { ) \" b");"""
        val tokens = CppLexer.tokenize(src)
        val str = tokens.single { it.kind == CppLexer.Kind.STRING }
        assertEquals("\"a { ) \\\" b\"", src.substring(str.start, str.end))
        // the only OP-classified brackets are the call parens, not those in the string
        assertEquals(2, tokens.count { it.kind == CppLexer.Kind.OP && src[it.start] in "()" })
    }

    @Test
    fun `flags an unterminated string`() {
        val tokens = CppLexer.tokenize("""return "oops;""")
        assertTrue(tokens.any { it.kind == CppLexer.Kind.UNTERMINATED })
    }
}
