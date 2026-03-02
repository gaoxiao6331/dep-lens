package com.deplens.go

import com.intellij.codeInsight.hints.*
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.plugins.go.lang.psi.GoImportSpec
import java.util.concurrent.CompletableFuture

class GithubImportInlayProvider : InlayHintsProvider<NoSettings> {
    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ): InlayHintsCollector {
        val factory = PresentationFactory(editor)
        return object : FactoryInlayHintsCollector(editor) {
            override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
                if (element is GoImportSpec) {
                    val text = element.text
                    val match = GITHUB_IMPORT_REGEX.find(text)
                    if (match != null) {
                        val owner = match.groupValues[1]
                        val repo = match.groupValues[2]
                        val offset = element.textRange.endOffset
                        val placeholder = factory.smallText("  载入中…")
                        val presentationRef = sink.addInlineElement(offset, true, placeholder, false)
                        CompletableFuture.supplyAsync({
                            GithubRepoInfoService.getRepoInfo(owner, repo)
                        }, AppExecutorUtil.getAppExecutorService()).thenAccept { info ->
                            val label = "  ⭐ ${info.stars} • 更新 ${info.updatedDate}"
                            val pres = factory.smallText(label)
                            presentationRef?.replace(pres)
                        }
                    }
                }
                return true
            }
        }
    }

    override fun createSettings(): NoSettings = NoSettings.INSTANCE

    override val name: String get() = "GitHub仓库信息"
    override val key: SettingsKey<NoSettings> = SettingsKey("deplens.github.import.inlay")

    override val previewText: String?
        get() = """
            import "github.com/golang/protobuf"
            import (
                "fmt"
                "github.com/stretchr/testify"
            )
        """.trimIndent()

    companion object {
        private val GITHUB_IMPORT_REGEX =
            Regex("\"github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)\"")
    }
}
