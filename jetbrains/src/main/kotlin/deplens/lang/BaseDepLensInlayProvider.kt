package deplens.lang

import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

abstract class BaseDepLensInlayProvider : InlayHintsProvider, DumbAware {

    protected open fun isFileSupported(file: PsiFile): Boolean = true

    protected abstract fun collectElement(file: PsiFile, element: PsiElement, sink: InlayTreeSink)

    final override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (!isFileSupported(file)) {
            return null
        }
        return object : SharedBypassCollector {
            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                collectElement(file, element, sink)
            }
        }
    }
}
