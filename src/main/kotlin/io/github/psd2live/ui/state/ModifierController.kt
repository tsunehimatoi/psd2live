package io.github.psd2live.ui.state

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.ModifierAuthoring
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.ui.CanvasEditor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.PuppetModel

/**
 * The modifier panel's commits and its preview while a field is dragged. Commits run one after another, each on the
 * document as the one before left it; a preview is the same `modifier_put` on a candidate, shown on the active canvas
 * until the committed model arrives. No document solver lives here.
 */
internal class ModifierController(
	private val state: () -> PSD2LiveState,
	private val backend: () -> WorkspaceBackend?,
	private val scope: CoroutineScope,
	private val editorFor: (String) -> CanvasEditor,
	/** The status bar's task indicator: on while an edit runs, off when the latest one ends. */
	private val progress: (Boolean) -> Unit = {},
	/** Where a failed edit or preview is reported, beside the indicator. */
	private val failed: (String) -> Unit = {},
) : AutoCloseable {
	fun put(request: JsonObject) = edit(ModifierAuthoring.PUT, request)
	fun delete(id: String) = edit(ModifierAuthoring.DELETE, buildJsonObject { put("id", id) })
	fun move(id: String, to: String) = edit(ModifierAuthoring.MOVE, buildJsonObject { put("id", id); put("to", to) })
	fun moveBefore(id: String, before: String) = edit(ModifierAuthoring.MOVE, buildJsonObject { put("id", id); put("before", before) })
	fun apply(id: String, mode: String, pose: Map<String, Float> = emptyMap()) = edit(ModifierAuthoring.APPLY, buildJsonObject {
		put("id", id); put("mode", mode)
		if (pose.isNotEmpty()) putJsonObject("pose") { pose.forEach { (k, v) -> put(k, v) } }
	})

	/** Adds a modifier of [kind] on [meshes], with the kind's defaults for where they rest. */
	fun add(kind: String, meshes: List<String>) = put(buildJsonObject { put("kind", kind); putJsonArray("targets") { meshes.forEach { add(it) } } })
	fun split(id: String, mesh: String) = edit(ModifierAuthoring.SPLIT, buildJsonObject { put("id", id); put("mesh", mesh) })
	fun copy(id: String, meshes: List<String>) = edit(ModifierAuthoring.COPY, buildJsonObject { put("id", id); putJsonArray("targets") { meshes.forEach { add(it) } } })
	fun applyTo(id: String, mesh: String, mode: String, pose: Map<String, Float> = emptyMap()) = edit(ModifierAuthoring.APPLY, buildJsonObject {
		put("id", id); put("mode", mode); put("mesh", mesh)
		if (pose.isNotEmpty()) putJsonObject("pose") { pose.forEach { (k, v) -> put(k, v) } }
	})

	/** Several `modifier_*` edits as one step of history, each acting on what the ones before it left. */
	fun batch(operations: List<Pair<String, JsonObject>>) = edits(operations)

	private val store = ModifierPresetStore()
	internal var presets by androidx.compose.runtime.mutableStateOf(store.list())
		private set
	/** The mesh whose stack the save-as-preset dialog names, while it is open. */
	internal var presetDialog by androidx.compose.runtime.mutableStateOf<String?>(null)

	/** The panel's page: [OBJECT], the selection's stages, or [WORLD], what belongs to the whole model. */
	internal var page by androidx.compose.runtime.mutableStateOf(OBJECT)

	fun savePreset(name: String, modifiers: List<io.github.psd2live.core.modifier.RigModifier>) {
		runCatching { store.save(io.github.psd2live.core.modifier.ModifierPreset.of(name, modifiers)) }.onFailure { failed(it.message ?: it.toString()) }
		presets = store.list()
	}

	fun deletePreset(name: String) {
		runCatching { store.delete(name) }.onFailure { failed(it.message ?: it.toString()) }
		presets = store.list()
	}

	/** Puts [preset]'s stack on [meshes] in one step; parameters it made that [parameters] already has are reused. */
	fun applyPreset(preset: io.github.psd2live.core.modifier.ModifierPreset, meshes: List<String>, parameters: Set<String>) =
		batch(preset.requests(meshes, parameters).map { ModifierAuthoring.PUT to it })

	private var editJob: Job? = null
	private var pending = 0

	private fun edit(operation: String, request: JsonObject) = edits(listOf(operation to request))

	private fun edits(operations: List<Pair<String, JsonObject>>) {
		if (operations.isEmpty()) return
		val workspace = backend() ?: return
		val previous = editJob
		pending++
		progress(true)
		editJob = scope.launch {
			try {
				// One after another: a move right after an add acts on the added modifier.
				previous?.join()
				val expected = workspace.snapshot()
				withContext(Dispatchers.Default + WorkspaceExecution(expected.projectId, expected.state, MutationAuthor.USER)) {
					workspace.applyDocumentEdits(expected.state, "Modifier", operations.map { (operation, request) -> WorkspaceDocumentOperation(operation, request) },
						MutationAuthor.USER)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				dropPreview()
				failed(e.message ?: e.toString())
			} finally {
				if (--pending == 0) progress(false)
			}
		}
	}

	private var previewJob: Job? = null
	private var shown: Pair<CanvasEditor, PuppetModel>? = null

	/**
	 * Shows `modifier_put` [request] on the active canvas before it is committed: the candidate rig, sampled as the
	 * commit will sample it. Later requests replace earlier ones; the committed model takes over when it arrives.
	 */
	fun preview(request: JsonObject) {
		val captured = state()
		val preview = captured.previewModel ?: return
		val canvas = captured.activeCanvas.id
		previewJob?.cancel()
		previewJob = scope.launch {
			delay(40)
			try {
				val puppet = withContext(Dispatchers.Default) {
					runInterruptible {
						val overlay = ModifierAuthoring.put(preview, preview.config.rigEdits, request)
						preview.baseRig.withRigEdits(overlay, preview.config.layerVisibility, preview.config.drawOrderOverrides,
							SeeThroughLayers.Settings.of(preview.config)).puppet
					}
				}
				val current = state()
				if (current.previewModel !== preview || current.activeCanvas.id != canvas) return@launch
				val editor = editorFor(canvas)
				editor.preview = puppet
				shown = editor to puppet
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				failed(e.message ?: e.toString())
			}
		}
	}

	/** Withdraws the preview, if this controller's is still the one shown. */
	fun dropPreview() {
		previewJob?.cancel()
		previewJob = null
		shown?.let { (editor, puppet) -> if (editor.preview === puppet) editor.preview = null }
		shown = null
	}

	override fun close() {
		editJob?.cancel()
		dropPreview()
	}

	companion object {
		const val OBJECT = 0
		const val WORLD = 1
	}
}
