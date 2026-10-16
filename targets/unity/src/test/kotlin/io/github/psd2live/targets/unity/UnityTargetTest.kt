package io.github.psd2live.targets.unity

import io.github.psd2live.format.compile.Compiler
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.compile.GeometryEvaluator
import io.github.psd2live.format.compile.GeometrySession
import io.github.psd2live.format.compile.PngEncoder
import io.github.psd2live.format.compile.PoseGeometry
import io.github.psd2live.format.model.Bytes
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Clip
import io.github.psd2live.format.model.Curve
import io.github.psd2live.format.model.CurveSegment
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.KeyAxis
import io.github.psd2live.format.model.KeyCell
import io.github.psd2live.format.model.KeyGrid
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.MeshGeometry
import io.github.psd2live.format.model.MeshOffsets
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.TexturePage
import io.github.psd2live.format.model.Textures
import io.github.psd2live.targets.spine.MiniJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A textured quad whose one parameter slides it, exported as a Unity folder. */
class UnityTargetTest {
	private val rest = floatArrayOf(10f, 10f, 90f, 10f, 90f, 90f, 10f, 90f)

	@Test fun shapeNamesAreSafeTokens() {
		assertEquals("ParamX_1", unityShapeName("ParamX", 1f))
		assertEquals("ParamAngleX_n30", unityShapeName("ParamAngleX", -30f))
		assertEquals("Param_0p5", unityShapeName("Param", 0.5f))
		assertEquals("Param_Eye_1", unityShapeName("Param.Eye", 1f))
	}

	@Test fun exportsAPrefabFolderUnityCanImport() {
		val first = export()
		val second = export()
		assertEquals(first.keys, second.keys)
		for ((path, bytes) in first) assertContentEquals(bytes.toList(), second.getValue(path).toList())

		val document = MiniJson(first.getValue("Hero.p2lunity").decodeToString()).value().obj()
		assertEquals(1.0, document["version"].num())
		assertEquals("Hero", document["name"].str())
		assertEquals(100.0, document["pixelsPerUnit"].num())
		assertEquals("Textures/page_00.png", document["textures"].arr().single().obj()["file"].str())

		val mesh = document["meshes"].arr().single().obj()
		assertEquals("box", mesh["id"].str())
		val vertices = mesh["vertices"].arr().map { it.num() }
		assertClose(-0.4, vertices[0]); assertClose(0.9, vertices[1]); assertClose(0.0, vertices[2])
		val uvs = mesh["uvs"].arr().map { it.num() }
		assertClose(0.0, uvs[0]); assertClose(1.0, uvs[1])
		val shape = mesh["blendShapes"].arr().single().obj()
		assertEquals("ParamX_1", shape["name"].str())
		val delta = shape["delta"].arr().map { it.num() }
		assertClose(0.2, delta[0]); assertClose(0.0, delta[1]); assertClose(0.0, delta[2])

		val clip = document["clips"].arr().single().obj()
		assertEquals("Idle", clip["name"].str())
		assertEquals(true, clip["loop"])
		val track = clip["tracks"].arr().single().obj()
		val times = track["times"].arr().map { it.num() }
		val values = track["values"].arr().map { it.num() }
		val at = times.indexOfLast { it <= 0.5 }.coerceAtLeast(0)
		val next = (at + 1).coerceAtMost(times.lastIndex)
		val span = times[next] - times[at]
		val weight = if (span == 0.0) values[at] else values[at] + (values[next] - values[at]) * ((0.5 - times[at]) / span)
		assertClose(50.0, weight)
		assertEquals("param_ParamX", document["poses"].arr().single().obj()["name"].str())

		assertContentEquals(listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), first.getValue("Textures/page_00.png").take(4))
		val script = first.keys.single { it.endsWith("Psd2LiveUnityImporter.cs.txt") }
		assertTrue("ScriptedImporter" in first.getValue(script).decodeToString())
		assertTrue("psd2live-importer 8" in first.getValue(script).decodeToString())
		val setup = first.keys.single { "Psd2LiveUnitySetup_" in it && it.endsWith(".cs") }
		val setupId = setup.substringAfter("Psd2LiveUnitySetup_").removeSuffix(".cs")
		val setupSource = first.getValue(setup).decodeToString()
		assertTrue("class Psd2LiveUnitySetup_$setupId" in setupSource)
		assertTrue("class Psd2LiveUnityImporter" !in setupSource)
		assertTrue("Hero.p2lunity" in first.getValue("README.txt").decodeToString())

		val fixture = Path.of("build/unity-fixture")
		fixture.toFile().deleteRecursively()
		for ((path, bytes) in first) {
			val file = fixture.resolve(path)
			Files.createDirectories(file.parent)
			Files.write(file, bytes)
		}
	}

	private fun export(): Map<String, ByteArray> {
		val png = PngEncoder.encode(2, 2, intArrayOf(0xFFFF0000.toInt(), 0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt()))
		val ir = RigIR(
			Canvas(100f, 100f),
			listOf(Parameter("ParamX", "X", 0f, 1f, 0f)),
			meshes = listOf(Mesh(
				id = "box", name = "Box", parent = null,
				geometry = MeshGeometry(
					Floats.values(*rest),
					Floats.values(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
					Ints.values(0, 1, 2, 0, 2, 3),
				),
				offsets = KeyGrid(
					listOf(KeyAxis("ParamX", Floats.values(0f, 1f))),
					listOf(
						KeyCell(Ints.values(0), MeshOffsets(Floats.of(FloatArray(rest.size)))),
						KeyCell(Ints.values(1), MeshOffsets(Floats.values(20f, 0f, 20f, 0f, 20f, 0f, 20f, 0f))),
					),
				),
				page = 0,
			)),
			textures = Textures(pages = listOf(TexturePage(2, 2, Bytes.wrap(png)))),
			clips = listOf(Clip(
				id = "idle", name = "Idle", group = "Idle", file = "idle",
				duration = 1f, fps = 30f, loop = true,
				curves = listOf(Curve("ParamX", 0f, 0f, listOf(CurveSegment.Linear(1f, 1f)))),
			)),
		)
		val files = LinkedHashMap<String, ByteArray>()
		Compiler.export(UnityTarget(evaluator()), ir, ExportOptions("Hero", settings = mapOf("sample_pairs" to "0"))) { path, bytes ->
			files[path] = bytes
		}
		return files
	}

	private fun evaluator() = object : GeometryEvaluator {
		override fun open(ir: RigIR): GeometrySession = object : GeometrySession {
			override fun evaluate(parameters: Map<String, Float>): PoseGeometry {
				val slide = parameters["ParamX"] ?: 0f
				val posed = FloatArray(rest.size) { if (it % 2 == 0) rest[it] + 20f * slide else rest[it] }
				return PoseGeometry(mapOf("box" to posed), mapOf("box" to 1f), mapOf("box" to 500f))
			}
			override fun close() {}
		}
	}

	private fun assertClose(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 1e-4, "$actual is not $expected")
	private fun Any?.num() = this as Double
	private fun Any?.str() = this as String
	@Suppress("UNCHECKED_CAST") private fun Any?.obj() = this as Map<String, Any?>
	@Suppress("UNCHECKED_CAST") private fun Any?.arr() = this as List<Any?>
}
