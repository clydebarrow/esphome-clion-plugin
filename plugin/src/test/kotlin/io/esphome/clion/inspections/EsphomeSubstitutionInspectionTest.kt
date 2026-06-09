package io.esphome.clion.inspections

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The unresolved-substitution inspection: flags typos, stays quiet otherwise. */
class EsphomeSubstitutionInspectionTest : BasePlatformTestCase() {

    private fun descriptions(): List<String> =
        myFixture.doHighlighting().mapNotNull { it.description }

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(EsphomeUnresolvedSubstitutionInspection())
    }

    fun `test undefined substitution is flagged`() {
        myFixture.configureByText(
            "device.yaml",
            "substitutions:\n  device_name: x\nesphome:\n  name: \${devnam}\n",
        )
        assertTrue(descriptions().any { it == "Unresolved substitution 'devnam'" })
    }

    fun `test defined substitution is not flagged`() {
        myFixture.configureByText(
            "device.yaml",
            "substitutions:\n  device_name: x\nesphome:\n  name: \${device_name}\n",
        )
        assertFalse(descriptions().any { it.startsWith("Unresolved substitution") })
    }

    fun `test near-match quick-fix is offered`() {
        myFixture.configureByText(
            "device.yaml",
            "substitutions:\n  device_name: x\nesphome:\n  name: \${device_nam}\n",
        )
        val fix = myFixture.getAllQuickFixes().firstOrNull { it.text == "Change to 'device_name'" }
        assertNotNull(fix)
        myFixture.launchAction(fix!!)
        assertTrue(myFixture.file.text.contains("\${device_name}"))
    }

    fun `test no substitutions defined means no flagging`() {
        // Could come from command-line -s; don't flag.
        myFixture.configureByText("device.yaml", "esphome:\n  name: \${from_cli}\n")
        assertFalse(descriptions().any { it.startsWith("Unresolved substitution") })
    }

    fun `test include vars are treated as defined`() {
        myFixture.addFileToProject("p/widget.yaml", "esphome:\n  name: \${label}\n")
        val vf = myFixture.addFileToProject(
            "p/device.yaml",
            "substitutions:\n  x: 1\nesphome:\n  name: dev\npackages:\n  w: !include\n    file: widget.yaml\n    vars:\n      label: Hello\n",
        ).virtualFile
        myFixture.configureFromExistingVirtualFile(vf)
        // `label` is supplied via include vars, so it must not be flagged in widget.yaml
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("p/widget.yaml"))
        assertFalse(descriptions().any { it.contains("'label'") })
    }
}
