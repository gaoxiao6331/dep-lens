import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.Properties

abstract class SyncI18nTask : DefaultTask() {
    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val inDir = inputDir.get().asFile
        val outDir = outputDir.get().asFile
        outDir.mkdirs()

        val files = inDir.listFiles { _, name -> name.endsWith(".json") } ?: return
        for (file in files) {
            val baseName = file.nameWithoutExtension.replace(" ", "")
            val outFile = File(outDir, "$baseName.properties")
            
            val content = file.readText()
            val props = Properties()
            
            val regex = """"([^"\\]*(?:\\.[^"\\]*)*)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""".toRegex()
            regex.findAll(content).forEach { match ->
                val key = match.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n")
                val value = match.groupValues[2].replace("\\\"", "\"").replace("\\n", "\n")
                if (key.isNotBlank()) {
                    props.setProperty(key, value)
                }
            }
            
            outFile.outputStream().use { os ->
                props.store(os, "Auto-generated from ${file.name}")
            }
        }
    }
}
