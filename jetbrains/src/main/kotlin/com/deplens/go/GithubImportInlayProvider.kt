package com.deplens.go

import com.goide.psi.GoImportSpec
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.hints.declarative.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.util.concurrent.ConcurrentHashMap
import com.intellij.psi.PsiDocumentManager

/**
 * 使用 2025 新版 Declarative Inlay Hints API
 */
class GithubImportInlayProvider : InlayHintsProvider, DumbAware {

    companion object {
        private val LOG = logger<GithubImportInlayProvider>()
        private val GITHUB_IMPORT_REGEX = Regex("\"github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)\"")
        
        // 全局缓存：Key -> GithubRepoInfo
        // 注意：这里使用全局缓存，跨项目共享
        private val repoInfoCache = ConcurrentHashMap<String, GithubRepoInfo>()
        
        // 记录正在加载的 key，避免重复请求
        private val loadingKeys = ConcurrentHashMap.newKeySet<String>()
    }

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        return object : SharedBypassCollector {
            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                // 仅处理 GoImportSpec 元素
                if (element !is GoImportSpec) return

                val match = GITHUB_IMPORT_REGEX.find(element.text) ?: return
                val owner = match.groupValues[1]
                val repo = match.groupValues[2]
                val repoKey = "$owner/$repo"

                // 获取当前缓存数据
                val info = repoInfoCache[repoKey]

                val displayText = if (info != null) {
                    if (info.stars == -1) "加载失败" else "⭐ ${info.stars} • ${info.updatedDate}"
                } else {
                    "加载中…"
                }

                // 添加 Declarative Hint
                // 位置：import 语句末尾
                // relatedToPrevious=true 意味着如果换行，它会跟随上一行（虽然 import 通常在一行）
                sink.addPresentation(InlineInlayPosition(element.textRange.endOffset, relatedToPrevious = true), hasBackground = true) {
                    text(displayText)
                }

                // 如果没有数据且不在加载中，触发异步加载
                if (info == null && loadingKeys.add(repoKey)) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            val result = GithubRepoInfoService.fetchRepoInfo(owner, repo)
                                ?: GithubRepoInfo(-1, "加载失败")

                            repoInfoCache[repoKey] = result

                            val project = file.project

                            ApplicationManager.getApplication().invokeLater {
                                if (!project.isDisposed && file.isValid) {

                                    val vFile = file.virtualFile
                                    if (vFile != null) {
                                        PsiDocumentManager.getInstance(project)
                                            .reparseFiles(listOf(vFile), true)
                                    }

                                    LOG.info("Reparsed file for $repoKey")
                                }
                            }

                        } catch (e: Exception) {
                            LOG.error("Failed to load repo info for $repoKey", e)
                            repoInfoCache[repoKey] = GithubRepoInfo(-1, "Error")
                        } finally {
                            loadingKeys.remove(repoKey)
                        }
                    }
                }
            }
        }
    }
}
