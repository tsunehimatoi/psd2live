package org.umamo.interop

import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.lineageRoot

/*
 * The semantic diff between two PuppetModels - the input the CMO3 export reconcile dispatches on.
 *
 * The exporter never consumes the editor's Change log: it compares the session's FINAL model against
 * a baseline re-imported from the retained CMO3 graph, so deletion is "baseline entity absent from
 * the edited model", creation is the reverse, and an empty diff proves the graph needs no touch at
 * all (which is what makes the no-change byte-identity gate structural rather than accidental).
 *
 * Completeness is the load-bearing property: any semantic difference between the two models MUST
 * surface in some field below, because an edit the diff misses is an edit the export silently drops.
 * Comparisons are bit-exact (FloatArray.contentEquals semantics; raw bits for loose scalars) - the
 * diff's job is to detect that anything changed, not to judge whether the change is significant.
 */

/** The changed aspects of a [Parameter]. */
enum class ParameterField { NAME, RANGE, KIND }

/** The changed aspects of a [ParameterNode.Group]. */
enum class ParameterGroupField { NAME, INITIALLY_OPEN, CHILDREN, LABEL_COLOR }

/** The changed aspects of a [Part]. */
enum class PartField { NAME, VISIBLE, SKETCH, SELECTABLE, GROUP_MODE, DRAW_ORDER, CHILDREN, CHANNELS, COMPOSITE }

/** The changed aspects of a [Deformer]. */
enum class DeformerField {
	KIND,
	NAME,
	PARENT,
	PART,
	SELECTABLE,
	LATTICE,
	QUAD_TRANSFORM,
	BASE_ANGLE,
	HANDLE_LENGTH,
	GEOMETRY,
	CHANNELS,
	STATICS,
	BLEND_SHAPES,
}

/** The changed aspects of a [Drawable]. */
enum class DrawableField {
	NAME,
	PARENT_DEFORMER,
	BLEND_MODE,
	ALPHA_BLEND_MODE,
	MASKED_BY,
	INVERT_MASK,
	CULLING,
	VISIBLE,
	SELECTABLE,
	TEXTURE_SOURCE,
	ATLAS_TILE,
	MESH_TOPOLOGY,
	MESH_POSITIONS,
	MESH_UVS,
	GEOMETRY,
	CHANNELS,
	STATICS,
	BLEND_SHAPES,
}

/** The changed aspects of a [Glue]. */
enum class GlueField { PAIRS, CHANNELS, INTENSITY }

/** The changed aspects of an [AtlasTile]. */
enum class AtlasTileField {
	/** Where the tile's art sits on its page - the authored half, and the only one an edit produces. */
	PLACEMENT,

	/** Its name, pixel size, or source binding: what the art itself is, not where it was packed. */
	METADATA,
}

/** The changed document-level aspects of a [PuppetModel]. */
enum class DocumentField {
	CANVAS_SIZE,
	WORLD_ORIGIN,
	RUNTIME_TARGET,
	SOURCE_LAYER_DISPLAY,
	PARAMETER_ORDER,
	PARAMETER_LINKS,
	PARAMETER_TREE,
	ROOT_CHILDREN,
	ATLAS_PAGES,
	SOURCE_FILES,
}

/**
 * One entity's difference between the baseline and the edited model: present only in the edited
 * model (Created), only in the baseline (Deleted), or in both with changed fields (Changed).
 */
sealed interface EntityDiff<out TId, out TField> {
	/** The entity's id in whichever model carries it. */
	val id: TId

	/** An entity the edited model has and the baseline does not - it needs CMO3-side synthesis. */
	data class Created<out TId>(override val id: TId) : EntityDiff<TId, Nothing>

	/** An entity the baseline has and the edited model does not - its CMO3 node must be removed. */
	data class Deleted<out TId>(override val id: TId) : EntityDiff<TId, Nothing>

	/** An entity present in both models whose [fields] differ. */
	data class Changed<out TId, TField>(override val id: TId, val fields: Set<TField>) : EntityDiff<TId, TField>
}

/**
 * One glue's difference.  A glue has no id (CGlueSource carries none), so it is keyed by its
 * ordered mesh pair plus an ordinal among glues sharing that pair.
 */
sealed interface GlueDiff {
	val meshA: DrawableId
	val meshB: DrawableId
	val ordinal: Int

