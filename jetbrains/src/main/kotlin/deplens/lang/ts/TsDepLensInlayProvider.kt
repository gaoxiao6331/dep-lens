package deplens.lang.ts

import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import deplens.utils.inlay.NpmInlayUtils
import deplens.utils.resolver.TsImportResolver

class TsDepLensInlayProvider : InlayHintsProvider, DumbAware {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector {
        return object : SharedBypassCollector {
            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                if (!TsImportResolver.isImport(element)) return

                val dep = TsImportResolver.getDepName(element) ?: return
                if (TsImportResolver.isLocalImport(dep)) return

                val pkg = TsImportResolver.getPkgName(dep)
                val offset = element.textRange.endOffset
                NpmInlayUtils.addNpmDepInlay(file, sink, pkg, offset)
            }
        }
    }

}
