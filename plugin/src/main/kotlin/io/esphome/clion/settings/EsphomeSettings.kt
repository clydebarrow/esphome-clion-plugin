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
 * Persisted ESPHome plugin settings. Currently just the path to the `esphome`
 * executable used for validation; blank means "auto-detect from PATH".
 */
@Service
@State(name = "EsphomeSettings", storages = [Storage("esphome.xml")])
class EsphomeSettings : SimplePersistentStateComponent<EsphomeSettings.State>(State()) {

    class State : BaseState() {
        var executablePath: String? by string("")
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

    companion object {
        fun getInstance(): EsphomeSettings = service()
    }
}