	/** A glue only the edited model has. */
	data class Created(
		override val meshA: DrawableId,
		override val meshB: DrawableId,
		override val ordinal: Int,
	) : GlueDiff

	/** A glue only the baseline has. */
	data class Deleted(
		override val meshA: DrawableId,
		override val meshB: DrawableId,
		override val ordinal: Int,
	) : GlueDiff

	/** A glue present in both models whose [fields] differ. */
	data class Changed(
		override val meshA: DrawableId,
		override val meshB: DrawableId,
		override val ordinal: Int,
		val fields: Set<GlueField>,
	) : GlueDiff
}

/**
 * The complete semantic difference between a baseline and an edited [PuppetModel].
 *
 * @property List parameters      Per-parameter diffs.
 * @property List parameterGroups Per-parameter-panel-group diffs (groups flattened out of the tree).
 * @property List parts           Per-part diffs.
 * @property List deformers       Per-deformer diffs.
 * @property List drawables       Per-drawable diffs.
 * @property List glues           Per-glue diffs.
 * @property List atlasTiles      Per-atlas-tile diffs.
 * @property Set  document        Document-level field diffs.
 */
data class PuppetDiff(
	val parameters: List<EntityDiff<ParameterId, ParameterField>>,
	val parameterGroups: List<EntityDiff<ParameterGroupId, ParameterGroupField>>,
	val parts: List<EntityDiff<PartId, PartField>>,
	val deformers: List<EntityDiff<DeformerId, DeformerField>>,
	val drawables: List<EntityDiff<DrawableId, DrawableField>>,
	val glues: List<GlueDiff>,
	val atlasTiles: List<EntityDiff<AtlasTileId, AtlasTileField>>,
	val document: Set<DocumentField>,
) {
	/** True when the models are semantically identical and the export graph needs no touch. */
	val isEmpty: Boolean
		get() =
			parameters.isEmpty() &&
				parameterGroups.isEmpty() &&
				parts.isEmpty() &&
				deformers.isEmpty() &&
				drawables.isEmpty() &&
				glues.isEmpty() &&
				atlasTiles.isEmpty() &&
				document.isEmpty()
}

/**
 * Diffs [edited] against [baseline] (the model re-imported from the retained CMO3 graph).
 *
 * @param PuppetModel baseline The graph-derived baseline.
 * @param PuppetModel edited   The session's current model.
 * @return PuppetDiff The complete semantic difference.
 */
fun diffPuppetModels(baseline: PuppetModel, edited: PuppetModel): PuppetDiff =
	PuppetDiff(
		parameters = diffEntities(baseline.parameters, edited.parameters, Parameter::id, ::parameterFields),
		parameterGroups =
			diffEntities(
				flattenGroups(baseline.parameterTree),
				flattenGroups(edited.parameterTree),
				ParameterNode.Group::id,
				::parameterGroupFields,
			),
		parts = diffEntities(baseline.parts, edited.parts, Part::id, ::partFields),
		deformers = diffEntities(baseline.deformers, edited.deformers, Deformer::id, ::deformerFields),
		drawables = diffEntities(baseline.drawables, edited.drawables, Drawable::id, ::drawableFields),
		glues = diffGlues(baseline.glues, edited.glues),
		atlasTiles = diffAtlasTiles(baseline.atlas.tiles, edited.atlas.tiles),
		document = documentFields(baseline, edited),
	)

/**
 * Generic per-category entity diff: keys both lists by id, emits Created/Deleted for one-sided ids
 * and Changed for shared ids whose [changedFields] set is non-empty.  Baseline-order for stability.
 *
 * @param List     baselineEntities The baseline's entities.
 * @param List     editedEntities   The edited model's entities.
 * @param Function idOf             The entity's id accessor.
 * @param Function changedFields    The field comparator for a shared id.
 * @return List The category's diffs, empty when nothing differs.
 */
