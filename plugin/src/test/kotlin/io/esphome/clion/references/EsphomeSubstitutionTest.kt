package io.esphome.clion.references

import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.esphome.clion.services.EsphomeSubstitutions
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Substitution navigation and value resolution: `${name}` resolves to its
 * `substitutions:` definition (incl. across an include), and nested values
 * expand.
 */
class EsphomeSubstitutionTest : BasePlatformTestCase() {

    private fun resolvedKeyText(text: String, usage: String): String? {
        val offset = text.indexOf(usage) + usage.indexOf('{').let { if (it >= 0) it + 1 else 1 }
        val reference = myFixture.file.findReferenceAt(offset)
        return (reference?.resolve() as? YAMLKeyValue)?.keyText
    }

    fun `test braced substitution resolves to its definition`() {
        val text = """
            substitutions:
              device_name: living_room
            esphome:
              name: ${'$'}{device_name}
        """.trimIndent()
        myFixture.configureByText("device.yaml", text)
        assertEquals("device_name", resolvedKeyText(text, "\${device_name}"))
    }

    fun `test substitution resolves across an include`() {
        myFixture.addFileToProject("p/common.yaml", "substitutions:\n  prefix: home\n")
        val text = "esphome:\n  name: x\npackages:\n  c: !include common.yaml\nweb_server:\n  id: ${'$'}{prefix}_web\n"
        val vf = myFixture.addFileToProject("p/device.yaml", text).virtualFile
        myFixture.configureFromExistingVirtualFile(vf)
        val offset = myFixture.file.text.indexOf("\${prefix}") + 2
        val reference = myFixture.file.findReferenceAt(offset)
        assertEquals("prefix", (reference?.resolve() as? YAMLKeyValue)?.keyText)
    }

    fun `test value resolution expands nested substitutions`() {
        val text = """
            substitutions:
              base: living
              full: ${'$'}{base}_room
            esphome:
              name: ${'$'}{full}
        """.trimIndent()
        val vf = myFixture.configureByText("device.yaml", text).virtualFile
        val service = EsphomeSubstitutions.getInstance(project)
        assertEquals("living_room", service.valueOf("full", vf))
    }

    fun `test bare dollar form resolves too`() {
        val text = "substitutions:\n  host_name: sdl\nesphome:\n  name: \$host_name\n"
        myFixture.configureByText("device.yaml", text)
        val offset = myFixture.file.text.indexOf("\$host_name") + 1
        val reference = myFixture.file.findReferenceAt(offset)
        assertEquals("host_name", (reference?.resolve() as? YAMLKeyValue)?.keyText)
    }
}
