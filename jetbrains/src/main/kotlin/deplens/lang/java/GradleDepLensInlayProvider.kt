package deplens.lang.java

import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import deplens.utils.inlay.GithubInlayUtils
import deplens.utils.resolver.MavenRepoResolver

class GradleDepLensInlayProvider : InlayHintsProvider, DumbAware {

    private val depNotationRegex = Regex(
        """(implementation|api|compileOnly|runtimeOnly|testImplementation|testCompileOnly|testRuntimeOnly|kapt|annotationProcessor|classpath|compile|testCompile)\s*\(?\s*['\"]([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):[^'\"\s\)]+['\"]"""
    )

    private val mapNotationRegex = Regex(
        """(implementation|api|compileOnly|runtimeOnly|testImplementation|testCompileOnly|testRuntimeOnly|kapt|annotationProcessor|classpath|compile|testCompile)\s*\(?\s*group:\s*['\"]([A-Za-z0-9_.-]+)['\"]\s*,\s*name:\s*['\"]([A-Za-z0-9_.-]+)['\"]"""
    )

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        val name = file.name
        if (name != "build.gradle" && name != "build.gradle.kts") return null

        return object : SharedBypassCollector {

            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                if (element !is PsiFile) return

                val text = element.text

                for (match in depNotationRegex.findAll(text)) {
                    val groupId = match.groupValues[2]
                    val artifactId = match.groupValues[3]
                    val repoKey = MavenRepoResolver.repoKeyFromGroupArtifact(groupId, artifactId) ?: continue
                    val offset = match.range.last + 1
                    GithubInlayUtils.addRepoInlay(file, sink, repoKey, offset)
                }

                for (match in mapNotationRegex.findAll(text)) {
                    val groupId = match.groupValues[2]
                    val artifactId = match.groupValues[3]
                    val repoKey = MavenRepoResolver.repoKeyFromGroupArtifact(groupId, artifactId) ?: continue
                    val offset = match.range.last + 1
                    GithubInlayUtils.addRepoInlay(file, sink, repoKey, offset)
                }
            }
        }
    }
}