private inline fun <TEntity, TId, TField> diffEntities(
	baselineEntities: List<TEntity>,
	editedEntities: List<TEntity>,
	idOf: (TEntity) -> TId,
	changedFields: (TEntity, TEntity) -> Set<TField>,
): List<EntityDiff<TId, TField>> {
	val baselineById = baselineEntities.associateBy(idOf)
	val editedById = editedEntities.associateBy(idOf)
	val diffs = ArrayList<EntityDiff<TId, TField>>()
	for (baselineEntity in baselineEntities) {
		val entityId = idOf(baselineEntity)
		val editedEntity = editedById[entityId]
		if (editedEntity == null) {
			diffs.add(EntityDiff.Deleted(entityId))
			continue
		}
		if (baselineEntity === editedEntity) {
			continue
		}
		val fields = changedFields(baselineEntity, editedEntity)
		if (fields.isNotEmpty()) {
			diffs.add(EntityDiff.Changed(entityId, fields))
		}
	}
	for (editedEntity in editedEntities) {
		val entityId = idOf(editedEntity)
		if (entityId !in baselineById) {
			diffs.add(EntityDiff.Created(entityId))
		}
	}
	return diffs
}

private fun parameterFields(baseline: Parameter, edited: Parameter): Set<ParameterField> =
	buildSet {
		if (baseline.name != edited.name) {
			add(ParameterField.NAME)
		}
		if (!floatEq(baseline.min, edited.min) || !floatEq(baseline.max, edited.max) || !floatEq(baseline.default, edited.default)) {
			add(ParameterField.RANGE)
		}
		if (baseline.kind != edited.kind) {
			add(ParameterField.KIND)
		}
	}

private fun parameterGroupFields(baseline: ParameterNode.Group, edited: ParameterNode.Group): Set<ParameterGroupField> =
	buildSet {
		if (baseline.name != edited.name) {
			add(ParameterGroupField.NAME)
		}
		if (baseline.initiallyOpen != edited.initiallyOpen) {
			add(ParameterGroupField.INITIALLY_OPEN)
		}
		if (nodeIdentities(baseline.children) != nodeIdentities(edited.children)) {
			add(ParameterGroupField.CHILDREN)
		}
		if (baseline.labelColor != edited.labelColor) {
			add(ParameterGroupField.LABEL_COLOR)
		}
	}

private fun partFields(baseline: Part, edited: Part): Set<PartField> =
	buildSet {
		if (baseline.name != edited.name) {
			add(PartField.NAME)
		}
		if (baseline.isVisible != edited.isVisible) {
			add(PartField.VISIBLE)
		}
		if (baseline.isSketch != edited.isSketch) {
			add(PartField.SKETCH)
		}
		if (baseline.isSelectable != edited.isSelectable) {
			add(PartField.SELECTABLE)
		}
		if (baseline.groupMode != edited.groupMode) {
			add(PartField.GROUP_MODE)
		}
		if (baseline.drawOrder != edited.drawOrder) {
			add(PartField.DRAW_ORDER)
		}
		if (baseline.children != edited.children) {
			add(PartField.CHILDREN)
		}
		if (!channelGridsEqual(baseline.channelGrids, edited.channelGrids)) {
			add(PartField.CHANNELS)
		}
		if (baseline.composite != edited.composite) {
			add(PartField.COMPOSITE)
		}
	}

