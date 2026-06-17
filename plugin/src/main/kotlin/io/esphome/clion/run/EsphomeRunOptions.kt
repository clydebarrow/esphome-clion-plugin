package io.esphome.clion.run

import com.intellij.execution.configurations.RunConfigurationOptions
import io.esphome.clion.settings.EsphomeSettings

/** Persisted state of an [EsphomeRunConfiguration]. */
class EsphomeRunOptions : RunConfigurationOptions() {
    /** Absolute path to the ESPHome YAML config the command runs on. */
    var configPath: String? by string("")

    /**
     * The ESPHome sub-command: `compile`, `run`, `upload`, `logs`, `clean`.
     * New configurations default to `run` (compile + upload + logs).
     */
    var command: String? by string(EsphomeCommand.RUN.id)

    /** Where ESPHome runs: [EsphomeBackend.LOCAL] or [EsphomeBackend.DOCKER]. */
    var backend: String? by string(EsphomeSettings.DEFAULT_BACKEND)

    /** Image used when [backend] is Docker. */
    var dockerImage: String? by string(DEFAULT_DOCKER_IMAGE)

    /**
     * Optional `--device`: an OTA hostname/IP or a serial port. Blank lets
     * ESPHome pick (it prompts/auto-selects). Serial devices don't work in
     * Docker on macOS — see [EsphomeBackend].
     */
    var device: String? by string("")

    /** Whether `logs`/`run` subscribe to entity states (`--states`/`--no-states`). */
    var stateReporting: String? by string(StateReporting.DEFAULT.id)

    /** Reset the device before starting serial logs (`--reset`), for `logs`/`run`. */
    var resetBeforeLogs: Boolean by property(false)

    /**
     * Optional `--upload_speed` — the serial flashing baud rate — for `run` and
     * `upload`. Blank uses ESPHome's default/configured speed. Defaults to the
     * plugin's default-baud setting (read when the config is created); ignored for
     * OTA (network) targets.
     */
    var uploadSpeed: String? by string(EsphomeSettings.getInstance().state.defaultUploadSpeed)

    /** Extra arguments appended after the config file (e.g. `-s name value`). */
    var extraArgs: String? by string("")

    /**
     * Run under a pseudo-terminal so esptool emits ANSI colour and an in-place
     * progress bar. On by default — the handler now keeps the serial upload bar
     * visible (see [EsphomeProcessHandler]), so there's no longer a serial
     * downside. Turn it off only if a specific tool misbehaves under a PTY.
     */
    var emulateTerminal: Boolean by property(true)

    companion object {
        val DEFAULT_DOCKER_IMAGE: String get() = EsphomeSettings.DEFAULT_DOCKER_IMAGE
    }
}

/** ESPHome sub-commands the run configuration can invoke. */
enum class EsphomeCommand(val id: String, val display: String) {
    COMPILE("compile", "Compile"),
    RUN("run", "Run (compile + upload + logs)"),
    UPLOAD("upload", "Upload"),
    LOGS("logs", "Logs"),
    CLEAN("clean", "Clean build files"),
    ;

    /** Commands that flash or talk to a device, so a `--device` applies. */
    val usesDevice: Boolean get() = this == RUN || this == UPLOAD || this == LOGS

    /** Commands that stream logs, where `--states`/`--no-states` applies. */
    val streamsLogs: Boolean get() = this == RUN || this == LOGS

    /** Commands that flash, where `--upload_speed` (serial baud) applies. */
    val usesUploadSpeed: Boolean get() = this == RUN || this == UPLOAD

    companion object {
        fun of(id: String?): EsphomeCommand = entries.firstOrNull { it.id == id } ?: COMPILE

        /** Common serial flashing baud rates offered in the baud selector. */
        val COMMON_UPLOAD_SPEEDS = listOf("", "115200", "230400", "460800", "921600")
    }
}

/** Execution backend for ESPHome commands. */
enum class EsphomeBackend(val id: String, val display: String) {
    LOCAL("local", "Local esphome"),
    VENV("venv", "Managed venv (pip esphome)"),
    DOCKER("docker", "Docker (ghcr.io/esphome/esphome)"),
    ;

    companion object {
        fun of(id: String?): EsphomeBackend = entries.firstOrNull { it.id == id } ?: LOCAL
    }
}

/** Whether log output subscribes to entity state changes. */
enum class StateReporting(val id: String, val display: String, val flag: String?) {
    DEFAULT("default", "Default (ESPHome decides)", null),
    ON("on", "Show device states", "--states"),
    OFF("off", "Hide device states", "--no-states"),
    ;

    companion object {
        fun of(id: String?): StateReporting = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
