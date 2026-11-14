package io.github.psd2live.core.modifier

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.format.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TestProtocol2Worker {
    @JvmStatic
    fun main(args: Array<String>) {
        val plugin = object : ChangingModifierAlgorithmPlugin {
            override fun changes(
                input: RigIR,
                instance: RigModifierInstance,
                context: ModifierAlgorithmContext,
                checkpoint: () -> Unit,
            ): ModifierModelChanges {
                val targetId = instance.targets.first().id
                val oldMesh = input.meshes.first { it.id == targetId }
                val newMesh = oldMesh.copy(opacity = 0.25f * instance.version)
                return ModifierModelChanges(
                    meshes = ModifierObjectChanges(updated = listOf(ModifierObjectUpdate(oldMesh, newMesh))),
                )
            }
        }
        AlgorithmWorker.run(
            mapOf(
                ("thirdparty.eye/fitted" to 2) to plugin,
                ("shared.one/op" to 1) to plugin,
                ("shared.two/op" to 1) to plugin,
            )
        )
    }
}

private val syntheticJson = Json { encodeDefaults = true }

internal fun createSyntheticPluginZip(
    workDir: Path,
    id: String,
    algorithmType: String,
    algorithmVersion: Int,
    protocol: Int = 2,
): Path {
    val manifestObj = java.util.jar.Manifest().apply {
        mainAttributes[java.util.jar.Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainAttributes[java.util.jar.Attributes.Name.MAIN_CLASS] = TestProtocol2Worker::class.java.name
        mainAttributes[java.util.jar.Attributes.Name.CLASS_PATH] = System.getProperty("java.class.path")
            .split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
            .joinToString(" ") { Path.of(it).toUri().toASCIIString() }
    }
    val jarBytes = java.io.ByteArrayOutputStream().apply {
        java.util.jar.JarOutputStream(this).use { jar ->
            jar.putNextEntry(java.util.jar.JarEntry("META-INF/MANIFEST.MF").apply { time = 0 })
            manifestObj.write(jar)
            jar.closeEntry()
        }
    }.toByteArray()
    val jarHash = ModifierPluginStore.hash(jarBytes)
    val manifest = ModifierPluginManifest(
        protocol = protocol,
        id = id,
        name = id,
        version = "1.0.0",
        commands = mapOf("jvm" to listOf("\$JAVA", "-jar", "worker.jar")),
        files = mapOf("worker.jar" to jarHash),
        algorithms = listOf(
            ModifierPluginAlgorithm(
                type = algorithmType,
                version = algorithmVersion,
                name = algorithmType,
                targets = setOf("mesh"),
                reads = setOf("mesh"),
                writes = setOf("mesh"),
                configSchema = buildJsonObject { },
                defaults = buildJsonObject { },
            )
        ),
    )
    val zipPath = workDir.resolve("$id.zip")
    ZipOutputStream(Files.newOutputStream(zipPath)).use { zip ->
        zip.putNextEntry(ZipEntry("plugin.json"))
        zip.write(syntheticJson.encodeToString(manifest).encodeToByteArray())
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("worker.jar"))
        zip.write(jarBytes)
        zip.closeEntry()
    }
    return zipPath
}

internal fun syntheticWorkerRig(): RigIR {
    val mesh = Mesh(
        id = "m1",
        name = "m1",
        parent = null,
        geometry = MeshGeometry(
            Floats.values(0f, 0f, 1f, 0f, 0f, 1f),
            Floats.values(0f, 0f, 1f, 0f, 0f, 1f),
            Ints.values(0, 1, 2),
        ),
        offsets = null,
        opacity = 1f,
    )
    return RigIR(Canvas(100f, 100f), emptyList(), meshes = listOf(mesh))
}

