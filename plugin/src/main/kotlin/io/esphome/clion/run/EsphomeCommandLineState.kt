package io.esphome.clion.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.configurations.CommandLineState
import com.intellij.util.execution.ParametersListUtil
import io.esphome.clion.settings.EsphomeSettings
import java.io.File
import java.nio.charset.StandardCharsets

/** Builds and runs the `esphome`/`docker` command line for a run configuration. */
class EsphomeCommandLineState(
    private val configuration: EsphomeRunConfiguration,
    environment: ExecutionEnvironment,
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        // Run under a pseudo-terminal: otherwise ESPHome/PlatformIO see no TTY
        // and print compile/upload progress as separate scrolling lines instead
        // of overwriting one line with `\r`, which the console renders in place.
        val commandLine = PtyCommandLine(buildCommandLine()).withConsoleMode(false)
        val handler = KillableColoredProcessHandler(commandLine)
        ProcessTerminatedListener.attach(handler)
        return handler
    }

    private fun buildCommandLine(): GeneralCommandLine = EsphomeCommandLines.build(
        backend = configuration.backend,
        command = configuration.command,
        configFile = File(configuration.configPath ?: error("No config file")),
        executable = EsphomeSettings.getInstance().resolveExecutable(),
        dockerExecutable = EsphomeCommandLines.resolveDocker(),
        dockerImage = configuration.dockerImage,
        device = configuration.device,
        stateReporting = configuration.stateReporting,
        extraArgs = configuration.extraArgs,
        // Persist PlatformIO toolchains across Docker runs instead of
        // re-downloading them into each project's .esphome dir.
        cacheDir = EsphomeSettings.getInstance().dockerCacheDir(),
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
        dockerExecutable: String = "docker",
        stateReporting: StateReporting = StateReporting.DEFAULT,
        extraArgs: String? = null,
        cacheDir: File? = null,
    ): GeneralCommandLine {
        // Trailing args common to both backends: device, state reporting, extras.
        val trailing = buildList {
            device?.trim()?.takeIf { it.isNotEmpty() && command.usesDevice }
                ?.let { addAll(listOf("--device", it)) }
            if (command.streamsLogs) stateReporting.flag?.let(::add)
            addAll(ParametersListUtil.parse(extraArgs.orEmpty()))
        }

        val commandLine = when (backend) {
            EsphomeBackend.LOCAL -> {
                val exe = executable ?: error("esphome executable not found")
                GeneralCommandLine(exe, command.id, configFile.path).withParams(trailing)
            }
            // Mount the config's directory at /config (ESPHome's working dir),
            // plus a persistent host cache at /cache, and run on the file by
            // name. Serial devices won't pass through on macOS; OTA works.
            EsphomeBackend.DOCKER -> {
                val dir = configFile.parentFile?.path ?: "."
                val mounts = buildList {
                    addAll(listOf("-v", "$dir:/config"))
                    cacheDir?.let { addAll(listOf("-v", "${it.path}:/cache")) }
                }
                GeneralCommandLine(dockerExecutable)
                    .withParams(listOf("run", "--rm") + mounts + listOf("-w", "/config", dockerImage, command.id, configFile.name))
                    .withParams(trailing)
            }
        }
        return commandLine
            .withWorkDirectory(configFile.parentFile)
            .withCharset(StandardCharsets.UTF_8)
            // A GUI-launched IDE on macOS has a minimal PATH; use the login-shell
            // environment so `docker` (and a PATH `esphome`) resolve at exec time.
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
    }

    /**
     * Full path to `docker` — a GUI IDE's PATH often omits where Docker Desktop
     * installs it, so fall back to the usual locations. Returns the literal
     * `"docker"` if none resolve, so the run still attempts (and errors clearly).
     */
    fun resolveDocker(): String = findDocker() ?: "docker"

    /** Resolved `docker` path, or null when it can't be located. */
    fun findDocker(): String? =
        PathEnvironmentVariableUtil.findInPath("docker")?.absolutePath
            ?: DOCKER_FALLBACKS.firstOrNull { File(it).canExecute() }

    private val DOCKER_FALLBACKS = listOf(
        "/usr/local/bin/docker",
        "/opt/homebrew/bin/docker",
        "/Applications/Docker.app/Contents/Resources/bin/docker",
        "/usr/bin/docker",
    )

    private fun GeneralCommandLine.withParams(args: List<String>): GeneralCommandLine =
        apply { addParameters(args) }
}
