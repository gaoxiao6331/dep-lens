package deplens.lang.ts

import com.intellij.lang.ecmascript6.psi.ES6ImportCall
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSCallExpression
import deplens.utils.RepoKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import com.intellij.lang.ecmascript6.resolve.JSFileReferencesUtil
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSVarStatement


internal object TsImportResolver {

    private val jsonParser = Json { ignoreUnknownKeys = true }
    private val tsConfigCache = ConcurrentHashMap<String, Pair<Long, TsConfig>>()

    private fun isRequireCall(element: PsiElement?): Boolean {
        return element is JSCallExpression && element.isRequireCall
    }

    fun isImport(element: PsiElement): Boolean {
        return element is ES6ImportDeclaration || // import a from 'a'
                element is ES6ImportCall || // import('a')
                isRequireCall(element) // require('a')
    }

    fun isLocalImport(path: String): Boolean {
        return JSFileReferencesUtil.isRelative(path)
    }

    fun getDependencyPath(element: PsiElement): String? {
        val text = when (element) {
            // import a from 'lodash'
            is ES6ImportDeclaration -> {
                val v = element.fromClause?.referenceText
                v
            }

            // import('lodash')
            is ES6ImportCall -> {
                element.stringArgument?.text
            }

            // require('lodash')
            is JSCallExpression -> {
                val argument = element.arguments.firstOrNull() as? JSLiteralExpression
                argument?.stringValue
            }

            else -> null
        }
        return text?.trim('\'', '"', '`')
    }

    fun getModuleName(rawPkg: String): String {
        if (rawPkg.startsWith("@")) {
            val parts = rawPkg.split("/")
            if (parts.size >= 2) return "${parts[0]}/${parts[1]}"
        }
        return rawPkg.split("/")[0]
    }

    fun findPackageJsonFile(startDir: VirtualFile?, packageName: String, projectRoot: VirtualFile): VirtualFile? {
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

    fun resolveGithubRepoFromPackageJson(pkgJsonFile: VirtualFile): RepoKey? {
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

    fun extractGithubRepo(repoVal: String): RepoKey? {
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

    private fun findTsConfigFile(startDir: VirtualFile?, projectRoot: VirtualFile): VirtualFile? {
        var currentDir = startDir
        while (currentDir != null && currentDir.path.startsWith(projectRoot.path)) {
            val candidate = currentDir.findChild("tsconfig.json")
            if (candidate != null) return candidate
            if (currentDir == projectRoot) break
            currentDir = currentDir.parent
        }
        return null
    }

    private data class TsConfig(
        val baseUrl: String?,
        val paths: Map<String, List<String>>
    )

    private fun loadTsConfig(tsConfigFile: VirtualFile): TsConfig? {
        val path = tsConfigFile.path
        val timestamp = tsConfigFile.timeStamp
        val cached = tsConfigCache[path]
        if (cached != null && cached.first == timestamp) {
            return cached.second
        }

        return try {
            val content = String(tsConfigFile.contentsToByteArray(), Charsets.UTF_8)
            val jsonElement = jsonParser.parseToJsonElement(content)
            val compilerOptions = jsonElement.jsonObject["compilerOptions"]?.jsonObject

            val baseUrl = compilerOptions?.get("baseUrl")?.jsonPrimitive?.contentOrNull
            val pathsObj = compilerOptions?.get("paths")?.jsonObject

            val paths = mutableMapOf<String, List<String>>()
            pathsObj?.forEach { (key, value) ->
                val arr = value as? JsonArray ?: return@forEach
                val list = arr.mapNotNull { it.jsonPrimitive.contentOrNull }
                if (list.isNotEmpty()) paths[key] = list
            }

            val config = TsConfig(baseUrl, paths)
            tsConfigCache[path] = timestamp to config
            config
        } catch (e: Exception) {
            null
        }
    }

    private fun resolvesWithTsConfig(
        rawValue: String,
        tsConfig: TsConfig,
        tsConfigDir: VirtualFile?,
        projectRoot: VirtualFile
    ): Boolean {
        if (tsConfigDir == null) return false

        val baseDir = when {
            tsConfig.baseUrl.isNullOrBlank() -> tsConfigDir
            else -> tsConfigDir.findFileByRelativePath(tsConfig.baseUrl) ?: tsConfigDir
        }

        if (tsConfig.paths.isNotEmpty()) {
            for ((key, targets) in tsConfig.paths) {
                val match = matchPathAlias(key, rawValue) ?: continue
                for (target in targets) {
                    val candidatePath = applyPathAlias(target, match)
                    if (candidatePath != null && existsAsLocalFile(candidatePath, baseDir, projectRoot)) {
                        return true
                    }
                }
            }
            return false
        }

        return existsAsLocalFile(rawValue, baseDir, projectRoot)
    }

    private data class AliasMatch(val prefix: String, val suffix: String, val middle: String)

    private fun matchPathAlias(pattern: String, rawValue: String): AliasMatch? {
        if (!pattern.contains("*")) {
            return if (pattern == rawValue) AliasMatch(pattern, "", "") else null
        }

        val idx = pattern.indexOf("*")
        val prefix = pattern.substring(0, idx)
        val suffix = pattern.substring(idx + 1)

        if (!rawValue.startsWith(prefix) || !rawValue.endsWith(suffix)) return null
        val middle = rawValue.substring(prefix.length, rawValue.length - suffix.length)
        return AliasMatch(prefix, suffix, middle)
    }

    private fun applyPathAlias(target: String, match: AliasMatch): String? {
        return if (target.contains("*")) {
            target.replace("*", match.middle)
        } else {
            if (match.middle.isNotEmpty()) null else target
        }
    }

    private fun existsAsLocalFile(candidatePath: String, baseDir: VirtualFile, projectRoot: VirtualFile): Boolean {
        val extensions = listOf("ts", "tsx", "js", "jsx", "d.ts")

        fun resolve(path: String): VirtualFile? {
            return if (path.startsWith("/")) {
                LocalFileSystem.getInstance().findFileByPath(path)
            } else {
                baseDir.findFileByRelativePath(path)
            }
        }

        fun isLocalCandidate(file: VirtualFile): Boolean {
            val path = file.path
            return path.startsWith(projectRoot.path) && !path.contains("/node_modules/")
        }

        val direct = resolve(candidatePath)
        if (direct != null) {
            if (!isLocalCandidate(direct)) return false
            if (!direct.isDirectory) return true
            for (ext in extensions) {
                val file = direct.findChild("index.$ext")
                if (file != null) return true
            }
            return false
        }

        for (ext in extensions) {
            val file = resolve("$candidatePath.$ext")
            if (file != null && isLocalCandidate(file)) return true
        }

        return false
    }

    fun isImportSourceLiteral(element: PsiElement): Boolean {
        val parent = element.parent
        if (parent is JSArgumentList) {
            val callExpr = parent.parent as? JSCallExpression
            val methodName = callExpr?.methodExpression?.text
            if (methodName == "require" || methodName == "import") return true
        }

        var current: PsiElement? = element
        while (current != null && current !is PsiFile) {
            val name = current.javaClass.simpleName
            if (name == "JSImportStatement" ||
                name == "JSImportDeclaration" ||
                name == "JSImportModuleStatement" ||
                name == "ES6ImportDeclaration" ||
                name == "JSImportSource" ||
                name == "JSFromClause") {
                return true
            }
            current = current.parent
        }

        return false
    }
}
