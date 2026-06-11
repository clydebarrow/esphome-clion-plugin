package io.esphome.clion.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.io.File

/**
 * A plugin-managed Python venv with `esphome` installed — an alternative to a
 * system esphome (Local) or Docker. It lets users pin an esphome version
 * without a global install, and keeps full serial capability (unlike Docker on
 * macOS). The venv lives under the IDE's system directory and is provisioned
 * once via [provision].
 */
object EsphomeVenv {
    /** Managed venv location, outside any project. */
    fun dir(): File = File(PathManager.getSystemPath(), "esphome-clion/venv")

    /** The venv's `esphome` executable (OS-aware), whether or not it exists yet. */
    fun esphome(): File = bin("esphome")

    fun isProvisioned(): Boolean = esphome().canExecute()

    private fun bin(name: String): File =
        if (SystemInfo.isWindows) File(dir(), "Scripts/$name.exe") else File(dir(), "bin/$name")

    private fun basePython(): String =
        PathEnvironmentVariableUtil.findInPath(if (SystemInfo.isWindows) "python" else "python3")?.absolutePath
            ?: PathEnvironmentVariableUtil.findInPath("python")?.absolutePath
            ?: "python3"

    /**
     * Create (or refresh) the venv and install `esphome` — a specific [version],
     * or latest when blank — in the background, notifying on completion/failure.
     */
    fun provision(project: Project?, version: String, onSuccess: (() -> Unit)? = null) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Setting up ESPHome venv", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    dir().parentFile?.mkdirs()
                    indicator.text = "Creating virtual environment…"
                    step(listOf(basePython(), "-m", "venv", dir().path), indicator)
                    val install = pipRequirement(version)
                    indicator.text = "Installing esphome (${version.ifBlank { "latest" }})…"
                    step(
                        listOf(bin("python").path, "-m", "pip", "install", "--upgrade", "--disable-pip-version-check") +
                            install,
                        indicator,
                    )
                    notify("ESPHome venv ready: ${esphome().path}", NotificationType.INFORMATION)
                    onSuccess?.let { ApplicationManager.getApplication().invokeLater(it) }
                }

                override fun onThrowable(error: Throwable) {
                    thisLogger().warn("ESPHome venv setup failed", error)
                    notify(error.message ?: "ESPHome venv setup failed.", NotificationType.ERROR)
                }
            },
        )
    }

    /**
     * Map a [version] field to `pip install` arguments. A bare `==dev` is not a
     * valid version specifier, so named channels resolve to the right form:
     *   blank/"latest" → esphome; "beta" → --pre esphome; "dev" or any other
     *   non-numeric value → the matching esphome git branch/tag; otherwise a
     *   numeric value (`2025.7.0`, `2025.7.0b1`) → `esphome==<version>`.
     */
    internal fun pipRequirement(version: String): List<String> {
        val v = version.trim()
        return when {
            v.isEmpty() || v.equals("latest", ignoreCase = true) -> listOf("esphome")
            v.equals("beta", ignoreCase = true) -> listOf("--pre", "esphome")
            v.first().isDigit() -> listOf("esphome==$v")
            else -> listOf("git+https://github.com/esphome/esphome.git@$v")
        }
    }

    private fun step(command: List<String>, indicator: ProgressIndicator) {
        val commandLine = GeneralCommandLine(command)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        val output = CapturingProcessHandler(commandLine).runProcessWithProgressIndicator(indicator)
        if (output.exitCode != 0) {
            val detail = output.stderr.ifBlank { output.stdout }.trim().takeLast(800)
            throw RuntimeException("`${command.joinToString(" ")}` failed (exit ${output.exitCode}):\n$detail")
        }
    }

    private fun notify(message: String, type: NotificationType) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("ESPHome")
            .createNotification("ESPHome venv", message, type)
        // Notifications must be posted on the EDT; provisioning runs on a pooled
        // background thread, so hop over explicitly.
        ApplicationManager.getApplication().invokeLater { notification.notify(null) }
    }
}
