package deplens.utils.inlay

import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiFile
import deplens.common.I18nKey
import deplens.common.Result
import deplens.utils.ProgressUtils
import deplens.utils.service.GithubRepoInfoService
import deplens.utils.I18n
import deplens.utils.service.RepoKey
import deplens.utils.UiUtils

object GithubInlayUtils {

    private val LOG = logger<GithubInlayUtils>()

    fun addRepoInlay(file: PsiFile, sink: InlayTreeSink, repoKey: RepoKey, offset: Int) {
        val displayText = getRepoDisplayText(file, repoKey)
        val githubUrl = "https://github.com/${repoKey.owner}/${repoKey.repo}"
        UiUtils.addInlay(sink, offset, displayText, githubUrl = githubUrl)
    }

    fun getRepoDisplayText(file: PsiFile, repoKey: RepoKey): String {
        val project = file.project
        val vFile = file.virtualFile
        val repoRes = GithubRepoInfoService.getRepoInfo(repoKey.owner, repoKey.repo)

        return when (repoRes.result) {
            Result.NONE -> {
                ProgressUtils.runBackground(
                    file.project,
                    "DepLens: Fetch GitHub ${repoKey.owner}/${repoKey.repo}"
                ) {
                    try {
                        GithubRepoInfoService.fetchRepoInfo(repoKey.owner, repoKey.repo) {
                            if (vFile != null) {
                                UiUtils.refreshInlayHints(project, vFile)
                            }
                        }
                    } catch (e: Exception) {
                        LOG.warn("Failed to load repo info for $repoKey", e)
                    }
                }
                I18n.message(I18nKey.loadingGithub)
            }
            Result.PENDING -> I18n.message(I18nKey.loadingGithub)
            Result.SUCCESS -> "⭐ ${repoRes.data?.stars ?: 0} • ${I18n.message(I18nKey.lastUpdated)} ${repoRes.data?.updatedDate ?: "N/A"}"
            else -> I18n.message(I18nKey.failedGithub)
        }
    }
}
