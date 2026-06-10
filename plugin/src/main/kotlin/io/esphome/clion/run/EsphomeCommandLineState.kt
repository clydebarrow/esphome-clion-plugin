package io.esphome.clion.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.configurations.CommandLineState
import io.esphome.clion.settings.EsphomeSettings
import java.io.File
import java.nio.charset.StandardCharsets

/** Builds and runs the `esphome`/`docker` command line for a run configuration. */
class EsphomeCommandLineState(
    private val configuration: EsphomeRunConfiguration,
    environment: ExecutionEnvironment,
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val handler = KillableColoredProcessHandler(buildCommandLine())
        ProcessTerminatedListener.attach(handler)
        return handler
    }

    private fun buildCommandLine(): GeneralCommandLine = EsphomeCommandLines.build(
        backend = configuration.backend,
        command = configuration.command,
        configFile = File(configuration.configPath ?: error("No config file")),
        executable = EsphomeSettings.getInstance().resolveExecutable(),
        dockerImage = configuration.dockerImage,
        device = configuration.device,
    )
}

/** Pure construction of the `esphome`/`docker` command line — unit-testable. */
object EsphomeCommandLines {
    fun build(
        backend: EsphomeBackend,
        command: EsphomeCommand,
        configFile: File,
        executable: String?,
        dockerImage: String,
        device: String?,
    ): GeneralCommandLine {
        val deviceArgs = device?.trim()
            ?.takeIf { it.isNotEmpty() && command.usesDevice }
            ?.let { listOf("--device", it) }
            ?: emptyList()

        val commandLine = when (backend) {
            EsphomeBackend.LOCAL -> {
                val exe = executable ?: error("esphome executable not found")
                GeneralCommandLine(exe, command.id, configFile.path).withParams(deviceArgs)
            }
            // Mount the config's directory at /config (ESPHome's working dir) and
            // run the command on the file by name. Serial devices won't pass
            // through on macOS; OTA (network) works.
            EsphomeBackend.DOCKER -> {
                val dir = configFile.parentFile?.path ?: "."
                GeneralCommandLine("docker", "run", "--rm", "-v", "$dir:/config", "-w", "/config")
                    .withParams(listOf(dockerImage, command.id, configFile.name) + deviceArgs)
            }
        }
        return commandLine
            .withWorkDirectory(configFile.parentFile)
            .withCharset(StandardCharsets.UTF_8)
    }

    private fun GeneralCommandLine.withParams(args: List<String>): GeneralCommandLine =
        apply { addParameters(args) }
}