private fun deformerFields(baseline: Deformer, edited: Deformer): Set<DeformerField> =
	buildSet {
		if (baseline::class != edited::class) {
			// A kind swap under one id has no edit op; surfacing it beats mis-reading field diffs.
			add(DeformerField.KIND)
			return@buildSet
		}
		if (baseline.name != edited.name) {
			add(DeformerField.NAME)
		}
		if (baseline.parent != edited.parent) {
			add(DeformerField.PARENT)
		}
		if (baseline.partId != edited.partId) {
			add(DeformerField.PART)
		}
		if (baseline.isSelectable != edited.isSelectable) {
			add(DeformerField.SELECTABLE)
		}
		when (baseline) {
			is Deformer.Warp -> {
				val editedWarp = edited as Deformer.Warp
				if (baseline.rows != editedWarp.rows || baseline.columns != editedWarp.columns) {
					add(DeformerField.LATTICE)
				}
				if (baseline.isQuadTransform != editedWarp.isQuadTransform) {
					add(DeformerField.QUAD_TRANSFORM)
				}
				if (!gridEquals(baseline.geometryGrid, editedWarp.geometryGrid, ::warpLatticeFormEqual)) {
					add(DeformerField.GEOMETRY)
				}
				if (
					!floatEq(baseline.opacity, editedWarp.opacity) ||
					baseline.multiplyColor != editedWarp.multiplyColor ||
					baseline.screenColor != editedWarp.screenColor
				) {
					add(DeformerField.STATICS)
				}
				if (!blendShapesEqual(baseline.blendShapes, editedWarp.blendShapes, ::warpFormEqual)) {
					add(DeformerField.BLEND_SHAPES)
				}
			}

			is Deformer.Rotation -> {
				val editedRotation = edited as Deformer.Rotation
				if (!floatEq(baseline.baseAngle, editedRotation.baseAngle)) {
					add(DeformerField.BASE_ANGLE)
				}
				if (baseline.handleLength != editedRotation.handleLength) {
					add(DeformerField.HANDLE_LENGTH)
				}
				if (!gridEquals(baseline.geometryGrid, editedRotation.geometryGrid, ::rotationPivotFormEqual)) {
					add(DeformerField.GEOMETRY)
				}
				if (
					!floatEq(baseline.opacity, editedRotation.opacity) ||
					baseline.multiplyColor != editedRotation.multiplyColor ||
					baseline.screenColor != editedRotation.screenColor ||
					baseline.flipX != editedRotation.flipX ||
					baseline.flipY != editedRotation.flipY
				) {
					add(DeformerField.STATICS)
				}
				if (!blendShapesEqual(baseline.blendShapes, editedRotation.blendShapes, ::rotationFormEqual)) {
					add(DeformerField.BLEND_SHAPES)
				}
			}
		}
		if (!channelGridsEqual(baseline.channelGrids, edited.channelGrids)) {
			add(DeformerField.CHANNELS)
		}
	}

/**
 * Diffs the atlas inventory, comparing only the tiles BOTH models carry.
 *
 * Set membership is deliberately not a difference here.  A tile is the art the document holds, which
 * arrives and leaves with an import rather than with an edit - and the two models being compared do not
 * always track it the same way: a MOC3-origin conversion synthesizes model images into the graph that
 * its puppet, which never had layered art, does not know about.  Reporting those as deletions would
 * describe an edit nobody made, on a path with nothing to reconcile.
 *
 * That is safe precisely because no edit can add or remove art today.  When re-import can (it is the
 * whole point of that phase), this grows the created/deleted arms together with the lowering that
 * serves them, rather than reporting a difference nothing acts on.
 *
 * @param List baseline The graph-derived tiles.
 * @param List edited   The session's tiles.
 * @return List One Changed entry per shared tile that differs.
 */
private fun diffAtlasTiles(baseline: List<AtlasTile>, edited: List<AtlasTile>): List<EntityDiff<AtlasTileId, AtlasTileField>> {
	val editedById = edited.associateBy { tile -> tile.id }
	// A reloaded tile is a new tile whose lineage root is the baseline's id; it is the same art to the
	// graph, compared under the root so the reload reads as that tile changing.
	val editedByRoot = edited.filter { tile -> tile.replaces != null }.associateBy { tile -> tile.id.lineageRoot }
	return baseline.mapNotNull { baselineTile ->
		val editedTile = editedById[baselineTile.id] ?: editedByRoot[baselineTile.id] ?: return@mapNotNull null
		val fields = atlasTileFields(baselineTile, editedTile)
		if (fields.isEmpty()) null else EntityDiff.Changed(baselineTile.id, fields)
	}
}

/**
 * The changed aspects of one atlas tile.
 *
 * @param AtlasTile baseline The graph-derived tile.
 * @param AtlasTile edited   The session's tile.
 * @return Set<AtlasTileField> The fields that differ.
 */
private fun atlasTileFields(baseline: AtlasTile, edited: AtlasTile): Set<AtlasTileField> =
	buildSet {
		if (baseline.placement != edited.placement) {
			add(AtlasTileField.PLACEMENT)
		}
		if (
			baseline.name != edited.name ||
			baseline.width != edited.width ||
			baseline.height != edited.height ||
			baseline.source != edited.source
		) {
			add(AtlasTileField.METADATA)
		}
	}

