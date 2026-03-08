package deplens.utils

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

object MyPluginBundle : DynamicBundle("messages.DepLensBundle") {
    fun message(@PropertyKey(resourceBundle = "messages.DepLensBundle") key: String, vararg params: Any): String {
        return getMessage(key, *params)
    }
}