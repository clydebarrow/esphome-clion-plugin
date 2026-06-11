package io.esphome.clion.api.toolwindow

import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.util.ui.JBUI
import io.esphome.clion.api.proto.ApiEntity
import io.esphome.clion.api.proto.ApiState
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import javax.swing.Scrollable

/**
 * The device's entities as fixed-width cards that flow into multiple columns and
 * reflow on resize (good for a wide, landscape tool window). Cards are sorted by
 * type then name so like entities cluster. Live states update cards by key; all
 * mutators run on the EDT.
 */
class EntityListView :
    JBPanelWithEmptyText(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))),
    Scrollable {

    private val rows = HashMap<Long, EntityRow>()

    /** Where a card's control sends its command. */
    var commandSink: CommandSink = CommandSink { _, _ -> }

    init {
        border = JBUI.Borders.empty(4)
        emptyText.text = "Not connected"
    }

    fun setEntities(entities: List<ApiEntity>) {
        clear()
        entities.sortedWith(compareBy({ it.type }, { it.name.lowercase() })).forEach { entity ->
            val row = EntityRow(entity, commandSink)
            rows[entity.key] = row
            add(row)
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

    // Track the viewport width so WrapLayout wraps cards; grow taller and scroll.
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
    override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int): Int = r.height
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
}
