package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3GraphEditor
import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.model.gen.ACDeformerSource
import org.umamo.format.cmo3.model.gen.ACParameterControllableSource
import org.umamo.format.cmo3.model.gen.CArtMeshForm
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CImageCanvas
import org.umamo.format.cmo3.model.gen.CParameterGroup
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.gen.CPartForm
import org.umamo.format.cmo3.model.gen.CPartSource
import org.umamo.format.cmo3.model.gen.CRotationDeformerSource
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_ModelImage
import org.umamo.format.cmo3.model.gen.CTextureInput_TextureAtlasRegion
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.CWarpDeformerSource
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.interop.DeformerField
import org.umamo.interop.DocumentField
import org.umamo.interop.DrawableField
import org.umamo.interop.EntityDiff
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.GlueDiff
import org.umamo.interop.GlueField
import org.umamo.interop.ParameterField
import org.umamo.interop.ParameterGroupField
import org.umamo.interop.PartField
import org.umamo.interop.alphaCompositionOf
import org.umamo.interop.cmo3TargetVersionNo
import org.umamo.interop.colorCompositionOf
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/**
 * The flat-property half of the CMO3 export reconcile: every diffed field with a direct CMO3 field
 * to write - names, flags, blend modes, masks, reference reparents, tree orders, parameter ranges
 * and links, and the canvas.  Channel-backed values (statics, keyforms, geometry) dispatch to the
 * shared [Cmo3KeyformLowering]; mesh topology and glue re-binding dispatch to [Cmo3StructureLowering].
 * Entity creation/deletion runs earlier, in Cmo3Export.apply's structural pass.  A Created diff
 * that still reaches this class means that pass already tried to synthesize the entity, failed,
 * and already reported why - this class has nothing to lower for it and skips it silently.
 * Deleted diffs never arrive: the structural pass consumes every deletion and Cmo3Export strips
 * them from the diff it forwards.
 *
 * Every assignment that can target an element or attribute the source document omitted pairs with
 * the graph editor's ensure call - the writer replays recorded slots, so a bare assignment on a
 * read object would otherwise be dropped silently.  Anchors follow the generated model's declared
 * field order (the editor's document order), so an ensured element lands where the editor writes it.
 */
