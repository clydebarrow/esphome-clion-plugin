package io.esphome.clion.structureview

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.openapi.editor.Editor
import io.esphome.clion.services.EsphomeIncludeGraph
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.structureView.YAMLCustomStructureViewFactory

/**
 * Swaps in [EsphomeStructureViewModel] for an ESPHome config (or a fragment
 * `!include`d by one) so `sensor:`/`binary_sensor:`/etc. list entries show
 * their `id`/`name` in the Structure window instead of the bundled YAML
 * view's generic "Sequence item". Returning null for any other YAML file
 * falls through to the bundled view, unaffected.
 */
class EsphomeStructureViewFactory : YAMLCustomStructureViewFactory {
    override fun getStructureViewBuilder(yamlFile: YAMLFile): StructureViewBuilder? {
        val virtualFile = yamlFile.virtualFile ?: return null
        if (!virtualFile.isInLocalFileSystem) return null
        if (!EsphomeIncludeGraph.getInstance(yamlFile.project).isEsphomeConfigContext(yamlFile)) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                EsphomeStructureViewModel(yamlFile, editor)
        }
    }
}
