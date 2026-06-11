package io.esphome.clion.api.toolwindow

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.OnOffButton
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.esphome.clion.api.proto.ApiEntity
import io.esphome.clion.api.proto.ApiMessages
import io.esphome.clion.api.proto.ApiState
import java.awt.BorderLayout
import java.awt.Dimension
import java.util.Locale
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Receives a command message (type + payload) to send to the device. */
fun interface CommandSink {
    fun send(type: Int, payload: ByteArray)
}

/**
 * One entity row — `[icon] name … [control|state]` — filling the width of its
 * type group (the group is the fixed-width column). Switch/light/fan get an
 * on-off toggle, a button a Press button, everything else a state label.
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

    // Fill the group's width; cap height so VerticalLayout doesn't stretch the row.
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

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
    fun updateState(state: ApiState) {
        iconLabel.icon = EntityIcons.iconFor(entity.type, entity.deviceClass, state.active)
        toggle?.let { t ->
            state.active?.let { on ->
                updatingProgrammatically = true
                try {
                    t.isSelected = on
                } finally {
                    updatingProgrammatically = false
                }
            }
        }
        stateLabel.text = formatState(state)
    }

    /** Sensors honor their `accuracy_decimals`; otherwise the pre-formatted display, plus the unit. */
    private fun formatState(state: ApiState): String {
        val text = if (entity.type == "sensor" && entity.accuracyDecimals >= 0 && state.number != null) {
            "%.${entity.accuracyDecimals}f".format(Locale.ROOT, state.number)
        } else {
            state.display
        }
        return if (entity.unit.isNotEmpty() && text != "—") "$text ${entity.unit}" else text
    }
}
