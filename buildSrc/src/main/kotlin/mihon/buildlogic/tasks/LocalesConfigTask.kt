package mihon.buildlogic.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider

private val emptyResourcesElement = "<resources>\\s*</resources>|<resources\\s*/>".toRegex()

abstract class LocalesConfigTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stringsFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputResourceDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val locales = stringsFiles
            .filter { !it.readText().contains(emptyResourcesElement) }
            .map {
                it.parentFile.name
                    .replace("base", "en")
                    .replace("-r", "-")
                    .replace("+", "-")
            }
            .sorted()
            .joinToString("\n") { "|   <locale android:name=\"$it\"/>" }

        val content = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
        $locales
        |</locale-config>
        """.trimMargin()

        outputResourceDir.get().asFile.resolve("xml/locales_config.xml").apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }
}

fun Project.getLocalesConfigTask(): TaskProvider<LocalesConfigTask> {
    return tasks.register("generateLocalesConfig", LocalesConfigTask::class.java) {
        stringsFiles.from(
            fileTree("$projectDir/src/commonMain/moko-resources/")
                .matching { include("**/strings.xml") },
        )
    }
}
