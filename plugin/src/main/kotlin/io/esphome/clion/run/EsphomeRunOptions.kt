package io.esphome.clion.run

import com.intellij.execution.configurations.RunConfigurationOptions
import io.esphome.clion.settings.EsphomeSettings

/** Persisted state of an [EsphomeRunConfiguration]. */
class EsphomeRunOptions : RunConfigurationOptions() {
    /** Absolute path to the ESPHome YAML config the command runs on. */
    var configPath: String? by string("")

    /** The ESPHome sub-command: `compile`, `run`, `upload`, `logs`, `clean`. */
    var command: String? by string(EsphomeCommand.COMPILE.id)

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

    /** Extra arguments appended after the config file (e.g. `-s name value`). */
    var extraArgs: String? by string("")

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

    companion object {
        fun of(id: String?): EsphomeCommand = entries.firstOrNull { it.id == id } ?: COMPILE
    }
}

/** Execution backend for ESPHome commands. */
enum class EsphomeBackend(val id: String, val display: String) {
    LOCAL("local", "Local esphome"),
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
