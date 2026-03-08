package deplens.lang.go

import com.goide.psi.GoImportSpec
import com.goide.vgo.mod.psi.VgoModuleSpec
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import deplens.utils.UiUtils
import deplens.utils.GithubRepoInfoService
import deplens.utils.RepoKey
import deplens.common.Result
import deplens.common.I18nKey
import deplens.utils.I18n


class GoDepLensInlayProvider : InlayHintsProvider, DumbAware {

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

        if (!path.startsWith("github.com/")) return null

        val parts = path.split("/")
        if (parts.size < 3) return null

        return RepoKey(parts[1], parts[2])
    }

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector {

        return object : SharedBypassCollector {

            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {

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
                    else -> I18n.message(I18nKey.failedToLoad)
                }

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