package io.esphome.clion.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import io.esphome.clion.settings.EsphomeSettings
import java.io.File

/**
 * Runs an ESPHome command (`compile`, `run`, `upload`, `logs`, `clean`) on a
 * config file, either via a local `esphome` or the official Docker image.
 */
class EsphomeRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : RunConfigurationBase<EsphomeRunOptions>(project, factory, name) {

    public override fun getOptions(): EsphomeRunOptions = super.getOptions() as EsphomeRunOptions

    var configPath: String?
        get() = options.configPath
        set(value) { options.configPath = value }

    var command: EsphomeCommand
        get() = EsphomeCommand.of(options.command)
        set(value) { options.command = value.id }

    var backend: EsphomeBackend
        get() = EsphomeBackend.of(options.backend)
        set(value) { options.backend = value.id }

    var dockerImage: String
        get() = options.dockerImage?.takeIf { it.isNotBlank() } ?: EsphomeRunOptions.DEFAULT_DOCKER_IMAGE
        set(value) { options.dockerImage = value }

    var device: String?
        get() = options.device
        set(value) { options.device = value }

    var stateReporting: StateReporting
        get() = StateReporting.of(options.stateReporting)
        set(value) { options.stateReporting = value.id }

    var extraArgs: String?
        get() = options.extraArgs
        set(value) { options.extraArgs = value }

    var emulateTerminal: Boolean
        get() = options.emulateTerminal
        set(value) { options.emulateTerminal = value }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfigurationBase<*>> =
        EsphomeRunConfigurationEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): EsphomeCommandLineState =
        EsphomeCommandLineState(this, environment)

    override fun checkConfiguration() {
        val path = configPath?.trim().orEmpty()
        if (path.isEmpty()) throw RuntimeConfigurationError("No ESPHome config file selected")
        if (!File(path).isFile) throw RuntimeConfigurationError("Config file does not exist: $path")
        when (backend) {
            EsphomeBackend.LOCAL ->
                if (EsphomeSettings.getInstance().resolveExecutable() == null) {
                    throw RuntimeConfigurationError(
                        "esphome executable not found. Set it in Settings | Tools | ESPHome, or use the venv/Docker backend.",
                    )
                }
            EsphomeBackend.VENV ->
                if (!EsphomeVenv.isProvisioned()) {
                    throw RuntimeConfigurationError(
                        "ESPHome venv not set up. Create it in Settings | Tools | ESPHome.",
                    )
                }
            EsphomeBackend.DOCKER -> {
                if (EsphomeCommandLines.findDocker() == null) {
                    throw RuntimeConfigurationError(
                        "docker not found on PATH. Install/start Docker, or use the Local backend.",
                    )
                }
                if (command.usesDevice && device.isNullOrBlank() && SystemInfo.isMac) {
                    // Docker Desktop on macOS can't reach host USB serial ports.
                    throw RuntimeConfigurationWarning(
                        "Serial ${command.display.lowercase()} won't work in Docker on macOS (no USB passthrough). " +
                            "Use the Local backend, or set Device to an OTA host.",
                    )
                }
            }
        }
    }
}
