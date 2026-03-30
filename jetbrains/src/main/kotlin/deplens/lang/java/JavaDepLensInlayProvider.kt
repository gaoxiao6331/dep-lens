package deplens.lang.java

import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiImportStatement
import com.intellij.psi.PsiJavaFile
import deplens.lang.BaseDepLensInlayProvider
import deplens.utils.inlay.GithubInlayUtils
import deplens.utils.resolver.MavenRepoResolver

class JavaDepLensInlayProvider : BaseDepLensInlayProvider() {

    override fun isFileSupported(file: PsiFile): Boolean = file is PsiJavaFile

    override fun collectElement(file: PsiFile, element: PsiElement, sink: InlayTreeSink) {
        if (element !is PsiImportStatement) return

        val resolved = element.resolve()
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
