package io.github.psd2live.scenario

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.legacy.ReplayCheckpoints
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Drives one workspace the way the editor and agents do: every edit is a named document operation committed through
 * [WorkspaceDocumentCommands], undo and redo are history checkouts, and a save is a real `.psd2live` archive opened by a
 * cold build. After each edit the [Oracles] check what must hold whatever the edit was; a failure names the step and
 * the steps before it.
 */
internal class Studio(private val temp: Path, private val checks: Set<Check> = Check.EVERY_EDIT) : AutoCloseable {
	enum class Check {
		/** A cold build of the committed document is the committed model. */
		REPLAY,
		/** No quality finding at warning level or above, other than those [allow] admits. */
		QUALITY,
		/** Undoing the edit shows the model before it, and redoing it the model after it. */
		UNDO;

		companion object { val EVERY_EDIT = entries.toSet() }
	}

	val builder = WorkspacePreviewBuilder()
	val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { document, previous -> builder.build(document, previous) })
	val commands = WorkspaceDocumentCommands(runtime)
	private val trail = ArrayList<String>()
	private val allowed = HashSet<String>()
	private val opened = ArrayList<Path>()
	private var saves = 0

	val now: WorkspaceCapture<RigPreviewModel> get() = runtime.capture()
	val puppet: PuppetModel get() = now.model.rig.puppet

	/** Quality codes this scenario expects to see reported (a conflict it provokes, say). */
	fun allow(vararg codes: String) { allowed += codes }

	// ---- Opening ----------------------------------------------------------------------------------------------

	fun open(document: WorkspaceDocument): Studio = apply {
		step("open document") { runtime.install(runtime.state.value.state, "project", document, builder.build(document)) }
	}

	fun importPsd(path: Path, config: PipelineConfig): Studio = apply {
		step("import ${path.fileName}") { WorkspaceSourceImporter(runtime).importPsd(path, null, runtime.state.value.state, initialConfig = config) }
	}

	fun importCmo3(path: Path, config: PipelineConfig = PipelineConfig(atlasSize = 512)): Studio = apply {
		step("import ${path.fileName}") {
			WorkspaceCmo3Importer(runtime).import(path, Cmo3ImportMode.NEW, null, runtime.state.value.state, MutationAuthor.USER, initialConfig = config)
		}
	}

	// ---- Steps ------------------------------------------------------------------------------------------------

	/** Runs [block] as the named step: any failure in it reports the step and the trail that led there. */
	fun <T> step(name: String, block: suspend () -> T): T {
		trail += name
		return try { runBlocking { block() } } catch (failure: Throwable) { throw failed(name, failure) }
	}

	private fun failed(name: String, failure: Throwable): AssertionError = if (failure is ScenarioFailure) failure else
		ScenarioFailure("Step ${trail.size} '$name' failed: ${failure.message ?: failure.javaClass.simpleName}\n  after: ${recent(trail.dropLast(1))}", failure)

	/** The last steps of [steps], for a failure message. */
	private fun recent(steps: List<String>) = (if (steps.size > 10) listOf("... ${steps.size - 10} earlier") + steps.takeLast(10) else steps).joinToString(" -> ")

	/** Asserts [condition] as part of step [name], with [message] describing what was expected. */
	fun expect(name: String, condition: Boolean, message: () -> String) {
		if (!condition) throw ScenarioFailure("Step '$name': ${message()}\n  after: ${recent(trail)}", null)
	}

	// ---- Edits --------------------------------------------------------------------------------------------------

	/** One operation, committed as the user's edit named [name], then checked. */
	fun edit(name: String, operation: String, request: JsonObject): WorkspaceCapture<RigPreviewModel> = edits(name, operation to request)

	/** Operations committed together, as one batch and one history node. */
	fun edits(name: String, vararg operations: Pair<String, JsonObject>, author: MutationAuthor = MutationAuthor.USER): WorkspaceCapture<RigPreviewModel> {
		val before = now
		val commit = timed("edits") { step(name) {
			commands.execute(before.projectId, before.state, name, operations.map { WorkspaceDocumentOperation(it.first, it.second) }, author)
		} }
		verify(name, before, commit.applied)
		return commit.capture
	}

	/**
	 * A structural edit (a new deformer, a re-parenting, a glue): committed like [edits], and every mesh must show at
	 * rest exactly where it showed before it.
	 */
	fun structural(name: String, vararg operations: Pair<String, JsonObject>): WorkspaceCapture<RigPreviewModel> {
		val rest = CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions
		val capture = edits(name, *operations)
		val now = CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions
		val moved = rest.mapNotNull { (id, p) -> now[id]?.takeIf { it.size == p.size }?.let { q -> id.raw to p.indices.maxOf { abs(p[it] - q[it]) } } }
			.filter { it.second > 0.05f }
		expect(name, moved.isEmpty()) { "a structural edit moved meshes at rest: ${moved.joinToString { "${it.first} by ${"%.2f".format(it.second)} px" }}" }
		return capture
	}

	/** Raw journal records committed as a canvas gesture commits them. */
	fun journal(name: String, records: JsonArray): WorkspaceCapture<RigPreviewModel> {
		val before = now
		val commit = step(name) { commands.executeJournal(before.projectId, before.state, name, records, MutationAuthor.USER) }
		verify(name, before, commit.applied)
		return commit.capture
	}

	/** Update Generated Rig; whether it changed anything. */
	fun updateGenerated(name: String = "update generated rig"): Boolean {
		val before = now
		val result = step(name) { WorkspaceGenerationUpdateCommands(runtime).execute(before.projectId, before.state, name, MutationAuthor.USER) }
		verify(name, before, result.commit.applied)
		return result.result.getValue("updated").jsonPrimitive.boolean
	}

	/** Image files imported as new layers, optionally under [parent]; the new layer IDs. */
	fun importImages(name: String, files: List<Path>, parent: String? = null): List<String> {
		val before = now
		val result = step(name) { WorkspaceImageLayerCommands(runtime).importImages(before.projectId, before.state, files, parent, name, MutationAuthor.USER) }
		verify(name, before, result.commit.applied)
		return result.mutation.affectedLayerIds
	}

	/** An edit expected to be refused: it fails, and the workspace and history are as they were. */
	fun refused(name: String, operation: String, request: JsonObject): Throwable {
		val before = now; val history = runtime.history()
		trail += name
		val failure = runCatching { runBlocking {
			commands.execute(before.projectId, before.state, name, listOf(WorkspaceDocumentOperation(operation, request)), MutationAuthor.USER)
		} }.exceptionOrNull()
		expect(name, failure != null) { "the edit was expected to be refused" }
		expect(name, now == before && runtime.history() == history) { "a refused edit published a change" }
		return failure!!
	}

	private fun verify(name: String, before: WorkspaceCapture<RigPreviewModel>, applied: Boolean) {
		// Undo first, while the builds of both nodes are still cached; the replay check then forgets them.
		if (Check.UNDO in checks && applied) timed("check undo") { Oracles.undoes(this, name, before) }
		if (Check.QUALITY in checks) timed("check quality") { Oracles.quality(this, name, allowed) }
		if (Check.REPLAY in checks) timed("check replay") { Oracles.replays(this, name) }
	}

	private val timings = LinkedHashMap<String, Long>()

	/** Runs [block], adding its time to [phase] in the summary printed when the studio closes. */
	fun <T> timed(phase: String, block: () -> T): T {
		val start = System.nanoTime()
		try { return block() } finally { timings[phase] = (timings[phase] ?: 0L) + System.nanoTime() - start }
	}

	// ---- History ----------------------------------------------------------------------------------------------

	fun head() = runtime.history().let { history -> history.selections.single { it.node.id == history.headNodeId }.node }

	fun undo(name: String = "undo"): WorkspaceCapture<RigPreviewModel> = step(name) {
		val capture = now
		runtime.checkout(capture.projectId, capture.state, requireNotNull(head().parentId) { "nothing to undo" })
	}

	/** Redo goes to the only child; [child] picks a branch. */
	fun redo(name: String = "redo", child: String? = null): WorkspaceCapture<RigPreviewModel> = step(name) {
		val capture = now
		val children = runtime.history().selections.map { it.node }.filter { it.parentId == capture.historyHead }
		runtime.checkout(capture.projectId, capture.state, child ?: children.single().id)
	}

	fun checkout(name: String, nodeId: String): WorkspaceCapture<RigPreviewModel> = step(name) {
		val capture = now
		runtime.checkout(capture.projectId, capture.state, nodeId)
	}

	// ---- Project files ----------------------------------------------------------------------------------------

	/** Saves to a new archive and returns its path; nothing about the workspace changes. */
	fun save(name: String = "save"): Path = step(name) {
		val capture = now
		val index = ++saves
		val store = WorkspaceStore(temp.resolve("store-$index"))
		val file = temp.resolve("project-$index.psd2live")
		ProjectRepository(writeHeadCache = false).save(ProjectSaveCapture(capture.projectId, runtime.history(), JsonObject(emptyMap()), null, store,
			auxiliary = capture.auxiliary), file)
		file
	}

	/**
	 * Saves, forgets every in-memory rig, opens the archive with a cold build and continues on it. The reopened
	 * workspace must show exactly what was saved: the same rig, document revision and history.
	 */
	fun reopen(name: String = "save and reopen"): WorkspaceCapture<RigPreviewModel> = timed("reopen") {
		val file = save("$name (save)")
		val live = now; val history = runtime.history()
		step(name) {
			forgetBuilds()
			val project = ProjectRepository().open(file)
			opened.add(project.transferDirectory())
			val head = project.history.head().snapshot
			val model = WorkspacePreviewBuilder().build(head)
			Oracles.sameRig(name, "reopened", live.model.rig.puppet, model.rig.puppet)
			expect(name, WorkspaceRevisions.of(head) == WorkspaceRevisions.of(live.document)) {
				"the reopened document is another revision; fields whose text changed: ${Oracles.textDifference(live.document, head)}"
			}
			expect(name, project.history.state().selections.map { it.node } == history.selections.map { it.node }) { "the reopened history differs" }
			runtime.install(live.state, project.projectId, head, model, project.history.state(), live.auxiliary, discardUnsaved = true)
		}
	}

	// ---- Export -------------------------------------------------------------------------------------------------

	/** Exports [target] into a fresh directory; the files written. */
	fun export(target: String, settings: Map<String, String> = emptyMap(), name: String = "export $target"): List<Path> = timed("export") { step(name) {
		val directory = Files.createTempDirectory(temp, target)
		val result = WorkspaceExportSession(now, "Figure.psd").target(target, directory.toAbsolutePath(), settings)
		result.getValue("files").jsonArray.map { directory.resolve(it.jsonPrimitive.content) }
	} }

	/** The exported moc3 read back as a runtime would load it. */
	fun moc3(name: String = "export moc3"): PuppetModel {
		val file = export("moc3", name = name).single { it.toString().endsWith(".moc3") }
		return step("$name (read back)") { Moc3Import.fromMocDocument(Moc3.read(Files.readAllBytes(file)), null) }
	}

	// ---- Observation ------------------------------------------------------------------------------------------

	/** The drawable generated for source layer [layer]. */
	fun mesh(layer: String): String = now.model.rig.layerIdByDrawableId.entries.singleOrNull { it.value == layer }?.key
		?: throw ScenarioFailure("No single mesh for layer $layer: ${now.model.rig.layerIdByDrawableId.filterValues { it == layer }.keys}", null)

	fun meshes(layer: String): List<String> = now.model.rig.layerIdByDrawableId.filterValues { it == layer }.keys.sorted()

	fun inspect(scope: String = "project"): JsonObject = WorkspaceReadSession(runtime.read()).inspect(buildJsonObject { put("scope", scope) })

	/** Mean distance [mesh]'s vertices move when [parameter] goes from its default to [value]. */
	fun motion(mesh: String, parameter: String, value: Float, puppet: PuppetModel = this.puppet): Double = Oracles.motion(puppet, mesh, mapOf(parameter to value))

	/** [mesh]'s canvas bounds `minX, minY, maxX, maxY` (y down) as shown at [pose]. */
	fun bounds(mesh: String, pose: Map<String, Float> = emptyMap()): FloatArray {
		val p = CpuDeformationEvaluator().evaluate(puppet, pose.mapKeys { ParameterId(it.key) }).worldPositions.getValue(DrawableId(mesh))
		// The evaluator's world space has y up.
		val xs = p.filterIndexed { i, _ -> i % 2 == 0 }; val ys = p.filterIndexed { i, _ -> i % 2 == 1 }.map { -it }
		return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
	}

	fun hash(model: RigPreviewModel = now.model): String = Oracles.hash(model.rig.puppet)

	fun forgetBuilds() {
		MaterializedRigStore.clear(); ReplayCheckpoints.clear(); SkeletonRig.clearCache()
	}

	override fun close() {
		if (timings.isNotEmpty()) println("Studio time: " + timings.entries.joinToString { "${it.key} ${it.value / 1_000_000} ms" })
		forgetBuilds()
		opened.forEach { runCatching { ProjectArchive.deleteTemporaryDirectory(it) } }
	}

	companion object {
		/** A scenario on [document] in [temp]; the studio is closed when [body] returns. */
		fun run(temp: Path, document: WorkspaceDocument? = null, checks: Set<Check> = Check.EVERY_EDIT, body: Studio.() -> Unit) {
			Studio(temp, checks).use { studio ->
				document?.let(studio::open)
				studio.body()
			}
		}
	}
}

