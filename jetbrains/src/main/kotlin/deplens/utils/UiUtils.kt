package deplens.utils

import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

object UiUtils {

    fun addInlay(sink: InlayTreeSink, offset: Int, displayText: String) {
        sink.addPresentation(
            InlineInlayPosition(offset, relatedToPrevious = true),
            tooltip = displayText,
            hasBackground = true
        ) {
            text(displayText)
        }
    }

    fun refreshInlayHints(file: PsiFile) {
        val project = file.project
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || !file.isValid) return@invokeLater

            val vFile = file.virtualFile ?: return@invokeLater

            PsiDocumentManager
                .getInstance(project)
                .reparseFiles(listOf(vFile), true)

        }, ModalityState.NON_MODAL)
    }
}
