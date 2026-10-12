package dev.mcdevmcp.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Wraps the application JAR and its runtime in a jpackage application image: a directory with a
 * native launcher that needs no installed JDK and no command-line flags.
 */
abstract class AppImageTask @Inject constructor(
    private val execOperations: ExecOperations, private val fileOperations: FileSystemOperations
) : DefaultTask() {
    @get:Input
    abstract val jdkHome: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeImage: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val mainJar: RegularFileProperty

    @get:Input
    abstract val applicationName: Property<String>

    @get:Input
    abstract val applicationVersion: Property<String>

    @get:Input
    abstract val mainClass: Property<String>

    @get:Input
    abstract val javaOptions: ListProperty<String>

    /** The directory jpackage writes the image into; the image itself is named after the application. */
    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun pack() {
        val output = destination.get().asFile
        val input = File(temporaryDir, "input")
        fileOperations.delete { delete(output, input) }
        fileOperations.copy {
            from(mainJar)
            into(input)
        }
        val arguments = mutableListOf(
            PackagingTools.executable(File(jdkHome.get()), "jpackage"),
            "--type", "app-image",
            "--name", applicationName.get(),
            "--app-version", applicationVersion.get(),
            "--input", input.absolutePath,
            "--main-jar", mainJar.get().asFile.name,
            "--main-class", mainClass.get(),
            "--runtime-image", runtimeImage.get().asFile.absolutePath,
            "--dest", output.absolutePath
        )
        javaOptions.get().forEach { option -> arguments += listOf("--java-options", option) }
        if (PackagingTools.windows) {
            // Without a console launcher the server has no standard streams to speak MCP over.
            arguments += "--win-console"
        }
        execOperations.exec { commandLine(arguments) }
    }
}
