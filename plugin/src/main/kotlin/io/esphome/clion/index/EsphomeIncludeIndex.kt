package io.esphome.clion.index

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import io.esphome.clion.references.EsphomeInclude
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Indexes the `!include` *targets* of each YAML file, keyed by the target's
 * **basename** (e.g. `wifi.yaml`) with the raw relative path as the value.
 *
 * Keying on the basename makes the index reverse-queryable:
 * `getContainingFiles(NAME, "wifi.yaml")` yields every file that includes
 * *something* named `wifi.yaml`. Because relative paths cannot be resolved
 * against the filesystem during indexing (an indexer must be a pure function of
 * one file's content), the caller re-resolves a candidate's edges precisely —
 * see [io.esphome.clion.services.EsphomeIncludeGraph].
 *
 * Phase 2 of `docs/roadmap-includes-and-navigation.md`.
 */
class EsphomeIncludeIndex : FileBasedIndexExtension<String, String>() {

    override fun getName(): ID<String, String> = NAME
    override fun getVersion(): Int = 1
    override fun dependsOnFileContent(): Boolean = true
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getValueExternalizer(): DataExternalizer<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getInputFilter(): FileBasedIndex.InputFilter =
        DefaultFileTypeSpecificInputFilter(YAMLFileType.YML)

    override fun getIndexer(): DataIndexer<String, String, FileContent> = DataIndexer { inputData ->
        val yaml = inputData.psiFile as? YAMLFile ?: return@DataIndexer emptyMap()
        val result = HashMap<String, String>()
        for (scalar in PsiTreeUtil.findChildrenOfType(yaml, YAMLScalar::class.java)) {
            val path = EsphomeInclude.includePathOf(scalar) ?: continue
            if (path.contains("\${") || path.contains("://")) continue
            val basename = path.substringAfterLast('/')
            if (basename.isEmpty()) continue
            result[basename] = path
        }
        result
    }

    companion object {
        val NAME: ID<String, String> = ID.create("io.esphome.clion.index.includeTargets")
    }
}
