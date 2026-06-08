package io.esphome.clion.references

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Find Usages and Rename driven from an `id:` declaration: usages are found
 * across the file (and includes), and rename rewrites the declaration plus every
 * reference.
 */
class EsphomeIdFindUsagesAndRenameTest : BasePlatformTestCase() {

    private val deviceText = """
        esphome:
          name: x
        output:
          - platform: gpio
            id: relay_out
            pin: 4
        light:
          - platform: binary
            name: L
            output: relay_out
    """.trimIndent()

    private fun declarationScalar(name: String): YAMLScalar {
        val offset = myFixture.file.text.indexOf("id: $name") + "id: ".length
        return PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(offset), YAMLScalar::class.java)!!
    }

    fun `test find usages from declaration finds the reference`() {
        myFixture.configureByText("device.yaml", deviceText)
        val usages = myFixture.findUsages(declarationScalar("relay_out"))
        assertEquals(1, usages.size)
        // the usage is the `output: relay_out` reference, not the declaration itself
        assertTrue(usages.single().element!!.text.contains("relay_out"))
    }

    fun `test find usages crosses an include`() {
        myFixture.addFileToProject("p/outputs.yaml", "output:\n  - platform: gpio\n    id: relay_out\n    pin: 4\n")
        val device = myFixture.addFileToProject(
            "p/device.yaml",
            "esphome:\n  name: x\npackages:\n  o: !include outputs.yaml\n" +
                "light:\n  - platform: binary\n    name: L\n    output: relay_out\n",
        )
        // search from the declaration in the included fragment
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("p/outputs.yaml"))
        val decl = declarationScalar("relay_out")
        val usages = myFixture.findUsages(decl)
        assertEquals(1, usages.size)
        assertEquals("device.yaml", usages.single().file!!.name)
    }

    fun `test rename from the declaration updates declaration and references`() {
        myFixture.configureByText("device.yaml", deviceText)
        val offset = myFixture.file.text.indexOf("id: relay_out") + "id: ".length
        myFixture.editor.caretModel.moveToOffset(offset + 1)
        myFixture.renameElementAtCaretUsingHandler("relay_main")

        val text = myFixture.file.text
        assertFalse("old name gone", text.contains("relay_out"))
        assertEquals("declaration + reference both renamed", 2, Regex("\\brelay_main\\b").findAll(text).count())
    }

    fun `test rename from a reference updates declaration and references`() {
        myFixture.configureByText("device.yaml", deviceText)
        val offset = myFixture.file.text.indexOf("output: relay_out") + "output: ".length
        myFixture.editor.caretModel.moveToOffset(offset + 1)
        myFixture.renameElementAtCaretUsingHandler("relay_main")

        val text = myFixture.file.text
        assertFalse(text.contains("relay_out"))
        assertEquals(2, Regex("\\brelay_main\\b").findAll(text).count())
    }
}
