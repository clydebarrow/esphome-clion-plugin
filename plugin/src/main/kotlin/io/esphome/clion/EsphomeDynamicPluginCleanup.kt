package io.esphome.clion

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import io.esphome.clion.api.EsphomeApiConnection

/**
 * Runs just before this plugin is unloaded (reload, update, or disable) and tears
 * down anything that would otherwise keep the plugin's classloader alive — chiefly
 * a live device connection's reader thread. Doing it here, synchronously, makes the
 * teardown independent of tool-window/panel disposal timing, so the plugin can be
 * reloaded without forcing an IDE restart.
 */
class EsphomeDynamicPluginCleanup : DynamicPluginListener {
    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString != PLUGIN_ID) return
        EsphomeApiConnection.stopAllAndAwait()
    }

    private companion object {
        const val PLUGIN_ID = "io.esphome.plugin"
    }
}
