package io.esphome.clion.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.NotNullLazyValue

/** Run-configuration type for invoking ESPHome commands (compile/run/logs/…). */
class EsphomeRunConfigurationType : ConfigurationTypeBase(
    ID,
    "ESPHome",
    "Compile, upload, or view logs for an ESPHome config",
    NotNullLazyValue.createValue { AllIcons.Actions.Execute },
) {
    init {
        addFactory(EsphomeConfigurationFactory(this))
    }

    companion object {
        const val ID = "EsphomeRunConfiguration"
    }
}

class EsphomeConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = "ESPHome"

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        EsphomeRunConfiguration(project, this, "ESPHome")

    override fun getOptionsClass(): Class<EsphomeRunOptions> = EsphomeRunOptions::class.java
}