private fun drawableFields(baseline: Drawable, edited: Drawable): Set<DrawableField> =
	buildSet {
		if (baseline.name != edited.name) {
			add(DrawableField.NAME)
		}
		if (baseline.parentDeformerId != edited.parentDeformerId) {
			add(DrawableField.PARENT_DEFORMER)
		}
		if (baseline.blendMode != edited.blendMode) {
			add(DrawableField.BLEND_MODE)
		}
		if (baseline.alphaBlendMode != edited.alphaBlendMode) {
			add(DrawableField.ALPHA_BLEND_MODE)
		}
		if (baseline.maskedBy != edited.maskedBy) {
			add(DrawableField.MASKED_BY)
		}
		if (baseline.invertMask != edited.invertMask) {
			add(DrawableField.INVERT_MASK)
		}
		if (baseline.culling != edited.culling) {
			add(DrawableField.CULLING)
		}
		if (baseline.isVisible != edited.isVisible) {
			add(DrawableField.VISIBLE)
		}
		if (baseline.isSelectable != edited.isSelectable) {
			add(DrawableField.SELECTABLE)
		}
		if (baseline.textureSourceId != edited.textureSourceId) {
			add(DrawableField.TEXTURE_SOURCE)
		}
		// Absence is "not tracked", not "unbound", for the same reason the page list is only compared
		// when there is an atlas: a drawable the model carries no art for says nothing about the art the
		// graph carries for it.
		// Compared under the lineage root: a drawable carried onto its tile's reload replacement still
		// samples the same art as far as the graph knows.
		val editedTileId = edited.atlasTileId
		if (editedTileId != null && baseline.atlasTileId?.lineageRoot != editedTileId.lineageRoot) {
			add(DrawableField.ATLAS_TILE)
		}
		addAll(meshFields(baseline.mesh, edited.mesh))
		if (!gridEquals(restOnlyAsUnkeyed(baseline.geometryGrid), restOnlyAsUnkeyed(edited.geometryGrid), ::meshDeltaFormEqual)) {
			add(DrawableField.GEOMETRY)
		}
		if (!channelGridsEqual(baseline.channelGrids, edited.channelGrids)) {
			add(DrawableField.CHANNELS)
		}
		if (
			!floatEq(baseline.drawOrder, edited.drawOrder) ||
			!floatEq(baseline.opacity, edited.opacity) ||
			baseline.multiplyColor != edited.multiplyColor ||
			baseline.screenColor != edited.screenColor
		) {
			add(DrawableField.STATICS)
		}
		if (!blendShapesEqual(baseline.blendShapes, edited.blendShapes, ::meshFormEqual)) {
			add(DrawableField.BLEND_SHAPES)
		}
	}

/**
 * The mesh aspect of a drawable diff.  A vertex-count or triangulation change (or a mesh appearing/
 * disappearing) is MESH_TOPOLOGY; with topology intact, moved vertices are MESH_POSITIONS and
 * remapped texels MESH_UVS - the split the lowering dispatches on, since topology forces a full
 * GEditableMesh2 rebuild while the others patch arrays in place.
 *
 * @param DrawableMesh? baseline The baseline's mesh.
 * @param DrawableMesh? edited   The edited model's mesh.
 * @return Set The changed mesh fields.
 */
private fun meshFields(baseline: DrawableMesh?, edited: DrawableMesh?): Set<DrawableField> {
	if (baseline === edited) {
		return emptySet()
	}
	if (baseline == null || edited == null) {
		return setOf(DrawableField.MESH_TOPOLOGY)
	}
	if (baseline.vertexCount != edited.vertexCount || !baseline.indices.contentEquals(edited.indices)) {
		return setOf(DrawableField.MESH_TOPOLOGY)
	}
	return buildSet {
		if (!baseline.positions.contentEquals(edited.positions)) {
			add(DrawableField.MESH_POSITIONS)
		}
		if (!baseline.uvs.contentEquals(edited.uvs)) {
			add(DrawableField.MESH_UVS)
		}
	}
}

