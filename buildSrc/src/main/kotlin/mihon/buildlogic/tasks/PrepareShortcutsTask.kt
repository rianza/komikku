package mihon.buildlogic.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

/**
 * Generates a variant-specific shortcuts.xml by injecting the applicationId of the
 * variant as android:targetPackage into every shortcut intent.
 *
 * Replacement for the deprecated com.github.zellius.shortcut-helper plugin,
 * which relies on AGP APIs removed in AGP 9.
 */
abstract class PrepareShortcutsTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val shortcutFile: RegularFileProperty

    @get:Input
    abstract val applicationId: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val androidNs = "http://schemas.android.com/apk/res/android"

        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isNamespaceAware = true
        }
        val document = factory.newDocumentBuilder().parse(shortcutFile.get().asFile)

        val intents = document.getElementsByTagName("intent")
        for (i in 0 until intents.length) {
            val intent = intents.item(i) as Element
            intent.setAttributeNS(androidNs, "android:targetPackage", applicationId.get())
        }

        val transformer = TransformerFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }.newTransformer().apply {
            setOutputProperty(OutputKeys.METHOD, "xml")
            setOutputProperty(OutputKeys.ENCODING, "utf-8")
            setOutputProperty(OutputKeys.STANDALONE, "yes")
        }

        val target = outputDir.get().asFile
            .resolve("xml")
            .apply { mkdirs() }
            .resolve(shortcutFile.get().asFile.name)

        transformer.transform(DOMSource(document), StreamResult(target))
    }
}
