package deplens.utils.inlay

import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiFile
import deplens.common.I18nKey
import deplens.common.Result
import deplens.utils.service.GithubRepoInfoService
import deplens.utils.I18n
import deplens.utils.service.NpmPkgInfoService
import deplens.utils.UiUtils

object NpmInlayUtils {

    private val LOG = logger<NpmInlayUtils>()

    fun addNpmDepInlay(file: PsiFile, sink: InlayTreeSink, pkg: String, offset: Int) {
        val npmRes = NpmPkgInfoService.getPackageInfo(pkg)

        when (npmRes.result) {
            Result.NONE -> {
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        NpmPkgInfoService.fetchPackageInfo(pkg) {
                            val npmRes = NpmPkgInfoService.getPackageInfo(pkg)

                            if (npmRes.result == Result.SUCCESS) {
                                val url = (npmRes.data?.githubUrl ?: "")
                                val repoKey = GithubRepoInfoService.getRepoKey(url)
                                if (repoKey != null) {
                                    val repoRes = GithubRepoInfoService.getRepoInfo(repoKey.owner, repoKey.repo)
                                    if (repoRes.result == Result.NONE) {
                                        GithubRepoInfoService.fetchRepoInfo(repoKey.owner, repoKey.repo) {
                                            UiUtils.refreshInlayHints(file)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        LOG.warn("Failed to load repo info for $pkg", e)
                    }
                }
                return
            }
            Result.SUCCESS -> {}
            else -> return
        }

        val npmInfo = npmRes.data ?: return
        val url = npmInfo.githubUrl ?: return
        val repoKey = GithubRepoInfoService.getRepoKey(url) ?: return

        val repoRes = GithubRepoInfoService.getRepoInfo(repoKey.owner, repoKey.repo)

        val displayText = when (repoRes.result) {
            Result.NONE -> {
                ApplicationManager.getApplication().executeOnPooledThread {
                    GithubRepoInfoService.fetchRepoInfo(repoKey.owner, repoKey.repo) {
                        UiUtils.refreshInlayHints(file)
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