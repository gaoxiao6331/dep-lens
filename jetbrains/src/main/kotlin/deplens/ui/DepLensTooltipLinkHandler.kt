package deplens.ui

import com.intellij.codeInsight.highlighting.TooltipLinkHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import deplens.utils.ProgressUtils
import deplens.utils.UiUtils
import deplens.utils.service.GithubRepoInfoService
import deplens.utils.service.NpmPkgInfoService
import deplens.utils.service.PubPkgInfoService
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class DepLensTooltipLinkHandler : TooltipLinkHandler() {

    override fun handleLink(refSuffix: String, editor: Editor): Boolean {
        if (!refSuffix.startsWith("retry:")) {
            return false
        }

        // Retry link payload format: retry:{urlEncoded("github:owner/repo" | "npm:package" | "pub:package")}
        val encodedToken = refSuffix.removePrefix("retry:")
        val token = URLDecoder.decode(encodedToken, StandardCharsets.UTF_8)

        return when {
            token.startsWith("github:") -> {
                retryGithub(token.removePrefix("github:"), editor)
                true
            }
            token.startsWith("npm:") -> {
                retryNpm(token.removePrefix("npm:"), editor)
                true
            }
            token.startsWith("pub:") -> {
                retryPub(token.removePrefix("pub:"), editor)
                true
            }
            else -> false
        }
    }

    private fun retryGithub(repoKey: String, editor: Editor) {
        val parts = repoKey.split("/", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return

        val owner = parts[0]
        val repo = parts[1]

        // Refresh once before dispatch so users see loading state immediately after clicking retry.
        refreshInlays(editor)
        ProgressUtils.runBackground(
            editor.project ?: return,
            "DepLens: Retry GitHub $owner/$repo",
            // Keep task visible briefly; otherwise fast retries look like "no request happened".
            minVisibleMillis = 700
        ) {
            GithubRepoInfoService.retryRepoInfo(owner, repo) {
                refreshInlays(editor)
            }
        }
    }

    private fun retryNpm(packageName: String, editor: Editor) {
        if (packageName.isBlank()) return

        refreshInlays(editor)
        ProgressUtils.runBackground(
            editor.project ?: return,
            "DepLens: Retry npm $packageName",
            // Keep task visible briefly; otherwise fast retries look like "no request happened".
            minVisibleMillis = 700
        ) {
            NpmPkgInfoService.retryPackageInfo(packageName) {
                refreshInlays(editor)
            }
        }
    }

    private fun retryPub(packageName: String, editor: Editor) {
        if (packageName.isBlank()) return

        refreshInlays(editor)
        ProgressUtils.runBackground(
            editor.project ?: return,
            "DepLens: Retry pub $packageName",
            minVisibleMillis = 700
        ) {
            PubPkgInfoService.retryPackageInfo(packageName) {
                refreshInlays(editor)
            }
        }
    }

    private fun refreshInlays(editor: Editor) {
        val project = editor.project ?: return
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        UiUtils.refreshInlayHints(psiFile)
    }
}
