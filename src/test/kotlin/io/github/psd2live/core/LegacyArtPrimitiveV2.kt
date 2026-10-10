package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.*
import io.github.psd2live.core.legacy.ArtPrimitiveReplay

/**
 * The version 2 `art_primitive` primitive with an authored layer, as builds before splits became regenerations wrote
 * it (two capture passes: base fields, then residuals against the parts the base generated). Such records are legacy
 * data now: projects keep them and replay them where no checkpoint follows. Tests write them with this encoder.
 */
internal object LegacyArtPrimitiveV2 {
	/** One part of a version 2 record and the `generated_override` entries for its edited generator cells. */
	class EncodedPart(val primitive: JsonObject, val overrides: List<JsonObject>)

	/**
	 * One version 2 primitive. [authoredPart] is the part in [authoredModel] (the superseded drawable's authored
	 * state carried onto it by the partition, texture coordinates in canvas units). [ghost] is the superseded
	 * drawable in [ghostAuthored] (the authored rig the partition ran on) and [ghostGenerated] (the base rig);
	 * [sources] derive the part's vertices from the ghost's. [parked] is the part the pinned base generated, in
	 * [parkedModel] (that base with the part spliced in); null for the first pass, which records no residual. The
	 * record's base fields never depend on [parked], so the base the first pass builds stays valid.
	 */
	fun encode(authoredModel: PuppetModel, authoredPart: Drawable, ghostAuthored: PuppetModel, ghostGenerated: PuppetModel,
	                      ghost: DrawableId, sources: List<VertexSource>,
	                      layerId: String, textureSourceId: String, sourceBounds: LayerBounds, neutralBounds: Bounds,
	                      classification: LayerClassificationOverride, fixedTopology: Boolean,
	                      parkedModel: PuppetModel? = null, parked: Drawable? = null, skins: PrimitiveSkins = PrimitiveSkins.None,
	                      checkpoint: () -> Unit = {}): EncodedPart {
		val mesh = requireNotNull(authoredPart.mesh) { "An art primitive needs a mesh" }
		RasterMeshJournal.validateMesh(mesh)
		require(sources.size == mesh.vertexCount) { "Art primitive vertex sources do not match its mesh" }
		val authoredGhost = ghostAuthored.drawables.single { it.id == ghost }
		val generatedPart = ghostGenerated.drawables.singleOrNull { it.id == ghost }
			?: throw IllegalArgumentException("The split original is not generated: ${ghost.raw}")
		val generatedModel = ghostGenerated
		val ghostVertices = requireNotNull(authoredGhost.mesh).vertexCount
		require(requireNotNull(generatedPart.mesh).vertexCount == ghostVertices &&
			RasterMeshJournal.fingerprint(generatedPart.mesh!!) == RasterMeshJournal.fingerprint(authoredGhost.mesh!!)) {
			"The split original's mesh was edited by hand"
		}
		// Rest positions through the parent at the default pose, without the part's own keyforms (those are the residual's).
		// Under its generated parent the part is placed through the generated deformers: the base normalises these
		// positions into that parent, and the journal's own edits of the parent chain (a moved rest lattice) replay on
		// top, so taking them from the authored rig would apply those edits twice. A parent a journal edit chose is
		// held in canvas units and placed into the authored parent on replay, so it maps through the authored rig.
		val generatedParent = authoredPart.parentDeformerId == generatedPart.parentDeformerId &&
			(authoredPart.parentDeformerId == null || ghostGenerated.deformers.any { it.id == authoredPart.parentDeformerId })
		val placement = if (generatedParent) ghostGenerated.copy(drawables = ghostGenerated.drawables.filterNot { it.id == authoredPart.id } +
			authoredPart.copy(geometryGrid = null, blendShapes = emptyList())) else authoredModel
		val world = requireNotNull(org.umamo.render.eval.drawableSpaceMapping(placement, emptyMap(), authoredPart.id)) {
			"Art primitive parent cannot be evaluated: ${authoredPart.id.raw}"
		}.localToWorld(mesh.positions)
		val canvasPositions = FloatArray(world.size) { if (it % 2 == 0) world[it] else -world[it] }
		fun owner(model: PuppetModel, drawable: Drawable) = model.parts.singleOrNull { OrgChild.Drawable(drawable.id) in it.children }?.id
		val a = authoredPart; val g = generatedPart
		val generatedPaths = generatedModel.deformPaths.filter { it.drawableId == g.id }.mapTo(HashSet()) { it.id }
		val generatedGroups = generatedModel.vertexGroups.filter { it.drawableId == g.id }
		val generatedBlends = g.blendShapes.mapTo(HashSet()) { it.parameterId }
		val userBlends = a.blendShapes.filter { it.parameterId !in generatedBlends }
		// The partition names a carried path `<part>/<path>` (a depth front `<glue>/<path>`); generated ones stay with the base.
		val userPaths = authoredModel.deformPaths.filter { path -> path.drawableId == a.id &&
			path.id !in generatedPaths && generatedPaths.none { path.id.endsWith("/$it") } }
		val userGroups = authoredModel.vertexGroups.filter { group -> group.drawableId == a.id &&
			generatedGroups.none { it.name == group.name && it.kind == group.kind &&
				PrimitiveResidual.transferScalars(it.weights, sources).let { weights -> weights.indices.all { i -> kotlin.math.abs(weights[i] - group.weights[i]) <= 1e-6f } } } }
		val parameters = authoredModel.parameters.associateBy { it.id }
		val delta = PrimitiveResidual.delta(ghostAuthored, authoredGhost, ghostGenerated, generatedPart, sources, checkpoint)
		// Per-vertex user data pins the recorded topology: the skeleton must not insert joint rows under it.
		val pinned = fixedTopology || delta != null || userBlends.isNotEmpty() || userPaths.isNotEmpty() || userGroups.isNotEmpty()
		val residual = if (parked == null || parkedModel == null) null else {
			val parkedMesh = requireNotNull(parked.mesh)
			val map = ArtPrimitiveReplay.partMap(parked, mesh.uvs, mesh.indices)
			val seed = if (map == null) parkedMesh.positions else FloatArray(mesh.positions.size) { 0.5f }
			val space = PrimitiveResidual.ParentSpace(authoredModel, a, parkedModel, parked, seed)
			// A part the base generates no keyforms for (a zero grid: a split lip ribbon, a part under a parent a journal
			// edit made) has nothing to add back the original's generated motion: its residual is the whole authored form.
			val unkeyed = parked.geometryGrid?.axes.isNullOrEmpty() && generatedPart.geometryGrid?.axes?.isNotEmpty() == true
			val residualDelta = if (!unkeyed) delta else PrimitiveResidual.delta(ghostAuthored, authoredGhost, ghostGenerated,
				generatedPart.copy(geometryGrid = null), sources, checkpoint)
			PrimitiveResidual.geometry(parameters, residualDelta, mesh.positions.size, parked, space::convert,
				{ values -> map?.let { PrimitiveResidual.transfer(values, it.sources) } ?: values }, { skins.owner(parked.id, it) }, checkpoint)
		}
		// Channels belong to the drawable, not its vertices: the part's own (a depth slice drops its draw order grid) over the original's.
		val channels = if (parked == null) ChannelGrids.Empty else PrimitiveResidual.channels(authoredModel, a, g, checkpoint)
		val used = (residual?.residual?.axes.orEmpty().map { it.parameterId } + channels.gridsByChannel.values.flatMap { grid -> grid.axes.map { it.parameterId } } +
			userBlends.flatMap { binding -> listOf(binding.parameterId) + binding.limits.map { it.parameterId } }).distinct()
		val generatedParameters = generatedModel.parameters.mapTo(HashSet()) { it.id }
		val authored = buildJsonObject {
			if (owner(authoredModel, a) != owner(generatedModel, g)) put(ArtPrimitiveV2.PART, owner(authoredModel, a)?.raw?.let(::JsonPrimitive) ?: JsonNull)
			if (a.blendMode != g.blendMode) put(ArtPrimitiveV2.BLEND, a.blendMode.name)
			if (a.opacity != g.opacity) put(ArtPrimitiveV2.OPACITY, a.opacity)
			put(ArtPrimitiveV2.ORDER, a.drawOrder)
			if (a.isVisible != g.isVisible) put(ArtPrimitiveV2.VISIBLE, a.isVisible)
			if (a.isSelectable != g.isSelectable) put(ArtPrimitiveV2.SELECTABLE, a.isSelectable)
			if (a.multiplyColor != g.multiplyColor) put(ArtPrimitiveV2.MULTIPLY, color(a.multiplyColor))
			if (a.screenColor != g.screenColor) put(ArtPrimitiveV2.SCREEN, color(a.screenColor))
			if (a.invertMask != g.invertMask) put(ArtPrimitiveV2.INVERT_MASK, a.invertMask)
			if (a.alphaBlendMode != g.alphaBlendMode) put(ArtPrimitiveV2.ALPHA_BLEND, a.alphaBlendMode.name)
			if (a.culling != g.culling) put(ArtPrimitiveV2.CULLING, a.culling)
			if (a.userData != g.userData) put(ArtPrimitiveV2.USER_DATA, a.userData)
			if (a.maskedBy != g.maskedBy) put(ArtPrimitiveV2.MASKS, JsonArray(a.maskedBy.map { JsonPrimitive(it.raw) }))
			put(ArtPrimitiveV2.PARAMETERS, JsonArray(used.filter { it !in generatedParameters }.map { axis ->
				val parameter = authoredModel.parameters.single { it.id == axis }
				buildJsonObject {
					put("id", axis.raw); put("name", parameter.name); put("min", parameter.min)
					put("max", parameter.max); put("default", parameter.default); put("kind", parameter.kind.name); put("repeat", parameter.repeat)
				}
			}))
			put(ArtPrimitiveV2.GEOMETRY_RESIDUAL, residual?.residual?.let { grid -> RasterMeshCreation.grid(grid) { floats(it.positionDeltas) } } ?: JsonNull)
			put(ArtPrimitiveV2.CHANNELS_RESIDUAL, GeneratedRigJournalCodec.channels(channels))
			put(ArtPrimitiveV2.BLENDS, JsonArray(userBlends.map(::encodeBlend)))
			put(ArtPrimitiveV2.PATHS, JsonArray(userPaths.map(DeformPathJournal::encode)))
			put(ArtPrimitiveV2.VERTEX_GROUPS, JsonArray(userGroups.map { group -> buildJsonObject {
				put("name", group.name); put("kind", group.kind.jsonName); put("weights", floats(group.weights))
			} }))
		}
		val primitive = buildJsonObject {
			put(ArtPrimitiveV2.ID, a.id.raw); put(ArtPrimitiveV2.LAYER_ID, layerId); put(ArtPrimitiveV2.SOURCE_ID, textureSourceId)
			put(ArtPrimitiveV2.SOURCE_BOUNDS, floats(floatArrayOf(sourceBounds.left.toFloat(), sourceBounds.top.toFloat(),
				(sourceBounds.left + sourceBounds.width).toFloat(), (sourceBounds.top + sourceBounds.height).toFloat())))
			put(ArtPrimitiveV2.NEUTRAL_BOUNDS, floats(floatArrayOf(neutralBounds.left, neutralBounds.top, neutralBounds.right, neutralBounds.bottom)))
			put(ArtPrimitiveV2.NAME, a.name)
			put(ArtPrimitiveV2.CLASSIFICATION, ArtPrimitiveV2.encodeClassification(classification))
			// Generated unless a journal edit had reparented the original.
			put(ArtPrimitiveV2.PARENT, a.parentDeformerId?.takeIf { it != g.parentDeformerId }?.raw?.let(::JsonPrimitive) ?: JsonNull)
			putJsonObject(ArtPrimitiveV2.MESH) {
				put(ArtPrimitiveV2.CANVAS_POSITIONS, floats(canvasPositions))
				put(ArtPrimitiveV2.TRIANGLES, JsonArray(mesh.indices.map(::JsonPrimitive)))
				put(ArtPrimitiveV2.CANVAS_UVS, floats(mesh.uvs))
			}
			put(ArtPrimitiveV2.FIXED_TOPOLOGY, pinned)
			put(ArtPrimitiveV2.FROZEN_AXES, JsonArray(emptyList()))
			put(ArtPrimitiveV2.AUTHORED, authored)
		}
		return EncodedPart(primitive, residual?.overrides.orEmpty())
	}

	private fun encodeBlend(binding: BlendShapeBinding<MeshForm>) = buildJsonObject {
		put("parameter", binding.parameterId.raw); put("keys", floats(binding.keys)); put("neutral", binding.neutralIndex)
		put("forms", JsonArray(binding.forms.map { form -> form?.let { buildJsonObject {
			put("deltas", floats(it.positionDeltas)); put("order", it.drawOrder); put("opacity", it.opacity)
			put("multiply", color(it.multiplyColor)); put("screen", color(it.screenColor))
		} } ?: JsonNull }))
		put("limits", JsonArray(binding.limits.map { limit -> buildJsonObject {
			put("parameter", limit.parameterId.raw)
			put("points", JsonArray(limit.points.map { point -> buildJsonArray { add(point.value); add(point.weight) } }))
		} }))
	}

	private fun floats(values: FloatArray) = JsonArray(values.map(::JsonPrimitive))
	private fun color(value: ColorRgb) = floats(floatArrayOf(value.red, value.green, value.blue))
}
