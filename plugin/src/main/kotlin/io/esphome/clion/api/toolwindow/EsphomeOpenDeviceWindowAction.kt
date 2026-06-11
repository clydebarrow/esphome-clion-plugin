package io.esphome.clion.api.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager
import io.esphome.clion.psi.EsphomeYaml
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Context-menu action (editor & project view) that opens the **ESPHome Device**
 * tool window and pre-fills host + key from the right-clicked config — the
 * companion to the Run action. Visible only on an ESPHome config file.
 */
class EsphomeOpenDeviceWindowAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && isEsphomeConfig(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(EsphomeApiToolWindowFactory.ID) ?: return
        toolWindow.activate({
            (toolWindow.contentManager.contents.firstOrNull()?.component as? EsphomeApiPanel)?.prefill(file)
        }, /* autoFocusContents = */ true)
    }

    private fun isEsphomeConfig(e: AnActionEvent): Boolean {
        val yaml = e.getData(CommonDataKeys.PSI_FILE) as? YAMLFile ?: return false
        return EsphomeYaml.isStandaloneConfig(yaml)
    }
}
