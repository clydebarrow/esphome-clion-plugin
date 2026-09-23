package io.esphome.clion.structureview

import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.openapi.editor.Editor
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Root of the ESPHome structure view: document order, no alphabetical
 * re-sort — a `sensor:` list's order (and a lambda's action order) is
 * meaningful in ESPHome, unlike an alphabetized member list.
 */
class EsphomeStructureViewModel(file: YAMLFile, editor: Editor?) :
    StructureViewModelBase(file, editor, EsphomeStructureViewElement(file))
