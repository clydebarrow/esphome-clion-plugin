package io.esphome.clion.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.esphome.clion.run.EsphomeVenv

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

        group("Run configuration defaults") {
            row("Default backend:") {
                comboBox(listOf(BACKEND_LOCAL, BACKEND_DOCKER))
                    .applyToComponent {
                        renderer = SimpleListCellRenderer.create("") {
                            if (it == BACKEND_DOCKER) "Docker" else "Local esphome"
                        }
                    }
                    .bindItem(
                        { EsphomeSettings.getInstance().state.defaultBackend ?: BACKEND_LOCAL },
                        { EsphomeSettings.getInstance().state.defaultBackend = it ?: BACKEND_LOCAL },
                    )
            }
            row("Docker image:") {
                textField()
                    .bindText(
                        { EsphomeSettings.getInstance().state.dockerImage ?: EsphomeSettings.DEFAULT_DOCKER_IMAGE },
                        { EsphomeSettings.getInstance().state.dockerImage = it.trim() },
                    )
                    .columns(40)
            }.rowComment("Image new Docker run configurations start with.")
            row {
                checkBox("Mount a shared cache for Docker")
                    .bindSelected(
                        { EsphomeSettings.getInstance().state.dockerCacheMount },
                        { EsphomeSettings.getInstance().state.dockerCacheMount = it },
                    )
            }.rowComment(
                "Speeds up repeat compiles, but the cache directory must be shared with " +
                    "Docker Desktop (Settings → Resources → File Sharing).",
            )
        }

        group("Managed esphome venv") {
            lateinit var versionField: JBTextField
            row("esphome version:") {
                versionField = textField()
                    .bindText(
                        { EsphomeSettings.getInstance().state.esphomeVersion ?: "" },
                        { EsphomeSettings.getInstance().state.esphomeVersion = it.trim() },
                    )
                    .columns(20)
                    .component
            }.rowComment("Version to install (e.g. <code>2025.7.0</code>), or blank for the latest.")
            row {
                button("Set up / update venv") {
                    EsphomeVenv.provision(null, versionField.text.trim())
                }
            }.rowComment(
                "Creates a managed Python venv and pip-installs esphome. " +
                    "Select the <b>Managed venv</b> backend on a run configuration to use it.",
            )
        }
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

    private companion object {
        const val BACKEND_LOCAL = "local"
        const val BACKEND_DOCKER = "docker"
    }
}
