package deplens.utils.inlay

import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiFile
import deplens.common.I18nKey
import deplens.common.Result
import deplens.utils.service.GithubRepoInfoService
import deplens.utils.I18n
import deplens.utils.ProgressUtils
import deplens.utils.service.NpmPkgInfoService
import deplens.utils.UiUtils

object NpmInlayUtils {

    private val LOG = logger<NpmInlayUtils>()

    fun addNpmDepInlay(file: PsiFile, sink: InlayTreeSink, pkg: String, offset: Int) {
        val npmRes = NpmPkgInfoService.getPackageInfo(pkg)

        when (npmRes.result) {
            // TOOD 处理pending
            Result.NONE -> {
                ProgressUtils.runBackground(file.project, "DepLens: Fetch npm $pkg") {
                    try {
                        NpmPkgInfoService.fetchPackageInfo(pkg) {

                            // 请求完npm元数据
                            val npmRes = NpmPkgInfoService.getPackageInfo(pkg)

                            if (npmRes.result == Result.SUCCESS) {
                                val url = (npmRes.data?.githubUrl ?: "")
                                val repoKey = GithubRepoInfoService.getRepoKey(url)
                                if (repoKey != null) {
                                    val repoRes = GithubRepoInfoService.getRepoInfo(repoKey.owner, repoKey.repo)
                                    if (repoRes.result == Result.NONE) {
                                        ProgressUtils.runBackground(
                                            file.project,
                                            "DepLens: Fetch GitHub ${repoKey.owner}/${repoKey.repo}"
                                        ) {
                                            GithubRepoInfoService.fetchRepoInfo(repoKey.owner, repoKey.repo) {

                                                UiUtils.refreshInlayHints(file)
                                            }
                                        }
                                    }
                                } else {
                                    LOG.warn("Failed to get repo key for $pkg")
                                }
                            } else {
                                // 没有拿到元数据，插入错误
                            }
                        }
                    } catch (e: Exception) {
                        LOG.warn("Failed to load repo info for $pkg", e)
                    }
                }
                UiUtils.addInlay(sink, offset, I18n.message(I18nKey.loadingNpmMeta))
                return
            }
            Result.SUCCESS -> {}
            else -> {
                UiUtils.addInlay(sink, offset, I18n.message(I18nKey.failedNpmMeta))
                return
            }
        }

        val npmInfo = npmRes.data ?: run {
            UiUtils.addInlay(sink, offset, I18n.message(I18nKey.failedNpmMeta))
            return
        }
        val url = npmInfo.githubUrl
        if (url.isNullOrBlank()) {
            UiUtils.addInlay(sink, offset, I18n.message(I18nKey.noGithubUrl))
            return
        }
        val repoKey = GithubRepoInfoService.getRepoKey(url)
        if (repoKey == null) {
            UiUtils.addInlay(sink, offset, I18n.message(I18nKey.invalidGithubUrl))
            return
        }

        val repoRes = GithubRepoInfoService.getRepoInfo(repoKey.owner, repoKey.repo)

        val displayText = when (repoRes.result) {
            Result.NONE -> {
                ProgressUtils.runBackground(
                    file.project,
                    "DepLens: Fetch GitHub ${repoKey.owner}/${repoKey.repo}"
                ) {
                    GithubRepoInfoService.fetchRepoInfo(repoKey.owner, repoKey.repo) {
                        UiUtils.refreshInlayHints(file)
                    }
                }
                I18n.message(I18nKey.loadingGithub)
            }
            Result.SUCCESS -> "⭐ ${repoRes.data?.stars ?: 0} • ${I18n.message(I18nKey.lastUpdated)} ${repoRes.data?.updatedDate ?: "N/A"}"
            else -> I18n.message(I18nKey.failedGithub)
        }

        UiUtils.addInlay(sink, offset, displayText)
    }
}
