package io.esphome.clion.references

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Find Usages driven from a secrets-file `key:` declaration: since our
 * `!secret` references are soft and resolve forward only (usage →
 * declaration), the declaration side needs its own searcher/target-provider
 * wiring — without it Find Usages on a declaration reports "no usages found".
 *
 * The declaration element is the whole [YAMLKeyValue], not its key leaf: the
 * bundled YAML plugin treats a `YAMLKeyValue` as the `PsiNamedElement` for a
 * YAML key, and that's the shape a real Cmd-click/Find-Usages invocation
 * actually hands the platform (confirmed by logging it) — so tests must use
 * the same shape, not a bare leaf built by hand.
 */
class EsphomeSecretFindUsagesTest : BasePlatformTestCase() {

    private fun declarationElement(name: String): YAMLKeyValue {
        val offset = myFixture.file.text.indexOf("$name:")
        return PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(offset), YAMLKeyValue::class.java)!!
    }

    fun `test usage target is offered when the caret is on a secret declaration`() {
        myFixture.configureByText("secrets.yaml", "wifi_pass<caret>word: hunter2\n")
        val targets = EsphomeSecretUsageTargetProvider().getTargets(myFixture.editor, myFixture.file)
        assertNotNull("a usage target on the declaration", targets)
        assertEquals(1, targets!!.size)
    }

    fun `test no usage target when the caret is not on a secret declaration`() {
        myFixture.configureByText("device.yaml", "esphome:\n  na<caret>me: x\n")
        assertNull(EsphomeSecretUsageTargetProvider().getTargets(myFixture.editor, myFixture.file))
    }

    fun `test declaration is a named target under the caret`() {
        myFixture.configureByText("secrets.yaml", "wifi_pass<caret>word: hunter2\n")
        val target = TargetElementUtil.getInstance()
            .findTargetElement(myFixture.editor, TargetElementUtil.ELEMENT_NAME_ACCEPTED, myFixture.editor.caretModel.offset)
        assertNotNull(target)
        assertTrue(target!!.text.contains("wifi_password"))
    }

    fun `test find usages from a secret declaration finds the reference`() {
        myFixture.addFileToProject("secrets.yaml", "wifi_password: hunter2\n")
        myFixture.configureByText(
            "device.yaml",
            "wifi:\n  ssid: Home\n  password: !secret wifi_password\n",
        )
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("secrets.yaml"))
        val usages = myFixture.findUsages(declarationElement("wifi_password"))
        assertEquals(1, usages.size)
        assertEquals("device.yaml", usages.single().file!!.name)
    }

    fun `test find usages from a secret declared only in a merge-included file`() {
        myFixture.addFileToProject("secrets/location1.yaml", "project_name: my-project\n")
        myFixture.addFileToProject("secrets.yaml", "<<: !include secrets/location1.yaml\n")
        myFixture.configureByText(
            "device.yaml",
            "esphome:\n  project:\n    name: !secret project_name\n",
        )
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("secrets/location1.yaml"))
        val usages = myFixture.findUsages(declarationElement("project_name"))
        assertEquals(1, usages.size)
        assertEquals("device.yaml", usages.single().file!!.name)
    }

    fun `test find usages works end-to-end from the caret via platform target resolution`() {
        // Regression test for the real bug: earlier code recognized only a bare
        // key leaf as a secret declaration, built by hand in tests — but the
        // platform's own `TargetElementUtil` (what a real Cmd-click/Find Usages
        // invocation actually uses) resolves the caret to the *YAMLKeyValue*.
        // Passing a hand-built leaf into `findUsages` masked this: it worked in
        // every other test here but not in a real IDE. This test goes through
        // the same target-resolution path a real invocation does.
        myFixture.addFileToProject("secrets.yaml", "wifi_password: hunter2\n")
        myFixture.configureByText(
            "device.yaml",
            "wifi:\n  ssid: Home\n  password: !secret wifi_password\n",
        )
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("secrets.yaml"))
        val offset = myFixture.file.text.indexOf("wifi_password") + 2
        myFixture.editor.caretModel.moveToOffset(offset)

        val target = TargetElementUtil.getInstance().findTargetElement(
            myFixture.editor,
            TargetElementUtil.ELEMENT_NAME_ACCEPTED,
            offset,
        )
        assertNotNull("platform must resolve the caret to a target", target)

        val usages = myFixture.findUsages(target!!)
        assertEquals(1, usages.size)
        assertEquals("device.yaml", usages.single().file!!.name)
    }

    fun `test find usages finds every reference across multiple files`() {
        myFixture.addFileToProject("secrets.yaml", "api_key: abc123\n")
        myFixture.addFileToProject("device1.yaml", "api:\n  encryption:\n    key: !secret api_key\n")
        myFixture.addFileToProject("device2.yaml", "api:\n  encryption:\n    key: !secret api_key\n")
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("secrets.yaml"))
        val usages = myFixture.findUsages(declarationElement("api_key"))
        assertEquals(2, usages.size)
        assertEquals(setOf("device1.yaml", "device2.yaml"), usages.map { it.file!!.name }.toSet())
    }
}
