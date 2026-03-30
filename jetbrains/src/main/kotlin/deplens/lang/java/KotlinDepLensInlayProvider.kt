package deplens.lang.java

import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import deplens.lang.BaseDepLensInlayProvider
import deplens.utils.inlay.GithubInlayUtils
import deplens.utils.resolver.MavenRepoResolver
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

class KotlinDepLensInlayProvider : BaseDepLensInlayProvider() {

    override fun isFileSupported(file: PsiFile): Boolean {
        return file is KtFile && !file.name.endsWith(".gradle.kts")
    }

    override fun collectElement(file: PsiFile, element: PsiElement, sink: InlayTreeSink) {
        if (element !is KtImportDirective) return

        val fqName = element.importedFqName?.asString() ?: return

        val psiClass = JavaPsiFacade.getInstance(file.project)
            .findClass(fqName, file.resolveScope) ?: return

        val resolvedPath = psiClass.containingFile?.virtualFile?.path ?: return

        val repoKey = MavenRepoResolver.repoKeyFromResolvedPath(resolvedPath) ?: return

        GithubInlayUtils.addRepoInlay(file, sink, repoKey, element.textRange.endOffset)
    }
}
