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

class TsDepLensInlayProvider : InlayHintsProvider, DumbAware {

    companion object {
        private val LOG = logger<TsDepLensInlayProvider>()
    }

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector {
        return object : SharedBypassCollector {
            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                if (!TsImportResolver.isImport(element)) return

                val projectPath = file.project.basePath ?: return
                val projectRoot = LocalFileSystem.getInstance().findFileByPath(projectPath) ?: return

                val importPath = TsImportResolver.getDependencyPath(element) ?: return
                if (TsImportResolver.isLocalImport(importPath)) return

                val pkgName = TsImportResolver.getModuleName("TODO")

                val pkgJsonFile = TsImportResolver.findPackageJsonFile(file.virtualFile.parent, pkgName, projectRoot) ?: return

                val repoKey = TsImportResolver.resolveGithubRepoFromPackageJson(pkgJsonFile) ?: return

                val offset = element.textRange.endOffset

                val res = GithubRepoInfoService.getRepoInfo(repoKey.owner, repoKey.repo)

                val displayText = when (res.result) {
                    Result.NONE -> I18n.message(I18nKey.loading)
                    Result.SUCCESS -> "⭐ ${res.data?.stars ?: 0} • ${I18n.message(I18nKey.lastUpdated)} ${res.data?.updatedDate ?: "N/A"}"
                    else -> I18n.message(I18nKey.failedToLoad)
                }

                UiUtils.addInlay(sink, offset, displayText)

                if (res.result == Result.NONE) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            GithubRepoInfoService.fetchRepoInfo(repoKey.owner, repoKey.repo)
                            UiUtils.refreshInlayHints(file)
                        } catch (e: Exception) {
                            LOG.warn("Failed to load repo info for \$repoKey", e)
                        }
                    }
                }
            }
        }
    }

}
