package example

import io.github.psd2live.algorithm.worker.AlgorithmChanges
import io.github.psd2live.algorithm.worker.AlgorithmContexts
import io.github.psd2live.algorithm.worker.AlgorithmObjects
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.ModifierPluginManifest
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import io.github.psd2live.algorithms.binding.Bounds
import io.github.psd2live.algorithms.binding.MouthCurve
import io.github.psd2live.algorithms.binding.MouthFit
import io.github.psd2live.algorithms.binding.MouthFittedAlgorithm
import io.github.psd2live.algorithms.binding.MouthFittedInputs
import io.github.psd2live.algorithms.binding.MouthFittedSourceAlgorithm
import io.github.psd2live.algorithms.binding.MouthOptions
import io.github.psd2live.algorithms.binding.MouthRig
import io.github.psd2live.algorithms.binding.MouthSourceContext
import io.github.psd2live.algorithms.binding.ScaffoldMesh
import io.github.psd2live.algorithms.binding.ScaffoldMeshContext
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.ModifierUpstreamCapability
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.*
import io.github.psd2live.bridge.PuppetIr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MouthFittedAlgorithmTest {
    @Test
    fun originalScaffoldVerticesAreUsedOnlyForAnUneditedMeshInTheCapturedFrame() {
        val frame = Bounds(-60f, 0f, 40f, 100f)
        val aperture = Bounds(-1f, 0f, 1f, 100f)
        val raw = floatArrayOf(1f / 13f, 45f, 0.5f, 65f, 0.8f, 45f)
        val uv = floatArrayOf(0f, 0f, 0.5f, 1f, 1f, 0f)
        val indices = intArrayOf(0, 1, 2)
        val resource = ScaffoldMeshContext.capture(listOf(ScaffoldMeshContext.Material("canonical", "source",
            ScaffoldMesh.RawMesh(raw, uv, indices), ScaffoldMesh.Geometry.Zero))).resources.getValue("source_meshes")
        val normalized = FloatArray(raw.size) { index ->
            if (index % 2 == 0) (raw[index] - frame.left) / frame.width else (raw[index] - frame.top) / frame.height
        }
        val geometry = MeshGeometry(Floats.of(normalized), Floats.of(uv), Ints.of(indices))
        val input = RigIR(Canvas(100f, 100f), listOf(
            Parameter("ParamMouthForm", "Form", -1f, 1f, 0f),
            Parameter("ParamMouthOpenY", "Open", 0f, 1f, 0f),
        ), meshes = listOf(Mesh("alias", "Alias", null, geometry = geometry, offsets = null)))
        val layer = MouthFittedInputs.Layer("MOUTH", null, "NONE", null, frame, aperture, 0f, 50f,
            opaque = true, clipped = false, preset = true)
        val inputs = MouthFittedInputs(null, 0.6f, null, closedMouth = false, separateInside = false, teethFade = 20f,
            layers = mapOf("alias" to layer), maskSources = emptyList(),
            fallback = MouthFittedInputs.Fallback(MouthCurve.preset("smile"), true, 100f, 0f, 50f, 0f, 0f),
            sourceVertices = MouthFittedInputs.SourceVertices(resource, mapOf("alias" to MouthFittedInputs.SourceVertexTarget("canonical", null))))
        val instance = RigModifierInstance("mouth", "mouth/fitted", 1, listOf(ModifierTarget("mesh", "alias")))
        fun offset(geometry: MeshGeometry, options: MouthFittedInputs): Float {
            val result = MouthFittedAlgorithm().evaluate(input.copy(meshes = listOf(input.meshes.single().copy(geometry = geometry))),
                instance, options.encode()) {}
            return result.meshes.single().offsets!!.cells.single { it.coordinate.toArray().contentEquals(intArrayOf(1, 0)) }.form.deltas[0]
        }
        val withoutRaw = inputs.copy(sourceVertices = null)
        assertEquals(-raw[0] / frame.width, offset(geometry, inputs))
        assertNotEquals(offset(geometry, withoutRaw), offset(geometry, inputs))

        val moved = geometry.copy(positions = Floats.of(normalized.copyOf().also { it[0] += 0.02f }))
        assertEquals(offset(moved, withoutRaw), offset(moved, inputs))
        val reordered = geometry.copy(indices = Ints.values(0, 2, 1))
        assertEquals(offset(reordered, withoutRaw), offset(reordered, inputs))
        val differentParent = inputs.copy(sourceVertices = MouthFittedInputs.SourceVertices(resource,
            mapOf("alias" to MouthFittedInputs.SourceVertexTarget("canonical", "old-parent"))))
        assertEquals(offset(geometry, withoutRaw), offset(geometry, differentParent))
    }

    @Test
    fun sourceCandidateMatchesTheBindingInputBeforeLowering() {
        val frame = Bounds(0f, 0f, 100f, 100f)
        val geometry = MeshGeometry(Floats.of(floatArrayOf(0.2f, 0.45f, 0.5f, 0.65f, 0.8f, 0.45f)),
            Floats.of(floatArrayOf(0f, 0f, 0.5f, 1f, 1f, 0f)), Ints.of(intArrayOf(0, 1, 2)))
        val meshes = listOf("open", "closed", "teeth", "untouched").map { Mesh(it, it, null, geometry = geometry, offsets = null) }
        val input = RigIR(Canvas(100f, 100f), listOf(
            Parameter("ParamMouthForm", "Form", -1f, 1f, 0f),
            Parameter("ParamMouthOpenY", "Open", 0f, 1f, 0f),
            Parameter("ParamAngleX", "Yaw", -30f, 30f, 0f),
            Parameter("ParamAngleY", "Pitch", -30f, 30f, 0f),
        ), meshes = meshes)
        val upper = List(MouthFit.SAMPLES) { i -> val u = MouthFit.u(i); -0.8f * u * (1f - u) }
        val lower = List(MouthFit.SAMPLES) { i -> val u = MouthFit.u(i); 1.2f * u * (1f - u) }
        val measurement = MouthSourceContext(MouthFit(20f, 50f, 1f, 0f, 60f, upper, lower), MouthOptions(reach = 1.2f), null)
        fun layer(tag: String) = MouthFittedInputs.Layer(tag, null, "NONE", null, frame, frame, 50f, 50f,
            opaque = true, clipped = false, preset = true, turnsWithHeadMesh = true)
        val context = MouthFittedInputs(measurement, 0.6f, null, closedMouth = true, separateInside = false, teethFade = 20f,
            layers = linkedMapOf("open" to layer("MOUTH_OPEN"), "closed" to layer("MOUTH_CLOSE"), "teeth" to layer("TOOTH_T")),
            maskSources = listOf("open"), head = MouthFittedInputs.Head("head", 50f, 50f, 50f, 50f, 0.45f, 1f, 100f))
        val targets = listOf("open", "closed", "teeth").map { ModifierTarget("mesh", it) }
        val instance = RigModifierInstance("mouth", "mouth/fitted", 1, targets)
        val algorithm = MouthFittedAlgorithm()
        var bindingInput = emptyList<Mesh>()
        algorithm.project(PuppetIr.toPuppet(input), targets.mapTo(linkedSetOf()) { it.id }, context,
            onBindingInput = { model -> bindingInput = model.drawables.map(PuppetIr::meshToIr) })
        val sourceAlgorithm = MouthFittedSourceAlgorithm()
        val provider = ModifierUpstreamCapability("face.depth-turn", "face.depth-turn", 1, targets, "head")
        val captured = context.encode().withUpstream(listOf(provider))
        val changes = sourceAlgorithm.changes(input, instance, captured) {}
        assertEquals(setOf("parameter", "mesh"), changes.writeKinds())
        System.getProperty("psd2live.mouthFittedPackage")?.let {
            assertEquals(changes.apply(input), packagedChanges(Path.of(it), input, instance, captured).apply(input))
        }
        val candidate = changes.apply(input)
        assertEquals(bindingInput.filter { it.id != "untouched" }, candidate.meshes.filter { it.id != "untouched" })
        assertTrue(candidate.meshes.single { it.id == "open" }.shapes.any { it.parameter == "ParamAngleX" })
        assertEquals(meshes.last(), candidate.meshes.last())

        val result = algorithm.evaluate(input, instance, context.encode()) {}
        val open = result.meshes.single { it.id == "open" }
        val closed = result.meshes.single { it.id == "closed" }
        val teeth = result.meshes.single { it.id == "teeth" }
        assertEquals(1.2f, result.parameters.single { it.id == "ParamMouthOpenY" }.max)
        assertEquals(listOf("open"), teeth.maskedBy)
        assertNotNull(open.offsets)
        assertTrue(open.shapes.any { it.parameter == "ParamAngleX" } ||
            result.deformers.isNotEmpty() || open.offsets!!.axes.any { it.parameter == "ParamAngleX" })
        assertEquals(listOf(0f, MouthRig.SWAP, 1f), closed.channels.getValue(Channel.OPACITY).axes.single().keys.toArray().toList())
        assertEquals(meshes.last(), result.meshes.single { it.id == "untouched" })
    }

    private fun packagedChanges(packageDirectory: Path, input: RigIR, instance: RigModifierInstance,
                                context: ModifierAlgorithmContext): ModifierModelChanges {
        val manifest = Json.decodeFromString<ModifierPluginManifest>(Files.readString(packageDirectory.resolve("plugin.json")))
        val algorithm = manifest.algorithms.single()
        val jar = packageDirectory.resolve("algorithm.jar")
        assertEquals(manifest.files.getValue("algorithm.jar"), AlgorithmObjects.hash(Files.readAllBytes(jar)))
        val resources = Files.createTempDirectory("mouth-fitted-worker")
        try {
            val write = { bytes: ByteArray -> AlgorithmObjects.write(resources, bytes) }
            val request = buildJsonObject {
                put("protocol", 1)
                put("id", "candidate")
                put("method", "evaluate")
                put("reset", true)
                put("capabilities", JsonArray(listOf(JsonPrimitive("rig_objects"), JsonPrimitive(AlgorithmChanges.CAPABILITY))))
                put("invocation", Json.encodeToJsonElement(instance.copy(type = algorithm.type)))
                put("changed", JsonObject(AlgorithmObjects.encode(input, write)))
                put("removed", JsonArray(emptyList()))
                put("mesh_order", JsonArray(input.meshes.map { JsonPrimitive(it.id) }))
                put("deformer_order", JsonArray(input.deformers.map { JsonPrimitive(it.id) }))
                put("context", AlgorithmContexts.encode(context, write))
            }
            val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            val responseFile = resources.resolve("response")
            val process = ProcessBuilder(java.toString(), "-jar", jar.toString()).redirectOutput(responseFile.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .apply { environment()["PSD2LIVE_PLUGIN_RESOURCES"] = resources.toString() }.start()
            try {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.appendLine(request.toString()) }
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Fitted mouth worker timed out")
                assertEquals(0, process.exitValue())
                val response = Json.parseToJsonElement(Files.readString(responseFile)).jsonObject
                assertEquals(JsonPrimitive("candidate"), response["id"])
                val encoded = assertNotNull(response["changes"], response["error"]?.toString()).jsonObject
                return AlgorithmChanges.decode(encoded, resources).also { it.requireWrites(algorithm.writes) }
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            }
        } finally {
            resources.toFile().deleteRecursively()
        }
    }
}
