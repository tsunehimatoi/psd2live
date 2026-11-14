package io.github.psd2live.ui.state

import io.github.psd2live.application.WorkspaceDocumentPort
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceMutationResult
import io.github.psd2live.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*

/** Material/reference commands and pointer drafts; the runtime remains the document owner. */
internal class MaterialController(
    private val state: () -> PSD2LiveState,
    private val documentPort: () -> WorkspaceDocumentPort?,
    private val fieldsOpen: () -> Boolean,
    private val runCommand: (() -> Unit, suspend (String) -> WorkspaceMutationResult) -> Unit,
    private val setStatus: (String) -> Unit,
    private val selectReference: (String?) -> Unit,
    private val toggleLayerVisibility: (String) -> Unit,
    private val deleteLayer: (String) -> Unit,
    private val fileDrops: FileDropRouter,
    private val perform: (CarriedFiles, FileDropIntent?) -> Unit,
) : AutoCloseable {
    private fun runWorkspaceCommand(onFinished: () -> Unit = {}, action: suspend (String) -> WorkspaceMutationResult) = runCommand(onFinished, action)

	private val _materialDrag = kotlinx.coroutines.flow.MutableStateFlow<PSD2LiveViewModel.MaterialDrag?>(null)
	val materialDrag: kotlinx.coroutines.flow.StateFlow<PSD2LiveViewModel.MaterialDrag?> = _materialDrag

	/**
	 * Where a material dropped at a root position lands on each edit canvas, in canvas units; null off the canvas.
	 * Each edit canvas registers its own while it shows.
	 */
	val materialDropTargets = java.util.concurrent.ConcurrentHashMap<String, (androidx.compose.ui.geometry.Offset) -> androidx.compose.ui.geometry.Offset?>()

	fun startMaterialDrag(id: String, label: String, position: androidx.compose.ui.geometry.Offset) {
		_materialDrag.value = PSD2LiveViewModel.MaterialDrag(id, label, position)
		fileDrops.move(CarriedFiles(emptyList(), CarriedMaterial(id, label)), position.x, position.y)
	}
	fun moveMaterialDrag(position: androidx.compose.ui.geometry.Offset) {
		val drag = _materialDrag.value ?: return
		_materialDrag.value = drag.copy(position = position)
		fileDrops.move(CarriedFiles(emptyList(), CarriedMaterial(drag.id, drag.label)), position.x, position.y)
	}
	fun cancelMaterialDrag() { _materialDrag.value = null; fileDrops.end() }

	/** Drops the carried material where the pointer is: whatever the target there does with it, as with files. */
	fun endMaterialDrag() {
		val drag = _materialDrag.value ?: return
		_materialDrag.value = null
		val carried = CarriedFiles(emptyList(), CarriedMaterial(drag.id, drag.label))
		perform(carried, fileDrops.drop(carried, drag.position.x, drag.position.y))
	}

	/** Adds material [materialId] as a further reference image, in front of the model or [under] it (`layout_add_reference`), or removes every one showing it with null. */
	fun setLayoutReference(materialId: String?, opacity: Float? = null, removing: String? = null, under: Boolean = false) {
		if (materialId == null) {
			val ids = state().layout.references.filter { removing == null || it.materialId == removing }.map { it.id }
			if (ids.isNotEmpty()) documentPort()?.let { backend ->
				val port: io.github.psd2live.application.WorkspaceDocumentPort = backend
				runWorkspaceCommand(onFinished = {}) { state -> port.applyDocumentEdits(state, tr("references.removed"), ids.map { id ->
					io.github.psd2live.application.WorkspaceDocumentOperation(io.github.psd2live.application.WorkspaceMaterialEdits.REFERENCE,
						kotlinx.serialization.json.buildJsonObject { put("id", id); put("material_id", kotlinx.serialization.json.JsonNull) })
				}, MutationAuthor.USER) }
			}
			return
		}
		layoutEdit(tr("materials.referenceSet"), io.github.psd2live.application.WorkspaceMaterialEdits.ADD_REFERENCE,
			kotlinx.serialization.json.buildJsonObject { put("material_id", materialId); opacity?.let { put("opacity", it) }; if (under) put("under", true) })
	}


    private var opacitySequence = 0L
	private val _referenceOpacityDraft = kotlinx.coroutines.flow.MutableStateFlow<ReferenceOpacityDraft?>(null)
	/** Transient feedback shared by all canvases; only the release is a document edit. */
	val referenceOpacityDraft: kotlinx.coroutines.flow.StateFlow<ReferenceOpacityDraft?> = _referenceOpacityDraft

	fun previewReferenceOpacity(id: String, opacity: Float) = previewReferenceOpacity(ReferenceTarget.Image(id), opacity)
	fun previewReferenceOpacity(target: ReferenceTarget, opacity: Float) {
		opacitySequence++
		_referenceOpacityDraft.value = ReferenceOpacityDraft(target, opacity.coerceIn(0f, 1f))
	}

	fun commitReferenceOpacity(id: String, opacity: Float) = commitReferenceOpacity(ReferenceTarget.Image(id), opacity)
	fun commitReferenceOpacity(target: ReferenceTarget, opacity: Float) {
		val workspace = documentPort()?.takeUnless { fieldsOpen() || state().workspaceEditBusy }
		if (workspace == null) { _referenceOpacityDraft.value = null; return }
		val port: io.github.psd2live.application.WorkspaceDocumentPort = workspace
		val sequence = opacitySequence
		runWorkspaceCommand(onFinished = { if (sequence == opacitySequence) _referenceOpacityDraft.value = null }) { state ->
			port.applyDocumentEdits(state, tr("references.edited"), listOf(io.github.psd2live.application.WorkspaceDocumentOperation(
				io.github.psd2live.application.WorkspaceReferenceOpacity.OP,
				kotlinx.serialization.json.buildJsonObject {
					when (target) {
						is ReferenceTarget.Image -> put("reference_id", target.id)
						is ReferenceTarget.Layer -> put("layer_id", target.layerId)
					}
					put("opacity", opacity.coerceIn(0f, 1f))
				})), MutationAuthor.USER)
		}
	}

	/** Changes reference image [id] with the fields [fields] puts (`layout_set_reference`): one history edit. */
	fun editReference(id: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
		layoutEdit(tr("references.edited"), io.github.psd2live.application.WorkspaceMaterialEdits.REFERENCE,
			kotlinx.serialization.json.buildJsonObject { put("id", id); fields() })

	fun removeReference(id: String) {
		if (state().selectedReferenceId == id) selectReference(null)
		layoutEdit(tr("references.removed"), io.github.psd2live.application.WorkspaceMaterialEdits.REFERENCE,
			kotlinx.serialization.json.buildJsonObject { put("id", id); put("material_id", kotlinx.serialization.json.JsonNull) })
	}

	fun orderReference(id: String, index: Int) = layoutEdit(tr("references.order"), io.github.psd2live.application.WorkspaceMaterialEdits.ORDER_REFERENCE,
		kotlinx.serialization.json.buildJsonObject { put("id", id); put("index", index) })

	/** Pins [target] to keyform [points] - each parameter ID to value - or unpins it with none. */
	fun setReferencePin(target: ReferenceTarget, points: List<io.github.psd2live.project.PinPoint>) {
		val encoded = kotlinx.serialization.json.JsonArray(points.map { point ->
			kotlinx.serialization.json.JsonObject(point.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }) })
		when (target) {
			is ReferenceTarget.Layer -> layoutEdit(tr(if (points.isEmpty()) "layers.pin.unpinned" else "layers.pin.pinned", 1),
				io.github.psd2live.application.WorkspaceLayoutEdits.PIN_REFERENCE,
				kotlinx.serialization.json.buildJsonObject { put("layer_ids", strings(listOf(target.layerId))); put("points", encoded) })
			is ReferenceTarget.Image -> editReference(target.id) { put("points", encoded) }
		}
	}

	/** Shows [target] at keyform [point] too, or no longer there when it already does. */
	fun toggleReferencePoint(target: ReferenceTarget, point: io.github.psd2live.project.PinPoint) {
		val points = target.pin(state().layout)
		val existing = points.firstOrNull { ReferencePinRows.same(it, point) }
		setReferencePin(target, if (existing != null) points - existing else points + point)
	}

	/** Shows [target] behind the model, in the layer stack or in front of it (`reference_set_mode`). */
	fun setReferenceMode(target: ReferenceTarget, mode: ReferenceMode) {
		if (target is ReferenceTarget.Image && state().selectedReferenceId == target.id && mode == ReferenceMode.LAYER) selectReference(null)
		layoutEdit(tr("references.moved", tr(mode.labelKey)), io.github.psd2live.application.WorkspaceReferenceModes.OP,
			kotlinx.serialization.json.buildJsonObject {
				when (target) {
					is ReferenceTarget.Image -> put("reference_id", target.id)
					is ReferenceTarget.Layer -> put("layer_id", target.layerId)
				}
				put("mode", mode.key)
			})
	}

	/** Shows or hides [target]: a reference image in the document, a reference layer as any layer. */
	fun setReferenceVisible(target: ReferenceTarget, visible: Boolean) {
		when (target) {
			is ReferenceTarget.Image -> editReference(target.id) { put("visible", visible) }
			is ReferenceTarget.Layer -> if (state().isLayerVisible(target.layerId) != visible) toggleLayerVisibility(target.layerId)
		}
	}

	/** Removes [target]: a reference image goes, a reference layer is deleted. */
	fun removeReference(target: ReferenceTarget) = when (target) {
		is ReferenceTarget.Image -> removeReference(target.id)
		is ReferenceTarget.Layer -> deleteLayer(target.layerId)
	}

	/** Reference image [id] moved, scaled or turned on the canvas to [rect] and [rotation]. */
	fun placeReference(id: String, rect: io.github.psd2live.project.LayerCanvasRect, rotation: Float) = editReference(id) {
		put("rect", kotlinx.serialization.json.buildJsonObject { put("left", rect.left); put("top", rect.top); put("width", rect.width); put("height", rect.height) })
		put("rotation", rotation)
	}

	fun layoutEdit(summary: String, operation: String, request: kotlinx.serialization.json.JsonObject) {
		val workspace = documentPort()?.takeUnless { fieldsOpen() } ?: return
		val port: io.github.psd2live.application.WorkspaceDocumentPort = workspace
		runWorkspaceCommand(onFinished = {}) { state -> port.applyDocumentEdits(state, summary,
			listOf(io.github.psd2live.application.WorkspaceDocumentOperation(operation, request)), MutationAuthor.USER) }
	}

	private fun strings(values: Collection<String>) = kotlinx.serialization.json.JsonArray(values.map { kotlinx.serialization.json.JsonPrimitive(it) })

	/** Reads [files] (images, PSD, CMO3 images, SVG, folders) into the material library under [folderId]: one history edit. */
	fun importMaterials(files: List<java.nio.file.Path>, folderId: String?, asReference: Boolean = false, under: Boolean = false) {
		if (files.isEmpty()) return
		val workspace = documentPort()?.takeUnless { fieldsOpen() } ?: return
		val port: io.github.psd2live.application.WorkspaceDocumentPort = workspace
		setStatus(tr("materials.importing", files.size))
		runWorkspaceCommand(onFinished = {}) { state ->
			val entries = kotlinx.coroutines.withContext(Dispatchers.IO) {
				io.github.psd2live.application.WorkspaceMaterialFiles.read(files.map { it.toAbsolutePath() })
			}
			val token = io.github.psd2live.application.WorkspaceMaterialStaging.stage(entries)
			port.applyDocumentEdits(state, tr("materials.imported", entries.size), listOf(io.github.psd2live.application.WorkspaceDocumentOperation(
				io.github.psd2live.application.WorkspaceMaterialEdits.ADD, kotlinx.serialization.json.buildJsonObject {
					put("staging", token); folderId?.let { put("folder_id", it) }
					if (asReference) put("as_reference", true)
					if (asReference && under) put("reference_under", true)
				})), MutationAuthor.USER)
		}
	}

	fun createMaterialFolder(name: String, parentId: String?) = layoutEdit(tr("materials.folder.created", name), io.github.psd2live.application.WorkspaceMaterialEdits.FOLDER,
		kotlinx.serialization.json.buildJsonObject { put("name", name); parentId?.let { put("parent_id", it) } })

	fun moveMaterials(ids: Collection<String>, folderId: String?) = layoutEdit(tr("materials.moved"), io.github.psd2live.application.WorkspaceMaterialEdits.MOVE,
		kotlinx.serialization.json.buildJsonObject { put("ids", strings(ids)); put("folder_id", folderId?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull) })

	fun renameMaterial(id: String, name: String) = layoutEdit(tr("materials.renamed"), io.github.psd2live.application.WorkspaceMaterialEdits.RENAME,
		kotlinx.serialization.json.buildJsonObject { put("id", id); put("name", name) })

	fun deleteMaterials(ids: Collection<String>) = layoutEdit(tr("materials.deleted"), io.github.psd2live.application.WorkspaceMaterialEdits.DELETE,
		kotlinx.serialization.json.buildJsonObject { put("ids", strings(ids)) })

	fun tagMaterials(ids: Collection<String>, set: Set<String>) = layoutEdit(tr("materials.tagged"), io.github.psd2live.application.WorkspaceMaterialEdits.TAG,
		kotlinx.serialization.json.buildJsonObject { put("ids", strings(ids)); put("set", strings(set)) })

	fun setMaterialResolution(id: String, width: Int, height: Int) = layoutEdit(tr("materials.resolution.set"), io.github.psd2live.application.WorkspaceMaterialEdits.RESOLUTION,
		kotlinx.serialization.json.buildJsonObject { put("id", id); put("width", width); put("height", height) })

	/** Places material [id] on the canvas, centred at ([x], [y]) in canvas units, or at its own place when null. */
	fun placeMaterial(id: String, x: Float? = null, y: Float? = null, reference: Boolean? = null) = layoutEdit(tr("materials.placed"), io.github.psd2live.application.WorkspaceMaterialEdits.PLACE,
		kotlinx.serialization.json.buildJsonObject {
			put("material_id", id)
			if (x != null && y != null) put("at", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(x)); add(kotlinx.serialization.json.JsonPrimitive(y)) })
			if (reference != null) put("reference", reference)
		})

	/** Marks material items or folders to place as reference layers, or not (`material_mark_reference`). */
	fun markMaterialsReference(ids: Collection<String>, reference: Boolean) = layoutEdit(
		tr(if (reference) "materials.referenceMarked" else "materials.referenceUnmarked"), io.github.psd2live.application.WorkspaceMaterialEdits.MARK_REFERENCE,
		kotlinx.serialization.json.buildJsonObject { put("ids", strings(ids)); put("reference", reference) })

	/** Layer [layerId] shows material [materialId] over its unchanged rectangle: `layer_replace_image` with the material. */
	fun replaceLayerWithMaterial(layerId: String, materialId: String) = layoutEdit(tr("materials.replaced"), "layer_replace_image",
		kotlinx.serialization.json.buildJsonObject { put("layer_id", layerId); put("material_id", materialId) })

	private val _materialEditor = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

	/** The material item the material window shows, or null while the window is closed. */
	val materialEditor: kotlinx.coroutines.flow.StateFlow<String?> = _materialEditor

	fun openMaterialEditor(id: String) { _materialEditor.value = id }
	fun closeMaterialEditor() { _materialEditor.value = null }

	/** The material window follows the panel's selection while it is open. */
	fun showInMaterialEditor(id: String) { if (_materialEditor.value != null) _materialEditor.value = id }

	/** One paint gesture ([request] as `material_paint` takes it, without the id) on material [id]'s pixels. */
	fun paintMaterial(id: String, request: kotlinx.serialization.json.JsonObject) = layoutEdit(tr("materials.painted"),
		io.github.psd2live.application.WorkspaceMaterialPixels.PAINT, kotlinx.serialization.json.JsonObject(request + ("id" to kotlinx.serialization.json.JsonPrimitive(id))))

	/** Material [id]'s pixels flipped and recoloured, as `material_adjust` does. */
	internal fun adjustMaterial(id: String, adjustment: io.github.psd2live.application.WorkspaceMaterialPixels.Adjustment) = layoutEdit(tr("materials.adjusted"),
		io.github.psd2live.application.WorkspaceMaterialPixels.ADJUST, kotlinx.serialization.json.buildJsonObject {
			put("id", id)
			adjustment.flip?.let { put("flip", it) }
			if (adjustment.hue != 0f) put("hue", adjustment.hue)
			if (adjustment.saturation != 0f) put("saturation", adjustment.saturation)
			if (adjustment.lightness != 0f) put("lightness", adjustment.lightness)
			if (adjustment.contrast != 0f) put("contrast", adjustment.contrast)
		})

	/** Gives [layerIds] copies of the materials they share, their pixels unchanged (`material_fork`). */
	fun unlinkMaterials(layerIds: Collection<String>) = layoutEdit(tr("materials.unlinked"), io.github.psd2live.application.WorkspaceMaterialEdits.FORK,
		kotlinx.serialization.json.buildJsonObject { put("layer_ids", strings(layerIds)) })

	/** Gives every layer that shows no material one of its own (`material_adopt_layers`). */
	fun adoptLayerMaterials() = layoutEdit(tr("materials.adopt.action"), io.github.psd2live.application.WorkspaceMaterialEdits.ADOPT,
		kotlinx.serialization.json.buildJsonObject { })

	/** Generates the rig, or generates it again: what waits joins it, the user's edits stay. [refresh]: mesh replaced images from their new pixels. */
	fun generateLayout(refresh: Boolean = true) = layoutEdit(tr("layout.generate.summary"), io.github.psd2live.application.WorkspaceGenerationEdits.GENERATE,
		kotlinx.serialization.json.buildJsonObject { if (!refresh) put("refresh_geometry", kotlinx.serialization.json.JsonArray(emptyList())) })


    override fun close() {
        cancelMaterialDrag()
        opacitySequence++
        _referenceOpacityDraft.value = null
        closeMaterialEditor()
    }
}
