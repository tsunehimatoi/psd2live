package io.github.psd2live.core

import org.umamo.edit.Pose
import org.umamo.edit.channelValueAt
import org.umamo.edit.geometryGridOf
import org.umamo.edit.withChannelKeyCaptured
import org.umamo.edit.withChannelKeyRemovedAt
import org.umamo.edit.withGeometryKeyRemoved
import org.umamo.edit.withParameterCreated
import org.umamo.edit.withParameterDeleted
import org.umamo.edit.withParameterRange
import org.umamo.runtime.keyform.MeshDeltaInterpolator
import org.umamo.runtime.keyform.RotationPivotInterpolator
import org.umamo.runtime.keyform.WarpLatticeInterpolator
import org.umamo.runtime.keyform.axisIndexOf
import org.umamo.runtime.keyform.keyIndexAt
import org.umamo.runtime.keyform.withAxisCollapsed
import org.umamo.runtime.keyform.withAxisSeeded
import org.umamo.runtime.keyform.withFormCaptured
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.channelGridsOf
import org.umamo.runtime.model.withDerivedRenderRoot

/**
 * A durable, replayable edit to one parameter. The complete desired value is stored instead of a
 * sequence of UI gestures, so rebuilding meshes or the generated base rig cannot silently lose it.
 */
data class RigParameterEdit(
	val id: String,
	val name: String,
	val min: Float,
	val max: Float,
	val default: Float,
	val kind: ParameterKind = ParameterKind.NORMAL,
	val repeat: Boolean = false,
	/** True when this parameter did not exist in the generated base rig at creation time. */
	val created: Boolean = false,
) {
	init {
		require(id.isNotBlank() && id.none(Char::isISOControl)) { "Parameter ID must not be blank or contain control characters" }
		require(name.isNotBlank() && name.none(Char::isISOControl)) { "Parameter name must not be blank or contain control characters" }
		require(min.isFinite() && max.isFinite() && default.isFinite()) { "Parameter range values must be finite" }
		require(min < max) { "Parameter minimum must be less than maximum" }
		require(default in min..max) { "Parameter default must be within its range" }
	}

	fun asParameter(): Parameter = Parameter(ParameterId(id), name.trim(), min, max, default, kind, repeat)
}

enum class RigTargetKind {
	ART_MESH,
	WARP_DEFORMER,
	ROTATION_DEFORMER,
	PART,
	GLUE;

	companion object {
		fun fromString(raw: String): RigTargetKind = when (raw.trim().lowercase()) {
			"art_mesh", "artmesh", "drawable", "mesh" -> ART_MESH
			"warp_deformer", "warp", "warpdeformer" -> WARP_DEFORMER
			"rotation_deformer", "rotation", "rotationdeformer" -> ROTATION_DEFORMER
			"part" -> PART
			"glue" -> GLUE
			else -> throw IllegalArgumentException("Unknown target kind: $raw")
		}
	}
}

data class RigTargetRef(
	val kind: RigTargetKind,
	val id: String,
	val secondaryId: String? = null,
) {
	init {
		require(id.isNotBlank()) { "Target ID must not be blank" }
		if (kind == RigTargetKind.GLUE) {
			require(!secondaryId.isNullOrBlank()) { "Glue target requires secondaryId (meshB)" }
		}
	}

	fun asKeyformOwner(): KeyformOwner = when (kind) {
		RigTargetKind.ART_MESH -> KeyformOwner.Drawable(DrawableId(id))
		RigTargetKind.WARP_DEFORMER, RigTargetKind.ROTATION_DEFORMER -> KeyformOwner.Deformer(DeformerId(id))
		RigTargetKind.PART -> KeyformOwner.Part(PartId(id))
		RigTargetKind.GLUE -> KeyformOwner.Glue(DrawableId(id), DrawableId(secondaryId ?: id))
	}
}

