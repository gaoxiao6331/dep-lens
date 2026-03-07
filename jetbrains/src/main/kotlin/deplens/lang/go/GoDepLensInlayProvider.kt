package deplens.lang.go

import com.goide.psi.GoImportSpec
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import deplens.utils.UiUtils
import deplens.utils.GithubRepoInfoService
import deplens.utils.RepoKey
import deplens.common.Result


class GoDepLensInlayProvider : InlayHintsProvider, DumbAware {

    companion object {

        private val LOG = logger<GoDepLensInlayProvider>()
    }

    private fun extractRepoInfo(element: GoImportSpec): RepoKey? {

        val path = element.path ?: return null

        if (!path.startsWith("github.com/")) return null

        val parts = path.split("/")
        if (parts.size < 3) return null

        return RepoKey(parts[1], parts[2])
    }

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector {

        return object : SharedBypassCollector {

            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {

                if (element !is GoImportSpec) return

                val repoKey = extractRepoInfo(element) ?: return

                val res = GithubRepoInfoService.getRepoInfo(repoKey.owner, repoKey.repo)

                val displayText = when (res.result) {
                    Result.NONE -> "加载中…"
                    Result.SUCCESS -> "⭐ ${res.data?.stars ?: 0} • 最后更新时间 ${res.data?.updatedDate ?: "N/A"}"
                    else -> "加载失败"
                }

                val literal = element.stringLiteral ?: return
                val offset = literal.textRange.endOffset

                UiUtils.addInlay(sink, offset, displayText)

                if (res.result == Result.NONE) {

                    ApplicationManager.getApplication().executeOnPooledThread {

                        try {

                            GithubRepoInfoService
                                .fetchRepoInfo(repoKey.owner, repoKey.repo)

                            UiUtils.refreshInlayHints(file)

                        } catch (e: Exception) {

                            LOG.warn("Failed to load repo info for $repoKey", e)

                        }
                    }
                }
            }
        }
    }
}