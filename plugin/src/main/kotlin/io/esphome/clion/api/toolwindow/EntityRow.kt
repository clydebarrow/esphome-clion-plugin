package io.esphome.clion.api.toolwindow

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.OnOffButton
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.esphome.clion.api.proto.ApiEntity
import io.esphome.clion.api.proto.ApiMessages
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Receives a command message (type + payload) to send to the device. */
fun interface CommandSink {
    fun send(type: Int, payload: ByteArray)
}

/**
 * One entity as a fixed-width card — `[icon] name … [control|state]` — so cards
 * flow into multiple columns on a wide window. Switch/light/fan get an on-off
 * toggle, a button gets a Press button, everything else a state label.
 * Live state flows through [updateState]; a binary sensor's icon tracks on/off.
 */
class EntityRow(
    private val entity: ApiEntity,
    private val commands: CommandSink,
) : JPanel(BorderLayout(JBUI.scale(8), 0)) {

    private val iconLabel = JBLabel(EntityIcons.iconFor(entity.type, entity.deviceClass))
    private val nameLabel = JBLabel(entity.name).apply { toolTipText = entity.name }
    private val stateLabel = JBLabel("—", SwingConstants.RIGHT)
    private var toggle: OnOffButton? = null
    private var updatingProgrammatically = false

    init {
        isOpaque = false
        border = JBUI.Borders.empty(3, 8)
        add(iconLabel, BorderLayout.WEST)
        add(nameLabel, BorderLayout.CENTER)
        add(buildControl(), BorderLayout.EAST)
    }

    // Fixed width so cards tile in columns; natural height.
    override fun getPreferredSize(): Dimension =
        Dimension(JBUI.scale(CARD_WIDTH), super.getPreferredSize().height)

    override fun getMaximumSize(): Dimension = preferredSize

    private fun buildControl(): JComponent = when {
        entity.type == "button" -> JButton("Press").apply {
            addActionListener { val (t, p) = ApiMessages.buttonCommand(entity.key); commands.send(t, p) }
        }
        ApiMessages.isControllable(entity.type) -> OnOffButton().also { btn ->
            toggle = btn
            btn.addActionListener {
                if (!updatingProgrammatically) {
                    ApiMessages.toggleCommand(entity.type, entity.key, btn.isSelected)
                        ?.let { (t, p) -> commands.send(t, p) }
                }
            }
        }
        else -> stateLabel.apply { foreground = UIUtil.getContextHelpForeground() }
    }

    /** Reflect a live state: icon (binary), toggle (without re-sending), and/or label. */
    fun updateState(display: String, active: Boolean?) {
        iconLabel.icon = EntityIcons.iconFor(entity.type, entity.deviceClass, active)
        toggle?.let { t ->
            if (active != null) {
                updatingProgrammatically = true
                try {
                    t.isSelected = active
                } finally {
                    updatingProgrammatically = false
                }
            }
        }
        stateLabel.text = if (entity.unit.isNotEmpty() && display != "—") "$display ${entity.unit}" else display
    }

    private companion object {
        const val CARD_WIDTH = 240
    }
}