private fun diffGlues(baselineGlues: List<Glue>, editedGlues: List<Glue>): List<GlueDiff> {
	if (baselineGlues === editedGlues) {
		return emptyList()
	}
	// A glue has no id: key by the ordered mesh pair, with an ordinal among same-pair glues.
	val baselineByPair = baselineGlues.groupBy { glue -> glue.meshA to glue.meshB }
	val editedByPair = editedGlues.groupBy { glue -> glue.meshA to glue.meshB }
	val diffs = ArrayList<GlueDiff>()
	for ((pair, baselineList) in baselineByPair) {
		val editedList = editedByPair[pair].orEmpty()
		for (ordinal in baselineList.indices) {
			val editedGlue = editedList.getOrNull(ordinal)
			if (editedGlue == null) {
				diffs.add(GlueDiff.Deleted(pair.first, pair.second, ordinal))
				continue
			}
			val fields = glueFields(baselineList[ordinal], editedGlue)
			if (fields.isNotEmpty()) {
				diffs.add(GlueDiff.Changed(pair.first, pair.second, ordinal, fields))
			}
		}
	}
	for ((pair, editedList) in editedByPair) {
		val baselineCount = baselineByPair[pair].orEmpty().size
		for (ordinal in baselineCount until editedList.size) {
			diffs.add(GlueDiff.Created(pair.first, pair.second, ordinal))
		}
	}
	return diffs
}

private fun glueFields(baseline: Glue, edited: Glue): Set<GlueField> {
	if (baseline === edited) {
		return emptySet()
	}
	return buildSet {
		val pairsEqual =
			baseline.pairs.size == edited.pairs.size &&
				baseline.pairs.indices.all { pairIndex ->
					val baselinePair = baseline.pairs[pairIndex]
					val editedPair = edited.pairs[pairIndex]
					baselinePair.indexA == editedPair.indexA &&
						baselinePair.indexB == editedPair.indexB &&
						floatEq(baselinePair.weightA, editedPair.weightA) &&
						floatEq(baselinePair.weightB, editedPair.weightB)
				}
		if (!pairsEqual) {
			add(GlueField.PAIRS)
		}
		if (!channelGridsEqual(baseline.channelGrids, edited.channelGrids)) {
			add(GlueField.CHANNELS)
		}
		if (!floatEq(baseline.intensity, edited.intensity)) {
			add(GlueField.INTENSITY)
		}
	}
}

private fun documentFields(baseline: PuppetModel, edited: PuppetModel): Set<DocumentField> =
	buildSet {
		if (!floatEq(baseline.canvasWidth, edited.canvasWidth) || !floatEq(baseline.canvasHeight, edited.canvasHeight)) {
			add(DocumentField.CANVAS_SIZE)
		}
		if (!floatEq(baseline.worldOriginX, edited.worldOriginX) || !floatEq(baseline.worldOriginY, edited.worldOriginY)) {
			add(DocumentField.WORLD_ORIGIN)
		}
		if (baseline.runtimeTarget != edited.runtimeTarget) {
			add(DocumentField.RUNTIME_TARGET)
		}
		if (baseline.rendersFromSourceLayers != edited.rendersFromSourceLayers) {
			add(DocumentField.SOURCE_LAYER_DISPLAY)
		}
		// The page LIST, not the tiles on it: a page appearing, disappearing, or resizing changes what
		// every placement on it means, and no per-tile diff would say so.
		//
		// Only when the edited model actually tracks an atlas.  Carrying none is "this model has no
		// source art" - a MOC3 origin never had any - and is not the same as an atlas that was emptied;
		// comparing against a graph that does carry one would report a deletion nobody performed.
		if (edited.atlas.tiles.isNotEmpty() && baseline.atlas.pages != edited.atlas.pages) {
			add(DocumentField.ATLAS_PAGES)
		}
		// Document order of the flat parameter list is semantic in CMO3: combined (2D) pairs are
		// encoded positionally (the Y axis is the next source after its combined X).
		if (baseline.parameters.map(Parameter::id) != edited.parameters.map(Parameter::id)) {
			add(DocumentField.PARAMETER_ORDER)
		}
		if (baseline.parameterLinks != edited.parameterLinks) {
			add(DocumentField.PARAMETER_LINKS)
		}
		if (nodeIdentities(baseline.parameterTree) != nodeIdentities(edited.parameterTree)) {
			add(DocumentField.PARAMETER_TREE)
		}
		if (baseline.rootChildren != edited.rootChildren) {
			add(DocumentField.ROOT_CHILDREN)
		}
		// The file each source record points at - its name, path, and modification time - and nothing
		// else about the record: the inventory and the hashes change on every reload and have no CMO3
		// home, so they must not turn a reload into a document change the export cannot lower.  Only
		// the records BOTH models list: a record one side lacks is art added this session, or a page
		// slice a fresh synthesis minted under its own id, and has no layered image to repoint.
		if (sourceFilesChanged(baseline, edited)) {
			add(DocumentField.SOURCE_FILES)
		}
	}

