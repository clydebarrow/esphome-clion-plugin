package io.esphome.clion.api.toolwindow

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.OnOffButton
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.esphome.clion.api.proto.ApiEntity
import io.esphome.clion.api.proto.ApiMessages
import io.esphome.clion.api.proto.ApiState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.util.Locale
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants

/** Receives a command message (type + payload) to send to the device. */
fun interface CommandSink {
    fun send(type: Int, payload: ByteArray)
}

/**
 * One entity row — `[icon] name … [control|state]` — filling the width of its
 * type group (the group is the fixed-width column). Switch/light/fan/lock get an
 * on-off toggle, a button a Press button, cover/valve/select a drop-down, number
 * a spinner, text a field; everything else a state label. Live state flows through
 * [updateState]; a binary sensor's icon tracks on/off.
 */
class EntityRow(
    private val entity: ApiEntity,
    private val commands: CommandSink,
) : JPanel(BorderLayout(JBUI.scale(8), 0)) {

    private val iconLabel = JBLabel(EntityIcons.iconFor(entity.type, entity.deviceClass))
    private val nameLabel = JBLabel(entity.name).apply { toolTipText = entity.name }
    private val stateLabel = JBLabel("—", SwingConstants.RIGHT)
    private var toggle: OnOffButton? = null
    private var selectCombo: ComboBox<String>? = null
    private var numberSpinner: JSpinner? = null
    private var textField: JBTextField? = null
    /** Last text value sent or received, so a commit only fires on a real change. */
    private var lastTextValue = ""
    private var updatingProgrammatically = false

    private val isEvent = entity.type == "event"
    /** When the event last fired (epoch ms), or 0 before any. Events carry no resting state. */
    private var eventAt = 0L
    private var lastEventType = ""

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
        entity.type == "cover" -> buildPositionControl("Cover commands") { ApiMessages.coverCommand(entity.key, it) }
        entity.type == "valve" -> buildPositionControl("Valve commands") { ApiMessages.valveCommand(entity.key, it) }
        entity.type == "select" && entity.options.isNotEmpty() -> buildSelectControl()
        entity.type == "number" -> buildNumberControl()
        entity.type == "text" -> buildTextControl()
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

    /**
     * A cover/valve: live state label plus a compact command drop-down (three
     * buttons overflow the column). BorderLayout, not FlowLayout, so the label is
     * never wrapped or clipped out of the tight group column. [commandFor] maps a
     * chosen action to its (type, payload).
     */
    private fun buildPositionControl(tooltip: String, commandFor: (ApiMessages.CoverAction) -> Pair<Int, ByteArray>): JComponent =
        JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            stateLabel.foreground = UIUtil.getContextHelpForeground()
            add(stateLabel, BorderLayout.CENTER)
            add(positionCombo(tooltip, commandFor), BorderLayout.EAST)
        }

    /** A drop-down that fires Open/Stop/Close, then snaps back to its prompt so the same pick repeats. */
    private fun positionCombo(tooltip: String, commandFor: (ApiMessages.CoverAction) -> Pair<Int, ByteArray>): ComboBox<String> {
        val combo = ComboBox((listOf(COVER_PROMPT) + COVER_ACTIONS.keys).toTypedArray())
        combo.toolTipText = tooltip
        combo.addActionListener {
            if (updatingProgrammatically) return@addActionListener
            COVER_ACTIONS[combo.selectedItem as? String]?.let { action ->
                val (t, p) = commandFor(action)
                commands.send(t, p)
                updatingProgrammatically = true
                try { combo.selectedIndex = 0 } finally { updatingProgrammatically = false }
            }
        }
        return combo
    }

    /** A select: a drop-down of the entity's options that fires a command on user choice. */
    private fun buildSelectControl(): ComboBox<String> {
        val combo = ComboBox(entity.options.toTypedArray())
        combo.toolTipText = entity.name
        combo.addActionListener {
            if (updatingProgrammatically) return@addActionListener
            (combo.selectedItem as? String)?.let { option ->
                val (t, p) = ApiMessages.selectCommand(entity.key, option)
                commands.send(t, p)
            }
        }
        selectCombo = combo
        return combo
    }

    /** A number: a spinner bounded by the entity's min/max/step, with an optional unit suffix. */
    private fun buildNumberControl(): JComponent {
        val hasRange = entity.numberMax > entity.numberMin
        val min: Double? = if (hasRange) entity.numberMin.toDouble() else null
        val max: Double? = if (hasRange) entity.numberMax.toDouble() else null
        val step = if (entity.numberStep > 0f) entity.numberStep.toDouble() else 1.0
        val spinner = JSpinner(SpinnerNumberModel(min ?: 0.0, min, max, step))
        spinner.toolTipText = entity.name
        spinner.addChangeListener {
            if (updatingProgrammatically) return@addChangeListener
            val (t, p) = ApiMessages.numberCommand(entity.key, (spinner.value as Number).toFloat())
            commands.send(t, p)
        }
        numberSpinner = spinner
        if (entity.unit.isEmpty()) return spinner
        return JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(spinner, BorderLayout.CENTER)
            add(JBLabel(entity.unit).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.EAST)
        }
    }

    /**
     * A text entity: a field committing its value on Enter or when it loses focus,
     * but only when the value actually changed. Live updates skip it while it has focus.
     */
    private fun buildTextControl(): JComponent {
        val field = JBTextField(10)
        field.toolTipText = entity.name
        val commit = {
            if (field.text != lastTextValue) {
                lastTextValue = field.text
                val (t, p) = ApiMessages.textCommand(entity.key, field.text)
                commands.send(t, p)
            }
        }
        field.addActionListener { commit() }
        field.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = commit()
        })
        textField = field
        return field
    }

    /** Reflect a live state: icon (binary), toggle (without re-sending), and/or label. */
    fun updateState(state: ApiState) {
        // An event has no resting state — each update is a fresh trigger. Record
        // when it fired and show "<event_type> · 10s ago", refreshed by [tick].
        if (isEvent) {
            eventAt = System.currentTimeMillis()
            lastEventType = state.display
            renderEvent()
            return
        }
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
        selectCombo?.let { combo ->
            // Reflect the live value without re-firing the command; ignore unknown values.
            if (state.display != combo.selectedItem && entity.options.contains(state.display)) {
                updatingProgrammatically = true
                try {
                    combo.selectedItem = state.display
                } finally {
                    updatingProgrammatically = false
                }
            }
            return
        }
        numberSpinner?.let { spinner ->
            // Reflect the live value without re-firing; leave the user's edit alone while focused.
            state.number?.let { n ->
                val editing = (spinner.editor as? JSpinner.DefaultEditor)?.textField?.isFocusOwner == true
                if (!editing) {
                    updatingProgrammatically = true
                    try {
                        spinner.value = clampToModel(spinner.model as SpinnerNumberModel, n.toDouble())
                    } finally {
                        updatingProgrammatically = false
                    }
                }
            }
            return
        }
        textField?.let { field ->
            if (!field.isFocusOwner && field.text != state.display) {
                field.text = state.display
                lastTextValue = state.display
            }
            return
        }
        stateLabel.text = formatState(state)
    }

    /** Clamp [v] to a spinner model's bounds so reflecting a live value never throws. */
    private fun clampToModel(model: SpinnerNumberModel, v: Double): Double {
        var r = v
        (model.minimum as? Number)?.toDouble()?.let { r = maxOf(r, it) }
        (model.maximum as? Number)?.toDouble()?.let { r = minOf(r, it) }
        return r
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

    /** Re-render an event's "… ago" label; a no-op until it has fired. Called every second. */
    fun tick() {
        if (isEvent && eventAt != 0L) renderEvent()
    }

    private fun renderEvent() {
        val relative = relativeTime(System.currentTimeMillis() - eventAt)
        stateLabel.text = if (lastEventType.isNotEmpty() && lastEventType != "triggered") {
            "$lastEventType · $relative"
        } else {
            relative
        }
        stateLabel.toolTipText = "Last event: ${lastEventType.ifEmpty { "triggered" }}"
    }

    /** Coarse "time since" for an event trigger: just now / 12s / 5m / 2h / 3d ago. */
    private fun relativeTime(elapsedMs: Long): String {
        val seconds = elapsedMs / 1000
        return when {
            seconds < 1 -> "just now"
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86_400}d ago"
        }
    }

    private companion object {
        const val COVER_PROMPT = "Move…"
        val COVER_ACTIONS = linkedMapOf(
            "Open" to ApiMessages.CoverAction.OPEN,
            "Stop" to ApiMessages.CoverAction.STOP,
            "Close" to ApiMessages.CoverAction.CLOSE,
        )
    }
}
