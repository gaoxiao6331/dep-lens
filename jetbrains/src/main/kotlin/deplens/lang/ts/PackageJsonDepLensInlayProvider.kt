package deplens.lang.ts

import com.intellij.codeInsight.hints.declarative.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import deplens.lang.BaseDepLensInlayProvider
import deplens.utils.inlay.NpmInlayUtils

class PackageJsonDepLensInlayProvider : BaseDepLensInlayProvider() {

    companion object {
        private val DEP_SECTION_NAMES = setOf(
            "dependencies",
            "devDependencies",
            "peerDependencies",
            "optionalDependencies",
            "bundledDependencies",
            "bundleDependencies"
        )
    }

    override fun isFileSupported(file: PsiFile): Boolean = file.name == "package.json"

    override fun collectElement(file: PsiFile, element: PsiElement, sink: InlayTreeSink) {

        // 只处理 JsonProperty
        if (element !is JsonProperty) return

        // 只处理 dependencies / devDependencies 等区块
        if (!DEP_SECTION_NAMES.contains(element.name)) return

        // dependencies 对应的 value 必须是一个 JsonObject
        val depsObject = element.value as? JsonObject ?: return

        // 遍历 dependencies 里的每一个依赖
        depsObject.propertyList.forEach { depProp ->

            val pkgName = depProp.name
            val valueElement = depProp.value ?: return@forEach

            val endOffset = valueElement.textRange.endOffset

            NpmInlayUtils.addNpmDepInlay(
                file,
                sink,
                pkgName,
                endOffset
            )
        }
    }
}
