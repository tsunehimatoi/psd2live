package io.github.psd2live.core.modifier

import io.github.psd2live.algorithm.worker.AlgorithmChanges
import io.github.psd2live.algorithm.worker.AlgorithmObjects
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.UUID
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.prefs.Preferences
import javax.tools.ToolProvider
import kotlin.test.*

class ModifierRasterWorkerTest {
    @Test fun installedWorkerReturnsPixelsAndGeometryThroughTheHostResourceBoundary() {
        val root = Files.createTempDirectory("raster-worker-")
        val preferences = Preferences.userRoot().node("psd2live-test/raster/${UUID.randomUUID()}")
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        var reference: ModifierPluginReference? = null
        try {
            val pack = Files.createDirectory(root.resolve("package"))
            val instance = RigModifierInstance("raster", "raster-test/art", 1, listOf(ModifierTarget("mesh", "owner")))
            val geometry = MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Floats.values(0f, 0f, 2f, 0f, 0f, 2f), Ints.values(0, 1, 2))
            val owner = Mesh("owner", "Owner", null, geometry = geometry, offsets = null)
            val input = RigIR(Canvas(8f, 8f), emptyList(), meshes = listOf(owner), rootChildren = listOf(ChildRef.MeshRef("owner")))
            val raster = ModifierRasterOutput(owner, owner.copy(id = instance.outputId("mesh")),
                SourceLayer(instance.outputId("art"), "Art", "", 0, 0, 2, 2, true), ModifierAlgorithmResource(ByteArray(16) { -1 }))
            val wire = AlgorithmChanges.encode(ModifierModelChanges(rasters = listOf(raster)), allowRasters = true) {
                AlgorithmObjects.write(pack, it)
            }
            Files.writeString(pack.resolve("result.json"), wire.toString())
            // The installed executable uses only the JDK: it emits the portable binary-resource protocol.
            val source = pack.resolve("RasterWorker.java")
            Files.writeString(source, """
                import java.io.*;
                import java.nio.file.*;
                public class RasterWorker {
                    public static void main(String[] args) throws Exception {
                        Path pack = Path.of(RasterWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
                        Path resources = Path.of(System.getenv("PSD2LIVE_PLUGIN_RESOURCES"));
                        String result = Files.readString(pack.resolve("result.json"));
                        try (var files = Files.list(pack)) {
                            for (Path file : files.toList()) if (file.getFileName().toString().matches("[0-9a-f]{64}"))
                                Files.copy(file, resources.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                        }
                        var reader = new BufferedReader(new InputStreamReader(System.in));
                        int sequence = 0;
                        while (reader.readLine() != null)
                            System.out.println("{\"protocol\":1,\"id\":" + (++sequence) + ",\"changes\":" + result + "}");
                    }
                }
            """.trimIndent())
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, source.toString()))
            JarOutputStream(Files.newOutputStream(pack.resolve("worker.jar"))).use { jar ->
                jar.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
                jar.write("Manifest-Version: 1.0\r\nMain-Class: RasterWorker\r\n\r\n".toByteArray()); jar.closeEntry()
                jar.putNextEntry(JarEntry("RasterWorker.class")); jar.write(Files.readAllBytes(pack.resolve("RasterWorker.class"))); jar.closeEntry()
            }
            Files.delete(source); Files.delete(pack.resolve("RasterWorker.class"))
            val files = Files.list(pack).use { stream -> stream.toList().associate { it.fileName.toString() to ModifierPluginStore.hash(it) } }
            val algorithm = ModifierPluginAlgorithm(instance.type, name = "Raster", writes = setOf("mesh", "part", "hierarchy", "textures", "authoring"),
                configSchema = buildJsonObject { put("type", "object"); put("additionalProperties", false) }, defaults = JsonObject(emptyMap()))
            val manifest = ModifierPluginManifest(id = "raster-test", version = "1.0.0", name = "Raster",
                commands = mapOf("jvm" to listOf("\$JAVA", "-jar", "worker.jar")), files = files, algorithms = listOf(algorithm))
            Files.writeString(pack.resolve("plugin.json"), Json.encodeToString(ModifierPluginManifest.serializer(), manifest))
            val entry = store.install(pack.toString()); reference = entry.reference
            var resolved = 0
            val resolver: ModifierRasterResolver = { model, outputs, checkpoint ->
                resolved++
                RigModifierRasterAssets.attach(model, outputs, PipelineConfig(atlasSize = 64), checkpoint)
            }
            fun evaluate() = ModifierPluginWorkers.evaluate(entry, algorithm, input, instance.copy(plugin = entry.reference), {}, store,
                resolveRasters = resolver)
            val first = evaluate()
            assertEquals(first, evaluate())
            assertEquals(2, resolved)
            assertEquals(raster.mesh.id, first.meshes.last().id)
            assertEquals(16, first.textures.tileArt.single().rgba.size)
            assertTrue(first.textures.pages.single().png.size > 0)
            store.uninstall(entry.reference)
            assertFailsWith<IllegalArgumentException> { evaluate() }
            assertEquals(listOf(owner), input.meshes)
        } finally {
            reference?.let(ModifierPluginWorkers::stop)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}
