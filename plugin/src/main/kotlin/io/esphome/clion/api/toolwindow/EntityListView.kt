package io.esphome.clion.api.toolwindow

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.esphome.clion.api.proto.ApiEntity
import io.esphome.clion.api.proto.ApiState
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.Timer

/**
 * The device's entities grouped by type, each group a fixed-width column
 * (header + its rows) that flows into as many columns as the window width
 * allows and reflows on resize — like the ESPHome webserver / Home Assistant.
 * Live states update rows by key; all mutators run on the EDT.
 */
class EntityListView :
    JBPanelWithEmptyText(WrapLayout(FlowLayout.LEFT, JBUI.scale(10), JBUI.scale(8))),
    Scrollable {

    private val rows = HashMap<Long, EntityRow>()
    private val eventRows = mutableListOf<EntityRow>()

    /** Drives the "… ago" relative time on event rows; runs only while events are present. */
    private val ticker = Timer(1000) { eventRows.forEach(EntityRow::tick) }.apply { isRepeats = true }

    /** Where a row's control sends its command. */
    var commandSink: CommandSink = CommandSink { _, _ -> }

    init {
        border = JBUI.Borders.empty(6)
        emptyText.text = "Not connected"
    }

    fun setEntities(entities: List<ApiEntity>) {
        clear()
        entities.groupBy { it.type }.toSortedMap().forEach { (type, group) ->
            val groupRows = group.sortedBy { it.name.lowercase() }.map { entity ->
                EntityRow(entity, commandSink).also { row ->
                    rows[entity.key] = row
                    if (entity.type == "event") eventRows.add(row)
                }
            }
            add(GroupPanel("${displayName(type)} (${group.size})", groupRows))
        }
        if (eventRows.isNotEmpty()) ticker.start()
        revalidate()
        repaint()
    }

    fun updateState(state: ApiState) {
        rows[state.key]?.updateState(state)
    }

    fun clear() {
        ticker.stop()
        rows.clear()
        eventRows.clear()
        removeAll()
        revalidate()
        repaint()
    }

    /** Stop the relative-time ticker (the panel calls this on dispose). */
    fun dispose() {
        ticker.stop()
    }

    /** `binary_sensor` → "Binary sensors", `switch` → "Switches". */
    private fun displayName(type: String): String {
        val label = type.split("_")
            .mapIndexed { i, w -> if (i == 0) w.replaceFirstChar(Char::uppercase) else w }
            .joinToString(" ")
        val suffix = if (label.endsWith("s") || label.endsWith("x") || label.endsWith("z") ||
            label.endsWith("ch") || label.endsWith("sh")
        ) "es" else "s"
        return label + suffix
    }

    /** A type group: a bold header over its rows, a fixed width so groups tile in columns. */
    private class GroupPanel(title: String, groupRows: List<EntityRow>) :
        JPanel(VerticalLayout(JBUI.scale(1))) {
        init {
            isOpaque = false
            add(
                JBLabel(title).apply {
                    font = JBUI.Fonts.label().asBold()
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.empty(2, 8, 4, 8)
                },
            )
            groupRows.forEach { add(it) }
        }

        override fun getPreferredSize(): Dimension =
            Dimension(JBUI.scale(COLUMN_WIDTH), super.getPreferredSize().height)

        override fun getMaximumSize(): Dimension = preferredSize

        private companion object {
            const val COLUMN_WIDTH = 300
        }
    }

    // Track the viewport width so groups wrap into columns; grow taller and scroll.
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
    override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int): Int = r.height
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
}
