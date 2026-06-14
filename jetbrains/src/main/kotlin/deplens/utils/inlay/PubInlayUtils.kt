package deplens.utils.inlay

import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiFile
import deplens.common.I18nKey
import deplens.common.Result
import deplens.utils.I18n
import deplens.utils.ProgressUtils
import deplens.utils.UiUtils
import deplens.utils.service.GithubRepoInfoService
import deplens.utils.service.PubPkgInfoService

object PubInlayUtils {

    private val LOG = logger<PubInlayUtils>()

    fun addPubDepInlay(file: PsiFile, sink: InlayTreeSink, pkg: String, offset: Int) {
        val project = file.project
        val vFile = file.virtualFile
        val pubRes = PubPkgInfoService.getPackageInfo(pkg)

        when (pubRes.result) {
            Result.NONE -> {
                if (PubPkgInfoService.hasFailure(pkg) && !PubPkgInfoService.isRequestRunning(pkg)) {
                    UiUtils.addInlay(
                        sink,
                        offset,
                        I18n.message("failedPubMeta"),
                        retryToken = "pub:$pkg"
                    )
                    return
                }
                ProgressUtils.runBackground(file.project, "DepLens: Fetch pub $pkg") {
                    try {
                        PubPkgInfoService.fetchPackageInfo(pkg) {
                            var dispatchedGithubFetch = false
                            val refreshedPubRes = PubPkgInfoService.getPackageInfo(pkg)

                            if (refreshedPubRes.result == Result.SUCCESS) {
                                val url = refreshedPubRes.data?.githubUrl.orEmpty()
                                val repoKey = GithubRepoInfoService.getRepoKey(url)
                                if (repoKey != null) {
                                    val repoRes = GithubRepoInfoService.getRepoInfo(repoKey.owner, repoKey.repo)
                                    if (repoRes.result == Result.NONE) {
                                        dispatchedGithubFetch = true
                                        ProgressUtils.runBackground(
                                            file.project,
                                            "DepLens: Fetch GitHub ${repoKey.owner}/${repoKey.repo}"
                                        ) {
                                            GithubRepoInfoService.fetchRepoInfo(repoKey.owner, repoKey.repo) {
                                                if (vFile != null) {
                                                    UiUtils.refreshInlayHints(project, vFile)
                                                }
                                            }
                                        }
                                    }
                                } else if (url.isNotBlank()) {
                                    LOG.warn("Failed to get repo key for $pkg")
                                }
                            }

                            if (!dispatchedGithubFetch && vFile != null) {
                                UiUtils.refreshInlayHints(project, vFile)
                            }
                        }
                    } catch (e: Exception) {
                        LOG.warn("Failed to load pub info for $pkg", e)
                    }
                }
                UiUtils.addInlay(
                    sink,
                    offset,
                    I18n.message("loadingPubMeta"),
                    retryToken = "pub:$pkg"
                )
                return
            }

            Result.SUCCESS -> {}

            Result.PENDING -> {
                UiUtils.addInlay(
                    sink,
                    offset,
                    I18n.message("loadingPubMeta"),
                    retryToken = "pub:$pkg"
                )
                return
            }

            else -> {
                UiUtils.addInlay(
                    sink,
                    offset,
                    I18n.message("failedPubMeta"),
                    retryToken = "pub:$pkg"
                )
                return
            }
        }

        val pubInfo = pubRes.data ?: run {
            UiUtils.addInlay(
                sink,
                offset,
                I18n.message("failedPubMeta"),
                retryToken = "pub:$pkg"
            )
            return
        }

        val url = pubInfo.githubUrl
        if (url.isNullOrBlank()) {
            UiUtils.addInlay(
                sink,
                offset,
                I18n.message(I18nKey.noGithubUrl),
                retryToken = "pub:$pkg"
            )
            return
        }

        val repoKey = GithubRepoInfoService.getRepoKey(url)
        if (repoKey == null) {
            UiUtils.addInlay(
                sink,
                offset,
                I18n.message(I18nKey.invalidGithubUrl),
                retryToken = "pub:$pkg"
            )
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
                        if (vFile != null) {
                            UiUtils.refreshInlayHints(project, vFile)
                        }
                    }
                }
                I18n.message(I18nKey.loadingGithub)
            }

            Result.PENDING -> I18n.message(I18nKey.loadingGithub)
            Result.SUCCESS -> "⭐ ${repoRes.data?.stars ?: 0} • ${I18n.message(I18nKey.lastUpdated)} ${repoRes.data?.updatedDate ?: "N/A"}"
            else -> I18n.message(I18nKey.failedGithub)
        }

        val githubUrl = "https://github.com/${repoKey.owner}/${repoKey.repo}"
        UiUtils.addInlay(
            sink,
            offset,
            displayText,
            githubUrl = githubUrl,
            retryToken = "github:${repoKey.owner}/${repoKey.repo}"
        )
    }
}
