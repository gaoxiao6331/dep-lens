package deplens.utils.resolver

import com.intellij.lang.ecmascript6.psi.ES6ImportCall
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.ecmascript6.resolve.JSFileReferencesUtil
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.PsiElement

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
        return JSFileReferencesUtil.isRelative(path) || path.startsWith("node:")
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
        else if (depName.startsWith('@')) {
            if (paths.size > 1) paths[0] + "/" + paths[1]
            else ""
        }
        else paths.first()
    }

}