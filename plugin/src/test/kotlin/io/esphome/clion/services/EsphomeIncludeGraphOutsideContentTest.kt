package io.esphome.clion.services

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.esphome.clion.structureview.EsphomeStructureViewFactory
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import java.nio.file.Files

/**
 * A device-config directory that is not under any module content root (a
 * CLion "empty module" project, or one attached beside an unrelated project)
 * isn't covered by the file-based include index, so the include graph must
 * fall back to the filesystem — otherwise a package fragment looks like an
 * orphan: no Structure labels, no Find Usages across the packages.
 */
class EsphomeIncludeGraphOutsideContentTest : BasePlatformTestCase() {

    private fun setUpDir(files: Map<String, String>): Map<String, VirtualFile> {
        val dir = Files.createTempDirectory("esphome-outside").toFile()
        files.forEach { (name, text) -> java.io.File(dir, name).apply { parentFile.mkdirs() }.writeText(text) }
        val fs = LocalFileSystem.getInstance()
        return files.keys.associateWith { fs.refreshAndFindFileByIoFile(java.io.File(dir, it))!! }
    }

    private val device = """
        packages:
          - !include sensors.yaml
          - !include logic.yaml
    """.trimIndent()

    private val sensors = """
        binary_sensor:
          - platform: gpio
            id: float_switch
            pin: 12
    """.trimIndent()

    private val logic = """
        binary_sensor:
          - id: !extend float_switch
            name: "Float Switch"
          - platform: copy
            id: copy_of_float
            source_id: float_switch
    """.trimIndent()

    fun `test includers and roots resolve for files outside the content roots`() {
        val vfs = setUpDir(mapOf("device.yaml" to device, "sensors.yaml" to sensors, "logic.yaml" to logic))
        val sensorsFile = vfs.getValue("sensors.yaml")
        assertFalse(ProjectFileIndex.getInstance(project).isInContent(sensorsFile))

        val graph = EsphomeIncludeGraph.getInstance(project)
        assertEquals(listOf(vfs.getValue("device.yaml")), graph.directIncluders(sensorsFile))
        assertEquals(setOf(vfs.getValue("device.yaml")), graph.rootsOf(sensorsFile))
    }

    fun `test a package fragment outside the content roots gets the ESPHome structure view`() {
        val vfs = setUpDir(mapOf("device.yaml" to device, "sensors.yaml" to sensors, "logic.yaml" to logic))
        val yaml = com.intellij.psi.PsiManager.getInstance(project).findFile(vfs.getValue("sensors.yaml")) as YAMLFile
        assertNotNull(EsphomeStructureViewFactory().getStructureViewBuilder(yaml))
    }

    fun `test find usages from an id declaration reaches sibling packages outside the content roots`() {
        val vfs = setUpDir(mapOf("device.yaml" to device, "sensors.yaml" to sensors, "logic.yaml" to logic))
        val yaml = com.intellij.psi.PsiManager.getInstance(project).findFile(vfs.getValue("sensors.yaml")) as YAMLFile
        val declaration = PsiTreeUtil.findChildrenOfType(yaml, YAMLKeyValue::class.java)
            .first { it.keyText == "id" && it.valueText == "float_switch" }.value as YAMLScalar

        val usages = ReferencesSearch.search(declaration).findAll()
        assertEquals(2, usages.size)
        assertTrue(usages.all { it.element.containingFile.name == "logic.yaml" })
    }
}
