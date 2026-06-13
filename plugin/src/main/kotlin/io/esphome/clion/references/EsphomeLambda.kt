package io.esphome.clion.references

import com.intellij.openapi.util.TextRange
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLValue

/**
 * Recognises ESPHome lambdas (embedded C++) and the `id(<name>)` accessors inside
 * them. In a lambda, `id(abc)` is ESPHome codegen for the component/entity
 * declared with `id: abc`, so those names are real id references — drivable for
 * completion and navigation even though the body is otherwise opaque C++.
 *
 * Two spellings of a lambda:
 * ```yaml
 * on_...:
 *   - lambda: |-          # block form, key is `lambda`
 *       id(relay).turn_on();
 * value: !lambda 'return id(sensor).state;'   # tagged scalar form
 * ```
 */
object EsphomeLambda {

    /** `id(<name>)` — captures the id name; tolerant of inner whitespace. */
    private val ID_CALL = Regex("""(?<![A-Za-z0-9_])id\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)""")

    private fun tagName(value: YAMLValue?): String? = value?.tag?.text?.trim()?.removePrefix("!")

    /** True when [scalar] is a lambda body (a `!lambda` scalar or a `lambda:` value). */
    fun isLambda(scalar: YAMLScalar): Boolean {
        if (tagName(scalar) == "lambda") return true
        val keyValue = scalar.parent as? YAMLKeyValue ?: return false
        return keyValue.keyText == "lambda" && keyValue.value === scalar
    }

    /**
     * Every `id(<name>)` in [scalar], as the in-element [TextRange] of the *name*
     * (for a reference range) paired with the name. Empty when not a lambda.
     */
    fun idCalls(scalar: YAMLScalar): List<Pair<TextRange, String>> {
        if (!isLambda(scalar)) return emptyList()
        return ID_CALL.findAll(scalar.text).map { match ->
            val group = match.groups[1]!!
            TextRange(group.range.first, group.range.last + 1) to group.value
        }.toList()
    }
}
