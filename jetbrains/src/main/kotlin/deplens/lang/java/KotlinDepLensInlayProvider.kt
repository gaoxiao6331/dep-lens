package deplens.lang.java

import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMember
import deplens.utils.inlay.GithubInlayUtils
import deplens.utils.resolver.MavenRepoResolver
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

class KotlinDepLensInlayProvider : InlayHintsProvider, DumbAware {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector {
        if (file !is KtFile) {
            return object : SharedBypassCollector {
                override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) = Unit
            }
        }

        if (file.name.endsWith(".gradle.kts")) {
            return object : SharedBypassCollector {
                override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) = Unit
            }
        }

        return object : SharedBypassCollector {

            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                if (element !is KtImportDirective) return

                val resolved = element.importedReference
                    ?.references
                    ?.firstNotNullOfOrNull { it.resolve() }

                val resolvedClass = when (resolved) {
                    is com.intellij.psi.PsiClass -> resolved
                    is PsiMember -> resolved.containingClass
                    else -> null
                }

                val resolvedPath = resolvedClass?.containingFile?.virtualFile?.path
                val repoKey = MavenRepoResolver.repoKeyFromResolvedPath(resolvedPath) ?: return
                val offset = element.textRange.endOffset

                GithubInlayUtils.addRepoInlay(file, sink, repoKey, offset)
            }
        }
    }
}
