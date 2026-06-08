package io.esphome.clion.references

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Enables Find Usages on an `id:` declaration. The handler itself is the default
 * one (it searches references to the element via `ReferencesSearch`, which
 * [EsphomeIdReferenceSearcher] answers); this just makes the action available on
 * id declarations without disturbing the bundled YAML plugin's own find-usages.
 */
class EsphomeIdFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement): Boolean =
        element is YAMLScalar && EsphomeIdReferences.declaredIdName(element) != null

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler =
        object : FindUsagesHandler(element) {}
}
