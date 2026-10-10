package io.github.psd2live.tools

import io.github.psd2live.application.WorkspaceDocumentCommands
import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRuntime
import io.github.psd2live.application.WorkspaceSourceImporter
import io.github.psd2live.core.*
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.VertexGroup
import org.umamo.runtime.model.VertexGroupKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import io.github.psd2live.core.legacy.ReplayCheckpoints

/**
 * Skeleton commits on a large document: wall time and the rig builder's stages of creating, binding and moving
 * bones through the document command boundary, and whether each committed model equals a cold rebuild and replay.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*SkeletonCommitTool'
 * PSD2LIVE_SAMPLE picks the PSD (tml by default), PSD2LIVE_SCALE (default 2) upscales every layer's pixels, so the
 * document is that many times larger on each side; PSD2LIVE_GEOMETRY_EDITS (default 24) sets how many geometry commits
 * [profile] makes on the largest mesh before it times the skeleton commits. Writes build/tools/skeleton-commit/report.txt.
 */
class SkeletonCommitTool {
	private class Session(val runtime: WorkspaceRuntime<RigPreviewModel>, val commands: WorkspaceDocumentCommands)

	private fun session(): Session {
		val builder = WorkspacePreviewBuilder()
		lateinit var runtime: WorkspaceRuntime<RigPreviewModel>
		runtime = WorkspaceRuntime({ document -> builder.build(document, runtime.capture().model) })
		return Session(runtime, WorkspaceDocumentCommands(runtime))
	}

	private suspend fun Session.op(name: String, request: JsonObject, summary: String = name) {
		val before = runtime.capture()
		commands.execute(before.projectId, before.state, summary, listOf(WorkspaceDocumentOperation(name, request)), MutationAuthor.USER)
	}

	private suspend fun Session.journal(edit: JsonObject, summary: String) {
		val before = runtime.capture()
		commands.executeJournal(before.projectId, before.state, summary, JsonArray(listOf(edit)), MutationAuthor.USER)
	}

	private fun geometry(model: RigPreviewModel, id: String, round: Int): JsonObject {
		val current = model.rig.puppet.drawables.single { it.id.raw == id }.mesh!!.positions.copyOf()
		val delta = if (round % 2 == 0) 0.3f else -0.3f
		for (i in current.indices) current[i] += delta
		return buildJsonObject {
			put("op", "canvas_geometry"); put("kind", "mesh"); put("id", id)
			put("key", JsonObject(emptyMap())); put("pose", JsonObject(emptyMap())); put("preserve_image", true)
			put("points", JsonArray(current.map(::JsonPrimitive)))
		}
	}

	/** An imported, skeletal document with [geometryEdits] journal entries and a painted vertex group. */
	private suspend fun Session.setup(path: File, geometryEdits: Int, report: StringBuilder): String {
		var t = System.nanoTime()
		WorkspaceSourceImporter(runtime).importPsd(path.absoluteFile.toPath(), null, runtime.state.value.state, initialConfig = PipelineConfig())
		report.appendLine("import: %.0f ms".format(since(t)))
		t = System.nanoTime()
		op("skeleton_auto", JsonObject(emptyMap()))
		report.appendLine("skeleton_auto: %.0f ms".format(since(t)))
		val model = runtime.capture().model
		val target = model.rig.puppet.drawables.filter { it.mesh != null }.maxBy { it.mesh!!.positions.size }.id.raw
		t = System.nanoTime()
		repeat(geometryEdits) { journal(geometry(runtime.capture().model, target, it), "Geometry $it") }
		report.appendLine("$geometryEdits geometry commits: %.0f ms".format(since(t)))
		// A painted group: a skeleton commit on a document with one also migrates the painted weights.
		val skinned = runtime.capture().model
		val back = skinned.rig.puppet.drawables.filter { it.mesh != null && it.id.raw != target }.maxBy { it.mesh!!.positions.size }
		val world = CpuDeformationEvaluator().evaluate(skinned.rig.puppet, emptyMap()).worldPositions.getValue(back.id)
		val ys = (1 until world.size step 2).map { world[it] }
		val top = ys.max(); val bottom = ys.min()
		val pin = VertexGroup("pin", back.id, VertexGroupKind.PIN,
			FloatArray(world.size / 2) { if (world[it * 2 + 1] > top - (top - bottom) * 0.1f) 1f else 0f })
		journal(VertexGroupJournal.encode(pin), "Pin")
		val doc = runtime.capture().document
		report.appendLine("drawables=${skinned.rig.puppet.drawables.size} canvas=${doc.source.widthPx}x${doc.source.heightPx} " +
			"journal=${doc.rigEdits.authoringJournal.size} bones=${doc.rigEdits.skeleton!!.bones.size}")
		return target
	}

