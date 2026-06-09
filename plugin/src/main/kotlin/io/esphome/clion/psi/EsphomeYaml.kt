package io.esphome.clion.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * YAML PSI helpers for locating where the caret sits within an ESPHome config,
 * independent of the catalog. All structural reasoning the completion
 * contributor needs lives here so the contributor stays about *what* to suggest
 * rather than *where we are*.
 */
object EsphomeYaml {

    /** Top-level key that marks a file as an ESPHome config. */
    const val MARKER_KEY = "esphome"

    /** Top-level key that pulls in other configs; can itself supply `esphome:`. */
    const val PACKAGES_KEY = "packages"

    /** Top-level key defining `${name}` text substitutions. */
    const val SUBSTITUTIONS_KEY = "substitutions"

    /** The discriminator key inside a platform list item (`- platform: dht`). */
    const val PLATFORM_KEY = "platform"

    /** True if [file] has a top-level `esphome:` key in any of its documents. */
    fun isEsphomeFile(file: YAMLFile): Boolean = hasTopLevelKey(file, MARKER_KEY)

    /**
     * True if [file] is a config ESPHome can compile standalone — it has a
     * top-level `esphome:`, or a top-level `packages:` (which commonly supplies
     * the `esphome:` block from a base package, so the main file has none of its
     * own). Used to decide whether to validate a file directly vs. through the
     * root that includes it.
     */
    fun isStandaloneConfig(file: YAMLFile): Boolean =
        hasTopLevelKey(file, MARKER_KEY) || hasTopLevelKey(file, PACKAGES_KEY)

    private fun hasTopLevelKey(file: YAMLFile, key: String): Boolean =
        file.documents.any { topLevelMapping(it)?.getKeyValueByKey(key) != null }

    fun topLevelMapping(document: YAMLDocument): YAMLMapping? =
        document.topLevelValue as? YAMLMapping

    /**
     * Keys from the document root down to [mapping] (top-down). Sequence items
     * contribute no key of their own, so a mapping inside `sensor: - …` yields
     * `["sensor"]` and `wifi: ap:` yields `["wifi", "ap"]`. The root mapping
     * yields `[]`.
     */
    fun pathOfMapping(mapping: YAMLMapping?): List<String> {
        val keys = ArrayList<String>()
        var current: YAMLMapping? = mapping
        while (current != null) {
            val kv = PsiTreeUtil.getParentOfType(current, YAMLKeyValue::class.java) ?: break
            keys.add(kv.keyText)
            current = PsiTreeUtil.getParentOfType(kv, YAMLMapping::class.java)
        }
        keys.reverse()
        return keys
    }

    /**
     * True when [keyValue]'s value sits on the same line as its key (`key: val`)
     * — a genuine value position. When false, the "value" is on a following
     * line, which for a half-typed entry means the YAML parser has mis-attached
     * a nascent child key as the parent's scalar value (`key:\n  val`).
     */
    fun isValueOnKeyLine(keyValue: YAMLKeyValue): Boolean {
        val key = keyValue.key ?: return false
        val value = keyValue.value ?: return false
        val text = keyValue.containingFile.text
        val start = key.textRange.endOffset
        val end = value.textRange.startOffset
        if (start > end || end > text.length) return false
        return !text.substring(start, end).contains('\n')
    }

    /**
     * The `platform:` value of the sequence item enclosing [element], or null
     * when [element] isn't in a platform item or no platform is chosen yet.
     */
    fun platformOf(element: PsiElement): String? {
        val item = PsiTreeUtil.getParentOfType(element, YAMLSequenceItem::class.java) ?: return null
        val mapping = item.value as? YAMLMapping ?: return null
        return mapping.getKeyValueByKey(PLATFORM_KEY)?.valueText?.trim()?.ifEmpty { null }
    }
}
