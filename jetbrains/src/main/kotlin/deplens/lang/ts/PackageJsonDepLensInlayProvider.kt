package deplens.lang.ts

import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import deplens.utils.NpmInlayUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class PackageJsonDepLensInlayProvider : InlayHintsProvider, DumbAware {

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private val DEP_SECTION_NAMES = setOf(
            "dependencies",
            "devDependencies",
            "peerDependencies",
            "optionalDependencies",
            "bundledDependencies",
            "bundleDependencies"
        )
    }

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector {
        return object : SharedBypassCollector {
            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                if (file.name != "package.json") return
                if (element != file) return

                val depOffsets = parsePackageJsonDepsWithOffsets(file.text)
                if (depOffsets.isEmpty()) return

                for ((pkg, offset) in depOffsets) {
                    NpmInlayUtils.addNpmDepInlay(file, sink, pkg, offset)
                }
            }
        }
    }

    private fun parsePackageJsonDepsWithOffsets(text: String): List<Pair<String, Int>> {
        val root = parseJson(text) ?: return emptyList()
        val results = mutableListOf<Pair<String, Int>>()

        for (section in DEP_SECTION_NAMES) {
            val sectionObj = (root[section] as? JsonObject) ?: continue
            if (sectionObj.isEmpty()) continue

            val range = findSectionObjectRange(text, section) ?: continue
            val valueEnds = scanObjectPropertyValueEnds(text, range.first + 1, range.last)

            for (pkg in sectionObj.keys) {
                val endOffset = valueEnds[pkg] ?: continue
                results.add(pkg to endOffset)
            }
        }

        return results
    }

    private fun parseJson(text: String): JsonObject? {
        return try {
            JSON.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            null
        }
    }

    private fun findSectionObjectRange(text: String, section: String): IntRange? {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '"') {
                i++
                continue
            }
            val (key, endQuote) = readString(text, i) ?: return null
            i = endQuote + 1
            if (key != section) continue
            i = skipWhitespace(text, i)
            if (i >= text.length || text[i] != ':') continue
            i = skipWhitespace(text, i + 1)
            if (i >= text.length || text[i] != '{') continue
            val end = findMatchingBrace(text, i) ?: return null
            return IntRange(i, end)
        }
        return null
    }

    private fun scanObjectPropertyValueEnds(text: String, start: Int, end: Int): Map<String, Int> {
        val map = HashMap<String, Int>()
        var i = start
        var depthObj = 0
        var depthArr = 0
        var inString = false

        while (i < end) {
            val c = text[i]
            if (inString) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            when (c) {
                '"' -> {
                    val (key, keyEnd) = readString(text, i) ?: return map
                    var j = skipWhitespace(text, keyEnd + 1)
                    if (j >= end || text[j] != ':') {
                        i = keyEnd + 1
                        continue
                    }
                    j = skipWhitespace(text, j + 1)
                    if (j >= end) break
                    val valueEnd = parseValueEnd(text, j, end)
                    map[key] = valueEnd
                    i = valueEnd
                }
                '{' -> {
                    depthObj++
                    i++
                }
                '}' -> {
                    if (depthObj == 0 && depthArr == 0) return map
                    depthObj--
                    i++
                }
                '[' -> {
                    depthArr++
                    i++
                }
                ']' -> {
                    if (depthArr == 0 && depthObj == 0) return map
                    depthArr--
                    i++
                }
                else -> i++
            }
        }
        return map
    }

    private fun parseValueEnd(text: String, start: Int, end: Int): Int {
        var i = start
        if (text[i] == '"') {
            val (_, endQuote) = readString(text, i) ?: return i + 1
            return endQuote + 1
        }
        if (text[i] == '{') {
            val braceEnd = findMatchingBrace(text, i) ?: return i + 1
            return braceEnd + 1
        }
        if (text[i] == '[') {
            val bracketEnd = findMatchingBracket(text, i) ?: return i + 1
            return bracketEnd + 1
        }
        var lastNonWs = i
        while (i < end) {
            val c = text[i]
            if (c == ',' || c == '}' || c == ']') break
            if (!c.isWhitespace()) lastNonWs = i
            i++
        }
        return lastNonWs + 1
    }

    private fun readString(text: String, startQuote: Int): Pair<String, Int>? {
        var i = startQuote + 1
        val sb = StringBuilder()
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') {
                if (i + 1 < text.length) {
                    sb.append(text[i + 1])
                    i += 2
                } else {
                    i++
                }
                continue
            }
            if (c == '"') return sb.toString() to i
            sb.append(c)
            i++
        }
        return null
    }

    private fun skipWhitespace(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun findMatchingBrace(text: String, startBrace: Int): Int? {
        var i = startBrace + 1
        var depth = 1
        var inString = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    private fun findMatchingBracket(text: String, startBracket: Int): Int? {
        var i = startBracket + 1
        var depth = 1
        var inString = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            when (c) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }
}
