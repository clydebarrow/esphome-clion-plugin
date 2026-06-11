package io.esphome.clion.api.toolwindow

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * A [FlowLayout] that wraps its rows and reports the correct preferred height for
 * the available width — so fixed-width entity cards tile into as many columns as
 * fit and the container reflows on resize. (Plain `FlowLayout` reports a
 * single-row height, which breaks inside a scroll pane.)
 */
class WrapLayout(align: Int = LEFT, hgap: Int = 5, vgap: Int = 5) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)

    override fun minimumLayoutSize(target: Container): Dimension =
        layoutSize(target, false).also { it.width -= hgap + 1 }

    /**
     * Lay out left-to-right, wrapping rows — but **top-align** each item in its
     * row (plain `FlowLayout` vertically centers, which looks wrong when columns
     * have different heights).
     */
    override fun layoutContainer(target: Container) {
        synchronized(target.treeLock) {
            val insets = target.insets
            val maxWidth = target.width - (insets.left + insets.right + hgap * 2)
            var y = insets.top + vgap
            var x = 0
            var rowHeight = 0
            var rowStart = 0

            fun placeRow(end: Int) {
                var cx = insets.left + hgap
                for (i in rowStart until end) {
                    val m = target.getComponent(i)
                    if (!m.isVisible) continue
                    m.setLocation(cx, y) // top of the row, not centered
                    cx += m.width + hgap
                }
            }

            for (i in 0 until target.componentCount) {
                val m = target.getComponent(i)
                if (!m.isVisible) continue
                val d = m.preferredSize
                m.setSize(d.width, d.height)
                if (x != 0 && x + d.width > maxWidth) {
                    placeRow(i)
                    y += rowHeight + vgap
                    x = 0
                    rowHeight = 0
                    rowStart = i
                }
                if (x != 0) x += hgap
                x += d.width
                rowHeight = maxOf(rowHeight, d.height)
            }
            placeRow(target.componentCount)
        }
    }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            val targetWidth = (target.size.width.takeIf { it > 0 } ?: Int.MAX_VALUE)
            val insets = target.insets
            val horizontalInsetsAndGap = insets.left + insets.right + hgap * 2
            val maxWidth = targetWidth - horizontalInsetsAndGap

            val dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0
            for (i in 0 until target.componentCount) {
                val m = target.getComponent(i)
                if (!m.isVisible) continue
                val d = if (preferred) m.preferredSize else m.minimumSize
                if (rowWidth + d.width > maxWidth) {
                    addRow(dim, rowWidth, rowHeight)
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth != 0) rowWidth += hgap
                rowWidth += d.width
                rowHeight = maxOf(rowHeight, d.height)
            }
            addRow(dim, rowWidth, rowHeight)

            dim.width += horizontalInsetsAndGap
            dim.height += insets.top + insets.bottom + vgap * 2
            // Inside a scroll pane, trim a notch so a horizontal scrollbar isn't forced.
            if (SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target) != null && target.isValid) {
                dim.width -= hgap + 1
            }
            return dim
        }
    }

    private fun addRow(dim: Dimension, rowWidth: Int, rowHeight: Int) {
        dim.width = maxOf(dim.width, rowWidth)
        if (dim.height > 0) dim.height += vgap
        dim.height += rowHeight
    }
}