internal class Cmo3PropertyLowering(
	private val target: Cmo3Model,
	private val index: Cmo3GraphIndex,
	private val editor: Cmo3GraphEditor,
	private val baseline: PuppetModel,
	private val edited: PuppetModel,
	private val notices: MutableList<ExportNotice>,
) {
	private val editedParameterById = edited.parameters.associateBy(Parameter::id)
	private val editedPartById = edited.parts.associateBy(Part::id)
	private val editedDeformerById = edited.deformers.associateBy(Deformer::id)
	private val editedDrawableById = edited.drawables.associateBy(Drawable::id)
	private val baselinePartById = baseline.parts.associateBy(Part::id)
	private val baselineDeformerById = baseline.deformers.associateBy(Deformer::id)
	private val baselineDrawableById = baseline.drawables.associateBy(Drawable::id)

	/** Drawables whose base geometry/UVs diverged from the imported weld - one aggregated notice. */
	private val weldDivergedDrawableNames = ArrayList<String>()

	/** The keyform re-bundling engine, shared by the drawable/deformer/part/glue dispatches. */
	private val keyforms = Cmo3KeyformLowering(index, editor, baseline, notices)

	/** The structural engine, for topology rewrites and glue re-binding on Changed entities. */
	private val structure = Cmo3StructureLowering(index.modelSource, index, editor, edited, notices)

	private fun unsupported(
		category: ExportEntityCategory,
		subject: String?,
		reason: ExportNoticeReason,
	) {
		notices.add(ExportNotice.UnsupportedChange(category, subject, reason))
	}

	fun lowerParameters(diffs: List<EntityDiff<ParameterId, ParameterField>>) {
		for (diff in diffs) {
			when (diff) {
				// Structural synthesis already failed and reported why; see the class docblock.
				is EntityDiff.Created -> Unit
				// Deletions are consumed by the structural pass and stripped from the forwarded diff.
				is EntityDiff.Deleted -> Unit
				is EntityDiff.Changed -> {
					val source = index.parameterByIdStr[diff.id.raw]
					val editedParameter = editedParameterById[diff.id]
					if (source == null || editedParameter == null) {
						unsupported(ExportEntityCategory.Parameter, diff.id.raw, ExportNoticeReason.NoMatchingSourceToReconcile)
						continue
					}
					for (field in diff.fields) {
						when (field) {
							ParameterField.NAME -> {
								// CMO3: CParameterSource field name - the localizable display label.
								source.name = editedParameter.name
								editor.ensureChildSlot(source, "CParameterSource", "name", "description")
							}
							ParameterField.RANGE -> {
								// CMO3: CParameterSource fields minValue / maxValue / defaultValue.
								source.minValue = editedParameter.min
								source.maxValue = editedParameter.max
								source.defaultValue = editedParameter.default
								editor.ensureChildSlot(source, "CParameterSource", "minValue", "maxValue")
								editor.ensureChildSlot(source, "CParameterSource", "maxValue", "defaultValue")
								editor.ensureChildSlot(source, "CParameterSource", "defaultValue", "isRepeat")
							}
							ParameterField.KIND ->
								unsupported(ExportEntityCategory.Parameter, diff.id.raw, ExportNoticeReason.ParameterKindChangeNotLowered)
						}
					}
				}
			}
		}
	}

	fun lowerParameterGroups(diffs: List<EntityDiff<ParameterGroupId, ParameterGroupField>>) {
		for (diff in diffs) {
			when (diff) {
				// Structural synthesis already failed and reported why; see the class docblock.
				is EntityDiff.Created -> Unit
				// Deletions are consumed by the structural pass and stripped from the forwarded diff.
				is EntityDiff.Deleted -> Unit
				is EntityDiff.Changed -> {
					val group = index.groupByIdStr[diff.id.raw]
					val editedGroup = findEditedGroup(diff.id)
					if (group == null || editedGroup == null) {
						unsupported(ExportEntityCategory.ParameterGroup, diff.id.raw, ExportNoticeReason.NoMatchingSourceToReconcile)
						continue
					}
					for (field in diff.fields) {
						when (field) {
							ParameterGroupField.NAME -> {
								// CMO3: CParameterGroup field name - the folder label (not unique; identity is the id).
								group.name = editedGroup.name
								editor.ensureChildSlot(group, "CParameterGroup", "name", "description")
							}
							ParameterGroupField.INITIALLY_OPEN -> {
								// CMO3: CParameterGroup field folderIsOpened - the saved expand/collapse state.
								group.folderIsOpened = editedGroup.initiallyOpen
								editor.ensureChildSlot(group, "CParameterGroup", "folderIsOpened", "guid")
							}
							ParameterGroupField.CHILDREN -> rebuildGroupChildren(group, editedGroup.children)
						}
					}
				}
			}
		}
	}

	fun lowerParts(diffs: List<EntityDiff<PartId, PartField>>) {
		for (diff in diffs) {
			when (diff) {
				// Structural synthesis already failed and reported why; see the class docblock.
				is EntityDiff.Created -> Unit
				// Deletions are consumed by the structural pass and stripped from the forwarded diff.
				is EntityDiff.Deleted -> Unit
				is EntityDiff.Changed -> {
					val source = index.partByIdStr[diff.id.raw]
					val editedPart = editedPartById[diff.id]
					// A synthesized shell has no baseline; a default part stands in so the composite
					// lowering diffs the edited state against defaults (the shell's own values).
					val baselinePart = baselinePartById[diff.id] ?: Part(id = diff.id, name = "", children = emptyList())
					if (source == null || editedPart == null) {
						unsupported(ExportEntityCategory.Part, diff.id.raw, ExportNoticeReason.NoMatchingSourceToReconcile)
						continue
					}
					for (field in diff.fields) {
						when (field) {
							PartField.NAME -> lowerLocalName(source, editedPart.name)
							PartField.VISIBLE -> lowerIsVisible(source, editedPart.isVisible)
							PartField.SELECTABLE -> lowerIsLocked(source, editedPart.isSelectable)
							PartField.SKETCH -> {
								// CMO3: CPartSource field isSketch - the "Guide Image" reference-only flag.
								source.isSketch = editedPart.isSketch
								editor.ensureChildSlot(source, "CPartSource", "isSketch", "partsEditColor")
							}
							PartField.GROUP_MODE -> {
								// CMO3: CPartSource fields useOffscreen / enableDrawOrderGroup - Cubism's two
								// entangled checkboxes; offscreen forces grouping on, so Isolated writes both.
								source.useOffscreen = editedPart.groupMode is PartGroupMode.Isolated
								source.enableDrawOrderGroup = editedPart.groupMode !is PartGroupMode.PassThrough
								editor.ensureChildSlot(source, "CPartSource", "useOffscreen", "clipGuidList")
								editor.ensureChildSlot(source, "CPartSource", "enableDrawOrderGroup", "defaultOrder_forEditor")
							}
							PartField.DRAW_ORDER -> lowerPartDrawOrder(source, editedPart)
							PartField.CHILDREN -> rebuildOrgChildren(source, editedPart.children)
							PartField.CHANNELS -> keyforms.lowerPart(source, editedPart)
							PartField.COMPOSITE -> lowerPartComposite(source, diff.id, baselinePart, editedPart)
						}
					}
				}
			}
		}
	}

	fun lowerDeformers(diffs: List<EntityDiff<DeformerId, DeformerField>>) {
		for (diff in diffs) {
			when (diff) {
				// Structural synthesis already failed and reported why; see the class docblock.
				is EntityDiff.Created -> Unit
				// Deletions are consumed by the structural pass and stripped from the forwarded diff.
				is EntityDiff.Deleted -> Unit
				is EntityDiff.Changed -> {
					val source = index.deformerByIdStr[diff.id.raw]
					val editedDeformer = editedDeformerById[diff.id]
					if (source == null || editedDeformer == null) {
						unsupported(ExportEntityCategory.Deformer, diff.id.raw, ExportNoticeReason.NoMatchingSourceToReconcile)
						continue
					}
					for (field in diff.fields) {
						when (field) {
							DeformerField.KIND -> unsupported(ExportEntityCategory.Deformer, diff.id.raw, ExportNoticeReason.DeformerKindChangeNotLowered)
							DeformerField.NAME -> lowerLocalName(source, editedDeformer.name)
							DeformerField.SELECTABLE -> lowerIsLocked(source, editedDeformer.isSelectable)
							DeformerField.PARENT -> {
								// CMO3: ACDeformerSource field targetDeformerGuid - the transform-tree parent,
								// or the editor's fixed ROOT sentinel at the tree root (official deformers
								// never write null here).  Reusing the parent's own guid instance keeps the
								// shared xs.ref identity the editor writes.
								source.targetDeformerGuid =
									editedDeformer.parent?.let { index.deformerByIdStr[it.raw]?.guid }
										?: Cmo3SkeletonBuilder.rootDeformerSentinel()
								editor.ensureChildSlot(source, "ACDeformerSource", "targetDeformerGuid")
							}
							DeformerField.PART -> lowerDeformerPart(source, diff.id, editedDeformer.partId)
							DeformerField.QUAD_TRANSFORM -> {
								val warpSource = source as? CWarpDeformerSource
								if (warpSource == null) {
									unsupported(ExportEntityCategory.Deformer, diff.id.raw, ExportNoticeReason.DeformerEditNeedsWarpSource)
								} else {
									// CMO3: CWarpDeformerSource field isQuadTransform - the FFD interpolation mode.
									warpSource.isQuadTransform = (editedDeformer as Deformer.Warp).isQuadTransform
									editor.ensureChildSlot(warpSource, "CWarpDeformerSource", "isQuadTransform", "keyforms")
								}
							}
							DeformerField.BASE_ANGLE -> {
								val rotationSource = source as? CRotationDeformerSource
								if (rotationSource == null) {
									unsupported(ExportEntityCategory.Deformer, diff.id.raw, ExportNoticeReason.DeformerEditNeedsRotationSource)
								} else {
									// CMO3: CRotationDeformerSource field baseAngle - the static editor reference angle.
									rotationSource.baseAngle = (editedDeformer as Deformer.Rotation).baseAngle
									editor.ensureChildSlot(rotationSource, "CRotationDeformerSource", "baseAngle")
								}
							}
							DeformerField.LATTICE -> {
								val warpSource = source as? CWarpDeformerSource
								if (warpSource != null && editedDeformer is Deformer.Warp) {
									// CMO3: CWarpDeformerSource fields col / row - the FFD lattice
									// dimensions; the resized control-point forms ride the grid rebuild.
									warpSource.col = editedDeformer.columns
									warpSource.row = editedDeformer.rows
									editor.ensureChildSlot(warpSource, "CWarpDeformerSource", "col", "row")
									editor.ensureChildSlot(warpSource, "CWarpDeformerSource", "row", "isQuadTransform")
								} else {
									unsupported(ExportEntityCategory.Deformer, diff.id.raw, ExportNoticeReason.DeformerEditNeedsWarpSource)
								}
							}
							// Handled once per deformer by the keyform rebuild below.
							DeformerField.GEOMETRY,
							DeformerField.CHANNELS,
							DeformerField.STATICS,
							DeformerField.BLEND_SHAPES,
							-> Unit
						}
					}
					val rebuildGrid =
						DeformerField.GEOMETRY in diff.fields ||
							DeformerField.CHANNELS in diff.fields ||
							DeformerField.STATICS in diff.fields ||
							DeformerField.LATTICE in diff.fields
					val rebuildMorphs = DeformerField.BLEND_SHAPES in diff.fields
					if (rebuildGrid || rebuildMorphs) {
						when (editedDeformer) {
							is Deformer.Warp -> {
								val warpSource = source as? CWarpDeformerSource
								if (warpSource == null) {
									unsupported(ExportEntityCategory.Deformer, diff.id.raw, ExportNoticeReason.DeformerEditNeedsWarpSource)
								} else {
									keyforms.lowerWarp(warpSource, editedDeformer, rebuildGrid, rebuildMorphs)
								}
							}

							is Deformer.Rotation -> {
								val rotationSource = source as? CRotationDeformerSource
								if (rotationSource == null) {
									unsupported(ExportEntityCategory.Deformer, diff.id.raw, ExportNoticeReason.DeformerEditNeedsRotationSource)
								} else {
									keyforms.lowerRotation(rotationSource, editedDeformer, rebuildGrid, rebuildMorphs)
								}
							}
						}
					}
				}
			}
		}
	}

	fun lowerDrawables(diffs: List<EntityDiff<DrawableId, DrawableField>>) {
		for (diff in diffs) {
			when (diff) {
				// Structural synthesis already failed and reported why; see the class docblock.
				is EntityDiff.Created -> Unit
				// Deletions are consumed by the structural pass and stripped from the forwarded diff.
				is EntityDiff.Deleted -> Unit
				is EntityDiff.Changed -> {
					val source = index.drawableByIdStr[diff.id.raw]
					val editedDrawable = editedDrawableById[diff.id]
					if (source == null || editedDrawable == null) {
						unsupported(ExportEntityCategory.Drawable, diff.id.raw, ExportNoticeReason.NoMatchingSourceToReconcile)
						continue
					}
					for (field in diff.fields) {
						when (field) {
							DrawableField.NAME -> lowerLocalName(source, editedDrawable.name)
							DrawableField.VISIBLE -> lowerIsVisible(source, editedDrawable.isVisible)
							DrawableField.SELECTABLE -> lowerIsLocked(source, editedDrawable.isSelectable)
							DrawableField.PARENT_DEFORMER -> {
								// CMO3: ACDrawableSource field targetDeformerGuid - the deforming parent, or
								// the editor's fixed root-deformer sentinel when the drawable sits at the
								// deformer-tree root.  Never null: the editor's setter is non-null and NPEs
								// on absence, and no corpus drawable nulls it in any era.
								source.targetDeformerGuid =
									editedDrawable.parentDeformerId?.let { index.deformerByIdStr[it.raw]?.guid }
										?: Cmo3SkeletonBuilder.rootDeformerSentinel()
								editor.ensureChildSlot(source, "ACDrawableSource", "targetDeformerGuid", "clipGuidList")
							}
							DrawableField.BLEND_MODE -> {
								// CMO3: CArtMeshSource field colorComposition.
								source.colorComposition = colorCompositionOf(editedDrawable.blendMode)
								editor.ensureChildSlot(source, "CArtMeshSource", "colorComposition", "culling")
							}
							DrawableField.ALPHA_BLEND_MODE -> {
								// CMO3: CArtMeshSource field alphaComposition (absent pre-5.3).
								source.alphaComposition = alphaCompositionOf(editedDrawable.alphaBlendMode)
								editor.ensureChildSlot(source, "CArtMeshSource", "alphaComposition")
							}
							DrawableField.MASKED_BY -> {
								// CMO3: ACDrawableSource field clipGuidList - always drawable GUIDs.
								writeListField(
									owner = source,
									tag = "ACDrawableSource",
									property = "clipGuidList",
									beforeProperty = "invertClippingMask",
									current = source.clipGuidList,
									newElements = editedDrawable.maskedBy.mapNotNull { index.drawableByIdStr[it.raw]?.guid },
									assign = { list -> source.clipGuidList = list },
								)
							}
							DrawableField.INVERT_MASK -> {
								// CMO3: ACDrawableSource field invertClippingMask.
								source.invertClippingMask = editedDrawable.invertMask
								editor.ensureChildSlot(source, "ACDrawableSource", "invertClippingMask", "icon32")
							}
							DrawableField.CULLING -> {
								// CMO3: CArtMeshSource field culling - back-face culling.
								source.culling = editedDrawable.culling
								editor.ensureChildSlot(source, "CArtMeshSource", "culling", "textureState")
							}
							DrawableField.TEXTURE_SOURCE ->
								unsupported(ExportEntityCategory.Drawable, diff.id.raw, ExportNoticeReason.TextureSourceRebindingIsEditorOnly)
							DrawableField.MESH_TOPOLOGY -> {
								// The weld notice means "the base left the IMPORTED weld" - a drawable
								// with no baseline was never welded, so synthesis stays notice-free.
								val hadBaseline = baselineDrawableById[diff.id] != null
								if (structure.lowerMeshTopology(source, editedDrawable) && hadBaseline) {
									weldDivergedDrawableNames.add(editedDrawable.name)
								}
							}
							DrawableField.MESH_POSITIONS -> {
								// A base move combined with keyform-delta edits routes through the grid
								// rebuild below, which writes the base and the absolutes together.
								if (DrawableField.GEOMETRY !in diff.fields) {
									lowerMeshPositions(source, diff.id, editedDrawable)
								}
								if (baselineDrawableById[diff.id] != null) {
									weldDivergedDrawableNames.add(editedDrawable.name)
								}
							}
							DrawableField.MESH_UVS -> {
								lowerMeshUvs(source, diff.id, editedDrawable)
								if (baselineDrawableById[diff.id] != null) {
									weldDivergedDrawableNames.add(editedDrawable.name)
								}
							}
							// Handled once per drawable by the keyform rebuild below.
							DrawableField.GEOMETRY,
							DrawableField.CHANNELS,
							DrawableField.STATICS,
							DrawableField.BLEND_SHAPES,
							-> Unit
						}
					}
					val rebuildGrid =
						DrawableField.GEOMETRY in diff.fields ||
							DrawableField.CHANNELS in diff.fields ||
							DrawableField.STATICS in diff.fields ||
							DrawableField.MESH_TOPOLOGY in diff.fields
					val rebuildMorphs = DrawableField.BLEND_SHAPES in diff.fields
					if (rebuildGrid || rebuildMorphs) {
						keyforms.lowerDrawable(
							source = source,
							editedDrawable = editedDrawable,
							rebuildGrid = rebuildGrid,
							rebuildMorphs = rebuildMorphs,
							alsoWriteBase = DrawableField.MESH_POSITIONS in diff.fields && DrawableField.GEOMETRY in diff.fields,
						)
					}
				}
			}
		}
	}

	/**
	 * Flushes the aggregated weld-divergence notice.  Call after lowerDrawables: exported geometry
	 * and UVs are written as authored, a deliberate choice, and this tells the user which meshes
	 * Cubism's own mesh-edit / re-atlas operations could re-derive.
	 */
	fun flushWeldNotice() {
		if (weldDivergedDrawableNames.isNotEmpty()) {
			notices.add(ExportNotice.WeldDivergence(weldDivergedDrawableNames.toList()))
		}
	}

	/**
	 * Lowers an edited base mesh: rewrites CArtMeshSource.positions, mirrors the editable mesh's
	 * point array, and rebases every CArtMeshForm's ABSOLUTE positions onto the new base - keeping
	 * untouched vertices bit-identical by recomputing each delta from the stored values rather than
	 * re-deriving (a-b)+b through IEEE rounding.
	 *
	 * @param CArtMeshSource source         The drawable's graph source.
	 * @param DrawableId     drawableId     The drawable's id (for notices).
	 * @param Drawable       editedDrawable The edited drawable.
	 */
	private fun lowerMeshPositions(source: CArtMeshSource, drawableId: DrawableId, editedDrawable: Drawable) {
		val origBase = source.positions as? FloatArray
		val newBase = editedDrawable.mesh?.positions
		if (origBase == null || newBase == null || origBase.size != newBase.size) {
			unsupported(ExportEntityCategory.Drawable, drawableId.raw, ExportNoticeReason.BaseGeometryVertexCountMismatch)
			return
		}
		// Rebase the forms BEFORE swapping the base: CMO3 stores absolutes, so every form follows
		// the base move (grid cells and morph-target forms live in the same pool and rebase alike).
		for (form in Cmo3Import.elementsOf(source.keyforms).filterIsInstance<CArtMeshForm>()) {
			val origAbsolute = form.positions as? FloatArray ?: continue
			if (origAbsolute.size != newBase.size) {
				continue
			}
			val rebased =
				FloatArray(origAbsolute.size) { component ->
					if (newBase[component].toRawBits() == origBase[component].toRawBits()) {
						origAbsolute[component]
					} else {
						newBase[component] + (origAbsolute[component] - origBase[component])
					}
				}
			// CMO3: CArtMeshForm field positions - absolute vertex positions.
			form.positions = rebased
			editor.ensureChildSlot(form, "CArtMeshForm", "positions")
		}
		// CMO3: CArtMeshSource field positions - the rest/default geometry.
		source.positions = newBase.copyOf()
		editor.ensureChildSlot(source, "CArtMeshSource", "positions", "uvs")
		// CMO3: GEditableMesh2 field point mirrors the positions as its OWN float-array (the corpus
		// never shares the two array objects, and a shared object would hoist as an xs.ref).
		val editableMesh = Cmo3Import.editableMeshOf(source)
		val pointArray = editableMesh?.point as? FloatArray
		if (editableMesh != null && (pointArray == null || pointArray.size == newBase.size)) {
			editableMesh.point = newBase.copyOf()
			editor.ensureChildSlot(editableMesh, "GEditableMesh2", "point", "pointPriority")
		}
	}

	/**
	 * Lowers edited UVs: verbatim for a packed (atlas-region) drawable, and through the FORWARD
	 * model-image affine for an unpacked one - the inverse of the frame remap import applied.
	 * Unchanged texel pairs keep the stored values so the affine round trip cannot drift them.
	 *
	 * @param CArtMeshSource source         The drawable's graph source.
	 * @param DrawableId     drawableId     The drawable's id (for notices).
	 * @param Drawable       editedDrawable The edited drawable.
	 */
	private fun lowerMeshUvs(source: CArtMeshSource, drawableId: DrawableId, editedDrawable: Drawable) {
		val newUvs = editedDrawable.mesh?.uvs
		if (newUvs == null) {
			unsupported(ExportEntityCategory.Drawable, drawableId.raw, ExportNoticeReason.NoUvsToReconcile)
			return
		}
		val storedUvs = source.uvs as? FloatArray
		val baselineUvs = baselineDrawableById[drawableId]?.mesh?.uvs
		if (Cmo3Import.hasAtlasRegion(source)) {
			// CMO3: CArtMeshSource field uvs - a packed drawable stores its sampled-image frame verbatim.
			source.uvs = newUvs.copyOf()
		} else {
			// CMO3: GTexture2D field transformImageResource01toLogical01 - an unpacked drawable stores
			// uvs in the model-image LOGICAL frame; apply the forward affine (import applied the inverse).
			val affine = (source.texture as? GTexture2D)?.transformImageResource01toLogical01 as? CAffine
			val isIdentity =
				affine == null ||
					(
						affine.m00 == 1f &&
							affine.m01 == 0f &&
							affine.m02 == 0f &&
							affine.m10 == 0f &&
							affine.m11 == 1f &&
							affine.m12 == 0f
					)
			if (isIdentity) {
				source.uvs = newUvs.copyOf()
			} else {
				val result = FloatArray(newUvs.size)
				var component = 0
				while (component + 1 < newUvs.size) {
					val unchangedPair =
						storedUvs != null &&
							storedUvs.size == newUvs.size &&
							baselineUvs != null &&
							baselineUvs.size == newUvs.size &&
							newUvs[component].toRawBits() == baselineUvs[component].toRawBits() &&
							newUvs[component + 1].toRawBits() == baselineUvs[component + 1].toRawBits()
					if (unchangedPair) {
						result[component] = storedUvs[component]
						result[component + 1] = storedUvs[component + 1]
					} else {
						val u = newUvs[component]
						val v = newUvs[component + 1]
						result[component] = affine.m00 * u + affine.m01 * v + affine.m02
						result[component + 1] = affine.m10 * u + affine.m11 * v + affine.m12
					}
					component += 2
				}
				source.uvs = result
			}
		}
		editor.ensureChildSlot(source, "CArtMeshSource", "uvs", "texture")
	}

	fun lowerGlues(diffs: List<GlueDiff>) {
		if (diffs.isEmpty()) {
			return
		}
		// A glue has no id: resolve the graph source and the edited glue by ordered mesh pair plus
		// ordinal, the same keying the diff used.
		val sourcesByPair =
			index.glueSources.groupBy { glueSource ->
				val meshA = index.drawableIdStrByUuid[Cmo3Import.uuidOf(glueSource.targetArtMeshA_guid)]
				val meshB = index.drawableIdStrByUuid[Cmo3Import.uuidOf(glueSource.targetArtMeshB_guid)]
				meshA to meshB
			}
		val editedByPair = edited.glues.groupBy { glue -> glue.meshA.raw to glue.meshB.raw }
		for (diff in diffs) {
			val subject = "${diff.meshA.raw}+${diff.meshB.raw}"
			when (diff) {
				// Structural synthesis already failed and reported why; see the class docblock.
				is GlueDiff.Created -> Unit
				// Deletions are consumed by the structural pass and stripped from the forwarded diff.
				is GlueDiff.Deleted -> Unit
				is GlueDiff.Changed -> {
					val pairKey = diff.meshA.raw to diff.meshB.raw
					val glueSource = sourcesByPair[pairKey as Pair<String?, String?>]?.getOrNull(diff.ordinal)
					val editedGlue = editedByPair[pairKey]?.getOrNull(diff.ordinal)
					if (glueSource == null || editedGlue == null) {
						unsupported(ExportEntityCategory.Glue, subject, ExportNoticeReason.NoMatchingSourceToReconcile)
						continue
					}
					if (GlueField.PAIRS in diff.fields) {
						structure.lowerGluePairsFor(editedGlue)
					}
					if (GlueField.CHANNELS in diff.fields || GlueField.INTENSITY in diff.fields) {
						keyforms.lowerGlue(glueSource, editedGlue)
					}
				}
			}
		}
	}

	fun lowerDocument(fields: Set<DocumentField>) {
		// Order and links rewrite the same _sources list; run the shared lowering once.
		if (DocumentField.PARAMETER_ORDER in fields || DocumentField.PARAMETER_LINKS in fields) {
			lowerParameterOrderAndLinks()
		}
		for (field in fields) {
			when (field) {
				// CMO3: CModelSource field targetVersionNo (via the Cmo3Model facade, which also
				// records the slot on documents that never carried the element).
				DocumentField.RUNTIME_TARGET -> target.setTargetVersionNo(edited.runtimeTarget.cmo3TargetVersionNo())
				DocumentField.CANVAS_SIZE -> lowerCanvasSize()
				DocumentField.SOURCE_LAYER_DISPLAY -> lowerSourceLayerDisplay()
				DocumentField.WORLD_ORIGIN -> {
					// An origin AT the canvas center survives implicitly (import derives exactly that),
					// so only an off-center origin is unrepresentable and worth a notice.
					val atDerivedCenter =
						edited.worldOriginX == edited.canvasWidth / 2f &&
							edited.worldOriginY == -(edited.canvasHeight / 2f)
					if (!atDerivedCenter) {
						unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.NoAuthoredWorldOrigin)
					}
				}
				DocumentField.PARAMETER_ORDER, DocumentField.PARAMETER_LINKS -> Unit
				DocumentField.PARAMETER_TREE -> {
					val rootGroup = index.rootParameterGroup
					if (rootGroup == null) {
						unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.NoRootParameterGroup)
					} else {
						rebuildGroupChildren(rootGroup, edited.parameterTree)
					}
				}
				DocumentField.ROOT_CHILDREN -> {
					val rootPart = index.rootPartSource
					if (rootPart == null) {
						unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.NoRootPart)
					} else {
						rebuildOrgChildren(rootPart, edited.rootChildren)
					}
				}
			}
		}
	}

	/** CMO3: ACParameterControllableSource field localName - the user-facing display name. */
	private fun lowerLocalName(source: ACParameterControllableSource, name: String) {
		source.localName = name
		editor.ensureChildSlot(source, "ACParameterControllableSource", "localName", "isVisible")
	}

	/** CMO3: ACParameterControllableSource field isVisible - the Parts-panel eyeball. */
	private fun lowerIsVisible(source: ACParameterControllableSource, isVisible: Boolean) {
		source.isVisible = isVisible
		editor.ensureChildSlot(source, "ACParameterControllableSource", "isVisible", "isLocked")
	}

	/** CMO3: ACParameterControllableSource field isLocked (inverted: Cubism lock = not selectable). */
	private fun lowerIsLocked(source: ACParameterControllableSource, isSelectable: Boolean) {
		source.isLocked = !isSelectable
		editor.ensureChildSlot(source, "ACParameterControllableSource", "isLocked", "parentGuid")
	}

	/**
	 * Lowers a part's static draw order.  With no DRAW_ORDER track the value lives in
	 * defaultOrder_forEditor and every CPartForm cell (import reads the first form), so both are
	 * written; with a track the static is shadowed by the grid and has no independent CMO3 home.
	 *
	 * @param CPartSource source     The part's graph source.
	 * @param Part        editedPart The edited part.
	 */
	private fun lowerPartDrawOrder(source: CPartSource, editedPart: Part) {
		val drawOrderTrack = editedPart.channelGrids[FormChannel.DRAW_ORDER]
		if (drawOrderTrack != null) {
			// Import re-derives the static from the FIRST grid form, so a static that matches the
			// track's head cell survives on its own; only a disagreeing static is actually lost.
			val headDrawOrder = (drawOrderTrack.cells.firstOrNull()?.form as? ChannelValue.Scalar)?.value?.toInt()
			if (headDrawOrder != editedPart.drawOrder) {
				unsupported(ExportEntityCategory.Part, editedPart.id.raw, ExportNoticeReason.StaticDrawOrderShadowedByKeyforms)
			}
			return
		}
		// CMO3: CPartSource field defaultOrder_forEditor + CPartForm field drawOrder.
		source.defaultOrder_forEditor = editedPart.drawOrder
		editor.ensureChildSlot(source, "CPartSource", "defaultOrder_forEditor", "isSketch")
		for (form in Cmo3Import.elementsOf(source.keyforms).filterIsInstance<CPartForm>()) {
			form.drawOrder = editedPart.drawOrder
			editor.ensureChildSlot(form, "CPartForm", "drawOrder", "opacity")
		}
	}

	/**
	 * Lowers a part composite's flat fields (blend modes, masks, invert); the keyformed statics
	 * (opacity, colors) live in CPartForm cells and land with keyform lowering.  Part-typed masks
	 * (an Umamo extension) flatten to their descendant drawables - CMO3 has nowhere to keep the
	 * part reference, so the grouping itself is reported as not carried.
	 *
	 * @param CPartSource source       The part's graph source.
	 * @param PartId      partId       The part's id (for notices).
	 * @param Part        baselinePart The baseline part.
	 * @param Part        editedPart   The edited part.
	 */
	private fun lowerPartComposite(source: CPartSource, partId: PartId, baselinePart: Part, editedPart: Part) {
		val baselineComposite = baselinePart.composite
		val editedComposite = editedPart.composite
		if (baselineComposite.blendMode != editedComposite.blendMode) {
			// CMO3: CPartSource field colorComposition.
			source.colorComposition = colorCompositionOf(editedComposite.blendMode)
			editor.ensureChildSlot(source, "CPartSource", "colorComposition", "alphaComposition")
		}
		if (baselineComposite.alphaBlendMode != editedComposite.alphaBlendMode) {
			// CMO3: CPartSource field alphaComposition.
			source.alphaComposition = alphaCompositionOf(editedComposite.alphaBlendMode)
			editor.ensureChildSlot(source, "CPartSource", "alphaComposition", "targetDeformerGuid")
		}
		if (
			baselineComposite.maskedBy != editedComposite.maskedBy ||
			baselineComposite.maskedByParts != editedComposite.maskedByParts
		) {
			if (editedComposite.maskedByParts.isNotEmpty()) {
				unsupported(ExportEntityCategory.Part, partId.raw, ExportNoticeReason.PartMasksFlattenToDrawables)
			}
			// CMO3: CPartSource field clipGuidList - always drawable GUIDs, so part-typed masks are
			// expanded to the part's descendant drawables (the same expansion the render tree applies).
			val expandedMask = editedComposite.maskedBy + editedComposite.maskedByParts.flatMap(::descendantDrawables)
			writeListField(
				owner = source,
				tag = "CPartSource",
				property = "clipGuidList",
				beforeProperty = "invertClippingMask",
				current = source.clipGuidList,
				newElements = expandedMask.distinct().mapNotNull { index.drawableByIdStr[it.raw]?.guid },
				assign = { list -> source.clipGuidList = list },
			)
		}
		if (baselineComposite.invertMask != editedComposite.invertMask) {
			// CMO3: CPartSource field invertClippingMask.
			source.invertClippingMask = editedComposite.invertMask
			editor.ensureChildSlot(source, "CPartSource", "invertClippingMask", "colorComposition")
		}
		val staticsChanged =
			baselineComposite.opacity != editedComposite.opacity ||
				baselineComposite.multiplyColor != editedComposite.multiplyColor ||
				baselineComposite.screenColor != editedComposite.screenColor
		if (staticsChanged) {
			val opacityTrack = editedPart.channelGrids[FormChannel.OPACITY]
			val multiplyTrack = editedPart.channelGrids[FormChannel.MULTIPLY_COLOR]
			val screenTrack = editedPart.channelGrids[FormChannel.SCREEN_COLOR]
			if (opacityTrack != null || multiplyTrack != null || screenTrack != null) {
				// With tracks present the statics live in the grid cells; import re-derives each
				// from the FIRST form, so a static matching its track's head cell survives on its
				// own and only a disagreeing (or track-less-but-changed) one is actually lost.
				val opacitySurvives = (opacityTrack?.cells?.firstOrNull()?.form as? ChannelValue.Scalar)?.value == editedComposite.opacity
				val multiplySurvives =
					(multiplyTrack?.cells?.firstOrNull()?.form as? ChannelValue.Color)?.color == editedComposite.multiplyColor
				val screenSurvives =
					(screenTrack?.cells?.firstOrNull()?.form as? ChannelValue.Color)?.color == editedComposite.screenColor
				if (!opacitySurvives || !multiplySurvives || !screenSurvives) {
					unsupported(ExportEntityCategory.Part, partId.raw, ExportNoticeReason.CompositeStaticsShadowedByKeyforms)
				}
			} else {
				keyforms.writePartCompositeStatics(source, editedPart)
			}
		}
	}

	/**
	 * Moves a deformer to another part: rewrites its parentGuid and moves its guid between the
	 * parts' _childGuids lists (deformers live in the parts-panel child order even though the
	 * runtime routes them out of the org tree).  Runs before any CHILDREN rebuild, so the rebuild's
	 * anchor pass sees the deformer already in its new list.
	 *
	 * @param ACDeformerSource source     The deformer's graph source.
	 * @param DeformerId       deformerId The deformer's id.
	 * @param PartId?          newPartId  The new owning part, or null for the root.
	 */
	private fun lowerDeformerPart(source: ACDeformerSource, deformerId: DeformerId, newPartId: PartId?) {
		val deformerUuid = Cmo3Import.uuidOf(source.guid)
		val oldPartId = baselineDeformerById[deformerId]?.partId
		val oldPartSource = oldPartId?.let { index.partByIdStr[it.raw] } ?: index.rootPartSource
		val newPartSource = newPartId?.let { index.partByIdStr[it.raw] } ?: index.rootPartSource
		if (newPartSource == null) {
			unsupported(ExportEntityCategory.Deformer, deformerId.raw, ExportNoticeReason.DeformerHasNoPartToMoveTo)
			return
		}
		// CMO3: ACParameterControllableSource field parentGuid - the org-tree owner; the synthetic
		// root part is the "no part" owner.
		source.parentGuid = newPartSource.guid
		editor.ensureChildSlot(source, "ACParameterControllableSource", "parentGuid", "keyformGridSource")
		if (deformerUuid == null) {
			return
		}
		mutableGraphListOf(oldPartSource?._childGuids)?.removeAll { entry -> Cmo3Import.uuidOf(entry) == deformerUuid }
		val newChildList = mutableGraphListOf(newPartSource._childGuids)
		if (newChildList != null) {
			if (newChildList.none { entry -> Cmo3Import.uuidOf(entry) == deformerUuid }) {
				newChildList.add(source.guid)
			}
		} else {
			writeListField(
				owner = newPartSource,
				tag = "CPartSource",
				property = "_childGuids",
				beforeProperty = "useOffscreen",
				current = newPartSource._childGuids,
				newElements = listOf(source.guid),
				assign = { list -> newPartSource._childGuids = list },
			)
		}
	}

	/**
	 * Rebuilds a part's _childGuids from the edited org children, preserving non-org entries
	 * (deformers, unknown guids) by anchor: each keeps its position after the nearest preceding org
	 * child that survives, else moves to the end.  The org tree's single source of truth is the
	 * model's ordered children; the graph list is derived from it plus the preserved entries.
	 *
	 * @param CPartSource source         The part (or root part) whose list is rebuilt.
	 * @param List        editedChildren The edited model's ordered org children.
	 */
	private fun rebuildOrgChildren(source: CPartSource, editedChildren: List<OrgChild>) {
		val currentEntries = Cmo3Import.elementsOf(source._childGuids)
		val nonOrgAfterAnchor = LinkedHashMap<String?, MutableList<Any?>>()
		var lastOrgUuid: String? = null
		for (entry in currentEntries) {
			val uuid = Cmo3Import.uuidOf(entry)
			val isOrgChild = uuid != null && (uuid in index.drawableUuids || uuid in index.userPartUuids)
			if (isOrgChild) {
				lastOrgUuid = uuid
			} else {
				nonOrgAfterAnchor.getOrPut(lastOrgUuid) { ArrayList() }.add(entry)
			}
		}
		val newEntries = ArrayList<Any?>()
		nonOrgAfterAnchor[null]?.let(newEntries::addAll)
		for (child in editedChildren) {
			val childGuid =
				when (child) {
					is OrgChild.Drawable -> index.drawableByIdStr[child.id.raw]?.guid
					is OrgChild.Part -> index.partByIdStr[child.id.raw]?.guid
				}
			if (childGuid == null) {
				val childId = if (child is OrgChild.Drawable) child.id.raw else (child as OrgChild.Part).id.raw
				unsupported(ExportEntityCategory.Part, childId, ExportNoticeReason.CreatedEntityHasNoSourceYet)
				continue
			}
			newEntries.add(childGuid)
			nonOrgAfterAnchor.remove(Cmo3Import.uuidOf(childGuid))?.let(newEntries::addAll)
		}
		// Non-org entries whose anchor did not survive keep existing at the end of the list.
		for ((anchorUuid, trailing) in nonOrgAfterAnchor) {
			if (anchorUuid != null) {
				newEntries.addAll(trailing)
			}
		}
		writeListField(
			owner = source,
			tag = "CPartSource",
			property = "_childGuids",
			beforeProperty = "useOffscreen",
			current = source._childGuids,
			newElements = newEntries,
			assign = { list -> source._childGuids = list },
		)
	}

	/**
	 * Rebuilds a parameter group's _childGuids from the edited tree nodes, and refreshes every
	 * child's redundant parentGroupGuid back-pointer to this group (the walk is authoritative, but
	 * the editor maintains the back-pointers, so the export does too).
	 *
	 * @param CParameterGroup group        The group (or the hidden root group) to rebuild.
	 * @param List            editedNodes  The edited model's ordered child nodes.
	 */
	private fun rebuildGroupChildren(group: CParameterGroup, editedNodes: List<ParameterNode>) {
		val newEntries = ArrayList<Any?>()
		for (node in editedNodes) {
			when (node) {
				is ParameterNode.Param -> {
					val parameterSource = index.parameterByIdStr[node.id.raw]
					if (parameterSource == null) {
						unsupported(ExportEntityCategory.Parameter, node.id.raw, ExportNoticeReason.CreatedEntityHasNoSourceYet)
						continue
					}
					newEntries.add(parameterSource.guid)
					// CMO3: CParameterSource field parentGroupGuid - the redundant membership back-pointer.
					parameterSource.parentGroupGuid = group.guid
					editor.ensureChildSlot(parameterSource, "CParameterSource", "parentGroupGuid")
				}
				is ParameterNode.Group -> {
					val childGroup = index.groupByIdStr[node.id.raw]
					if (childGroup == null) {
						unsupported(ExportEntityCategory.ParameterGroup, node.id.raw, ExportNoticeReason.CreatedEntityHasNoSourceYet)
						continue
					}
					newEntries.add(childGroup.guid)
					// CMO3: CParameterGroup field parentGroupGuid - the enclosing group back-pointer.
					childGroup.parentGroupGuid = group.guid
					editor.ensureChildSlot(childGroup, "CParameterGroup", "parentGroupGuid", "_childGuids")
				}
			}
		}
		writeListField(
			owner = group,
			tag = "CParameterGroup",
			property = "_childGuids",
			beforeProperty = "id",
			current = group._childGuids,
			newElements = newEntries,
			assign = { list -> group._childGuids = list },
		)
	}

	/**
	 * Rewrites the parameter source order and combined flags from the edited model.  Document order
	 * is semantic: a combined (2D) pair is encoded positionally, X flagged and Y immediately next.
	 * The target order is the edited parameter order with each link's Y moved directly after its X
	 * when not already adjacent (reported, since the reimported order will then differ); sources
	 * with no edited counterpart (deletions pending structural lowering) keep a stable tail.
	 */
	private fun lowerParameterOrderAndLinks() {
		val sourceSet = index.modelSource.parameterSourceSet as? CParameterSourceSet
		if (sourceSet == null) {
			unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.NoParameterSourceSet)
			return
		}
		val orderedSources = ArrayList<Any?>()
		val placed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
		for (parameter in edited.parameters) {
			val source = index.parameterByIdStr[parameter.id.raw] ?: continue
			orderedSources.add(source)
			placed.add(source)
		}
		for (source in index.parameterSources) {
			if (source !in placed) {
				orderedSources.add(source)
			}
		}
		// CMO3: CParameterSource field combined - true only on the X member of a linked pair.
		val linkByHorizontal = edited.parameterLinks.associateBy { link -> link.horizontal }
		for (source in index.parameterSources) {
			val idStr = Cmo3Import.idStrOf(source.id) ?: continue
			val shouldCombine = ParameterId(idStr) in linkByHorizontal
			if (source.combined != shouldCombine) {
				source.combined = shouldCombine
				editor.ensureChildSlot(source, "CParameterSource", "combined", "parentGroupGuid")
			}
		}
		for (link in edited.parameterLinks) {
			val horizontalSource = index.parameterByIdStr[link.horizontal.raw] ?: continue
			val verticalSource = index.parameterByIdStr[link.vertical.raw] ?: continue
			val horizontalIndex = orderedSources.indexOfFirst { it === horizontalSource }
			val verticalIndex = orderedSources.indexOfFirst { it === verticalSource }
			if (horizontalIndex < 0 || verticalIndex < 0 || verticalIndex == horizontalIndex + 1) {
				continue
			}
			orderedSources.removeAt(verticalIndex)
			val anchorIndex = orderedSources.indexOfFirst { it === horizontalSource }
			orderedSources.add(anchorIndex + 1, verticalSource)
			unsupported(
				ExportEntityCategory.Document,
				null,
				ExportNoticeReason.CombinedPairReordered(link.horizontal.raw, link.vertical.raw),
			)
		}
		writeListField(
			owner = sourceSet,
			tag = "CParameterSourceSet",
			property = "_sources",
			beforeProperty = null,
			current = sourceSet._sources,
			newElements = orderedSources,
			assign = { list -> sourceSet._sources = list },
		)
	}

	/**
	 * Lowers the source-artwork display mode: the texture manager's flag AND the per-drawable input
	 * pointer it selects.
	 *
	 * The flag alone is not the whole state.  Flipping the mode in the official editor also retargets
	 * each art mesh's currentTextureInputData between its model-image input and its atlas-region one
	 * (docs/format/CMO3.md §4), so writing only the flag would leave every drawable pointing at the
	 * input the OTHER mode samples - a file that says one thing and points at another.  A drawable
	 * missing the input the mode needs keeps the pointer it has and takes a notice, rather than being
	 * given a dangling one.
	 */
	private fun lowerSourceLayerDisplay() {
		// CMO3: CModelSource field textureManager -> CTextureManager field isTextureInputModelImageMode.
		val textureManager = index.modelSource.textureManager as? CTextureManager
		if (textureManager == null) {
			unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.NoTextureManagerToReconcile)
			return
		}
		val fromSourceLayers = edited.rendersFromSourceLayers
		textureManager.isTextureInputModelImageMode = fromSourceLayers
		editor.ensureChildSlot(textureManager, "CTextureManager", "isTextureInputModelImageMode", "previewReductionRatio")
		for (drawable in edited.drawables) {
			val source = index.drawableByIdStr[drawable.id.raw] ?: continue
			// CMO3: CArtMeshSource field _extensions -> CTextureInputExtension fields _textureInputs /
			// currentTextureInputData - the pointer names which of the drawable's inputs is live.
			val extension = Cmo3Import.elementsOf(source._extensions).filterIsInstance<CTextureInputExtension>().firstOrNull() ?: continue
			val inputs = Cmo3Import.elementsOf(extension._textureInputs)
			val wanted =
				if (fromSourceLayers) {
					inputs.filterIsInstance<CTextureInput_ModelImage>().firstOrNull()
				} else {
					inputs.filterIsInstance<CTextureInput_TextureAtlasRegion>().firstOrNull()
				}
			if (wanted == null) {
				// An unpacked drawable in atlas mode, or one with no model image in layer mode.  Leaving
				// the live pointer alone keeps the file self-consistent; the notice names the gap.
				unsupported(ExportEntityCategory.Drawable, drawable.name, ExportNoticeReason.NoTextureInputForDisplayMode)
				continue
			}
			extension.currentTextureInputData = wanted
			editor.ensureChildSlot(extension, "CTextureInputExtension", "currentTextureInputData", null)
		}
	}

	/** CMO3: CModelSource.canvas -> CImageCanvas fields pixelWidth / pixelHeight. */
	private fun lowerCanvasSize() {
		val canvas = index.modelSource.canvas as? CImageCanvas
		if (canvas == null) {
			unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.NoCanvasToReconcile)
			return
		}
		val width = edited.canvasWidth
		val height = edited.canvasHeight
		if (width != width.toInt().toFloat() || height != height.toInt().toFloat()) {
			unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.FractionalCanvasSizeNotStorable)
			return
		}
		canvas.pixelWidth = width.toInt()
		canvas.pixelHeight = height.toInt()
		editor.ensureChildSlot(canvas, "CImageCanvas", "pixelWidth", "pixelHeight")
		editor.ensureChildSlot(canvas, "CImageCanvas", "pixelHeight", "background")
	}

	/** The drawables under a part in the edited model's org tree, in panel order. */
	private fun descendantDrawables(partId: PartId): List<DrawableId> {
		val part = editedPartById[partId] ?: return emptyList()
		val drawableIds = ArrayList<DrawableId>()

		fun walk(children: List<OrgChild>) {
			for (child in children) {
				when (child) {
					is OrgChild.Drawable -> drawableIds.add(child.id)
					is OrgChild.Part -> editedPartById[child.id]?.let { walk(it.children) }
				}
			}
		}
		walk(part.children)
		return drawableIds
	}

	private fun findEditedGroup(groupId: ParameterGroupId): ParameterNode.Group? {
		fun walk(nodes: List<ParameterNode>): ParameterNode.Group? {
			for (node in nodes) {
				if (node is ParameterNode.Group) {
					if (node.id == groupId) {
						return node
					}
					walk(node.children)?.let { return it }
				}
			}
			return null
		}
		return walk(edited.parameterTree)
	}

	/**
	 * Rewrites a collection field in place, or creates a fresh CArrayList and records its slot.
	 *
	 * The fresh list's runtime type decides the serialized tag, and every field this helper
	 * serves (_childGuids, _sources) is carray_list in every corpus model; the official
	 * editor's deserializers cast such fields to CArrayList, so array_list fails its load.
	 *
	 * @param Any      owner          The graph object owning the collection field.
	 * @param String   tag            The owner's serial tag for the child-slot record.
	 * @param String   property       The collection field's name.
	 * @param String?  beforeProperty The sibling field the slot precedes, or null for last.
	 * @param Any?     current        The current field value.
	 * @param List     newElements    The elements to write.
	 * @param Function assign         Assigns the fresh list to the field.
	 */
	private fun writeListField(
		owner: Any,
		tag: String,
		property: String,
		beforeProperty: String?,
		current: Any?,
		newElements: List<Any?>,
		assign: (MutableList<Any?>) -> Unit,
	) {
		val mutable = mutableGraphListOf(current)
		if (mutable != null) {
			mutable.clear()
			mutable.addAll(newElements)
			return
		}
		val fresh: MutableList<Any?> = CArrayList()
		fresh.addAll(newElements)
		assign(fresh)
		editor.ensureChildSlot(owner, tag, property, beforeProperty)
	}
}