data class RigKeyformGeometryEdit(
	val controlPoints: List<Float>? = null,
	val originX: Float? = null,
	val originY: Float? = null,
	val angle: Float? = null,
	val scale: Float? = null,
	val positionDeltas: List<Float>? = null,
) {
	init {
		controlPoints?.forEach { require(it.isFinite()) { "Control point values must be finite" } }
		positionDeltas?.forEach { require(it.isFinite()) { "Position delta values must be finite" } }
		originX?.let { require(it.isFinite()) { "originX must be finite" } }
		originY?.let { require(it.isFinite()) { "originY must be finite" } }
		angle?.let { require(it.isFinite()) { "angle must be finite" } }
		scale?.let { require(it.isFinite()) { "scale must be finite" } }
	}
}

data class RigKeyformChannelsEdit(
	val opacity: Float? = null,
	val drawOrder: Float? = null,
	val multiplyColor: List<Float>? = null,
	val screenColor: List<Float>? = null,
	val glueIntensity: Float? = null,
	val flipX: Boolean? = null,
	val flipY: Boolean? = null,
) {
	init {
		opacity?.let { require(it.isFinite() && it in 0f..1f) { "Opacity must be within 0..1" } }
		drawOrder?.let { require(it.isFinite() && it in 0f..1000f) { "Draw order must be within 0..1000" } }
		multiplyColor?.let {
			require(it.size == 3 && it.all { c -> c.isFinite() && c in 0f..1f }) { "multiplyColor must be 3 floats within 0..1" }
		}
		screenColor?.let {
			require(it.size == 3 && it.all { c -> c.isFinite() && c in 0f..1f }) { "screenColor must be 3 floats within 0..1" }
		}
		glueIntensity?.let { require(it.isFinite() && it in 0f..1f) { "glueIntensity must be within 0..1" } }
	}
}

data class RigKeyformSetEdit(
	val target: RigTargetRef,
	val coordinate: Map<String, Float>,
	val geometry: RigKeyformGeometryEdit? = null,
	val channels: RigKeyformChannelsEdit? = null,
) {
	init {
		require(coordinate.isNotEmpty()) { "Keyform coordinate must not be empty" }
		require(coordinate.keys.all { it.isNotBlank() }) { "Parameter IDs in coordinate must not be blank" }
		require(coordinate.values.all { it.isFinite() }) { "Coordinate values must be finite" }
		require(geometry != null || channels != null) { "Keyform set edit must specify geometry or channels" }
	}
}

data class RigKeyformDeleteEdit(
	val target: RigTargetRef,
	val parameterId: String,
	val keyValue: Float? = null,
	val channel: String? = null,
) {
	init {
		require(parameterId.isNotBlank()) { "Parameter ID must not be blank" }
		require(keyValue == null || keyValue.isFinite()) { "Key value must be finite" }
	}
}

data class RigKeyformCopyEdit(
	val sourceTarget: RigTargetRef,
	val sourceCoordinate: Map<String, Float>,
	val destinationTarget: RigTargetRef = sourceTarget,
	val destinationCoordinate: Map<String, Float>,
	val channels: List<String>? = null,
) {
	init {
		require(sourceCoordinate.isNotEmpty() && destinationCoordinate.isNotEmpty()) {
			"Source and destination coordinates must not be empty"
		}
	}
}

/**
 * Authoritative rig customization applied after every deterministic base-rig build. This value is
 * included in Agent history snapshots and export configuration.
 */
