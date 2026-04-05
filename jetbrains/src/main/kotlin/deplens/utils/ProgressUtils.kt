package deplens.utils

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

object ProgressUtils {

    fun runBackground(
        project: Project,
        title: String,
        cancellable: Boolean = true,
        minVisibleMillis: Long = 0L,
        action: (ProgressIndicator) -> Unit
    ) {
        if (project.isDisposed) {
            return
        }

        object : Task.Backgroundable(project, title, cancellable) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = title
                val start = System.currentTimeMillis()
                action(indicator)
                if (minVisibleMillis > 0) {
                    // A very fast request can finish before status bar paints; keep it visible a bit.
                    val elapsed = System.currentTimeMillis() - start
                    val remaining = minVisibleMillis - elapsed
                    if (remaining > 0) {
                        Thread.sleep(remaining)
                    }
                }
            }
        }.queue()
    }
}
