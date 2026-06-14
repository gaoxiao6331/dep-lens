package deplens.lang.dart

import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import deplens.lang.BaseDepLensInlayProvider
import deplens.utils.inlay.PubInlayUtils

class PubspecDepLensInlayProvider : BaseDepLensInlayProvider() {

    override val usesWholeFileCollection: Boolean = true

    override fun isFileSupported(file: PsiFile): Boolean = file.name == "pubspec.yaml"

    override fun collectElement(file: PsiFile, element: PsiElement, sink: InlayTreeSink) = Unit

    override fun collectFile(file: PsiFile, sink: InlayTreeSink) {
        val document = file.viewProvider.document ?: return
        val entries = collectDependencyEntries(document)

        for ((lineNumber, packageName) in entries) {
            val offset = document.getLineEndOffset(lineNumber)
            PubInlayUtils.addPubDepInlay(file, sink, packageName, offset)
        }
    }

    private fun collectDependencyEntries(document: Document): List<Pair<Int, String>> {
        val entries = mutableListOf<Pair<Int, String>>()
        var sectionIndent: Int? = null

        for (lineNumber in 0 until document.lineCount) {
            val lineText = document.getLineText(lineNumber)
            val trimmed = lineText.trim()
            val indent = lineText.length - lineText.trimStart().length

            val sectionMatch = sectionPattern.matchEntire(lineText)
            if (sectionMatch != null) {
                sectionIndent = sectionMatch.groupValues[1].length
                continue
            }

            val currentSectionIndent = sectionIndent ?: continue

            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && indent <= currentSectionIndent) {
                sectionIndent = null
                continue
            }

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue
            }

            val dependencyMatch = dependencyPattern.matchEntire(lineText) ?: continue
            val dependencyIndent = dependencyMatch.groupValues[1].length
            if (dependencyIndent != currentSectionIndent + 2) {
                continue
            }

            val packageName = dependencyMatch.groupValues[2]
            val value = dependencyMatch.groupValues[3].trim()

            if (packageName == "flutter") {
                continue
            }

            if (isUnsupportedInlineSource(value)) {
                continue
            }

            if (value.isEmpty() && hasUnsupportedNestedSource(document, lineNumber, dependencyIndent)) {
                continue
            }

            entries += lineNumber to packageName
        }

        return entries
    }

    private fun hasUnsupportedNestedSource(
        document: Document,
        startLine: Int,
        parentIndent: Int
    ): Boolean {
        for (lineNumber in startLine + 1 until document.lineCount) {
            val lineText = document.getLineText(lineNumber)
            val trimmed = lineText.trim()

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue
            }

            val indent = lineText.length - lineText.trimStart().length
            if (indent <= parentIndent) {
                break
            }

            if (unsupportedSourcePattern.containsMatchIn(trimmed)) {
                return true
            }
        }

        return false
    }

    private fun isUnsupportedInlineSource(value: String): Boolean {
        return unsupportedSourcePattern.containsMatchIn(value)
    }

    private fun Document.getLineText(lineNumber: Int): String {
        val startOffset = getLineStartOffset(lineNumber)
        val endOffset = getLineEndOffset(lineNumber)
        return getText(TextRange(startOffset, endOffset))
    }

    companion object {
        private val sectionPattern =
            Regex("""^(\s*)(dependencies|dev_dependencies|dependency_overrides):\s*(?:#.*)?$""")
        private val dependencyPattern = Regex("""^(\s*)([A-Za-z0-9_]+):\s*(.*)$""")
        private val unsupportedSourcePattern = Regex("""\b(sdk|path|git)\s*:""")
    }
}