data class RigEditOverlay(
	val parameterEdits: List<RigParameterEdit> = emptyList(),
	val deletedParameterIds: Set<String> = emptySet(),
	val keyformSetEdits: List<RigKeyformSetEdit> = emptyList(),
	val keyformDeleteEdits: List<RigKeyformDeleteEdit> = emptyList(),
	val keyformCopyEdits: List<RigKeyformCopyEdit> = emptyList(),
    val warpEdits: List<RigWarpEdit> = emptyList(),
    val physicsEdits: List<RigPhysicsEdit> = emptyList(),
    val assetLayers: Map<String, kotlinx.serialization.json.JsonObject> = emptyMap(),
    val calibrationLayerIds: Set<String> = emptySet(),
) {
	init {
		require(warpEdits.map { it.id }.distinct().size == warpEdits.size) { "Duplicate Warp IDs" }
        require(physicsEdits.map { it.id }.distinct().size == physicsEdits.size) { "Duplicate physics IDs" }
        require(physicsEdits.map { it.outputParameter }.distinct().size == physicsEdits.size) { "Independent physics must have distinct output parameters" }
		require(parameterEdits.map(RigParameterEdit::id).distinct().size == parameterEdits.size) {
			"Rig parameter edits contain duplicate IDs"
		}
		require(parameterEdits.none { it.id in deletedParameterIds }) {
			"A parameter cannot be both edited and deleted"
		}
	}

	fun applyTo(base: PuppetModel): PuppetModel {
		var model = base
		// 1. Delete removed parameters
		for (id in deletedParameterIds.sorted()) model = model.withParameterDeleted(ParameterId(id))
		// 2. Replay parameter creations and range/name updates
		for (edit in parameterEdits) {
			val id = ParameterId(edit.id)
			if (model.parameters.none { it.id == id }) {
				model = model.withParameterCreated(id, edit.name, edit.kind)
			}
			model = model.withParameterRange(id, edit.min, edit.default, edit.max)
			val desired = edit.asParameter()
			model = model.copy(parameters = model.parameters.map { current -> if (current.id == id) desired else current })
		}
		for (warp in warpEdits) model = warp.applyTo(model)
		physicsEdits.forEach { it.validate(model.parameters.map { p -> p.id.raw }.toSet()) }
		// 3. Apply keyform sets
		for (set in keyformSetEdits) {
			model = applyKeyformSet(model, set)
		}
		// 4. Apply keyform copies
		for (copy in keyformCopyEdits) {
			model = applyKeyformCopy(model, copy)
		}
		// 5. Apply keyform deletions
		for (delete in keyformDeleteEdits) {
			model = applyKeyformDelete(model, delete)
		}
		return model
	}

	fun upsert(edit: RigParameterEdit): RigEditOverlay {
		val index = parameterEdits.indexOfFirst { it.id == edit.id }
		val next = if (index < 0) parameterEdits + edit else parameterEdits.toMutableList().also { it[index] = edit }
		return copy(parameterEdits = next, deletedParameterIds = deletedParameterIds - edit.id)
	}

	fun delete(id: String): RigEditOverlay = copy(
		parameterEdits = parameterEdits.filterNot { it.id == id },
		deletedParameterIds = deletedParameterIds + id,
	)

	fun setKeyform(edit: RigKeyformSetEdit): RigEditOverlay {
		val index = keyformSetEdits.indexOfFirst { it.target == edit.target && it.coordinate == edit.coordinate }
		val next = if (index < 0) keyformSetEdits + edit else keyformSetEdits.toMutableList().also { it[index] = edit }
		return copy(keyformSetEdits = next)
	}

	fun deleteKeyform(edit: RigKeyformDeleteEdit): RigEditOverlay {
		val filteredSets = keyformSetEdits.filterNot {
			it.target == edit.target &&
				it.coordinate.containsKey(edit.parameterId) &&
				(edit.keyValue == null || it.coordinate[edit.parameterId] == edit.keyValue)
		}
		val filteredCopies = keyformCopyEdits.filterNot {
			it.destinationTarget == edit.target &&
				it.destinationCoordinate.containsKey(edit.parameterId) &&
				(edit.keyValue == null || it.destinationCoordinate[edit.parameterId] == edit.keyValue)
		}
		return copy(
			keyformSetEdits = filteredSets,
			keyformCopyEdits = filteredCopies,
			keyformDeleteEdits = keyformDeleteEdits + edit,
		)
	}

	fun copyKeyform(edit: RigKeyformCopyEdit): RigEditOverlay = copy(keyformCopyEdits = keyformCopyEdits + edit)

	companion object {
		val Empty = RigEditOverlay()
	}
}

internal fun BuiltRig.withRigEdits(overlay: RigEditOverlay): BuiltRig =
	if (overlay == RigEditOverlay.Empty) this else copy(puppet = overlay.applyTo(puppet))

