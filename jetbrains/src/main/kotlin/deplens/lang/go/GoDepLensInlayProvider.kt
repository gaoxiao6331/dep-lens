package deplens.lang.go

import com.goide.psi.GoImportSpec
import com.goide.vgo.mod.psi.VgoModuleSpec
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import deplens.lang.BaseDepLensInlayProvider
import deplens.utils.UiUtils
import deplens.utils.service.GithubRepoInfoService
import deplens.utils.service.RepoKey
import deplens.common.Result
import deplens.common.I18nKey
import deplens.utils.I18n
import deplens.utils.ProgressUtils

/**
 * Go 依赖分析 Inlay 提示提供者
 * 实现逻辑与 Rust 库保持一致
 */
class GoDepLensInlayProvider : BaseDepLensInlayProvider() {

    companion object {
        private val LOG = logger<GoDepLensInlayProvider>()
        
        // 与 Rust 库一致的正则表达式，用于匹配 GitHub 路径
        private val GITHUB_PATH_REGEX = Regex("github\\.com[:/]+([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)")
        
        // 匹配 go import 语句的完整路径
        private val GITHUB_IMPORT_REGEX = Regex("\"github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:/[^\"]*)?\"")
    }

    /**
     * 判断是否为间接依赖，与 Rust 实现保持一致
     */
    private fun isIndirectDependency(element: VgoModuleSpec): Boolean {
        val file = element.containingFile ?: return false
        val doc = file.viewProvider.document ?: return false

        val lineNum = doc.getLineNumber(element.textOffset)
        val lineStart = doc.getLineStartOffset(lineNum)
        val lineEnd = doc.getLineEndOffset(lineNum)

        val lineText = doc.getText(TextRange(lineStart, lineEnd))

        return lineText.contains("// indirect")
    }

    /**
     * 从 Go 源码或 go.mod 中提取仓库信息
     * 保持与 Rust 库的 parse_go_dependencies 相同的逻辑
     */
    private fun extractRepoInfo(element: PsiElement): RepoKey? {
        return when (element) {
            is GoImportSpec -> {
                extractFromImportSpec(element)
            }
            is VgoModuleSpec -> {
                if (isIndirectDependency(element)) return null
                extractFromModuleSpec(element)
            }
            else -> null
        }
    }

    /**
     * 从 GoImportSpec 中提取信息
     */
    private fun extractFromImportSpec(importSpec: GoImportSpec): RepoKey? {
        val pathText = importSpec.path ?: return null
        val text = "\"${pathText.text}\""
        
        val match = GITHUB_IMPORT_REGEX.find(text) ?: return null
        
        val owner = match.groupValues.getOrNull(1) ?: return null
        val repo = match.groupValues.getOrNull(2) ?: return null
        
        if (owner.isBlank() || repo.isBlank()) return null
        
        return RepoKey(owner, repo)
    }

    /**
     * 从 VgoModuleSpec 中提取信息
     */
    private fun extractFromModuleSpec(moduleSpec: VgoModuleSpec): RepoKey? {
        val identifierText = moduleSpec.identifier?.text ?: return null
        
        val match = GITHUB_PATH_REGEX.find(identifierText) ?: return null
        
        val owner = match.groupValues.getOrNull(1) ?: return null
        val repo = match.groupValues.getOrNull(2) ?: return null
        
        if (owner.isBlank() || repo.isBlank()) return null
        
        return RepoKey(owner, repo)
    }

    /**
     * 计算 Inlay 应该放置的位置
     * 保持与 Rust 库的字符计数一致
     */
    private fun calculateInlayOffset(element: PsiElement): Int? {
        return when (element) {
            is GoImportSpec -> {
                element.stringLiteral?.textRange?.endOffset
            }
            is VgoModuleSpec -> {
                element.textRange.endOffset
            }
            else -> null
        }
    }

    override fun collectElement(file: PsiFile, element: PsiElement, sink: InlayTreeSink) {
        val repoKey = extractRepoInfo(element) ?: return
        val offset = calculateInlayOffset(element) ?: return

        val res = GithubRepoInfoService.getRepoInfo(repoKey.owner, repoKey.repo)

        val displayText = when (res.result) {
            Result.NONE -> I18n.message(I18nKey.loadingGithub)
            Result.PENDING -> I18n.message(I18nKey.loadingGithub)
            Result.SUCCESS -> "⭐ ${res.data?.stars ?: 0} • ${I18n.message(I18nKey.lastUpdated)} ${res.data?.updatedDate ?: "N/A"}"
            Result.FAILURE -> I18n.message(I18nKey.failedGithub)
        }

        val githubUrl = "https://github.com/${repoKey.owner}/${repoKey.repo}"
        UiUtils.addInlay(
            sink,
            offset,
            displayText,
            githubUrl = githubUrl,
            retryToken = "github:${repoKey.owner}/${repoKey.repo}"
        )

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
