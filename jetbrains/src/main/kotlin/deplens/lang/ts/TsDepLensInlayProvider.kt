package deplens.lang.ts

import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import deplens.lang.BaseDepLensInlayProvider
import deplens.utils.inlay.NpmInlayUtils
import deplens.utils.resolver.TsImportResolver

class TsDepLensInlayProvider : BaseDepLensInlayProvider() {

    override fun collectElement(file: PsiFile, element: PsiElement, sink: InlayTreeSink) {
        if (!TsImportResolver.isImport(element)) return

        val dep = TsImportResolver.getDepName(element) ?: return
        if (TsImportResolver.isLocalImport(dep)) return

        val pkg = TsImportResolver.getPkgName(dep)
        val offset = element.textRange.endOffset
        NpmInlayUtils.addNpmDepInlay(file, sink, pkg, offset)
    }

}
