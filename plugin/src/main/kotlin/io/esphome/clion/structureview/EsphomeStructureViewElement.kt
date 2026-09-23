package io.esphome.clion.structureview

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import com.intellij.util.PsiNavigateUtil
import io.esphome.clion.api.toolwindow.EntityIcons
import io.esphome.clion.psi.EsphomeYaml
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem
import org.jetbrains.yaml.psi.YAMLValue
import javax.swing.Icon

/**
 * A structure-view node for one element of an ESPHome config. Mirrors the
 * bundled YAML structure view's shape (file -> top-level keys -> mapping keys
 * / sequence items, recursively), but gives a *sequence item* a useful label —
 * its `id:` or `name:`, falling back to `platform:` — instead of the bundled
 * view's generic "Sequence item": `sensor:`, `binary_sensor:`, etc. are lists
 * of component declarations, not anonymous scalars, so the id/name is what a
 * user actually wants to scan for.
 *
 * Implements [StructureViewTreeElement] + [ItemPresentation] directly rather
 * than extending the platform's `PsiTreeElementBase` convenience base class:
 * that class moved into a separately-versioned bundled content module
 * (`intellij.platform.structureView`) as of CLion/IntelliJ 2026.2, which the
 * plugin verifier can't resolve without a matching module dependency we'd
 * otherwise have to declare and keep in step with the platform's own
 * modularization. `StructureViewTreeElement`, `ItemPresentation` and
 * `PsiNavigateUtil` all remain in core platform modules on every supported
 * version.
 *
 * [domain] is the nearest enclosing *top-level* key's name (`"sensor"`,
 * `"switch"`, …) when [element] is a direct child of that key's sequence —
 * null anywhere else — so an icon is only guessed for an actual entity list,
 * not for an incidental `id:`/`platform:`-shaped mapping nested deep inside an
 * action or lambda.
 */
class EsphomeStructureViewElement(
    private val element: PsiElement,
    private val domain: String? = null,
) : StructureViewTreeElement, ItemPresentation {

    override fun getValue(): Any = element

    // PsiNavigateUtil.getNavigatable(PsiElement) isn't available on every
    // supported IDE version (unresolved on 242/243) — element.isValid is a
    // safe, version-stable stand-in: navigate() itself (below) does the real
    // work and already handles a non-navigable element gracefully.
    override fun navigate(requestFocus: Boolean) = PsiNavigateUtil.navigate(element, requestFocus)
    override fun canNavigate(): Boolean = element.isValid
    override fun canNavigateToSource(): Boolean = element.isValid

    override fun getPresentation(): ItemPresentation = this

    override fun getChildren(): Array<TreeElement> = getChildrenBase().toTypedArray()

    fun getChildrenBase(): List<EsphomeStructureViewElement> = when (val el = element) {
        is YAMLFile -> el.documents.mapNotNull(YAMLDocument::getTopLevelValue).flatMap { childrenOf(it, null) }
        is YAMLKeyValue -> el.value?.let { childrenOf(it, domainOf(el)) }.orEmpty()
        is YAMLSequenceItem -> el.value?.let { childrenOf(it, domain) }.orEmpty()
        else -> emptyList()
    }

    /** [keyValue]'s own name, but only when it's a top-level key (a direct child of the document root). */
    private fun domainOf(keyValue: YAMLKeyValue): String? {
        val mapping = keyValue.parent as? YAMLMapping ?: return null
        return keyValue.keyText.takeIf { mapping.parent is YAMLDocument }
    }

    private fun childrenOf(value: YAMLValue, domain: String?): List<EsphomeStructureViewElement> = when (value) {
        is YAMLMapping -> value.keyValues.map { EsphomeStructureViewElement(it, domain) }
        is YAMLSequence -> value.items.map { EsphomeStructureViewElement(it, domain) }
        else -> emptyList()
    }

    override fun getPresentableText(): String = when (val el = element) {
        is YAMLFile -> el.name
        is YAMLKeyValue -> el.keyText
        is YAMLSequenceItem -> sequenceItemLabel(el) ?: "Sequence item"
        else -> el.text.orEmpty()
    }

    override fun getLocationString(): String? {
        val el = element
        return when {
            el is YAMLSequenceItem -> sequenceItemLocation(el)
            el is YAMLKeyValue && el.value is YAMLScalar -> el.valueText.trim().takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    override fun getIcon(unused: Boolean): Icon? {
        val domain = domain ?: return null
        val mapping = (element as? YAMLSequenceItem)?.value as? YAMLMapping ?: return null
        val deviceClass = mapping.getKeyValueByKey(DEVICE_CLASS_KEY)?.valueText?.trim().orEmpty()
        return EntityIcons.iconFor(domain, deviceClass)
    }

    /** The item's `id:`, else `name:`, else `platform:` — whichever first identifies it. */
    private fun sequenceItemLabel(item: YAMLSequenceItem): String? {
        val mapping = item.value as? YAMLMapping ?: return (item.value as? YAMLScalar)?.textValue
        return scalarValue(mapping, EsphomeYaml.ID_KEY)
            ?: scalarValue(mapping, NAME_KEY)
            ?: scalarValue(mapping, EsphomeYaml.PLATFORM_KEY)
    }

    /** The `platform:` value, shown alongside the label when it isn't the label itself. */
    private fun sequenceItemLocation(item: YAMLSequenceItem): String? {
        val mapping = item.value as? YAMLMapping ?: return null
        val platform = scalarValue(mapping, EsphomeYaml.PLATFORM_KEY) ?: return null
        return platform.takeUnless { it == sequenceItemLabel(item) }
    }

    private fun scalarValue(mapping: YAMLMapping, key: String): String? =
        mapping.getKeyValueByKey(key)?.valueText?.trim()?.ifEmpty { null }

    private companion object {
        const val NAME_KEY = "name"
        const val DEVICE_CLASS_KEY = "device_class"
    }
}