private fun applyKeyformDelete(model: PuppetModel, delete: RigKeyformDeleteEdit): PuppetModel {
	val paramId = ParameterId(delete.parameterId)
	val param = model.parameters.firstOrNull { it.id == paramId } ?: return model
	val owner = delete.target.asKeyformOwner()
	var current = model

	val deleteGeometry = delete.channel == null || delete.channel.equals("geometry", ignoreCase = true)
	if (deleteGeometry) {
		val grid = current.geometryGridOf(owner)
		if (grid != null && grid.axisIndexOf(paramId) >= 0) {
			if (delete.keyValue != null) {
				val keyIndex = grid.keyIndexAt(paramId, delete.keyValue)
				if (keyIndex >= 0) {
					current = current.withGeometryKeyRemoved(owner, param, keyIndex)
				}
			} else {
				val collapsed = grid.withAxisCollapsed(paramId, param.default)
				current = current.withReplacedGeometryGrid(owner, collapsed)
			}
		}
	}

	val deleteChannels = delete.channel == null || !delete.channel.equals("geometry", ignoreCase = true)
	if (deleteChannels) {
		val targetCh = delete.channel?.let { name ->
			runCatching { FormChannel.valueOf(name.uppercase()) }.getOrNull()
		}
		val channelFilter: (FormChannel) -> Boolean = { ch ->
			targetCh == null || ch == targetCh
		}
		val grids = current.channelGridsOf(owner)
		if (grids != null) {
			for ((channel, track) in grids.gridsByChannel) {
				if (channelFilter(channel) && track.axisIndexOf(paramId) >= 0) {
					if (delete.keyValue != null) {
						val keyIndex = track.keyIndexAt(paramId, delete.keyValue)
						if (keyIndex >= 0) {
							current = current.withChannelKeyRemovedAt(KeyableTarget(owner, channel), param, keyIndex)
						}
					} else {
						val survivingKey = track.axes[track.axisIndexOf(paramId)].keys.firstOrNull() ?: param.default
						val collapsed = track.withAxisCollapsed(paramId, survivingKey)
						val nextGrids = if (collapsed == null) {
							grids.gridsByChannel - channel
						} else {
							grids.gridsByChannel + (channel to collapsed)
						}
						current = current.withReplacedChannelGrids(owner, ChannelGrids(nextGrids))
					}
				}
			}
		}
	}
	return current
}