/**
 * Whether any source record both models list points at a different file in [edited]: another name,
 * path, or modification time.
 *
 * @param PuppetModel baseline The graph-derived baseline.
 * @param PuppetModel edited   The session's current model.
 * @return Boolean True when a shared record was repointed.
 */
private fun sourceFilesChanged(baseline: PuppetModel, edited: PuppetModel): Boolean {
	val baselineById = baseline.sources.associateBy { source -> source.id }
	return edited.sources.any { source ->
		val before = baselineById[source.id] ?: return@any false
		before.name != source.name || before.path != source.path || before.lastModified != source.lastModified
	}
}

/**
 * The identity sequence of one tree level - a Param's id or a Group's id, in order.  Group content
 * changes are reported per group (CHILDREN), so tree-level comparisons only look at this level's
 * ordered identities.
 *
 * @param List nodes The level's nodes.
 * @return List One identity token per node.
 */
private fun nodeIdentities(nodes: List<ParameterNode>): List<String> =
	nodes.map { node ->
		when (node) {
			is ParameterNode.Param -> "p:${node.id.raw}"
			is ParameterNode.Group -> "g:${node.id.raw}"
		}
	}

private fun flattenGroups(tree: List<ParameterNode>): List<ParameterNode.Group> {
	val groups = ArrayList<ParameterNode.Group>()

	fun walk(nodes: List<ParameterNode>) {
		for (node in nodes) {
			if (node is ParameterNode.Group) {
				groups.add(node)
				walk(node.children)
			}
		}
	}
	walk(tree)
	return groups
}

/*
 * Bit-exact equality helpers.  Two independent imports of the same graph produce bit-identical
 * floats, so raw-bits comparison never yields a false diff there - while an edit that flips only a
 * sign bit (0.0 vs -0.0) still surfaces.  Every helper takes the `===` fast path first: the common
 * call compares a model against itself or against structurally shared sub-objects.
 */

private fun floatEq(baseline: Float, edited: Float): Boolean = baseline.toRawBits() == edited.toRawBits()

/**
 * A drawable's geometry grid with the rest-only shape read as unkeyed: an axis-less grid holding one
 * cell of zero deltas IS the unkeyed drawable, since both mean "the base mesh, nothing keyed".  The
 * two spellings arise from the formats - every CMO3 source carries a default form, so an unkeyed
 * drawable exports as that one cell and re-imports as this grid - and neither is an edit of the
 * other, so the diff must not call it one.
 *
 * @param KeyformGrid? grid The drawable's geometry grid.
 * @return KeyformGrid? The grid, or null when it is the rest-only shape.
 */
private fun restOnlyAsUnkeyed(grid: KeyformGrid<MeshDeltaForm>?): KeyformGrid<MeshDeltaForm>? {
	if (grid == null || grid.axes.isNotEmpty() || grid.cells.size != 1) {
		return grid
	}
	val deltas = grid.cells.single().form.positionDeltas
	return if (deltas.all { delta -> delta == 0f }) null else grid
}

private fun <TForm> gridEquals(
	baseline: KeyformGrid<TForm>?,
	edited: KeyformGrid<TForm>?,
	formEqual: (TForm, TForm) -> Boolean,
): Boolean {
	if (baseline === edited) {
		return true
	}
	if (baseline == null || edited == null) {
		return false
	}
	if (baseline.axes.size != edited.axes.size || baseline.cells.size != edited.cells.size) {
		return false
	}
	for (axisIndex in baseline.axes.indices) {
		val baselineAxis = baseline.axes[axisIndex]
		val editedAxis = edited.axes[axisIndex]
		if (baselineAxis.parameterId != editedAxis.parameterId || !baselineAxis.keys.contentEquals(editedAxis.keys)) {
			return false
		}
	}
	// Cells compare by coordinate, not list position: the import and the edit ops are both free to
	// order cells differently without that being a semantic change.
	val baselineByCoordinate = HashMap<List<Int>, TForm>(baseline.cells.size)
	for (cell in baseline.cells) {
		baselineByCoordinate[cell.coordinate.toList()] = cell.form
	}
	for (cell in edited.cells) {
		val baselineForm = baselineByCoordinate[cell.coordinate.toList()] ?: return false
		if (!formEqual(baselineForm, cell.form)) {
			return false
		}
	}
	return true
}

