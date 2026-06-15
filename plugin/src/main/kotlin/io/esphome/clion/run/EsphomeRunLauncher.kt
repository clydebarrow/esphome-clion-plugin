package io.esphome.clion.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.esphome.clion.settings.EsphomeSettings

/**
 * Launches an ESPHome [command] on [file] from a one-click entry point (the
 * editor floating toolbar). It reuses an existing run configuration for the same
 * file+command if the user already made one, otherwise creates a *temporary*
 * one — seeded from the same defaults as [EsphomeRunConfigurationProducer], so it
 * behaves like right-clicking the config and running it, and the user can still
 * tweak and save it.
 */
object EsphomeRunLauncher {

    fun launch(project: Project, file: VirtualFile, command: EsphomeCommand) {
        val runManager = RunManager.getInstance(project)
        val settings = existingSettings(project, file, command)
            ?: createTemporarySettings(project, file, command)
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    private fun existingSettings(project: Project, file: VirtualFile, command: EsphomeCommand) =
        RunManager.getInstance(project).allSettings.firstOrNull {
            val config = it.configuration
            config is EsphomeRunConfiguration && config.configPath == file.path && config.command == command
        }

    private fun createTemporarySettings(project: Project, file: VirtualFile, command: EsphomeCommand) =
        RunManager.getInstance(project).createConfiguration(
            "esphome ${command.id} ${file.name}",
            ConfigurationTypeUtil.findConfigurationType(EsphomeRunConfigurationType::class.java)
                .configurationFactories.first(),
        ).also { settings ->
            val config = settings.configuration as EsphomeRunConfiguration
            config.configPath = file.path
            config.command = command
            val state = EsphomeSettings.getInstance().state
            config.backend = EsphomeBackend.of(state.defaultBackend)
            config.dockerImage = state.dockerImage ?: EsphomeRunOptions.DEFAULT_DOCKER_IMAGE
            RunManager.getInstance(project).setTemporaryConfiguration(settings)
        }
}