private fun applyKeyformSet(model: PuppetModel, set: RigKeyformSetEdit): PuppetModel {
	var current = model
	val owner = set.target.asKeyformOwner()
	val poseFn: (ParameterId) -> Float = { id ->
		set.coordinate[id.raw] ?: (current.parameters.firstOrNull { it.id == id }?.default ?: 0f)
	}
	val poseMap: Pose = set.coordinate.mapKeys { ParameterId(it.key) }

	// 1. Geometry edit
	val geo = set.geometry
	if (geo != null) {
		when (set.target.kind) {
			RigTargetKind.WARP_DEFORMER -> {
				val deformer = current.deformers.firstOrNull { it.id.raw == set.target.id } as? Deformer.Warp
				if (deformer != null && geo.controlPoints != null) {
					val expectedSize = (deformer.rows + 1) * (deformer.columns + 1) * 2
					require(geo.controlPoints.size == expectedSize) {
						"Warp ${set.target.id} control points size mismatch: expected $expectedSize, got ${geo.controlPoints.size}"
					}
					val form = WarpLatticeForm(geo.controlPoints.toFloatArray())
					val identity = FloatArray(expectedSize)
					for (r in 0..deformer.rows) for (c in 0..deformer.columns) {
						val i = (r * (deformer.columns + 1) + c) * 2
						identity[i] = c.toFloat() / deformer.columns
						identity[i + 1] = r.toFloat() / deformer.rows
					}
					var grid: KeyformGrid<WarpLatticeForm>? = deformer.geometryGrid
						?: KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(identity))))
					for ((paramName, _) in set.coordinate) {
						val param = current.parameters.firstOrNull { it.id.raw == paramName }
							?: throw IllegalArgumentException("Parameter not found: $paramName")
						if (grid == null || grid.axisIndexOf(param.id) < 0) {
							grid = grid.withAxisSeeded(param, form)
								?: throw IllegalStateException("Cannot seed warp axis: $paramName")
						}
					}
					if (grid != null) {
						grid = grid.withFormCaptured(poseFn, form, WarpLatticeInterpolator)
						current = current.copy(
							deformers = current.deformers.map {
								if (it.id == deformer.id && it is Deformer.Warp) it.copy(geometryGrid = grid) else it
							},
						)
					}
				}
			}
			RigTargetKind.ROTATION_DEFORMER -> {
				val deformer = current.deformers.firstOrNull { it.id.raw == set.target.id } as? Deformer.Rotation
				if (deformer != null && geo.angle != null && geo.originX != null && geo.originY != null) {
					val form = RotationPivotForm(geo.originX, geo.originY, geo.angle, geo.scale ?: 1f)
					var grid: KeyformGrid<RotationPivotForm>? = deformer.geometryGrid
					for ((paramName, _) in set.coordinate) {
						val param = current.parameters.firstOrNull { it.id.raw == paramName }
							?: throw IllegalArgumentException("Parameter not found: $paramName")
						if (grid == null || grid.axisIndexOf(param.id) < 0) {
							grid = grid.withAxisSeeded(param, form)
								?: throw IllegalStateException("Cannot seed rotation axis: $paramName")
						}
					}
					if (grid != null) {
						grid = grid.withFormCaptured(poseFn, form, RotationPivotInterpolator)
						current = current.copy(
							deformers = current.deformers.map {
								if (it.id == deformer.id && it is Deformer.Rotation) it.copy(geometryGrid = grid) else it
							},
						)
					}
				}
			}
			RigTargetKind.ART_MESH -> {
				val drawable = current.findDrawable(set.target.id)
				if (drawable != null && geo.positionDeltas != null) {
					val expectedDeltas = (drawable.mesh?.positions?.size ?: geo.positionDeltas.size)
					require(geo.positionDeltas.size == expectedDeltas) {
						"Mesh ${set.target.id} position deltas size mismatch: expected $expectedDeltas, got ${geo.positionDeltas.size}"
					}
					val form = MeshDeltaForm(geo.positionDeltas.toFloatArray())
					val neutralForm = MeshDeltaForm(FloatArray(expectedDeltas))
					var grid: KeyformGrid<MeshDeltaForm>? = drawable.geometryGrid
						?: KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), neutralForm)))
					for ((paramName, _) in set.coordinate) {
						val param = current.parameters.firstOrNull { it.id.raw == paramName }
							?: throw IllegalArgumentException("Parameter not found: $paramName")
						if (grid == null || grid.axisIndexOf(param.id) < 0) {
							grid = grid.withAxisSeeded(param, neutralForm)
								?: throw IllegalStateException("Cannot seed drawable axis: $paramName")
						}
					}
					if (grid != null) {
						grid = grid.withFormCaptured(poseFn, form, MeshDeltaInterpolator)
						current = current.copy(
							drawables = current.drawables.map {
								if (it.id == drawable.id) it.copy(geometryGrid = grid) else it
							},
						)
					}
				}
			}
			RigTargetKind.PART, RigTargetKind.GLUE -> {
				// Parts and glues do not hold standalone geometry
			}
		}
	}

	// 2. Channels edit
	val ch = set.channels
	if (ch != null) {
		val channelEntries = buildList<Pair<FormChannel, ChannelValue>> {
			ch.opacity?.let { add(FormChannel.OPACITY to ChannelValue.Scalar(it)) }
			ch.drawOrder?.let { add(FormChannel.DRAW_ORDER to ChannelValue.Scalar(it)) }
			ch.multiplyColor?.let { add(FormChannel.MULTIPLY_COLOR to ChannelValue.Color(ColorRgb(it[0], it[1], it[2]))) }
			ch.screenColor?.let { add(FormChannel.SCREEN_COLOR to ChannelValue.Color(ColorRgb(it[0], it[1], it[2]))) }
			ch.glueIntensity?.let { add(FormChannel.GLUE_INTENSITY to ChannelValue.Scalar(it)) }
			ch.flipX?.let { add(FormChannel.FLIP_X to ChannelValue.Flag(it)) }
			ch.flipY?.let { add(FormChannel.FLIP_Y to ChannelValue.Flag(it)) }
		}
		for ((channel, value) in channelEntries) {
			for ((paramName, _) in set.coordinate) {
				val param = current.parameters.firstOrNull { it.id.raw == paramName } ?: continue
				current = current.withChannelKeyCaptured(KeyableTarget(owner, channel), param, poseMap, value)
			}
		}
	}
	return current
}

