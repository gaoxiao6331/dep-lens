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

    fun getDepName(element: PsiElement): String? {
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

    fun getPkgName(depName: String): String = depName.split('/').let { paths ->
        if (paths.isEmpty()) ""
        else if (depName.startsWith('@')) paths.first()
        else if (paths.size > 1) paths[0] + "/" + paths[1]
        else ""
    }

}