	private fun boneRequest(spec: SkeletonSpec, id: String, length: Float, drawables: List<String> = emptyList()) = buildJsonObject {
		val parent = spec.bones.first { it.role == BoneRole.UPPER_BODY }
		put("bone", buildJsonObject {
			put("id", id); put("name", id); put("parent", parent.id); put("role", "CUSTOM")
			putJsonArray("head") { add(parent.tailX); add(parent.tailY) }
			putJsonArray("tail") { add(parent.tailX + length * 0.3f); add(parent.tailY - length) }
			putJsonArray("drawables") { drawables.forEach { add(it) } }
		})
	}

	@Test fun profile() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val out = output("skeleton-commit")
		val factor = setting("PSD2LIVE_SCALE", "2").toInt()
		val path = scaledSample(sample, factor, out)
		val report = StringBuilder("# skeleton commits on ${sample.name} x$factor\n")
		val s = session()
		runBlocking {
			s.setup(path, setting("PSD2LIVE_GEOMETRY_EDITS", "24").toInt(), report)
			suspend fun timed(title: String, block: suspend () -> Unit) {
				RigBuildProfile.reset(); RigBuildProfile.recording = true
				val t = System.nanoTime()
				try { block() } finally { RigBuildProfile.recording = false }
				report.appendLine("$title: %.0f ms".format(since(t)))
				RigBuildProfile.snapshot().filterValues { it.first >= 5.0 || it.first == 0.0 }
					.forEach { (k, v) -> report.appendLine("    $k: %.0f ms (calls ${v.second})".format(v.first)) }
			}
			val unbound = s.runtime.capture().model.rig.puppet.drawables.filter { d ->
				d.mesh != null && s.runtime.capture().document.rigEdits.skeleton!!.bones.none { d.id.raw in it.drawableIds }
			}.minBy { it.mesh!!.positions.size }.id.raw
			for (round in 0 until 3) timed("create bone $round") {
				s.op("skeleton_bone", boneRequest(s.runtime.capture().document.rigEdits.skeleton!!, "perf_$round", 30f * factor + round))
			}
			timed("bind mesh to bone") { s.op("skeleton_bind", buildJsonObject { put("drawable_id", unbound); put("bone_id", "perf_0") }) }
			for (round in 0 until 2) timed("move joint $round") {
				val bone = s.runtime.capture().document.rigEdits.skeleton!!.bone("perf_1")!!
				s.op("skeleton_move", buildJsonObject {
					put("bone_id", "perf_1"); put("end", "tail")
					putJsonArray("point") { add(bone.tailX + 3f); add(bone.tailY - 2f) }
				})
			}
			// A joint of the limb that skins the largest bound mesh: that mesh, and every other one on its limb, rebake.
			val limb = run {
				val c = s.runtime.capture()
				val sizes = c.model.rig.puppet.drawables.associate { it.id.raw to (it.mesh?.positions?.size ?: 0) / 2 }
				val bone = c.document.rigEdits.skeleton!!.bones.filter { !it.role.body && !it.role.anchor && it.drawableIds.isNotEmpty() }
					.maxBy { b -> b.drawableIds.maxOf { sizes[it] ?: 0 } }
				report.appendLine("limb bone ${bone.id} (${bone.role}): meshes ${bone.drawableIds.map { "$it=${sizes[it]}" }}")
				bone.id
			}
			for (round in 0 until 2) timed("move limb joint $round") {
				val bone = s.runtime.capture().document.rigEdits.skeleton!!.bone(limb)!!
				s.op("skeleton_move", buildJsonObject {
					put("bone_id", limb); put("end", "tail")
					putJsonArray("point") { add(bone.tailX + 3f * factor); add(bone.tailY - 2f * factor) }
				})
			}
			timed("geometry commit after bones") {
				val target = s.runtime.capture().model.rig.puppet.drawables.filter { it.mesh != null }.maxBy { it.mesh!!.positions.size }.id.raw
				s.journal(geometry(s.runtime.capture().model, target, 1), "Geometry after")
			}
			// What matching replay checkpoints by the base's content would cost against what it could save.
			val model = s.runtime.capture().model
			report.appendLine("base content hash (IR): %.1f ms".format(mean(3) { ContentHash.of(PuppetIr.toIr(model.baseRig.puppet)) }))
			val was = ReplayCheckpoints.enabled; val reuse = GeneratorReuse.enabled
			ReplayCheckpoints.enabled = false; GeneratorReuse.enabled = false
			try {
				report.appendLine("full replay of ${model.config.rigEdits.authoringJournal.size} entries: %.1f ms".format(mean(3) {
					model.baseRig.withRigEdits(model.config.rigEdits, model.config.layerVisibility, model.config.drawOrderOverrides) }))
			} finally { ReplayCheckpoints.enabled = was; GeneratorReuse.enabled = reuse }
		}
		File(out, "report.txt").writeText(report.toString())
		println(report)
	}

	/** The committed model's content: the replayed rig, the base rig and the runtime files. */
	private fun digest(model: RigPreviewModel): List<String> = listOf(
		ContentHash.of(PuppetIr.toIr(model.rig.puppet)),
		ContentHash.of(PuppetIr.toIr(model.baseRig.puppet)),
		ContentHash.of(model.runtimeBundle.assets.map { it.path to ContentHash.of(it.bytes.toList()) }),
		model.config.toString().hashCode().toString(),
	)

	/**
	 * A fresh builder with every process-wide cache emptied, the stage caches off and no stored authored rig to build
	 * from: [document] generated and replayed.
	 */
	private fun cold(document: WorkspaceDocument): RigPreviewModel {
		val stages = RigStageCache.enabled; val checkpoints = ReplayCheckpoints.enabled; val reuse = GeneratorReuse.enabled
		RigStageCache.enabled = false; ReplayCheckpoints.enabled = false; GeneratorReuse.enabled = false
		SkeletonRig.clearCache()
		try { return withoutMaterializedRigs { runBlocking { WorkspacePreviewBuilder().build(document) } } }
		finally { RigStageCache.enabled = stages; ReplayCheckpoints.enabled = checkpoints; GeneratorReuse.enabled = reuse }
	}

	/**
	 * Skeleton, geometry and vertex group commits, then undo, redo and a switch to a side branch: after each
	 * step the runtime's model must equal a cold rebuild and replay of its document.
	 */
	@Test fun equivalence() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val out = output("skeleton-commit")
		val factor = setting("PSD2LIVE_SCALE", "1").toInt()
		val path = if (factor == 1) sample.path.toFile() else scaledSample(sample, factor, out)
		val report = StringBuilder()
		val s = session()
		var checks = 0
		fun check(step: String) {
			val capture = s.runtime.capture()
			val expected = digest(cold(capture.document))
			val actual = digest(capture.model)
			report.appendLine("$step: ${if (expected == actual) "equal" else "DIFFERENT $expected vs $actual"}")
			assertEquals(expected, actual, step)
			checks++
		}
		runBlocking {
			val target = s.setup(path, 6, report)
			check("setup")
			val history = mutableListOf(s.runtime.capture().historyHead)
			s.op("skeleton_bone", boneRequest(s.runtime.capture().document.rigEdits.skeleton!!, "eq_a", 40f)); check("create bone")
			history += s.runtime.capture().historyHead
			s.journal(geometry(s.runtime.capture().model, target, 0), "Geometry"); check("geometry after bone")
			history += s.runtime.capture().historyHead
			val unbound = s.runtime.capture().model.rig.puppet.drawables.filter { d ->
				d.mesh != null && s.runtime.capture().document.rigEdits.skeleton!!.bones.none { d.id.raw in it.drawableIds }
			}.minBy { it.mesh!!.positions.size }.id.raw
			s.op("skeleton_bind", buildJsonObject { put("drawable_id", unbound); put("bone_id", "eq_a") }); check("bind")
			history += s.runtime.capture().historyHead
			s.op("skeleton_move", buildJsonObject {
				val bone = s.runtime.capture().document.rigEdits.skeleton!!.bone("eq_a")!!
				put("bone_id", "eq_a"); put("end", "tail"); putJsonArray("point") { add(bone.tailX + 4f); add(bone.tailY) }
			}); check("move joint")
			history += s.runtime.capture().historyHead
			// The torso: the stance, the body frame and the deformers read it.
			s.op("skeleton_move", buildJsonObject {
				val torso = s.runtime.capture().document.rigEdits.skeleton!!.bones.first { it.role == BoneRole.UPPER_BODY }
				put("bone_id", torso.id); put("end", "tail"); putJsonArray("point") { add(torso.tailX + 3f); add(torso.tailY - 4f) }
			}); check("move the upper body")
			history += s.runtime.capture().historyHead
			suspend fun checkout(node: String, step: String) {
				val c = s.runtime.capture()
				s.runtime.checkout(c.projectId, c.state, node); check(step)
			}
			checkout(history[history.size - 2], "undo")
			checkout(history[history.size - 3], "undo twice")
			checkout(history.last(), "redo to head")
			checkout(history[1], "back to the bone")
			// A side branch from there, then back to the main line.
			s.op("skeleton_bone", boneRequest(s.runtime.capture().document.rigEdits.skeleton!!, "eq_b", 25f)); check("branch: another bone")
			s.journal(geometry(s.runtime.capture().model, target, 1), "Geometry on branch"); check("branch: geometry")
			checkout(history.last(), "switch to the main line")
			checkout(history[0], "switch to before the bones")
			s.op("skeleton_remove", buildJsonObject { put("bone_id", s.runtime.capture().document.rigEdits.skeleton!!.bones.first { !it.role.anchor && !it.role.body }.id) })
			check("remove a bone")
		}
		File(out, "equivalence.txt").writeText(report.toString())
		println(report)
		println("equivalence: $checks checks equal")
	}
}
