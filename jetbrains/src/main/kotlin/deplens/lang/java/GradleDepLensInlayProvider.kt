package deplens.lang.java

import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import deplens.lang.BaseDepLensInlayProvider
import deplens.utils.inlay.GithubInlayUtils
import deplens.utils.resolver.MavenRepoResolver

class GradleDepLensInlayProvider : BaseDepLensInlayProvider() {

    private val depNotationRegex = Regex(
        """(implementation|api|compileOnly|runtimeOnly|testImplementation|testCompileOnly|testRuntimeOnly|kapt|annotationProcessor|classpath|compile|testCompile)\s*\(?\s*['\"]([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+)[^'\"\s\)]*['\"]"""
    )

    private val mapNotationRegex = Regex(
        """(implementation|api|compileOnly|runtimeOnly|testImplementation|testCompileOnly|testRuntimeOnly|kapt|annotationProcessor|classpath|compile|testCompile)\s*\(?\s*group:\s*['\"]([A-Za-z0-9_.-]+)['\"]\s*,\s*name:\s*['\"]([A-Za-z0-9_.-]+)['\"](?:\s*,\s*version:\s*['\"]([A-Za-z0-9_.-]+)['\"])?"""
    )

    override fun isFileSupported(file: PsiFile): Boolean {
        val name = file.name
        return name == "build.gradle" || name == "build.gradle.kts"
    }

    override fun collectElement(file: PsiFile, element: PsiElement, sink: InlayTreeSink) {
        if (element !is PsiFile) return

        val text = element.text

        for (match in depNotationRegex.findAll(text)) {
            val groupId = match.groupValues[2]
            val artifactId = match.groupValues[3]
            val version = match.groupValues[4]
            val repoKey = MavenRepoResolver.repoKeyFromGroupArtifact(groupId, artifactId, version) ?: continue
            val offset = match.range.last + 1
            GithubInlayUtils.addRepoInlay(file, sink, repoKey, offset)
        }

        for (match in mapNotationRegex.findAll(text)) {
            val groupId = match.groupValues[2]
            val artifactId = match.groupValues[3]
            val version = match.groupValues.getOrNull(4)
            val repoKey = MavenRepoResolver.repoKeyFromGroupArtifact(groupId, artifactId, version) ?: continue
            val offset = match.range.last + 1
            GithubInlayUtils.addRepoInlay(file, sink, repoKey, offset)
        }
    }
}
