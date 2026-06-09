package io.esphome.clion.completion

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Completion of substitution names inside a `${…}` token. */
class EsphomeSubstitutionCompletionTest : BasePlatformTestCase() {

    private fun complete(text: String): List<String> {
        myFixture.configureByText("device.yaml", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings ?: emptyList()
    }

    fun `test substitution names are offered inside braces`() {
        val items = complete(
            "substitutions:\n  device_name: x\n  board_kind: y\nesphome:\n  name: \${<caret>}\n",
        )
        assertContainsElements(items, "device_name", "board_kind")
    }

    fun `test partial substitution name filters by prefix`() {
        // Two names share the prefix (so the lookup stays open rather than
        // auto-completing a single match); the non-matching one is excluded.
        val items = complete(
            "substitutions:\n  device_name: x\n  device_type: y\n  board_kind: z\nesphome:\n  name: \${dev<caret>}\n",
        )
        assertContainsElements(items, "device_name", "device_type")
        assertDoesntContain(items, "board_kind")
    }
}
