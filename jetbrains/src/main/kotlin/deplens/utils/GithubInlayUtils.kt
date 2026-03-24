package deplens.utils

import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiFile
import deplens.common.I18nKey
import deplens.common.Result

object GithubInlayUtils {

    private val LOG = logger<GithubInlayUtils>()

    fun addRepoInlay(file: PsiFile, sink: InlayTreeSink, repoKey: RepoKey, offset: Int) {
        val repoRes = GithubRepoInfoService.getRepoInfo(repoKey.owner, repoKey.repo)

        val displayText = when (repoRes.result) {
            Result.NONE -> {
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        GithubRepoInfoService.fetchRepoInfo(repoKey.owner, repoKey.repo) {
                            UiUtils.refreshInlayHints(file)
                        }
                    } catch (e: Exception) {
                        LOG.warn("Failed to load repo info for $repoKey", e)
                    }
                }
                I18n.message(I18nKey.loading)
            }
            Result.SUCCESS -> "⭐ ${repoRes.data?.stars ?: 0} • ${I18n.message(I18nKey.lastUpdated)} ${repoRes.data?.updatedDate ?: "N/A"}"
            else -> I18n.message(I18nKey.failedToLoad)
        }

        UiUtils.addInlay(sink, offset, displayText)
    }
}
