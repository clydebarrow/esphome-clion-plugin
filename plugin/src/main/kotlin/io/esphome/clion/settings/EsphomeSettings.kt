package io.esphome.clion.settings

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.service
import java.io.File

/**
 * Persisted ESPHome plugin settings: the `esphome` executable (blank =
 * auto-detect from PATH) used for validation, plus the defaults new run
 * configurations start from (backend and Docker image).
 */
@Service
@State(name = "EsphomeSettings", storages = [Storage("esphome.xml")])
class EsphomeSettings : SimplePersistentStateComponent<EsphomeSettings.State>(State()) {

    class State : BaseState() {
        var executablePath: String? by string("")

        /** Default backend for new run configs: `local` or `docker`. */
        var defaultBackend: String? by string(DEFAULT_BACKEND)

        /** Default image for the Docker backend. */
        var dockerImage: String? by string(DEFAULT_DOCKER_IMAGE)

        /**
         * Default serial flashing baud rate (`--upload_speed`) new run configs
         * start with. Blank uses ESPHome's default/configured speed.
         */
        var defaultUploadSpeed: String? by string(DEFAULT_UPLOAD_SPEED)

        /** Whether new `logs`/`run` configs reset the device before logs (`--reset`). */
        var defaultResetBeforeLogs: Boolean by property(false)

        /** esphome version to install in the managed venv; blank = latest. */
        var esphomeVersion: String? by string("")

        /**
         * Mount a shared host cache at the Docker container's `/cache`. Off by
         * default: it requires the cache directory to be shared with Docker
         * Desktop, and ESPHome otherwise caches into the (already-mounted)
         * config directory's `.esphome/`.
         */
        var dockerCacheMount: Boolean by property(false)
    }

    /**
     * The esphome executable to run, or null if none can be found. Uses the
     * configured path when set and runnable, otherwise looks it up on PATH.
     */
    fun resolveExecutable(): String? {
        val configured = state.executablePath?.trim().orEmpty()
        if (configured.isNotEmpty()) {
            return configured.takeIf { File(it).canExecute() }
        }
        return PathEnvironmentVariableUtil.findInPath("esphome")?.absolutePath
    }

    /** Persistent host cache directory mounted into the Docker container's `/cache`. */
    fun dockerCacheDir(): File =
        File(System.getProperty("user.home"), ".cache/esphome").apply { mkdirs() }

    companion object {
        const val DEFAULT_BACKEND = "local"
        const val DEFAULT_DOCKER_IMAGE = "ghcr.io/esphome/esphome:latest"

        /** esptool's default serial flashing baud rate. */
        const val DEFAULT_UPLOAD_SPEED = "460800"

        fun getInstance(): EsphomeSettings = service()
    }
}