internal class ScenarioFailure(message: String, cause: Throwable?) : AssertionError(message, cause)

/** The checks every edit is held to, independent of what the edit did. */
internal object Oracles {
	fun hash(puppet: PuppetModel): String = ContentHash.of(PuppetIr.toIr(puppet))

	/** A cold build of the committed document is the model the edit committed: nothing lives only in the model. */
	fun replays(studio: Studio, step: String) {
		val capture = studio.now
		studio.forgetBuilds()
		val cold = runCatching { runBlocking { WorkspacePreviewBuilder().build(capture.document) } }.getOrElse {
			throw ScenarioFailure("Step '$step': the committed document does not build cold: ${it.message}", it)
		}
		sameRig(step, "cold build", capture.model.rig.puppet, cold.rig.puppet)
	}

	/** No quality finding at warning level or above outside [allowed]. */
	fun quality(studio: Studio, step: String, allowed: Set<String>) {
		val quality = studio.inspect().getValue("quality").jsonObject
		val findings = quality.entries.flatMap { (section, report) ->
			report.jsonObject["findings"]?.jsonArray.orEmpty().map { section to it.jsonObject }
		}.filter { (_, f) -> f["severity"]?.jsonPrimitive?.content in setOf("warning", "error") && f["code"]?.jsonPrimitive?.content !in allowed }
		if (findings.isNotEmpty()) throw ScenarioFailure("Step '$step': quality findings: " +
			findings.joinToString("; ") { (section, f) -> "$section $f" }, null)
	}

