package deplens.lang.java

import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlTag
import deplens.lang.BaseDepLensInlayProvider
import deplens.utils.inlay.GithubInlayUtils
import deplens.utils.resolver.MavenRepoResolver

class MavenDepLensInlayProvider : BaseDepLensInlayProvider() {

    override fun isFileSupported(file: PsiFile): Boolean = file.name == "pom.xml"

    override fun collectElement(file: PsiFile, element: PsiElement, sink: InlayTreeSink) {
        val tag = element as? XmlTag ?: return
        if (tag.name != "dependency") return

        val groupId = tag.findFirstSubTag("groupId")?.value?.text?.trim()
        val artifactIdTag = tag.findFirstSubTag("artifactId")
        val artifactId = artifactIdTag?.value?.text?.trim()
        val version = tag.findFirstSubTag("version")?.value?.text?.trim()

        val repoKey = MavenRepoResolver.repoKeyFromGroupArtifact(groupId, artifactId, version) ?: return
        val offset = artifactIdTag?.value?.textRange?.endOffset ?: return

        GithubInlayUtils.addRepoInlay(file, sink, repoKey, offset)
    }
}
