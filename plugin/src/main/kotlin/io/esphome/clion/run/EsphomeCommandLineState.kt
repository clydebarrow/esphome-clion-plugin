package io.esphome.clion.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.configurations.CommandLineState
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
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
        val base = buildCommandLine()
        // Run under a pseudo-terminal when it helps: ESPHome/PlatformIO then think
        // they have a TTY and emit ANSI-colored logs and in-place `\r` progress.
        // Force it for device operations over the network (OTA upload/logs), where
        // there's no downside — but never for serial, where a PTY buffers/empties
        // the output. The per-config "Emulate a terminal" still forces it too.
        val networkOp = configuration.command.usesDevice &&
            EsphomeCommandLines.isNetworkDevice(configuration.device)
        val commandLine = if (configuration.emulateTerminal || networkOp) {
            PtyCommandLine(base).withConsoleMode(false)
        } else {
            base
        }
        val handler = KillableColoredProcessHandler(commandLine)
        ProcessTerminatedListener.attach(handler)
        return handler
    }

    private fun buildCommandLine(): GeneralCommandLine = EsphomeCommandLines.build(
        backend = configuration.backend,
        command = configuration.command,
        configFile = File(configuration.configPath ?: error("No config file")),
        executable = EsphomeExecutables.forBackend(configuration.backend),
        dockerExecutable = EsphomeCommandLines.resolveDocker(),
        dockerImage = configuration.dockerImage,
        device = configuration.device,
        stateReporting = configuration.stateReporting,
        resetBeforeLogs = configuration.resetBeforeLogs,
        extraArgs = configuration.extraArgs,
        // Optional shared cache for Docker (off by default — needs the dir shared
        // with Docker Desktop; ESPHome otherwise caches into /config/.esphome).
        cacheDir = EsphomeSettings.getInstance()
            .takeIf { it.state.dockerCacheMount }
            ?.dockerCacheDir(),
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
        resetBeforeLogs: Boolean = false,
        extraArgs: String? = null,
        cacheDir: File? = null,
    ): GeneralCommandLine {
        // Trailing args common to both backends: device, state reporting, extras.
        val trailing = buildList {
            device?.trim()?.takeIf { it.isNotEmpty() && command.usesDevice }
                ?.let { addAll(listOf("--device", it)) }
            if (command.streamsLogs) {
                stateReporting.flag?.let(::add)
                if (resetBeforeLogs) add("--reset")
            }
            addAll(ParametersListUtil.parse(extraArgs.orEmpty()))
        }

        val commandLine = when (backend) {
            // Local and the managed venv differ only in which `esphome` runs.
            EsphomeBackend.LOCAL, EsphomeBackend.VENV -> {
                val exe = executable ?: error("esphome executable not found")
                GeneralCommandLine(exe, command.id, configFile.path)
                    .withParams(trailing)
                    .withEnvironment(sdlEnvironment()) // host/SDL builds need sdl2-config + libSDL2
            }
            // Mount the config's directory at /config (ESPHome's working dir),
            // plus a persistent host cache at /cache, and run on the file by
            // name. Serial devices won't pass through on macOS; OTA works.
            EsphomeBackend.DOCKER ->
                GeneralCommandLine(dockerExecutable)
                    .withParams(dockerRun(configFile, cacheDir, dockerImage, command.id, configFile.name))
                    .withParams(trailing)
        }
        return commandLine.finalize(configFile.parentFile)
    }

    /**
     * The `esphome config <file>` command used for background validation, built
     * for the same [backend] (and [executable]) as a run — so validation and
     * running agree on which ESPHome is authoritative. Diagnostics are matched by
     * file basename, so Docker's in-container `/config/<name>` paths still map
     * back to the open file. [executable] is required for LOCAL/VENV and ignored
     * for DOCKER.
     */
    fun buildConfig(
        backend: EsphomeBackend,
        configFile: File,
        executable: String?,
        dockerImage: String,
        dockerExecutable: String = "docker",
        cacheDir: File? = null,
    ): GeneralCommandLine {
        val commandLine = when (backend) {
            EsphomeBackend.LOCAL, EsphomeBackend.VENV -> {
                val exe = executable ?: error("esphome executable not found")
                GeneralCommandLine(exe, "config", configFile.path)
            }
            EsphomeBackend.DOCKER ->
                GeneralCommandLine(dockerExecutable)
                    .withParams(dockerRun(configFile, cacheDir, dockerImage, "config", configFile.name))
        }
        return commandLine.finalize(configFile.parentFile)
    }

    /**
     * Whether [device] is a network target (OTA: hostname/IP/`name.local`) rather
     * than a serial port. Blank is treated as not-network — ESPHome may pick a
     * serial port — so a PTY isn't forced where it could break serial.
     */
    fun isNetworkDevice(device: String?): Boolean {
        val d = device?.trim().orEmpty()
        return d.isNotEmpty() && !SERIAL_PORT.matches(d)
    }

    // Serial ports: /dev/ttyUSB0, /dev/cu.usbserial-…, COM3, ttyACM0, cu.…
    private val SERIAL_PORT = Regex("(?i)^(COM\\d+|/dev/.*|tty.*|cu\\..*)$")

    /** `run --rm -v <dir>:/config [-v <cache>:/cache] -w /config <image> <sub> <target>`. */
    private fun dockerRun(
        configFile: File,
        cacheDir: File?,
        dockerImage: String,
        subcommand: String,
        target: String,
    ): List<String> {
        val dir = configFile.parentFile?.path ?: "."
        val mounts = buildList {
            addAll(listOf("-v", "$dir:/config"))
            cacheDir?.let { addAll(listOf("-v", "${it.path}:/cache")) }
        }
        return listOf("run", "--rm") + mounts + listOf("-w", "/config", dockerImage, subcommand, target)
    }

    /** Common command-line finishing: working dir, UTF-8, and a login-shell PATH. */
    private fun GeneralCommandLine.finalize(workDir: File?): GeneralCommandLine =
        withWorkDirectory(workDir)
            .withCharset(StandardCharsets.UTF_8)
            // A GUI-launched IDE on macOS has a minimal PATH; use the login-shell
            // environment so `docker` (and a PATH `esphome`) resolve at exec time.
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

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

    // --- host/SDL display: make sdl2-config and libSDL2 reachable ---

    /**
     * Extra environment for a host/SDL build: `sdl2-config` (which Homebrew
     * installs outside a GUI IDE's PATH) added to PATH, and the SDL library dirs
     * — taken from `sdl2-config --libs` — added to the dynamic-linker path
     * (`DYLD_LIBRARY_PATH` on macOS, `LD_LIBRARY_PATH` elsewhere). Empty when
     * sdl2-config isn't found. Computed once per session.
     */
    fun sdlEnvironment(): Map<String, String> = sdlEnv

    private val sdlEnv: Map<String, String> by lazy { computeSdlEnvironment() }

    private fun computeSdlEnvironment(): Map<String, String> {
        val sdlConfig = SDL_BIN_DIRS.map { File(it, "sdl2-config") }.firstOrNull { it.canExecute() }
            ?: return emptyMap()
        val env = linkedMapOf("PATH" to prepend(sdlConfig.parent, EnvironmentUtil.getValue("PATH")))
        val libDirs = parseLibDirs(runForOutput(sdlConfig.path, "--libs"))
        if (libDirs.isNotEmpty()) {
            val libVar = if (SystemInfo.isMac) "DYLD_LIBRARY_PATH" else "LD_LIBRARY_PATH"
            env[libVar] = prepend(libDirs.joinToString(File.pathSeparator), EnvironmentUtil.getValue(libVar))
        }
        return env
    }

    /** Library search dirs from a `sdl2-config --libs` line (the `-L<dir>` flags). */
    internal fun parseLibDirs(libsOutput: String): List<String> =
        Regex("""-L(\S+)""").findAll(libsOutput).map { it.groupValues[1] }.distinct().toList()

    private fun runForOutput(vararg command: String): String = try {
        // CapturingProcessHandler drains stdout *and* stderr on separate threads
        // and kills the process on timeout — so this can't hang or deadlock.
        val output = CapturingProcessHandler(GeneralCommandLine(*command)).runProcess(5_000)
        if (output.isTimeout || output.exitCode != 0) "" else output.stdout
    } catch (_: Exception) {
        ""
    }

    private fun prepend(dir: String, base: String?): String =
        if (base.isNullOrEmpty()) dir else dir + File.pathSeparator + base

    private val SDL_BIN_DIRS = listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")

    private fun GeneralCommandLine.withParams(args: List<String>): GeneralCommandLine =
        apply { addParameters(args) }
}
