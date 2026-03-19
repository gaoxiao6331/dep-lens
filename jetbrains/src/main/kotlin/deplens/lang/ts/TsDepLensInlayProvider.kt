package deplens.lang.ts

import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.lang.javascript.psi.JSLiteralExpression
import deplens.utils.UiUtils
import deplens.utils.GithubRepoInfoService
import deplens.common.Result
import deplens.common.I18nKey
import deplens.utils.I18n
import deplens.utils.NpmPkgInfoService

class TsDepLensInlayProvider : InlayHintsProvider, DumbAware {

    companion object {
        private val LOG = logger<TsDepLensInlayProvider>()
    }

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector {
        return object : SharedBypassCollector {
            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                if (!TsImportResolver.isImport(element)) return

                val dep = TsImportResolver.getDepName(element) ?: return
                if (TsImportResolver.isLocalImport(dep)) return

                val pkg = TsImportResolver.getPkgName(dep)

                // 获取pkg信息
                val npmRes = NpmPkgInfoService.getPackageInfo(pkg)

                when (npmRes.result) {
                    Result.NONE ->  {
                        ApplicationManager.getApplication().executeOnPooledThread {
                            try {
                                NpmPkgInfoService.fetchPackageInfo(pkg) {
                                    val npmRes = NpmPkgInfoService.getPackageInfo(pkg)

                                    if(npmRes.result == Result.SUCCESS) {
                                        val url = (npmRes.data?.githubUrl ?: "")
                                        val repoKey = GithubRepoInfoService.getRepoKey(url)
                                        if(repoKey != null) {
                                            val repoRes = GithubRepoInfoService.getRepoInfo(repoKey.owner, repoKey.repo)
                                            if(repoRes.result == Result.NONE) {
                                                GithubRepoInfoService.fetchRepoInfo(repoKey.owner, repoKey.repo) {
                                                    UiUtils.refreshInlayHints(file)
                                                }
                                            }

                                        }
                                    }

                                }

                            } catch (e: Exception) {
                                LOG.warn("Failed to load repo info for \$repoKey", e)
                            }
                        }

                        return
                    }
                    Result.SUCCESS -> {}
                    else -> return // TODO: pending 状态下没有注册刷新，可能导致bug
                }

                val npmInfo = npmRes.data ?: return

                val url = npmInfo.githubUrl ?: return

                val repoKey = GithubRepoInfoService.getRepoKey(url) ?: return

                // 获取github信息
                val repoRes = GithubRepoInfoService.getRepoInfo(repoKey.owner, repoKey.repo)

                val offset = element.textRange.endOffset

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
    }

}
