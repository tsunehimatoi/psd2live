package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.edit
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.raster.RasterImage
import org.umamo.interop.DeformerField
import org.umamo.interop.DrawableField
import org.umamo.interop.EntityDiff
import org.umamo.interop.ExportFormat
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportReport
import org.umamo.interop.GlueDiff
import org.umamo.interop.GlueField
import org.umamo.interop.ParameterField
import org.umamo.interop.ParameterGroupField
import org.umamo.interop.PartField
import org.umamo.interop.PuppetDiff
import org.umamo.interop.diffPuppetModels
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PuppetModel

/**
 * Lowers an edited [PuppetModel] back onto the retained CMO3 graph it was imported from - the
 * export half of the CMO3 interop boundary (Cmo3Import is the import half).
 *
 * The design is a state-based reconcile, not a Change-log replay: [apply] re-imports the graph
 * to get a baseline, diffs the edited model against it, and only diff entries touch the graph.
 * An unedited model therefore leaves the graph untouched and a subsequent Cmo3.write byte-
 * identical; a re-export after an export reconciles against the just-updated graph and is a
 * no-op.  Structure runs first (created entities get identity shells, deleted ones leave their
 * source sets), then every surviving/created entity's fields flow through the one field-level
 * lowering path.  Edits the lowering cannot (yet or ever) express in CMO3 are returned as
 * notices, never silently dropped.
 *
 * The graph is mutated IN PLACE ([Cmo3Model] holds identity-keyed reconcile metadata a deep
 * copy cannot carry).  The full diff is computed before any mutation, so a failed export
 * leaves either the untouched or the fully-reconciled graph - and either way the next apply
 * self-heals from model state.
 */
object Cmo3Export {
	/** Every field of a synthesized drawable, so its shell is populated through the Changed path. */
	private val ALL_DRAWABLE_FIELDS =
		setOf(
			DrawableField.NAME,
			DrawableField.PARENT_DEFORMER,
			DrawableField.BLEND_MODE,
			DrawableField.ALPHA_BLEND_MODE,
			DrawableField.MASKED_BY,
			DrawableField.INVERT_MASK,
			DrawableField.CULLING,
			DrawableField.VISIBLE,
			DrawableField.SELECTABLE,
			DrawableField.MESH_TOPOLOGY,
			DrawableField.GEOMETRY,
			DrawableField.CHANNELS,
			DrawableField.STATICS,
			DrawableField.BLEND_SHAPES,
		)

	/** Every field of a synthesized part. */
	private val ALL_PART_FIELDS = PartField.entries.toSet()

	/** Every warp field except KIND (fixed at synthesis) and the rotation-only BASE_ANGLE. */
	private val ALL_WARP_FIELDS = DeformerField.entries.toSet() - DeformerField.KIND - DeformerField.BASE_ANGLE - DeformerField.HANDLE_LENGTH

	/** Every rotation field except KIND (fixed at synthesis) and the warp-only lattice pair. */
	private val ALL_ROTATION_FIELDS =
		DeformerField.entries.toSet() - DeformerField.KIND - DeformerField.LATTICE - DeformerField.QUAD_TRANSFORM

	/** Every field of a synthesized glue. */
	private val ALL_GLUE_FIELDS = GlueField.entries.toSet()