private fun applyKeyformCopy(model: PuppetModel, copy: RigKeyformCopyEdit): PuppetModel {
	val sourceOwner = copy.sourceTarget.asKeyformOwner()
	val sourcePose: Pose = copy.sourceCoordinate.mapKeys { ParameterId(it.key) }

	var extractedGeo: RigKeyformGeometryEdit? = null
	val copyGeometry = copy.channels == null || copy.channels.any { it.equals("geometry", ignoreCase = true) }
	if (copyGeometry) {
		when (copy.sourceTarget.kind) {
			RigTargetKind.WARP_DEFORMER -> {
				val warp = model.deformers.firstOrNull { it.id.raw == copy.sourceTarget.id } as? Deformer.Warp
				val grid = warp?.geometryGrid
				if (grid != null) {
					val cell = findCellAtCoordinate(grid, copy.sourceCoordinate)
					if (cell != null) {
						extractedGeo = RigKeyformGeometryEdit(controlPoints = cell.form.controlPoints.toList())
					}
				}
			}
			RigTargetKind.ROTATION_DEFORMER -> {
				val rot = model.deformers.firstOrNull { it.id.raw == copy.sourceTarget.id } as? Deformer.Rotation
				val grid = rot?.geometryGrid
				if (grid != null) {
					val cell = findCellAtCoordinate(grid, copy.sourceCoordinate)
					if (cell != null) {
						extractedGeo = RigKeyformGeometryEdit(
							originX = cell.form.originX,
							originY = cell.form.originY,
							angle = cell.form.angle,
							scale = cell.form.scale,
						)
					}
				}
			}
			RigTargetKind.ART_MESH -> {
				val drawable = model.findDrawable(copy.sourceTarget.id)
				val grid = drawable?.geometryGrid
				if (grid != null) {
					val cell = findCellAtCoordinate(grid, copy.sourceCoordinate)
					if (cell != null) {
						extractedGeo = RigKeyformGeometryEdit(positionDeltas = cell.form.positionDeltas.toList())
					}
				}
			}
			RigTargetKind.PART, RigTargetKind.GLUE -> {}
		}
	}

	val channelsEdit: RigKeyformChannelsEdit? = run {
		var opacity: Float? = null
		var drawOrder: Float? = null
		var multiplyColor: List<Float>? = null
		var screenColor: List<Float>? = null
		var glueIntensity: Float? = null
		var flipX: Boolean? = null
		var flipY: Boolean? = null
		var foundAny = false

		fun shouldCopy(name: String): Boolean =
			copy.channels == null || copy.channels.any { it.equals(name, ignoreCase = true) }

		if (shouldCopy("opacity")) {
			(model.channelValueAt(KeyableTarget(sourceOwner, FormChannel.OPACITY), sourcePose) as? ChannelValue.Scalar)?.let {
				opacity = it.value
				foundAny = true
			}
		}
		if (shouldCopy("draw_order") || shouldCopy("drawOrder")) {
			(model.channelValueAt(KeyableTarget(sourceOwner, FormChannel.DRAW_ORDER), sourcePose) as? ChannelValue.Scalar)?.let {
				drawOrder = it.value
				foundAny = true
			}
		}
		if (shouldCopy("multiply_color") || shouldCopy("multiplyColor")) {
			(model.channelValueAt(KeyableTarget(sourceOwner, FormChannel.MULTIPLY_COLOR), sourcePose) as? ChannelValue.Color)?.let {
				multiplyColor = listOf(it.color.red, it.color.green, it.color.blue)
				foundAny = true
			}
		}
		if (shouldCopy("screen_color") || shouldCopy("screenColor")) {
			(model.channelValueAt(KeyableTarget(sourceOwner, FormChannel.SCREEN_COLOR), sourcePose) as? ChannelValue.Color)?.let {
				screenColor = listOf(it.color.red, it.color.green, it.color.blue)
				foundAny = true
			}
		}
		if (shouldCopy("glue_intensity") || shouldCopy("glueIntensity")) {
			(model.channelValueAt(KeyableTarget(sourceOwner, FormChannel.GLUE_INTENSITY), sourcePose) as? ChannelValue.Scalar)?.let {
				glueIntensity = it.value
				foundAny = true
			}
		}
		if (shouldCopy("flip_x") || shouldCopy("flipX")) {
			(model.channelValueAt(KeyableTarget(sourceOwner, FormChannel.FLIP_X), sourcePose) as? ChannelValue.Flag)?.let {
				flipX = it.flag
				foundAny = true
			}
		}
		if (shouldCopy("flip_y") || shouldCopy("flipY")) {
			(model.channelValueAt(KeyableTarget(sourceOwner, FormChannel.FLIP_Y), sourcePose) as? ChannelValue.Flag)?.let {
				flipY = it.flag
				foundAny = true
			}
		}

		if (foundAny) {
			RigKeyformChannelsEdit(
				opacity = opacity,
				drawOrder = drawOrder,
				multiplyColor = multiplyColor,
				screenColor = screenColor,
				glueIntensity = glueIntensity,
				flipX = flipX,
				flipY = flipY,
			)
		} else null
	}

	if (extractedGeo == null && channelsEdit == null) return model
	return applyKeyformSet(
		model,
		RigKeyformSetEdit(
			target = copy.destinationTarget,
			coordinate = copy.destinationCoordinate,
			geometry = extractedGeo,
			channels = channelsEdit,
		),
	)
}

