package io.github.psd2live.core.modifier

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.format.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

class ModifierPluginRuntimeFixTest {
    private val json = Json { encodeDefaults = true }

    private fun createPluginZip(
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
            java.util.jar.JarOutputStream(this, manifestObj).close()
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
            zip.write(json.encodeToString(manifest).encodeToByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("worker.jar"))
            zip.write(jarBytes)
            zip.closeEntry()
        }
        return zipPath
    }

    private fun sampleRig(): RigIR {
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

    @Test
    fun thirdPartyPluginVersionAboveOneIsNotNormalizedToOne() {
        val thirdPartyRef = ModifierPluginReference("thirdparty.eye", "1.0.0", "digest-tp")
        assertEquals(2, BuiltinModifierPlugins.resolveVersion("eye/fitted", 2, thirdPartyRef))
        assertFalse(BuiltinModifierPlugins.isHistoricalBuiltinReference("eye/fitted", 2, thirdPartyRef))
        assertEquals(1, BuiltinModifierPlugins.resolveVersion("eye/fitted", 2, ModifierPluginReference("builtin", "1.0.0", "builtin")))
        assertEquals(1, BuiltinModifierPlugins.resolveVersion("eye/fitted", 2, null))

        // Verify installed third-party plugin with protocol 2 + version 2 works via ModifierPluginCatalog & Workers.
        val dir = Files.createTempDirectory("plugin-ver-test-")
        val prefs = Preferences.userRoot().node("plugin-ver-test/" + UUID.randomUUID())
        val store = ModifierPluginStore(dir.resolve("installed"), prefs)
        var installedRef: ModifierPluginReference? = null
        try {
            val zip = createPluginZip(dir, "thirdparty.eye", "thirdparty.eye/fitted", 2, protocol = 2)
            val installed = store.install(zip.toString())
            installedRef = installed.reference
            val instance = RigModifierInstance(
                id = "inst-1",
                type = "thirdparty.eye/fitted",
                version = 2,
                targets = listOf(ModifierTarget("mesh", "m1")),
                config = buildJsonObject { },
                plugin = installed.reference,
            )
            assertTrue(store.resolution(installed.reference).available)
            val result = ModifierPluginWorkers.evaluate(
                installed,
                installed.manifest.algorithms.single(),
                sampleRig(),
                instance,
                {},
                store,
            )
            assertEquals(0.5f, result.meshes.single().opacity)
        } finally {
            installedRef?.let(ModifierPluginWorkers::stop)
            prefs.removeNode()
            ModifierPluginStore.deleteTree(dir)
        }
    }

    @Test
    fun sharedWorkerUsesRefcountingAndProtocol2AvoidsTempFiles() {
        val dir = Files.createTempDirectory("plugin-shared-worker-")
        val prefs = Preferences.userRoot().node("plugin-shared-worker/" + UUID.randomUUID())
        val store = ModifierPluginStore(dir.resolve("installed"), prefs)
        var ref1: ModifierPluginReference? = null
        var ref2: ModifierPluginReference? = null
        try {
            val installed1 = store.install(createPluginZip(dir, "shared.one", "shared.one/op", 1, protocol = 2).toString())
            val installed2 = store.install(createPluginZip(dir, "shared.two", "shared.two/op", 1, protocol = 2).toString())
            ref1 = installed1.reference
            ref2 = installed2.reference
            val rig = sampleRig()
            val inst1 = RigModifierInstance("i1", "shared.one/op", 1, listOf(ModifierTarget("mesh", "m1")), plugin = installed1.reference)
            val inst2 = RigModifierInstance("i2", "shared.two/op", 1, listOf(ModifierTarget("mesh", "m1")), plugin = installed2.reference)

            val out1 = ModifierPluginWorkers.evaluate(installed1, installed1.manifest.algorithms.single(), rig, inst1, {}, store)
            val out2 = ModifierPluginWorkers.evaluate(installed2, installed2.manifest.algorithms.single(), rig, inst2, {}, store)
            assertEquals(0.25f, out1.meshes.single().opacity)
            assertEquals(0.25f, out2.meshes.single().opacity)

            // Uninstalling package 1 stops only ref1; pooled worker stays alive for ref2 and pkg1 directory can be deleted.
            store.uninstall(installed1.reference)
            assertFalse(Files.exists(installed1.directory))
            val out2After = ModifierPluginWorkers.evaluate(installed2, installed2.manifest.algorithms.single(), rig, inst2, {}, store)
            assertEquals(0.25f, out2After.meshes.single().opacity)

            store.uninstall(installed2.reference)
            assertFalse(Files.exists(installed2.directory))
        } finally {
            ref1?.let(ModifierPluginWorkers::stop)
            ref2?.let(ModifierPluginWorkers::stop)
            prefs.removeNode()
            ModifierPluginStore.deleteTree(dir)
        }
    }
}