	/**
	 * Reconciles [edited] onto [target]'s graph in place and returns the advisory report.
	 *
	 * The fresh-graph synthesis path (MOC3-origin -> CMO3) constructs a blank target graph and
	 * reuses this same reconcile: an empty baseline lowers everything as created, with
	 * [drawableTextureBindings] supplying the texture web created drawables bind to when they have
	 * no existing source to clone (a session duplicate) - the CMO3-origin export path passes none,
	 * and its created drawables over reconciled art bind through the bindings the atlas-web
	 * reconcile mints instead.
	 *
	 * @param PuppetModel edited The session's current model (EditorSession.model.value - NOT the
	 *                           document's original import, which edits never update).
	 * @param Cmo3Model   target The retained CMO3 model whose graph receives the edits.
	 * @param Map         drawableTextureBindings Per-drawable-id texture webs for created drawables
	 *                           without a texture source; empty for CMO3-origin exports.
	 * @param List        recomposedPages The pages a repack composed for the edited model's packing,
	 *                           in the edited model's page order; empty when the session did not
	 *                           repack, which leaves the graph and archive untouched.
	 * @param Function    tileRasters The document's own pixels for a tile, or null when it holds none;
	 *                           a reloaded or added tile with a raster writes its real layer into the
	 *                           retained graph, and one without declines the atlas-web reconcile.
	 * @param Long        nowMillis The timestamp a layer minted into the retained graph records.
	 * @param RasterImage modelThumbnail The model's rest-pose thumbnail the three model icons take on
	 *                           an edited export, or null to leave the icons the graph has.
	 * @return ExportReport The notices for everything not (yet) lowered.
	 */
	fun apply(
		edited: PuppetModel,
		target: Cmo3Model,
		drawableTextureBindings: Map<String, Cmo3DrawableTextureBinding> = emptyMap(),
		recomposedPages: List<Cmo3Conversion.AtlasPage> = emptyList(),
		tileRasters: (AtlasTileId) -> RasterImage? = { null },
		nowMillis: Long = 0L,
		modelThumbnail: RasterImage? = null,
	): ExportReport {
		val modelSource = target.root as? CModelSource ?: error("CMO3 model root is not a CModelSource")
		val baseline = Cmo3Import.fromModelSource(modelSource)
		val diff = diffPuppetModels(baseline, edited)
		if (diff.isEmpty && baseline.deformPaths == edited.deformPaths) {
			return ExportReport(ExportFormat.Cmo3, emptyList())
		}
		val notices = ArrayList<ExportNotice>()
		val editor = target.edit()
		// The atlas-web reconcile runs before the graph lowering so the stale-page notices know
		// whether the stored pages already show the new packing.  Strictly diff-gated by the CALLER:
		// an unedited document passes no pages, and the graph and archive are then never touched.
		val webResult = Cmo3AtlasWebLowering(target, modelSource, baseline, edited, editor, tileRasters, nowMillis).reconcile(recomposedPages)
		val pagesRecomposed = webResult.pagesRecomposed
		// The model icons follow an edited export (the diff is non-empty here): the editor regenerates
		// them on its own saves, and a thumbnail of the rest pose is what it shows for the file.
		if (modelThumbnail != null) {
			(modelSource.textureManager as? CTextureManager)?.let { textureManager ->
				Cmo3RetainedLayerWeb(target, textureManager, editor, edited, tileRasters, nowMillis).replaceModelIcons(modelSource, modelThumbnail)
			}
		}
		// A created drawable over art the reconcile minted binds through the binding it handed back;
		// a caller's own binding for the same drawable (the fresh-graph path) outranks it.
		val bindings =
			if (webResult.mintedBindings.isEmpty()) {
				drawableTextureBindings
			} else {
				webResult.mintedBindings + drawableTextureBindings
			}

		// Structural pass - set membership: identity shells for creations, source removal for
		// deletions.  Category order follows the reference web (parameters/groups first, then parts
		// and deformers, then the drawables that bind to them, then the glues that bind drawables),
		// and the structural index is REBUILT between categories so same-export creations resolve.
		var anyDeleted = webResult.pruneNeeded
		var structural =
			Cmo3StructureLowering(
				modelSource,
				Cmo3GraphIndex(modelSource),
				editor,
				edited,
				notices,
				bindings,
			)

		/**
		 * Rolls the structural lowering onto a fresh index, carrying the deletion flag forward.
		 *
		 * @return Cmo3StructureLowering The replacement instance.
		 */
		fun refreshedStructural(): Cmo3StructureLowering {
			anyDeleted = anyDeleted || structural.deletedAnything
			return Cmo3StructureLowering(
				modelSource,
				Cmo3GraphIndex(modelSource),
				editor,
				edited,
				notices,
				bindings,
			)
		}

		val editedParameterById = edited.parameters.associateBy(Parameter::id)
		val editedPartById = edited.parts.associateBy(Part::id)
		val editedDeformerById = edited.deformers.associateBy(Deformer::id)
		val editedDrawableById = edited.drawables.associateBy(Drawable::id)
		val synthesizedParameters = HashSet<Any>()
		val synthesizedGroups = HashSet<Any>()
		val synthesizedParts = HashSet<Any>()
		val synthesizedDeformers = HashSet<Any>()
		val synthesizedDrawables = HashSet<Any>()
		val synthesizedGlues = HashSet<Triple<Any, Any, Int>>()
		for (entityDiff in diff.parameters) {
			when (entityDiff) {
				is EntityDiff.Created ->
					editedParameterById[entityDiff.id]?.let { parameter ->
						if (structural.synthesizeParameter(parameter)) {
							synthesizedParameters.add(entityDiff.id)
						}
					}

				is EntityDiff.Deleted -> structural.deleteParameter(entityDiff.id)
				is EntityDiff.Changed -> Unit
			}
		}
		for (entityDiff in diff.parameterGroups) {
			when (entityDiff) {
				is EntityDiff.Created ->
					if (structural.synthesizeParameterGroup(entityDiff.id)) {
						synthesizedGroups.add(entityDiff.id)
					}

				is EntityDiff.Deleted -> structural.deleteParameterGroup(entityDiff.id)
				is EntityDiff.Changed -> Unit
			}
		}
		for (entityDiff in diff.parts) {
			when (entityDiff) {
				is EntityDiff.Created ->
					editedPartById[entityDiff.id]?.let { part ->
						if (structural.synthesizePart(part)) {
							synthesizedParts.add(entityDiff.id)
						}
					}

				is EntityDiff.Deleted -> structural.deletePart(entityDiff.id.raw)
				is EntityDiff.Changed -> Unit
			}
		}
		for (entityDiff in diff.deformers) {
			when (entityDiff) {
				is EntityDiff.Created ->
					editedDeformerById[entityDiff.id]?.let { deformer ->
						if (structural.synthesizeDeformer(deformer)) {
							synthesizedDeformers.add(entityDiff.id)
						}
					}

				is EntityDiff.Deleted -> structural.deleteDeformer(entityDiff.id.raw)
				is EntityDiff.Changed -> Unit
			}
		}
		structural = refreshedStructural()
		for (entityDiff in diff.drawables) {
			when (entityDiff) {
				is EntityDiff.Created ->
					editedDrawableById[entityDiff.id]?.let { drawable ->
						if (structural.synthesizeDrawable(drawable)) {
							synthesizedDrawables.add(entityDiff.id)
						}
					}

				is EntityDiff.Deleted -> structural.deleteDrawable(entityDiff.id)
				is EntityDiff.Changed -> Unit
			}
		}
		structural = refreshedStructural()
		val editedGluesByPair = edited.glues.groupBy { glue -> glue.meshA to glue.meshB }
		for (glueDiff in diff.glues) {
			when (glueDiff) {
				is GlueDiff.Created ->
					editedGluesByPair[glueDiff.meshA to glueDiff.meshB]?.getOrNull(glueDiff.ordinal)?.let { glue ->
						if (structural.synthesizeGlue(glue, glueDiff.ordinal)) {
							synthesizedGlues.add(Triple(glueDiff.meshA, glueDiff.meshB, glueDiff.ordinal))
						}
					}

				is GlueDiff.Deleted -> structural.deleteGlue(glueDiff.meshA, glueDiff.meshB, glueDiff.ordinal)
				is GlueDiff.Changed -> Unit
			}
		}
		anyDeleted = anyDeleted || structural.deletedAnything

		// Deletions are done; synthesized creations become Changed-with-every-field so their shells
		// are populated by the ordinary field lowering (one tested path, no synthesis copy of it).
		val upgradedDiff =
			PuppetDiff(
				parameters =
					diff.parameters.mapNotNull { entityDiff ->
						when {
							entityDiff is EntityDiff.Created && entityDiff.id in synthesizedParameters ->
								EntityDiff.Changed(entityDiff.id, setOf(ParameterField.NAME, ParameterField.RANGE))

							entityDiff is EntityDiff.Deleted -> null
							else -> entityDiff
						}
					},
				parameterGroups =
					diff.parameterGroups.mapNotNull { entityDiff ->
						when {
							entityDiff is EntityDiff.Created && entityDiff.id in synthesizedGroups ->
								EntityDiff.Changed(
									entityDiff.id,
									setOf(
										ParameterGroupField.NAME,
										ParameterGroupField.INITIALLY_OPEN,
										ParameterGroupField.CHILDREN,
									),
								)

							entityDiff is EntityDiff.Deleted -> null
							else -> entityDiff
						}
					},
				parts =
					diff.parts.mapNotNull { entityDiff ->
						when {
							entityDiff is EntityDiff.Created && entityDiff.id in synthesizedParts ->
								EntityDiff.Changed(entityDiff.id, ALL_PART_FIELDS)

							entityDiff is EntityDiff.Deleted -> null
							else -> entityDiff
						}
					},
				deformers =
					diff.deformers.mapNotNull { entityDiff ->
						when {
							entityDiff is EntityDiff.Created && entityDiff.id in synthesizedDeformers ->
								EntityDiff.Changed(
									entityDiff.id,
									if (editedDeformerById[entityDiff.id] is Deformer.Warp) ALL_WARP_FIELDS else ALL_ROTATION_FIELDS,
								)

							entityDiff is EntityDiff.Deleted -> null
							else -> entityDiff
						}
					},
				drawables =
					diff.drawables.mapNotNull { entityDiff ->
						when {
							entityDiff is EntityDiff.Created && entityDiff.id in synthesizedDrawables ->
								EntityDiff.Changed(entityDiff.id, ALL_DRAWABLE_FIELDS)

							entityDiff is EntityDiff.Deleted -> null
							else -> entityDiff
						}
					},
				glues =
					diff.glues.mapNotNull { glueDiff ->
						when {
							glueDiff is GlueDiff.Created &&
								Triple<Any, Any, Int>(
									glueDiff.meshA,
									glueDiff.meshB,
									glueDiff.ordinal,
								) in synthesizedGlues ->
								GlueDiff.Changed(glueDiff.meshA, glueDiff.meshB, glueDiff.ordinal, ALL_GLUE_FIELDS)

							glueDiff is GlueDiff.Deleted -> null
							else -> glueDiff
						}
					},
				// Tiles have no structural pass: art arrives and leaves with an import, never with a pack,
				// so a created or deleted tile forwards as-is and takes its notice in the lowering.
				atlasTiles = diff.atlasTiles,
				document = diff.document,
			)

		// Field-lowering pass over a REBUILT index (it must see the shells and forget the removed
		// sources).  Parameters and groups first (tree rebuilds reference their sources), then
		// deformer moves (they mutate _childGuids), then parts (whose CHILDREN rebuild anchors on
		// the moved lists), then drawables, glues, and the document fields.
		val lowering =
			Cmo3PropertyLowering(
				target = target,
				index = Cmo3GraphIndex(modelSource),
				editor = editor,
				baseline = baseline,
				edited = edited,
				notices = notices,
				pagesRecomposed = pagesRecomposed,
				reconciledTileIds = webResult.reconciledTileIds,
			)
		lowering.lowerParameters(upgradedDiff.parameters)
		lowering.lowerParameterGroups(upgradedDiff.parameterGroups)
		lowering.lowerDeformers(upgradedDiff.deformers)
		lowering.lowerParts(upgradedDiff.parts)
		lowering.lowerDrawables(upgradedDiff.drawables)
		lowering.flushWeldNotice()
		lowering.lowerGlues(upgradedDiff.glues)
		lowering.lowerAtlasTiles(upgradedDiff.atlasTiles)
		lowering.lowerDocument(upgradedDiff.document)
		Cmo3DeformPaths.write(edited, baseline, Cmo3GraphIndex(modelSource), editor)

		if (anyDeleted) {
			editor.pruneUnreachableShared()
		}
		return ExportReport(ExportFormat.Cmo3, notices)
	}
}