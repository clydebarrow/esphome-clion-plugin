package io.esphome.clion.run

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import io.esphome.clion.psi.EsphomeYaml
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Editor action that runs a fixed ESPHome [command] on the current config file —
 * the one-click buttons on the editor floating toolbar (see
 * [io.esphome.clion.run.EsphomeEditorFloatingToolbarProvider]). Enabled only on a
 * standalone ESPHome config; delegates to [EsphomeRunLauncher].
 */
abstract class EsphomeRunFromEditorAction(private val command: EsphomeCommand) : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && isEsphomeConfig(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        EsphomeRunLauncher.launch(project, file, command)
    }

    private fun isEsphomeConfig(e: AnActionEvent): Boolean {
        val yaml = e.getData(CommonDataKeys.PSI_FILE) as? YAMLFile ?: return false
        return EsphomeYaml.isStandaloneConfig(yaml)
    }
}

/** Floating-toolbar button: `esphome run` (compile + upload + logs). */
class EsphomeRunAction : EsphomeRunFromEditorAction(EsphomeCommand.RUN)

/** Floating-toolbar button: `esphome logs`. */
class EsphomeLogsAction : EsphomeRunFromEditorAction(EsphomeCommand.LOGS)
