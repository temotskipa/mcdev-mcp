package dev.mcdevmcp.buildlogic

import java.io.File
import java.util.Locale

/**
 * Platform facts shared by the tasks that drive the JDK's packaging tools.
 */
object PackagingTools {
    private val osName = System.getProperty("os.name").lowercase(Locale.ROOT)

    val windows: Boolean = osName.contains("win")

    /** The platform part of a bundle's file name, such as `windows-x64`. */
    val platform: String = run {
        val os = when {
            windows -> "windows"
            osName.contains("mac") || osName.contains("darwin") -> "macos"
            else -> "linux"
        }
        val arch = when (val reported = System.getProperty("os.arch").lowercase(Locale.ROOT)) {
            "amd64", "x86_64" -> "x64"
            "arm64" -> "aarch64"
            else -> reported
        }
        "$os-$arch"
    }

    fun executable(home: File, name: String): String = File(home, "bin/" + if (windows) "$name.exe" else name).absolutePath
}
