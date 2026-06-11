package io.esphome.clion.api.toolwindow

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.esphome.clion.api.proto.ApiEntity
import io.esphome.clion.api.proto.ApiState
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The device's entities as a vertical, HA-card-like list grouped by type, with
 * an icon and a control or state per row. Rebuilt once the entity list arrives;
 * live states update individual rows by key. All mutators run on the EDT.
 */
class EntityListView : JPanel(VerticalLayout(0)) {

    private val rows = HashMap<Long, EntityRow>()

    /** Where a row's control sends its command. */
    var commandSink: CommandSink = CommandSink { _, _ -> }

    /** Rebuild the grouped rows from the full entity list. */
    fun setEntities(entities: List<ApiEntity>) {
        clear()
        entities.groupBy { it.type }.toSortedMap().forEach { (type, group) ->
            add(sectionHeader(type, group.size))
            group.sortedBy { it.name.lowercase() }.forEach { entity ->
                val row = EntityRow(entity, commandSink)
                rows[entity.key] = row
                add(row)
            }
        }
        revalidate()
        repaint()
    }

    fun updateState(state: ApiState) {
        rows[state.key]?.updateState(state.display, state.active)
    }

    fun clear() {
        rows.clear()
        removeAll()
        revalidate()
        repaint()
    }

    private fun sectionHeader(type: String, count: Int): JComponent =
        JBLabel("${displayName(type)} ($count)").apply {
            font = JBUI.Fonts.label().asBold()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(10, 8, 3, 8)
        }

    /** `binary_sensor` → "Binary sensors", `media_player` → "Media players". */
    private fun displayName(type: String): String {
        val words = type.split("_")
        return words.mapIndexed { i, w -> if (i == 0) w.replaceFirstChar(Char::uppercase) else w }
            .joinToString(" ") + "s"
    }
}
