// 仅抑制必要警告，无内部API依赖
@file:Suppress("UnstableApiUsage")

package com.deplens.go

// 只导入绝对能找到的基础API
import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.Disposable // 新增：Disposable接口
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.goide.psi.GoImportSpec
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import javax.swing.JPanel
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key

// 实现Disposable接口，解决addEditorFactoryListener废弃警告
class GithubImportInlayProvider : InlayHintsProvider<NoSettings>, Disposable {
    // 协程作用域（使用SupervisorJob，可通过Disposable取消）
    private val coroutineScope = CoroutineScope(
        Dispatchers.IO +
                SupervisorJob() +
                CoroutineName("GithubImportInlayScope")
    )

    // 核心缓存：仓库信息
    private val repoInfoCache = ConcurrentHashMap<String, GithubRepoInfo?>()
    private val LOG = logger<GithubImportInlayProvider>()
    private val GITHUB_IMPORT_REGEX = Regex("\"github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)\"")

    // 正确的UserData Key类型
    private val REFRESH_MARKER: Key<Long> = Key.create("GithubImportHint_RefreshMarker")

    init {
        // 监听编辑器关闭事件，传入this作为Disposable
        com.intellij.openapi.editor.EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorReleased(event: EditorFactoryEvent) {
                    // 编辑器关闭时取消协程，清空缓存
                    coroutineScope.cancel("编辑器已关闭，取消协程")
                    repoInfoCache.clear()
                }
            },
            this
        )
    }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ): InlayHintsCollector {
        val factory = PresentationFactory(editor)
        val project = file.project ?: return EmptyInlayHintsCollector()

        return object : FactoryInlayHintsCollector(editor) {
            override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
                if (element is GoImportSpec) {
                    GITHUB_IMPORT_REGEX.find(element.text)?.let { match ->
                        val owner = match.groupValues[1]
                        val repo = match.groupValues[2]
                        val repoKey = "$owner/$repo"
                        val offset = element.textRange.endOffset

                        val displayText = repoInfoCache[repoKey]?.let {
                            // TODO 优化这里硬编码的逻辑
                            if (it.stars == -1) "加载失败" else "⭐ ${it.stars} • 最后更新 ${it.updatedDate}"
                        } ?: "载入中…"

                        val textPresentation = factory.smallText(displayText)
                        val finalPresentation = factory.container(
                            textPresentation,
                            InlayPresentationFactory.Padding(0, 0, (editor.lineHeight - textPresentation.height) / 2 + 1, 0)
                        )
                        sink.addInlineElement(offset, false, finalPresentation, false)

                        // 异步加载数据 + 触发自动刷新
                        if (repoInfoCache[repoKey] == null) {
                            coroutineScope.launch {
                                try {
                                    val repoInfo = GithubRepoInfoService.getRepoInfo(owner, repo)
                                    // TODO 优化加载失败
                                    repoInfoCache[repoKey] = repoInfo ?: GithubRepoInfo(-1, "加载失败")

                                    launch(Dispatchers.Main) {
                                        if (project.isDisposed.not() && editor.project != null) {
                                            file.putUserData(REFRESH_MARKER, System.currentTimeMillis())
                                            LOG.debug("缓存更新，框架自动刷新Hint：$repoKey")
                                        }
                                    }
                                } catch (e: Exception) {
                                    LOG.error("获取仓库信息失败：$repoKey", e)
                                    repoInfoCache[repoKey] = GithubRepoInfo(-1, "加载失败")
                                    launch(Dispatchers.Main) {
                                        if (project.isDisposed.not() && editor.project != null) {
                                            file.putUserData(REFRESH_MARKER, System.currentTimeMillis())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                return true
            }
        }
    }

    // 空Collector兜底
    private class EmptyInlayHintsCollector : InlayHintsCollector {
        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean = true
    }

    // 插件基础配置（必实现方法）
    override fun createSettings(): NoSettings = NoSettings()
    override val name: String get() = "GitHub仓库信息"
    override val key: SettingsKey<NoSettings> = SettingsKey("deplens.github.import.inlay")
    override val previewText: String? get() = """
        import "github.com/golang/protobuf"
        import (
            "fmt"
            "github.com/stretchr/testify"
        )
    """.trimIndent()

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent = JPanel()
        }
    }

    // 实现Disposable接口的dispose方法（无override报错，因为是直接实现）
    override fun dispose() {
        // 插件销毁时取消协程，清空缓存（生命周期最终兜底）
        coroutineScope.cancel("插件已销毁，取消协程")
        repoInfoCache.clear()
    }
}