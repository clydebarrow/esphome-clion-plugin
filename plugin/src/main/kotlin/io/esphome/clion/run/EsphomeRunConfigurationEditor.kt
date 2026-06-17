package io.esphome.clion.run

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/** Form for an [EsphomeRunConfiguration]: file, command, backend, image, device. */
class EsphomeRunConfigurationEditor : SettingsEditor<EsphomeRunConfiguration>() {

    private val configField = TextFieldWithBrowseButton().apply {
        addActionListener {
            val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("Select ESPHome Config")
            FileChooser.chooseFile(descriptor, null, null)?.let { text = it.presentableUrl }
        }
    }
    private val commandCombo = ComboBox(EsphomeCommand.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create { label, value, _ -> label.text = value?.display.orEmpty() }
    }
    private val backendCombo = ComboBox(EsphomeBackend.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create { label, value, _ -> label.text = value?.display.orEmpty() }
    }
    private val statesCombo = ComboBox(StateReporting.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create { label, value, _ -> label.text = value?.display.orEmpty() }
    }
    private val dockerImageField = JBTextField()
    private val deviceField = JBTextField()
    private val uploadSpeedCombo = ComboBox(EsphomeCommand.COMMON_UPLOAD_SPEEDS.toTypedArray()).apply {
        isEditable = true
    }
    private val extraArgsField = JBTextField()
    private val resetBeforeLogsBox = JBCheckBox("Reset device before starting logs")
    private val emulateTerminalBox = JBCheckBox("Emulate a terminal (ANSI colour and in-place progress)")

    override fun resetEditorFrom(s: EsphomeRunConfiguration) {
        configField.text = s.configPath.orEmpty()
        commandCombo.selectedItem = s.command
        backendCombo.selectedItem = s.backend
        statesCombo.selectedItem = s.stateReporting
        dockerImageField.text = s.dockerImage
        deviceField.text = s.device.orEmpty()
        uploadSpeedCombo.selectedItem = s.uploadSpeed.orEmpty()
        extraArgsField.text = s.extraArgs.orEmpty()
        resetBeforeLogsBox.isSelected = s.resetBeforeLogs
        emulateTerminalBox.isSelected = s.emulateTerminal
    }

    override fun applyEditorTo(s: EsphomeRunConfiguration) {
        s.configPath = configField.text.trim()
        s.command = commandCombo.selectedItem as EsphomeCommand
        s.backend = backendCombo.selectedItem as EsphomeBackend
        s.stateReporting = statesCombo.selectedItem as StateReporting
        s.dockerImage = dockerImageField.text.trim()
        s.device = deviceField.text.trim()
        s.uploadSpeed = (uploadSpeedCombo.selectedItem as? String)?.trim().orEmpty()
        s.extraArgs = extraArgsField.text.trim()
        s.resetBeforeLogs = resetBeforeLogsBox.isSelected
        s.emulateTerminal = emulateTerminalBox.isSelected
    }

    override fun createEditor(): JComponent = panel {
        row("Config file:") { cell(configField).align(AlignX.FILL) }
        row("Command:") { cell(commandCombo) }
        row("Backend:") { cell(backendCombo) }
        row("Docker image:") { cell(dockerImageField).align(AlignX.FILL) }
            .rowComment("Used only with the Docker backend.")
        row("Device:") { cell(deviceField).align(AlignX.FILL) }
            .rowComment("OTA host/IP or serial port for run/upload/logs. Blank = let ESPHome choose.")
        row("Baud rate:") { cell(uploadSpeedCombo) }
            .rowComment("Serial flashing speed (--upload_speed) for run/upload. Blank = ESPHome default; ignored for OTA.")
        row("Show device states:") { cell(statesCombo) }
            .rowComment("--states / --no-states for run and logs.")
        row { cell(resetBeforeLogsBox) }
            .rowComment("Adds --reset so the board restarts when logs (or run) begin.")
        row("Extra arguments:") { cell(extraArgsField).align(AlignX.FILL) }
            .rowComment("Appended after the config file, e.g. <code>-s name value</code> or <code>--only-generate</code>.")
        row { cell(emulateTerminalBox) }
            .rowComment("ANSI colour and an in-place progress bar (serial included). On by default.")
    }
}
