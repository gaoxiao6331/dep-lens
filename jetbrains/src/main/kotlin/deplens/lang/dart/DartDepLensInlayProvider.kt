package deplens.lang.dart

import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import deplens.lang.BaseDepLensInlayProvider
import deplens.utils.inlay.PubInlayUtils
import deplens.utils.resolver.DartImportResolver

class DartDepLensInlayProvider : BaseDepLensInlayProvider() {

    override val usesWholeFileCollection: Boolean = true

    override fun isFileSupported(file: PsiFile): Boolean = file.name.endsWith(".dart")

    override fun collectElement(file: PsiFile, element: PsiElement, sink: InlayTreeSink) = Unit

    override fun collectFile(file: PsiFile, sink: InlayTreeSink) {
        val document = file.viewProvider.document ?: return

        for (lineNumber in 0 until document.lineCount) {
            val lineText = document.getLineText(lineNumber)
            val dep = DartImportResolver.getDepName(lineText) ?: continue
            if (DartImportResolver.isLocalImport(dep) || !DartImportResolver.isPackageImport(dep)) {
                continue
            }

            val pkg = DartImportResolver.getPkgName(dep)
            val offset = document.getLineEndOffset(lineNumber)
            PubInlayUtils.addPubDepInlay(file, sink, pkg, offset)
        }
    }

    private fun Document.getLineText(lineNumber: Int): String {
        val startOffset = getLineStartOffset(lineNumber)
        val endOffset = getLineEndOffset(lineNumber)
        return getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))
    }
}
