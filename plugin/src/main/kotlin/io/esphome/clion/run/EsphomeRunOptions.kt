package io.esphome.clion.run

import com.intellij.execution.configurations.RunConfigurationOptions

/** Persisted state of an [EsphomeRunConfiguration]. */
class EsphomeRunOptions : RunConfigurationOptions() {
    /** Absolute path to the ESPHome YAML config the command runs on. */
    var configPath: String? by string("")

    /** The ESPHome sub-command: `compile`, `run`, `upload`, `logs`, `clean`. */
    var command: String? by string(EsphomeCommand.COMPILE.id)

    /** Where ESPHome runs: [EsphomeBackend.LOCAL] or [EsphomeBackend.DOCKER]. */
    var backend: String? by string(EsphomeBackend.LOCAL.id)

    /** Image used when [backend] is Docker. */
    var dockerImage: String? by string(DEFAULT_DOCKER_IMAGE)

    /**
     * Optional `--device`: an OTA hostname/IP or a serial port. Blank lets
     * ESPHome pick (it prompts/auto-selects). Serial devices don't work in
     * Docker on macOS — see [EsphomeBackend].
     */
    var device: String? by string("")

    companion object {
        const val DEFAULT_DOCKER_IMAGE = "ghcr.io/esphome/esphome:latest"
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
