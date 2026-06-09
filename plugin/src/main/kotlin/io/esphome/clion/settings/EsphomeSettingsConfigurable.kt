package io.esphome.clion.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/** Settings → Tools → ESPHome. */
class EsphomeSettingsConfigurable : BoundConfigurable("ESPHome") {

    override fun createPanel(): DialogPanel = panel {
        row("esphome executable:") {
            cell(browseField())
                .bindText(
                    { EsphomeSettings.getInstance().state.executablePath ?: "" },
                    { EsphomeSettings.getInstance().state.executablePath = it.trim() },
                )
                .align(AlignX.FILL)
        }.rowComment("Leave blank to auto-detect <code>esphome</code> from PATH. Used for config validation.")
    }

    /**
     * Builds the executable picker by hand rather than via the
     * `Row.textFieldWithBrowseButton` DSL: that extension's signature drifts
     * across platform versions, so the compiled `$default` synthetic is
     * unresolved on the since-build (242) floor and the Marketplace verifier
     * flags a potential `NoSuchMethodError`. Every API used here
     * (`TextFieldWithBrowseButton`, `addActionListener`, `FileChooser`, the
     * descriptor factory) is stable from 2024.2 onward.
     */
    private fun browseField(): TextFieldWithBrowseButton {
        val field = TextFieldWithBrowseButton()
        field.addActionListener {
            val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("Select ESPHome Executable")
            FileChooser.chooseFile(descriptor, null, null)?.let { field.text = it.presentableUrl }
        }
        return field
    }
}
