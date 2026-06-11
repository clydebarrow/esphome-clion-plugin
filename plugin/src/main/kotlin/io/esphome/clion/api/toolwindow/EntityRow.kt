package io.esphome.clion.api.toolwindow

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.OnOffButton
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.esphome.clion.api.proto.ApiEntity
import io.esphome.clion.api.proto.ApiMessages
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Receives a command message (type + payload) to send to the device. */
fun interface CommandSink {
    fun send(type: Int, payload: ByteArray)
}

/**
 * One entity row: `[icon] name … [control|state]`. Switch/light/fan get an
 * on-off toggle, a button gets a Press button, everything else a right-aligned
 * state label. Live state updates flow through [updateState].
 */
class EntityRow(
    private val entity: ApiEntity,
    private val commands: CommandSink,
) : JPanel(BorderLayout(JBUI.scale(8), 0)) {

    private val stateLabel = JBLabel("—", SwingConstants.RIGHT)
    private var toggle: OnOffButton? = null
    private var updatingProgrammatically = false

    init {
        isOpaque = false
        border = JBUI.Borders.empty(3, 8)
        add(JBLabel(EntityIcons.iconFor(entity.type, entity.deviceClass)), BorderLayout.WEST)
        add(JBLabel(entity.name), BorderLayout.CENTER)
        add(buildControl(), BorderLayout.EAST)
    }

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

    /** Reflect a live state: move the toggle (without re-sending) and/or set the label. */
    fun updateState(display: String, active: Boolean?) {
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
}