private fun channelGridsEqual(baseline: ChannelGrids, edited: ChannelGrids): Boolean {
	if (baseline === edited) {
		return true
	}
	if (baseline.gridsByChannel.keys != edited.gridsByChannel.keys) {
		return false
	}
	for ((channel, baselineGrid) in baseline.gridsByChannel) {
		val editedGrid = edited.gridsByChannel.getValue(channel)
		if (!gridEquals(baselineGrid, editedGrid) { baselineValue, editedValue -> baselineValue == editedValue }) {
			return false
		}
	}
	return true
}

private fun <TForm : Any> blendShapesEqual(
	baseline: List<BlendShapeBinding<TForm>>,
	edited: List<BlendShapeBinding<TForm>>,
	formEqual: (TForm, TForm) -> Boolean,
): Boolean {
	if (baseline === edited) {
		return true
	}
	if (baseline.size != edited.size) {
		return false
	}
	for (bindingIndex in baseline.indices) {
		val baselineBinding = baseline[bindingIndex]
		val editedBinding = edited[bindingIndex]
		if (
			baselineBinding.parameterId != editedBinding.parameterId ||
			!baselineBinding.keys.contentEquals(editedBinding.keys) ||
			baselineBinding.neutralIndex != editedBinding.neutralIndex ||
			baselineBinding.limits != editedBinding.limits ||
			baselineBinding.forms.size != editedBinding.forms.size
		) {
			return false
		}
		for (formIndex in baselineBinding.forms.indices) {
			val baselineForm = baselineBinding.forms[formIndex]
			val editedForm = editedBinding.forms[formIndex]
			if (baselineForm == null || editedForm == null) {
				if (baselineForm !== editedForm) {
					return false
				}
				continue
			}
			if (!formEqual(baselineForm, editedForm)) {
				return false
			}
		}
	}
	return true
}

private fun meshDeltaFormEqual(baseline: MeshDeltaForm, edited: MeshDeltaForm): Boolean =
	baseline === edited || baseline.positionDeltas.contentEquals(edited.positionDeltas)

private fun warpLatticeFormEqual(baseline: WarpLatticeForm, edited: WarpLatticeForm): Boolean =
	baseline === edited || baseline.controlPoints.contentEquals(edited.controlPoints)

private fun rotationPivotFormEqual(baseline: RotationPivotForm, edited: RotationPivotForm): Boolean =
	baseline === edited ||
		(
			floatEq(baseline.originX, edited.originX) &&
				floatEq(baseline.originY, edited.originY) &&
				floatEq(baseline.angle, edited.angle) &&
				floatEq(baseline.scale, edited.scale)
		)

private fun meshFormEqual(baseline: MeshForm, edited: MeshForm): Boolean =
	baseline === edited ||
		(
			baseline.positionDeltas.contentEquals(edited.positionDeltas) &&
				floatEq(baseline.drawOrder, edited.drawOrder) &&
				floatEq(baseline.opacity, edited.opacity) &&
				baseline.multiplyColor == edited.multiplyColor &&
				baseline.screenColor == edited.screenColor
		)

private fun warpFormEqual(baseline: WarpForm, edited: WarpForm): Boolean =
	baseline === edited ||
		(
			baseline.controlPoints.contentEquals(edited.controlPoints) &&
				floatEq(baseline.opacity, edited.opacity) &&
				baseline.multiplyColor == edited.multiplyColor &&
				baseline.screenColor == edited.screenColor
		)

private fun rotationFormEqual(baseline: RotationForm, edited: RotationForm): Boolean =
	baseline === edited ||
		(
			floatEq(baseline.originX, edited.originX) &&
				floatEq(baseline.originY, edited.originY) &&
				floatEq(baseline.angle, edited.angle) &&
				floatEq(baseline.scale, edited.scale) &&
				baseline.flipX == edited.flipX &&
				baseline.flipY == edited.flipY &&
				floatEq(baseline.opacity, edited.opacity) &&
				baseline.multiplyColor == edited.multiplyColor &&
				baseline.screenColor == edited.screenColor
		)