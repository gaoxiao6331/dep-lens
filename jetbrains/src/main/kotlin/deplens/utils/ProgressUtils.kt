package deplens.utils

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

object ProgressUtils {

    fun runBackground(
        project: Project,
        title: String,
        cancellable: Boolean = true,
        action: (ProgressIndicator) -> Unit
    ) {
        if (project.isDisposed) {
            return
        }

        object : Task.Backgroundable(project, title, cancellable) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = title
                // Ensure the task is visible in the status bar even for very fast requests.
                Thread.sleep(50)
                action(indicator)
            }
        }.queue()
    }
}
