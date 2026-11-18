package io.github.psd2live.testing

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/** Package a compiled, JDK-only test worker so the host still starts a real isolated JVM. */
internal fun writeWorkerJar(path: Path, main: Class<*>) {
    val manifest = Manifest().apply {
        mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainAttributes[Attributes.Name.MAIN_CLASS] = main.name
    }
    val entry = main.name.replace('.', '/') + ".class"
    JarOutputStream(Files.newOutputStream(path)).use { jar ->
        jar.putNextEntry(JarEntry("META-INF/MANIFEST.MF").apply { time = 0L })
        manifest.write(jar)
        jar.closeEntry()
        jar.putNextEntry(JarEntry(entry).apply { time = 0L })
        requireNotNull(main.getResourceAsStream("/$entry")).use { it.copyTo(jar) }
        jar.closeEntry()
    }
}