private fun <TForm> findCellAtCoordinate(
	grid: KeyformGrid<TForm>,
	coordinate: Map<String, Float>,
): org.umamo.runtime.model.KeyformCell<TForm>? {
	val indices = IntArray(grid.axes.size)
	for (axisIndex in grid.axes.indices) {
		val axis = grid.axes[axisIndex]
		val requestedValue = coordinate[axis.parameterId.raw] ?: return null
		val keyIndex = grid.keyIndexAt(axis.parameterId, requestedValue)
		if (keyIndex < 0) return null
		indices[axisIndex] = keyIndex
	}
	val linear = grid.linearIndexOf(indices)
	return grid.cellsByLinearIndex[linear]
}

internal fun PuppetModel.findDrawable(id: String): Drawable? =
	drawables.firstOrNull { it.id.raw == id || it.id.raw == "artmesh_$id" || it.id.raw.removePrefix("artmesh_") == id }

internal fun PuppetModel.withReplacedChannelGrids(owner: KeyformOwner, channelGrids: ChannelGrids): PuppetModel =
	when (owner) {
		is KeyformOwner.Drawable ->
			copy(drawables = drawables.map { if (it.id == owner.id) it.copy(channelGrids = channelGrids) else it })
		is KeyformOwner.Part ->
			copy(parts = parts.map { if (it.id == owner.id) it.copy(channelGrids = channelGrids) else it }).withDerivedRenderRoot()
		is KeyformOwner.Deformer ->
			copy(
				deformers = deformers.map { deformer ->
					if (deformer.id != owner.id) deformer else when (deformer) {
						is Deformer.Warp -> deformer.copy(channelGrids = channelGrids)
						is Deformer.Rotation -> deformer.copy(channelGrids = channelGrids)
					}
				},
			)
		is KeyformOwner.Glue ->
			copy(
				glues = glues.map { glue ->
					if (glue.meshA == owner.meshA && glue.meshB == owner.meshB) glue.copy(channelGrids = channelGrids) else glue
				},
			)
	}

@Suppress("UNCHECKED_CAST")
internal fun PuppetModel.withReplacedGeometryGrid(owner: KeyformOwner, grid: KeyformGrid<*>?): PuppetModel =
	when (owner) {
		is KeyformOwner.Drawable ->
			copy(
				drawables = drawables.map {
					if (it.id == owner.id) it.copy(geometryGrid = grid as KeyformGrid<MeshDeltaForm>?) else it
				},
			)
		is KeyformOwner.Deformer ->
			copy(
				deformers = deformers.map { deformer ->
					if (deformer.id != owner.id) deformer else when (deformer) {
						is Deformer.Warp -> deformer.copy(geometryGrid = grid as KeyformGrid<WarpLatticeForm>?)
						is Deformer.Rotation -> deformer.copy(geometryGrid = grid as KeyformGrid<RotationPivotForm>?)
					}
				},
			)
		is KeyformOwner.Part, is KeyformOwner.Glue -> this
	}
