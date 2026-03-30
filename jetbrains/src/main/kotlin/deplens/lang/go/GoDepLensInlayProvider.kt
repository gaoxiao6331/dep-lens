package deplens.lang.go

import com.goide.psi.GoImportSpec
import com.goide.vgo.mod.psi.VgoModuleSpec
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import deplens.lang.BaseDepLensInlayProvider
import deplens.utils.UiUtils
import deplens.utils.service.GithubRepoInfoService
import deplens.utils.service.RepoKey
import deplens.common.Result
import deplens.common.I18nKey
import deplens.utils.I18n
import deplens.utils.ProgressUtils


class GoDepLensInlayProvider : BaseDepLensInlayProvider() {

    companion object {

        private val LOG = logger<GoDepLensInlayProvider>()
    }

    private fun isIndirectDependency(element: VgoModuleSpec): Boolean {
        val file = element.containingFile ?: return false
        val doc = file.viewProvider.document ?: return false

        // 找模块元素所在行
        val lineNum = doc.getLineNumber(element.textOffset)
        val lineStart = doc.getLineStartOffset(lineNum)
        val lineEnd = doc.getLineEndOffset(lineNum)

        // 获取整行文本
        val lineText = doc.getText(TextRange(lineStart, lineEnd))

        // 判断是否包含 // indirect
        return lineText.contains("// indirect")
    }

    private fun extractRepoInfo(element: PsiElement): RepoKey? {

        val path = when (element) {
            is GoImportSpec -> element.path
            is VgoModuleSpec -> {
                if (isIndirectDependency(element)) return null
                element.identifier?.text
            }
            else -> return null
        } ?: return null

      return GithubRepoInfoService.getRepoKey(path)
    }

    override fun collectElement(file: PsiFile, element: PsiElement, sink: InlayTreeSink) {

        val repoKey = extractRepoInfo(element) ?: return

        val offset = when (element) {
            is GoImportSpec -> element.stringLiteral?.textRange?.endOffset
            is VgoModuleSpec -> element.textRange.endOffset
            else -> null
        } ?: return

        val res = GithubRepoInfoService.getRepoInfo(repoKey.owner, repoKey.repo)

        val displayText = when (res.result) {
            Result.NONE -> I18n.message(I18nKey.loading)
            Result.SUCCESS -> "⭐ ${res.data?.stars ?: 0} • ${I18n.message(I18nKey.lastUpdated)} ${res.data?.updatedDate ?: "N/A"}"
            else -> I18n.message(I18nKey.failedToLoad) // TODO: pending 时有问题
        }

        UiUtils.addInlay(sink, offset, displayText)

        if (res.result == Result.NONE) {

            ProgressUtils.runBackground(
                file.project,
                "DepLens: Fetch GitHub ${repoKey.owner}/${repoKey.repo}"
            ) {
                try {
                    GithubRepoInfoService
                        .fetchRepoInfo(repoKey.owner, repoKey.repo) {
                            UiUtils.refreshInlayHints(file)
                        }
                } catch (e: Exception) {
                    LOG.warn("Failed to load repo info for $repoKey", e)
                }
            }
        }
    }
}
