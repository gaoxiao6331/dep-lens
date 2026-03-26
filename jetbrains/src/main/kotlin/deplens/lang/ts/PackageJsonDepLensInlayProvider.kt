package deplens.lang.ts

import com.intellij.codeInsight.hints.declarative.*
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.diagnostic.logger
import deplens.utils.inlay.NpmInlayUtils

class PackageJsonDepLensInlayProvider : InlayHintsProvider, DumbAware {

    companion object {
        private val DEP_SECTION_NAMES = setOf(
            "dependencies",
            "devDependencies",
            "peerDependencies",
            "optionalDependencies",
            "bundledDependencies",
            "bundleDependencies"
        )

        private val LOG = logger<PackageJsonDepLensInlayProvider>()
    }

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {

        if (file.name != "package.json") return null

        return object : SharedBypassCollector {

            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {

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
    }
}