package io.esphome.clion.references

import com.intellij.openapi.util.TextRange

/**
 * Recognises ESPHome substitution usages in scalar text: the explicit
 * `${name}` form and the bare `$name` form. Substitution names are identifiers
 * (letter/underscore first), so `$123` or a literal `$` in a password is not
 * matched.
 */
object EsphomeSubstitutionSyntax {

    val PATTERN = Regex("""\$\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*}|\$([A-Za-z_][A-Za-z0-9_]*)""")

    /** One `${name}`/`$name` usage: the referenced [name], its [range] (of the name) in the text, and whether it was braced. */
    data class Usage(val name: String, val range: TextRange, val braced: Boolean)

    /** All substitution usages in [text], with name ranges relative to [text]. */
    fun scan(text: String): List<Usage> =
        PATTERN.findAll(text).map { match ->
            val braced = match.groups[1]
            val group = braced ?: match.groups[2]!!
            Usage(group.value, TextRange(group.range.first, group.range.last + 1), braced != null)
        }.toList()
}
