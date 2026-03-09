package deplens.lang.ts

import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSArgumentList
import deplens.utils.UiUtils
import deplens.utils.GithubRepoInfoService
import deplens.utils.RepoKey
import deplens.common.Result
import deplens.common.I18nKey
import deplens.common.ResultWrapper
import deplens.utils.I18n
import kotlinx.serialization.json.*

class TsDepLensInlayProvider : InlayHintsProvider, DumbAware {

    companion object {
        private val LOG = logger<TsDepLensInlayProvider>()
        
        private val jsonParser = Json { ignoreUnknownKeys = true }
    }

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector {
        return object : SharedBypassCollector {
            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                if (element !is JSLiteralExpression) return

                val rawValue = element.stringValue ?: return
                if (rawValue.isBlank() || rawValue.startsWith(".") || rawValue.startsWith("/")) return

                val parent = element.parent ?: return
                val parentName = parent.javaClass.simpleName
                
                var isPackageImport = false

                if (parentName.contains("ImportDeclaration") || parentName.contains("ExportDeclaration") || parentName.contains("FromClause")) {
                    isPackageImport = true
                } else if (parent is JSArgumentList) {
                    val callExpr = parent.parent as? JSCallExpression
                    val methodName = callExpr?.methodExpression?.text
                    if (methodName == "require" || methodName == "import") {
                        isPackageImport = true
                    }
                }

                if (!isPackageImport) return

                val pkgName = getModuleName(rawValue)
                val projectPath = file.project.basePath ?: return
                val projectRoot = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(projectPath) ?: return
                
                val pkgJsonFile = findPackageJsonFile(file.virtualFile.parent, pkgName, projectRoot) ?: return
                
                val repoKey = resolveGithubRepoFromPackageJson(pkgJsonFile) ?: return

                val offset = element.textRange.endOffset

                val res = GithubRepoInfoService.getRepoInfo(repoKey.owner, repoKey.repo)

                val displayText = when (res.result) {
                    Result.NONE -> I18n.message(I18nKey.loading)
                    Result.SUCCESS -> "⭐ ${res.data?.stars ?: 0} • ${I18n.message(I18nKey.lastUpdated)} ${res.data?.updatedDate ?: "N/A"}"
                    else -> I18n.message(I18nKey.failedToLoad)
                }

                UiUtils.addInlay(sink, offset, displayText)

                if (res.result == Result.NONE) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            GithubRepoInfoService.fetchRepoInfo(repoKey.owner, repoKey.repo)
                            UiUtils.refreshInlayHints(file)
                        } catch (e: Exception) {
                            LOG.warn("Failed to load repo info for \$repoKey", e)
                        }
                    }
                }
            }
        }
    }

    private fun getModuleName(rawPkg: String): String {
        if (rawPkg.startsWith("@")) {
            val parts = rawPkg.split("/")
            if (parts.size >= 2) return "${parts[0]}/${parts[1]}"
        }
        return rawPkg.split("/")[0]
    }

    private fun findPackageJsonFile(startDir: VirtualFile?, packageName: String, projectRoot: VirtualFile): VirtualFile? {
        var currentDir = startDir
        while (currentDir != null && currentDir.path.startsWith(projectRoot.path)) {
            val nodeModules = currentDir.findChild("node_modules")
            if (nodeModules != null) {
                if (packageName.startsWith("@")) {
                    val parts = packageName.split("/")
                    if (parts.size >= 2) {
                        val scoped = nodeModules.findChild(parts[0])?.findChild(parts[1])?.findChild("package.json")
                        if (scoped != null) return scoped
                    }
                } else {
                    val standard = nodeModules.findChild(packageName)?.findChild("package.json")
                    if (standard != null) return standard
                }
            }
            if (currentDir == projectRoot) break
            currentDir = currentDir.parent
        }
        return null
    }

    private fun resolveGithubRepoFromPackageJson(pkgJsonFile: VirtualFile): RepoKey? {
        try {
            val content = String(pkgJsonFile.contentsToByteArray(), Charsets.UTF_8)
            val jsonElement = jsonParser.parseToJsonElement(content)
            
            val repoObj = jsonElement.jsonObject["repository"] ?: return null
            
            var repoStr: String? = null
            if (repoObj is JsonPrimitive && repoObj.isString) {
                repoStr = repoObj.content
            } else if (repoObj is JsonObject) {
                repoStr = repoObj["url"]?.jsonPrimitive?.content
            }
            
            if (repoStr == null) return null
            
            return extractGithubRepo(repoStr)
        } catch (e: Exception) {
            return null
        }
    }

    private fun extractGithubRepo(repoVal: String): RepoKey? {
        var url = repoVal.removeSuffix(".git")
        if (url.startsWith("github:")) {
            val parts = url.substringAfter("github:").split("/")
            if (parts.size >= 2) return RepoKey(parts[0], parts[1])
        }
        
        val match = "github\\.com/([^/:]+)/([^/:]+)".toRegex().find(url)
        if (match != null) {
            val (owner, repoName) = match.destructured
            return RepoKey(owner, repoName)
        }
        
        if (!url.contains("://") && url.count { it == '/' } == 1) {
            val parts = url.split("/")
            return RepoKey(parts[0], parts[1])
        }
        
        return null
    }
}
