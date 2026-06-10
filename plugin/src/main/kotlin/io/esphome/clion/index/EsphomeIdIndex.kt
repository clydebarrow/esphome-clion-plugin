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
import com.intellij.util.io.IOUtil
import com.intellij.util.io.KeyDescriptor
import io.esphome.clion.psi.EsphomeYaml
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import java.io.DataInput
import java.io.DataOutput

/**
 * One `id:` declaration's location and owning component, captured at index time.
 * [domain] is the top-level component key (e.g. `sensor`, `i2c`); [platform] is
 * the `platform:` of the enclosing list item (`dht`), or null for a top-level
 * component. Together they identify the component class so Phase 3 can type-match
 * a reference against the catalog's `provides` — kept out of the index so a
 * catalog bump needs no reindex.
 */
data class IdDeclaration(val offset: Int, val domain: String, val platform: String?)

/**
 * Indexes every `id:` declaration by its name, so id references can be resolved
 * across the include graph (Phase 3). The value records where the declaration is
 * and which component owns it. Phase 2 of
 * `docs/roadmap-includes-and-navigation.md`.
 */
class EsphomeIdIndex : FileBasedIndexExtension<String, IdDeclaration>() {

    override fun getName(): ID<String, IdDeclaration> = NAME
    override fun getVersion(): Int = 2
    override fun dependsOnFileContent(): Boolean = true
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getValueExternalizer(): DataExternalizer<IdDeclaration> = Externalizer
    override fun getInputFilter(): FileBasedIndex.InputFilter =
        DefaultFileTypeSpecificInputFilter(YAMLFileType.YML)

    override fun getIndexer(): DataIndexer<String, IdDeclaration, FileContent> = DataIndexer { inputData ->
        val yaml = inputData.psiFile as? YAMLFile ?: return@DataIndexer emptyMap()
        val result = HashMap<String, IdDeclaration>()
        for (keyValue in PsiTreeUtil.findChildrenOfType(yaml, YAMLKeyValue::class.java)) {
            if (keyValue.keyText != ID_KEY) continue
            val value = keyValue.value as? YAMLScalar ?: continue
            // `id: !extend X` / `id: !remove X` reference an existing declaration
            // in a package — not a new one — so they aren't indexed as declarations.
            if (EsphomeYaml.isMergeTaggedId(value)) continue
            // Templated names (`id: ${prefix}_relay`) are indexed raw and expanded
            // at query time (the index is per-file, so it can't resolve them here).
            val name = value.textValue
            if (name.isEmpty()) continue
            val parentMapping = keyValue.parent as? YAMLMapping ?: continue
            val domain = EsphomeYaml.pathOfMapping(parentMapping).firstOrNull() ?: continue
            val platform = EsphomeYaml.platformOf(keyValue)
            result[name] = IdDeclaration(value.textRange.startOffset, domain, platform)
        }
        result
    }

    private object Externalizer : DataExternalizer<IdDeclaration> {
        override fun save(out: DataOutput, value: IdDeclaration) {
            out.writeInt(value.offset)
            IOUtil.writeUTF(out, value.domain)
            val platform = value.platform
            out.writeBoolean(platform != null)
            if (platform != null) IOUtil.writeUTF(out, platform)
        }

        override fun read(input: DataInput): IdDeclaration {
            val offset = input.readInt()
            val domain = IOUtil.readUTF(input)
            val platform = if (input.readBoolean()) IOUtil.readUTF(input) else null
            return IdDeclaration(offset, domain, platform)
        }
    }

    companion object {
        private const val ID_KEY = "id"
        val NAME: ID<String, IdDeclaration> = ID.create("io.esphome.clion.index.ids")
    }
}
