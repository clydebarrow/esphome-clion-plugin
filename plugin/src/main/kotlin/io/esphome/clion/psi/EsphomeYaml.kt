package io.esphome.clion.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem
import org.jetbrains.yaml.psi.YAMLValue

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

    /** Key whose value names a component instance (`id: my_sensor`). */
    const val ID_KEY = "id"

    /**
     * The normalised YAML tag on [value] (e.g. `!extend` → "extend"), or null
     * when it carries no explicit tag.
     */
    fun tagName(value: YAMLValue): String? = value.tag?.text?.trim()?.removePrefix("!")

    /**
     * Whether [scalar] is the value of an `id:` that *references* an existing
     * declaration through a package-merge tag — `!extend` (modify it) or
     * `!remove` (delete it) — rather than declaring a new id. Such an `id:`
     * resolves to the original declaration (in the package) and must not itself
     * be indexed as a declaration.
     */
    fun isMergeTaggedId(scalar: YAMLScalar): Boolean {
        val keyValue = scalar.parent as? YAMLKeyValue ?: return false
        if (keyValue.keyText != ID_KEY || keyValue.value !== scalar) return false
        return tagName(scalar) in MERGE_TAGS
    }

    private val MERGE_TAGS = setOf("extend", "remove")

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
     * Whether an `id:` key *declares* the enclosing component (naming it) rather
     * than passing a `use_id` reference as an action/condition argument — e.g.
     * `output.set_level: { id: buzzer_out, level: 50% }` or
     * `lvgl.label.update: { id: lbl_day }`. Action and condition keys are
     * `domain.verb` (dotted); a real component or platform key never contains a
     * dot, so an `id:` whose enclosing mapping is owned by a dotted key is a
     * reference, not a declaration. Also excludes `!extend`/`!remove` package
     * overrides. Without this, an action's `id:` argument would be indexed as a
     * declaration and — sharing the name with the real one — shadow it.
     */
    fun isDeclarationId(idKeyValue: YAMLKeyValue): Boolean {
        if (idKeyValue.keyText != ID_KEY) return false
        val value = idKeyValue.value as? YAMLScalar ?: return false
        if (isMergeTaggedId(value)) return false
        val mapping = idKeyValue.parent as? YAMLMapping ?: return false
        val ownerKey = pathOfMapping(mapping).lastOrNull() ?: return false
        return '.' !in ownerKey
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
