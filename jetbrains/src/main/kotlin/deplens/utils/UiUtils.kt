package deplens.utils

import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.openapi.vfs.VirtualFile

object UiUtils {

    fun addInlay(sink: InlayTreeSink, offset: Int, displayText: String) {
        sink.addPresentation(
            InlineInlayPosition(offset, relatedToPrevious = true),
            payloads = emptyList<InlayPayload>(),
            hasBackground = true
        ) {
            text(displayText)
        }
    }

    fun refreshInlayHints(file: PsiFile) {
        val vFile = file.virtualFile ?: return
        refreshInlayHints(file.project, vFile)
    }

    fun refreshInlayHints(project: Project, vFile: VirtualFile) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || !vFile.isValid) return@invokeLater

            PsiDocumentManager
                .getInstance(project)
                .reparseFiles(listOf(vFile), true)
        }, ModalityState.defaultModalityState())
    }
}