	/** Undo shows the model [before] the edit; redo shows the edit again. */
	fun undoes(studio: Studio, step: String, before: WorkspaceCapture<RigPreviewModel>) {
		val after = studio.now
		val undone = studio.undo("$step (undo)")
		sameRig(step, "undone", before.model.rig.puppet, undone.model.rig.puppet)
		if (undone.document != before.document) throw ScenarioFailure("Step '$step': undo shows another document", null)
		val redone = studio.redo("$step (redo)", child = after.historyHead)
		sameRig(step, "redone", after.model.rig.puppet, redone.model.rig.puppet)
	}

	/** [actual] is [expected], or a failure naming the first objects that differ and how. */
	fun sameRig(step: String, what: String, expected: PuppetModel, actual: PuppetModel) {
		if (hash(expected) == hash(actual)) return
		throw ScenarioFailure("Step '$step': the $what rig differs from the committed one:\n    " + difference(expected, actual).joinToString("\n    "), null)
	}

	/** Human-readable differences between two rigs: objects added or removed, reparented, reshaped, moved. */
	fun difference(expected: PuppetModel, actual: PuppetModel, limit: Int = 12): List<String> {
		val out = ArrayList<String>()
		val a = expected.parameters.associateBy { it.id }; val b = actual.parameters.associateBy { it.id }
		(a.keys - b.keys).forEach { out += "parameter ${it.raw} missing" }
		(b.keys - a.keys).forEach { out += "parameter ${it.raw} extra" }
		a.keys.intersect(b.keys).forEach { id ->
			val x = a.getValue(id); val y = b.getValue(id)
			if (x.min != y.min || x.max != y.max || x.default != y.default) out += "parameter ${id.raw} range ${x.min}..${x.max}/${x.default} -> ${y.min}..${y.max}/${y.default}"
		}
		val da = expected.deformers.associateBy { it.id }; val db = actual.deformers.associateBy { it.id }
		(da.keys - db.keys).forEach { out += "deformer ${it.raw} missing" }
		(db.keys - da.keys).forEach { out += "deformer ${it.raw} extra" }
		da.keys.intersect(db.keys).forEach { id ->
			val x = da.getValue(id); val y = db.getValue(id)
			if (x.parent != y.parent) out += "deformer ${id.raw} parent ${x.parent?.raw} -> ${y.parent?.raw}"
			else if (x != y) out += "deformer ${id.raw} keyforms differ"
		}
		val ma = expected.drawables.associateBy { it.id }; val mb = actual.drawables.associateBy { it.id }
		(ma.keys - mb.keys).forEach { out += "mesh ${it.raw} missing" }
		(mb.keys - ma.keys).forEach { out += "mesh ${it.raw} extra" }
		val restA = runCatching { CpuDeformationEvaluator().evaluate(expected, emptyMap()).worldPositions }.getOrNull()
		val restB = runCatching { CpuDeformationEvaluator().evaluate(actual, emptyMap()).worldPositions }.getOrNull()
		ma.keys.intersect(mb.keys).forEach { id ->
			val x = ma.getValue(id); val y = mb.getValue(id)
			when {
				x.parentDeformerId != y.parentDeformerId -> out += "mesh ${id.raw} parent ${x.parentDeformerId?.raw} -> ${y.parentDeformerId?.raw}"
				x.mesh?.vertexCount != y.mesh?.vertexCount -> out += "mesh ${id.raw} vertices ${x.mesh?.vertexCount} -> ${y.mesh?.vertexCount}"
				else -> {
					val p = restA?.get(id); val q = restB?.get(id)
					val moved = if (p != null && q != null && p.size == q.size) p.indices.maxOfOrNull { abs(p[it] - q[it]) } ?: 0f else 0f
					if (moved > 1e-3f) out += "mesh ${id.raw} moves ${"%.3f".format(moved)} px at rest"
					else if (x.geometryGrid?.axes?.map { it.parameterId } != y.geometryGrid?.axes?.map { it.parameterId })
						out += "mesh ${id.raw} keyform axes ${x.geometryGrid?.axes?.map { it.parameterId.raw }} -> ${y.geometryGrid?.axes?.map { it.parameterId.raw }}"
					else if (x.blendShapes.map { it.parameterId } != y.blendShapes.map { it.parameterId })
						out += "mesh ${id.raw} blend shapes ${x.blendShapes.map { it.parameterId.raw }} -> ${y.blendShapes.map { it.parameterId.raw }}"
					else if (x != y) out += "mesh ${id.raw} differs (keyforms, channels or texture)"
				}
			}
		}
		if (expected.glues != actual.glues) out += "glues differ"
		if (out.isEmpty()) out += "rigs differ outside parameters, deformers, meshes and glue (parts, physics, motions or metadata)"
		return if (out.size <= limit) out else out.take(limit) + "... ${out.size - limit} more"
	}

