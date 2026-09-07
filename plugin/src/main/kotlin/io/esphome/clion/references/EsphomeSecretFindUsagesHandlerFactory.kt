package io.esphome.clion.references

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.psi.PsiElement

/**
 * Enables Find Usages on a secrets-file `key:` declaration. The handler itself
 * is the default one (it searches references to the element via
 * `ReferencesSearch`, which [EsphomeSecretReferenceSearcher] answers); this
 * just makes the action available on secret declarations without disturbing
 * the bundled YAML plugin's own find-usages.
 */
class EsphomeSecretFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement): Boolean = EsphomeSecret.declaredSecretName(element) != null

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler =
        object : FindUsagesHandler(element) {}
}
