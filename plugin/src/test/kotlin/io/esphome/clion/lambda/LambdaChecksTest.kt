package io.esphome.clion.lambda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LambdaChecksTest {

    private fun check(src: String): List<LambdaChecks.Problem> =
        LambdaChecks.problems(src, CppLexer.tokenize(src))

    @Test
    fun `clean single-statement lambda has no problems`() {
        assertTrue(check("id(relay).turn_on();").isEmpty())
        assertTrue(check("return id(sensor).state > 5;").isEmpty())
    }

    @Test
    fun `a block ending in a brace is fine`() {
        assertTrue(check("if (id(x).state) { id(led).turn_on(); }").isEmpty())
    }

    @Test
    fun `missing trailing semicolon is a warning`() {
        val problems = check("id(relay).turn_on()")
        assertEquals(1, problems.size)
        assertEquals(LambdaChecks.Severity.WARNING, problems[0].severity)
        assertTrue(problems[0].message.contains("';'"))
    }

    @Test
    fun `unbalanced brace is an error`() {
        val problems = check("if (id(x).state) { id(led).turn_on();")
        assertTrue(problems.any { it.severity == LambdaChecks.Severity.ERROR && it.message.contains("Unclosed") })
    }

    @Test
    fun `missing close paren inside a block blames the paren, not the braces`() {
        val problems = check("if (id(x).state) {\n  id(relay).turn_on(\n}")
        assertTrue(problems.any { it.severity == LambdaChecks.Severity.ERROR && it.message.contains("Unclosed '('") })
        assertTrue(problems.none { it.message.contains("'{'") || it.message.contains("'}'") })
    }

    @Test
    fun `mismatched closer is an error`() {
        assertTrue(check("foo(a];").any { it.severity == LambdaChecks.Severity.ERROR && it.message.contains("Unbalanced") })
    }

    @Test
    fun `unterminated literal is an error`() {
        assertTrue(check("""return "oops;""").any { it.severity == LambdaChecks.Severity.ERROR })
    }
}