	/**
	 * The fields of two documents (and of their rig overlays) whose text differs: revisions hash the text, so a field
	 * equal in value but not in text (a set in another order) changes the revision.
	 */
	fun textDifference(a: WorkspaceDocument, b: WorkspaceDocument): List<String> {
		fun fields(x: Any, y: Any, prefix: String) = x.javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && '$' !in it.name }
			.mapNotNull { field ->
				field.isAccessible = true
				val p = field.get(x)?.toString(); val q = field.get(y)?.toString()
				if (p == q) null else {
					val at = (p.orEmpty().zip(q.orEmpty()).indexOfFirst { it.first != it.second }).let { if (it < 0) minOf(p.orEmpty().length, q.orEmpty().length) else it }
					"$prefix${field.name} (${if (field.get(x) == field.get(y)) "equal values, other text" else "other value"}): ..." +
						p.orEmpty().drop(maxOf(0, at - 60)).take(160) + " -> ..." + q.orEmpty().drop(maxOf(0, at - 60)).take(160)
				}
			}
		return fields(a.copy(source = b.source, generationSource = b.generationSource, meshSource = b.meshSource), b, "") .filter { !it.startsWith("rigEdits") } +
			fields(a.rigEdits, b.rigEdits, "rigEdits.")
	}

	/** Mean vertex displacement of [mesh] between the default pose and [pose]. */
	fun motion(puppet: PuppetModel, mesh: String, pose: Map<String, Float>): Double {
		val evaluator = CpuDeformationEvaluator()
		val rest = evaluator.evaluate(puppet, emptyMap()).worldPositions.getValue(DrawableId(mesh))
		val moved = evaluator.evaluate(puppet, pose.mapKeys { ParameterId(it.key) }).worldPositions.getValue(DrawableId(mesh))
		return (rest.indices step 2).sumOf { hypot((moved[it] - rest[it]).toDouble(), (moved[it + 1] - rest[it + 1]).toDouble()) } / (rest.size / 2)
	}
}

/** JSON for a request, from Kotlin values: numbers, strings, booleans, lists, maps and JSON elements. */
internal fun req(vararg fields: Pair<String, Any?>): JsonObject = JsonObject(fields.associate { (k, v) -> k to json(v) })

internal fun json(value: Any?): JsonElement = when (value) {
	null -> JsonNull
	is JsonElement -> value
	is String -> JsonPrimitive(value)
	is Number -> JsonPrimitive(value)
	is Boolean -> JsonPrimitive(value)
	is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to json(it.value) })
	is Iterable<*> -> JsonArray(value.map(::json))
	is IntArray -> JsonArray(value.map(::JsonPrimitive))
	is FloatArray -> JsonArray(value.map(::JsonPrimitive))
	else -> error("Not JSON: $value")
}
