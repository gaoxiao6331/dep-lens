package deplens.lang.java

import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
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

                val fqName = element.importedFqName?.asString() ?: return

                val psiClass = JavaPsiFacade.getInstance(file.project)
                    .findClass(fqName, file.resolveScope) ?: return

                val resolvedPath = psiClass.containingFile?.virtualFile?.path ?: return

                val repoKey = MavenRepoResolver.repoKeyFromResolvedPath(resolvedPath) ?: return

                GithubInlayUtils.addRepoInlay(file, sink, repoKey, element.textRange.endOffset)
            }
        }
    }
}
