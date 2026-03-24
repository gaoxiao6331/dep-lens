package deplens.lang.java

import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlTag
import deplens.utils.GithubInlayUtils
import deplens.utils.MavenRepoResolver

class MavenDepLensInlayProvider : InlayHintsProvider, DumbAware {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (file.name != "pom.xml") return null

        return object : SharedBypassCollector {

            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                val tag = element as? XmlTag ?: return
                if (tag.name != "dependency") return

                val groupId = tag.findFirstSubTag("groupId")?.value?.text?.trim()
                val artifactIdTag = tag.findFirstSubTag("artifactId")
                val artifactId = artifactIdTag?.value?.text?.trim()

                val repoKey = MavenRepoResolver.repoKeyFromGroupArtifact(groupId, artifactId) ?: return
                val offset = artifactIdTag?.value?.textRange?.endOffset ?: return

                GithubInlayUtils.addRepoInlay(file, sink, repoKey, offset)
            }
        }
    }
}
