package io.esphome.clion.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parentOfType
import io.esphome.clion.psi.EsphomeResolution
import io.esphome.clion.psi.EsphomeTarget
import io.esphome.clion.psi.EsphomeYaml
import io.esphome.clion.services.EsphomeCatalogService
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Quick-doc (hover / Ctrl-Q) for ESPHome YAML keys, sourced from the catalog's
 * field descriptions and docs links.
 */
class EsphomeDocumentationProvider : AbstractDocumentationProvider() {

    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int,
    ): PsiElement? {
        if (file !is YAMLFile || contextElement == null || !EsphomeYaml.isEsphomeFile(file)) return null
        // Anchor docs on the enclosing key/value; resolution decides if it maps.
        return contextElement.parentOfType<YAMLKeyValue>(withSelf = true)
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = resolve(element) ?: return null
        return EsphomeDocRenderer.render(target)
    }

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        return when (val target = resolve(element)) {
            is EsphomeTarget.Field -> "${target.entry.key} : ${target.entry.type.name.lowercase()}"
            is EsphomeTarget.Component -> target.index.name.ifEmpty { target.index.id }
            null -> null
        }
    }

    private fun resolve(element: PsiElement?): EsphomeTarget? {
        val keyValue = element as? YAMLKeyValue ?: return null
        val file = keyValue.containingFile as? YAMLFile ?: return null
        if (!EsphomeYaml.isEsphomeFile(file)) return null
        val repo = EsphomeCatalogService.getInstance().repository
        return EsphomeResolution.resolveKey(repo, keyValue)
    }
}
