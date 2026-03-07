package deplens.lang.go

import com.goide.psi.GoImportSpec
import com.intellij.codeInsight.hints.declarative.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import deplens.utils.GithubRepoInfo
import deplens.utils.GithubRepoInfoService
import deplens.utils.RepoKey
import java.util.concurrent.ConcurrentHashMap

class GoDepLensInlayProvider : InlayHintsProvider, DumbAware {

    companion object {

        private val LOG = logger<GoDepLensInlayProvider>()

        private val repoInfoCache = ConcurrentHashMap<RepoKey, GithubRepoInfo>()

        private val loadingKeys = ConcurrentHashMap.newKeySet<RepoKey>()
    }

    private fun extractRepoInfo(element: GoImportSpec): RepoKey? {

        val path = element.path ?: return null

        if (!path.startsWith("github.com/")) return null

        val parts = path.split("/")
        if (parts.size < 3) return null

        return RepoKey(parts[1], parts[2])
    }

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector {

        return object : SharedBypassCollector {

            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {

                if (element !is GoImportSpec) return

                val repoKey = extractRepoInfo(element) ?: return

                val info = repoInfoCache[repoKey]

                val displayText = when {
                    info == null -> "加载中…"
                    info.stars == -1 -> "加载失败"
                    else -> "⭐ ${info.stars} • ${info.updatedDate}"
                }

                val literal = element.stringLiteral ?: return
                val offset = literal.textRange.endOffset

                sink.addPresentation(
                    InlineInlayPosition(offset, relatedToPrevious = true),
                    hasBackground = true
                ) {
                    text(displayText)
                }

                if (info == null && loadingKeys.add(repoKey)) {

                    ApplicationManager.getApplication().executeOnPooledThread {

                        try {

                            val result = GithubRepoInfoService
                                .fetchRepoInfo(repoKey.owner, repoKey.repo)
                                ?: GithubRepoInfo(-1, "Error")

                            repoInfoCache[repoKey] = result

                            val project = file.project

                            ApplicationManager.getApplication().invokeLater({

                                if (project.isDisposed || !file.isValid) return@invokeLater

                                val vFile = file.virtualFile ?: return@invokeLater

                                PsiDocumentManager
                                    .getInstance(project)
                                    .reparseFiles(listOf(vFile), false)

                            }, ModalityState.NON_MODAL)

                        } catch (e: Exception) {

                            LOG.warn("Failed to load repo info for $repoKey", e)

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