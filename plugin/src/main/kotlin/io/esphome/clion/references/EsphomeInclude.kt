package io.esphome.clion.references

import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLValue

/**
 * Recognises ESPHome `!include` directives in the YAML PSI. Shared by the
 * include reference provider (Phase 1) and, later, the include-graph index
 * (Phase 2) — see `docs/roadmap-includes-and-navigation.md`.
 *
 * Two forms occur:
 *
 * ```yaml
 * wifi: !include common/wifi.yaml          # scalar form
 *
 * sensor: !include                         # block form (with substitution vars)
 *   file: my_sensor.yaml
 *   vars:
 *     name: Living Room
 * ```
 */
object EsphomeInclude {

    /** The YAML tag spelled with or without its leading `!`, normalised. */
    private fun tagName(value: YAMLValue?): String? =
        value?.tag?.text?.trim()?.removePrefix("!")

    private fun isIncludeTagged(value: YAMLValue?): Boolean = tagName(value) == "include"

    /**
     * If [scalar] is the path argument of an `!include` (either form), the raw
     * path text it points at; otherwise null. Substitution/remote paths are
     * still returned here — callers decide whether to act on them.
     */
    fun includePathOf(scalar: YAMLScalar): String? {
        // Scalar form: the path scalar itself carries the !include tag.
        if (isIncludeTagged(scalar)) return scalar.textValue.takeIf { it.isNotBlank() }

        // Block form: `file:` inside a mapping that carries the !include tag.
        val keyValue = scalar.parent as? YAMLKeyValue ?: return null
        if (keyValue.keyText != "file") return null
        val mapping = keyValue.parent as? YAMLMapping ?: return null
        return if (isIncludeTagged(mapping)) scalar.textValue.takeIf { it.isNotBlank() } else null
    }
}
