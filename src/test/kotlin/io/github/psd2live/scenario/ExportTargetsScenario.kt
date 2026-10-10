package io.github.psd2live.scenario

import io.github.psd2live.core.IrGeometryEvaluator
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.format.eval.NativeGeometryEvaluator
import io.github.psd2live.format.eval.P2lRuntime
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.png.PngCodec
import org.umamo.format.psd.PsdReader
import org.umamo.interop.cmo3.Cmo3Import
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test

/**
 * One authored character - skeleton, a user warp on a new axis, a motion - exported through every target a user can
 * pick. What the user saw is what each target holds: moc3 and cmo3 read back to the editor's deformation, exports are
 * byte-for-byte repeatable, the VTube Studio bundle references only files it wrote, the native runtime plays the
 * p2lrt like the editor, and the other targets write readable files.
 */
class ExportTargetsScenario {
	@TempDir lateinit var temp: Path

	@Test fun anAuthoredCharacterThroughEveryTarget() = Studio.run(temp, Characters.figure(), checks = setOf(Studio.Check.REPLAY)) {
		edit("apply the proposed skeleton", "skeleton_auto", req())
		structural("give the skirt a warp on a new axis",
			"canvas_warp" to req("id" to "SkirtFlare", "name" to "Skirt flare", "meshes" to listOf(mesh("skirt"))),
			"parameter_create" to req("parameter_id" to "ParamFlare", "name" to "Flare", "min" to 0, "max" to 1),
			"keyform_apply" to req("changes" to listOf(mapOf("target" to "warp:SkirtFlare", "key" to mapOf("ParamFlare" to 0), "op" to "seed"))),
			"rig_deform" to req("changes" to listOf(mapOf("target" to "warp:SkirtFlare", "key" to mapOf("ParamFlare" to 1),
				"operations" to listOf(mapOf("type" to "scale", "factors" to listOf(1.3, 1.0)))))))
		edits("add a motion and export settings", "motion_seed_builtin" to req("builtin" to "Wave", "id" to "wave"),
			"settings_update" to req("changes" to mapOf("exportMoc3" to true, "exportMotions" to true)))

		exportMatchesEditor(this)
		cubismCoreAccepts(this, export("moc3", name = "export moc3 for the Cubism Core").single { it.toString().endsWith(".moc3") })
		val first = export("moc3", name = "export moc3 again").associate { it.fileName.toString() to Files.readAllBytes(it) }
		val second = export("moc3", name = "export moc3 a third time").associate { it.fileName.toString() to Files.readAllBytes(it) }
		expect("repeatable", first.keys == second.keys && first.all { (name, bytes) -> bytes.contentEquals(second.getValue(name)) }) {
			"exporting twice wrote different files: " + first.keys.filter { !first.getValue(it).contentEquals(second[it]) }
		}

		val cmo3 = export("cmo3").single { it.toString().endsWith(".cmo3") }
		val readBack = step("read the cmo3 back") { Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(cmo3)).root as CModelSource) }
		exportMatchesEditor(this, readBack, "cmo3")

		val vts = export("vtube-studio")
		val model3 = vts.single { it.toString().endsWith(".model3.json") }
		val references = Json.parseToJsonElement(Files.readString(model3)).jsonObject.getValue("FileReferences").toString()
			.let { Regex("\"([^\"]+\\.(?:moc3|png|json))\"").findAll(it).map { m -> m.groupValues[1] }.toList() }
		expect("vtube studio", references.isNotEmpty() && references.all { Files.isRegularFile(model3.parent.resolve(it)) }) {
			"model3.json references missing files: ${references.filterNot { Files.isRegularFile(model3.parent.resolve(it)) }}"
		}

		val p2lrt = export("p2lrt").single { it.toString().endsWith(".p2lrt") }
		P2lRuntime.load()?.let { runtime ->
			val ir = RigIrCompiler.compile(now.model)
			val poses = listOf(emptyMap<String, Float>()) + ir.parameters.take(24).flatMap { listOf(mapOf(it.id to it.min), mapOf(it.id to it.max)) }
			runtime.load(Files.readAllBytes(p2lrt)).close()
			NativeGeometryEvaluator(runtime).open(ir).use { native -> IrGeometryEvaluator.open(ir).use { editor ->
				for (pose in poses) {
					val a = editor.evaluate(pose).positions; val b = native.evaluate(pose).positions
					val worst = a.entries.maxOf { (id, p) -> b[id]?.let { q -> p.indices.maxOf { abs(p[it] - q[it]) } } ?: Float.POSITIVE_INFINITY }
					expect("p2lrt", worst < 0.5f) { "at $pose the native runtime is $worst px from the editor" }
				}
			} }
		} ?: println("p2lrt: the native runtime is not built; its playback was not compared")

		for (target in listOf("spine", "dragonbones", "gltf", "web")) {
			val files = export(target)
			expect(target, files.isNotEmpty()) { "$target wrote nothing" }
			files.filter { it.toString().endsWith(".json") || it.toString().endsWith(".gltf") }.forEach { file ->
				step("parse ${file.fileName}") { Json.parseToJsonElement(Files.readString(file)) }
			}
		}
		val sheet = export("sprite-sheet", mapOf("size" to "64", "fps" to "4")).single { it.toString().endsWith(".png") }
		val image = PngCodec.read(Files.readAllBytes(sheet))
		expect("sprite sheet", image.rgba.indices.step(4).any { image.rgba[it + 3] != 0.toByte() }) { "the sprite sheet is blank" }
		val psd = export("psd-pose").single { it.toString().endsWith(".psd") }
		val layers = PsdReader.read(Files.readAllBytes(psd)).layers
		expect("psd pose", layers.size >= now.document.source.layers.size) { "the posed PSD has ${layers.size} layers" }
	}
}

/**
 * The official Cubism Core's consistency check (csmHasMocConsistency) accepts [moc3], when PSD2LIVE_TEST_CUBISM_CORE
 * names the Core library; the SDK is proprietary and not in the repository, so without it this only says so.
 */
internal fun cubismCoreAccepts(studio: Studio, moc3: Path) {
	val library = System.getenv("PSD2LIVE_TEST_CUBISM_CORE")?.takeIf { it.isNotBlank() }
		?: return println("Cubism Core: PSD2LIVE_TEST_CUBISM_CORE is not set; the moc3 was not checked by the Core")
	val core = com.sun.jna.Native.load(library, CubismCore::class.java)
	val bytes = Files.readAllBytes(moc3)
	// The Core reads a moc aligned to 64 bytes.
	com.sun.jna.Memory(bytes.size.toLong() + 63).use { memory ->
		val aligned = memory.share((64 - com.sun.jna.Pointer.nativeValue(memory) % 64) % 64)
		aligned.write(0, bytes, 0, bytes.size)
		studio.expect("Cubism Core", core.csmHasMocConsistency(aligned, bytes.size) == 1) { "the official Cubism Core rejects ${moc3.fileName}" }
	}
}

internal interface CubismCore : com.sun.jna.Library {
	fun csmHasMocConsistency(moc: com.sun.jna.Pointer, size: Int): Int
}
