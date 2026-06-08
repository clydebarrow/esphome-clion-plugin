package io.esphome.clion.documentation

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.StringUtil
import io.esphome.clion.psi.EsphomeTarget

/**
 * Renders catalog targets to the HTML the IDE shows in the documentation popup.
 * Pure (no PSI / services), so it is unit-tested directly.
 */
object EsphomeDocRenderer {

    fun render(target: EsphomeTarget): String = when (target) {
        is EsphomeTarget.Component -> renderComponent(target)
        is EsphomeTarget.Field -> renderField(target)
    }

    private fun renderComponent(target: EsphomeTarget.Component): String {
        val index = target.index
        val sb = StringBuilder()
        sb.append(DocumentationMarkup.DEFINITION_START)
        sb.append("<b>").append(escape(index.name.ifEmpty { index.id })).append("</b>")
        sb.append(DocumentationMarkup.DEFINITION_END)

        val description = target.body?.description?.ifBlank { null } ?: index.description.ifBlank { null }
        if (description != null) {
            sb.append(DocumentationMarkup.CONTENT_START).append(markdown(description)).append(DocumentationMarkup.CONTENT_END)
        }

        sb.append(DocumentationMarkup.SECTIONS_START)
        section(sb, "Component", code(index.id))
        if (index.dependencies.isNotEmpty()) section(sb, "Requires", index.dependencies.joinToString(", ") { code(it) })
        docsLink(index.docsUrl)?.let { section(sb, "Docs", it) }
        sb.append(DocumentationMarkup.SECTIONS_END)
        return sb.toString()
    }

    private fun renderField(target: EsphomeTarget.Field): String {
        val entry = target.entry
        val sb = StringBuilder()
        sb.append(DocumentationMarkup.DEFINITION_START)
        sb.append("<b>").append(escape(entry.key)).append("</b>")
        sb.append(" : ").append(escape(entry.type.name.lowercase()))
        sb.append(DocumentationMarkup.DEFINITION_END)

        entry.description?.ifBlank { null }?.let {
            sb.append(DocumentationMarkup.CONTENT_START).append(markdown(it)).append(DocumentationMarkup.CONTENT_END)
        }

        sb.append(DocumentationMarkup.SECTIONS_START)
        section(sb, "Required", if (entry.required) "yes" else "no")
        entry.defaultValue?.let { section(sb, "Default", code(it.toString().trim('"'))) }
        entry.range?.takeIf { it.size == 2 }?.let { section(sb, "Range", "${num(it[0])} – ${num(it[1])}") }
        entry.unitOptions?.takeIf { it.isNotEmpty() }?.let { section(sb, "Units", it.joinToString(", ") { u -> code(u) }) }
        entry.options?.takeIf { it.isNotEmpty() }?.let {
            section(sb, "Values", it.joinToString(", ") { o -> code(o.value) })
        }
        section(sb, "In", code(target.componentId))
        docsLink(entry.helpLink)?.let { section(sb, "Docs", it) }
        sb.append(DocumentationMarkup.SECTIONS_END)
        return sb.toString()
    }

    private fun section(sb: StringBuilder, header: String, value: String) {
        sb.append(DocumentationMarkup.SECTION_HEADER_START).append(escape(header))
        sb.append(DocumentationMarkup.SECTION_SEPARATOR).append("<p>").append(value)
        sb.append(DocumentationMarkup.SECTION_END)
    }

    private fun docsLink(url: String?): String? {
        val u = url?.ifBlank { null } ?: return null
        return "<a href=\"${escape(u)}\">${escape(u)}</a>"
    }

    private fun num(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    private fun escape(s: String): String = StringUtil.escapeXmlEntities(s)

    private fun code(s: String): String = "<code>${escape(s)}</code>"

    /**
     * Minimal markdown → HTML for ESPHome descriptions: `[label](url)` links and
     * `` `code` `` spans, on top of HTML escaping. Good enough for the popup.
     */
    private fun markdown(text: String): String {
        var html = escape(text)
        html = LINK.replace(html) { m -> "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>" }
        html = CODE.replace(html) { m -> "<code>${m.groupValues[1]}</code>" }
        return html
    }

    private val LINK = Regex("""\[([^\]]+)]\(([^)]+)\)""")
    private val CODE = Regex("""`([^`]+)`""")
}
