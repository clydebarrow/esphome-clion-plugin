package io.esphome.clion.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import io.esphome.clion.psi.EsphomeYaml
import io.esphome.clion.settings.EsphomeSettings
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Offers an ESPHome run configuration (Run/Debug ▸ Run) from a standalone
 * config file — one with a top-level `esphome:` or `packages:` — so the user can
 * right-click a device YAML and compile/run it.
 */
class EsphomeRunConfigurationProducer : LazyRunConfigurationProducer<EsphomeRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(EsphomeRunConfigurationType::class.java)
            .configurationFactories
            .first()

    override fun setupConfigurationFromContext(
        configuration: EsphomeRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = context.psiLocation?.containingFile as? YAMLFile ?: return false
        if (!EsphomeYaml.isStandaloneConfig(file)) return false
        val virtualFile = file.virtualFile ?: return false
        configuration.configPath = virtualFile.path
        // Start from the user's configured defaults.
        val settings = EsphomeSettings.getInstance()
        configuration.backend = EsphomeBackend.of(settings.state.defaultBackend)
        configuration.dockerImage = settings.state.dockerImage ?: EsphomeRunOptions.DEFAULT_DOCKER_IMAGE
        configuration.uploadSpeed = settings.state.defaultUploadSpeed.orEmpty()
        configuration.resetBeforeLogs = settings.state.defaultResetBeforeLogs
        configuration.name = "esphome ${configuration.command.id} ${virtualFile.name}"
        return true
    }

    override fun isConfigurationFromContext(
        configuration: EsphomeRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val virtualFile = (context.psiLocation?.containingFile as? YAMLFile)?.virtualFile ?: return false
        return configuration.configPath == virtualFile.path
    }
}
