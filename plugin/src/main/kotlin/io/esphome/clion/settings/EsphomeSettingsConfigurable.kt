package io.esphome.clion.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/** Settings → Tools → ESPHome. */
class EsphomeSettingsConfigurable : BoundConfigurable("ESPHome") {

    override fun createPanel(): DialogPanel = panel {
        row("esphome executable:") {
            textFieldWithBrowseButton()
                .bindText(
                    { EsphomeSettings.getInstance().state.executablePath ?: "" },
                    { EsphomeSettings.getInstance().state.executablePath = it.trim() },
                )
                .align(AlignX.FILL)
        }.rowComment("Leave blank to auto-detect <code>esphome</code> from PATH. Used for config validation.")
    }
}
