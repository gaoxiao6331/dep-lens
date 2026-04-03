package deplens.utils

import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.openapi.vfs.VirtualFile

object UiUtils {

    const val HOVER_TEXT_PAYLOAD_NAME: String = "deplens.hover.text"
    const val GITHUB_URL_PAYLOAD_NAME: String = "deplens.github.url"
    const val RETRY_TOKEN_PAYLOAD_NAME: String = "deplens.retry.token"

    fun addInlay(
        sink: InlayTreeSink,
        offset: Int,
        displayText: String,
        hoverText: String = displayText,
        githubUrl: String? = null,
        retryToken: String? = null
    ) {
        val payloads = buildList {
            add(InlayPayload(HOVER_TEXT_PAYLOAD_NAME, StringInlayActionPayload(hoverText)))
            if (!githubUrl.isNullOrBlank()) {
                add(InlayPayload(GITHUB_URL_PAYLOAD_NAME, StringInlayActionPayload(githubUrl)))
            }
            if (!retryToken.isNullOrBlank()) {
                add(InlayPayload(RETRY_TOKEN_PAYLOAD_NAME, StringInlayActionPayload(retryToken)))
            }
        }

        sink.addPresentation(
            InlineInlayPosition(offset, relatedToPrevious = true),
            payloads,
            null,
            HintFormat.default
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
