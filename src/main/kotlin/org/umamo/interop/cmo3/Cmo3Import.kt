package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3TargetVersion
import org.umamo.format.cmo3.model.custom.CFloatColor
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.custom.CRotationDeformerForm
import org.umamo.format.cmo3.model.custom.GEditableMesh2
import org.umamo.format.cmo3.model.gen.ACDeformerSource
import org.umamo.format.cmo3.model.gen.ACForm
import org.umamo.format.cmo3.model.gen.AlphaComposition
import org.umamo.format.cmo3.model.gen.CAffecterSourceSet
import org.umamo.format.cmo3.model.gen.CArtMeshForm
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDeformerSourceSet
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CEditableMeshExtension
import org.umamo.format.cmo3.model.gen.CGlueForm
import org.umamo.format.cmo3.model.gen.CGlueSource
import org.umamo.format.cmo3.model.gen.CImageCanvas
import org.umamo.format.cmo3.model.gen.CParameterGroup
import org.umamo.format.cmo3.model.gen.CParameterGroupSet
import org.umamo.format.cmo3.model.gen.CParameterSource
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.gen.CPartForm
import org.umamo.format.cmo3.model.gen.CPartSource
import org.umamo.format.cmo3.model.gen.CPartSourceSet
import org.umamo.format.cmo3.model.gen.CRotationDeformerSource
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_TextureAtlasRegion
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.CWarpDeformerForm
import org.umamo.format.cmo3.model.gen.CWarpDeformerSource
import org.umamo.format.cmo3.model.gen.ColorComposition
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.gen.KeyFormMorphTarget
import org.umamo.format.cmo3.model.gen.KeyFormMorphTargetSet
import org.umamo.format.cmo3.model.gen.KeyOnParameter
import org.umamo.format.cmo3.model.gen.KeyformBindingSource
import org.umamo.format.cmo3.model.gen.KeyformGridAccessKey
import org.umamo.format.cmo3.model.gen.KeyformGridSource
import org.umamo.format.cmo3.model.gen.KeyformOnGrid
import org.umamo.format.cmo3.model.gen.MorphTargetBlendWeightConstraint
import org.umamo.format.cmo3.model.gen.MorphTargetBlendWeightConstraintSet
import org.umamo.format.cmo3.model.gen.Type
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.interop.alphaBlendOfToken
import org.umamo.interop.colorBlendOfToken
import org.umamo.interop.runtimeTargetOfCmo3Target
import org.umamo.runtime.keyform.asChannelTrack
import org.umamo.runtime.keyform.channelGridsOf
import org.umamo.runtime.keyform.fanOutMesh
import org.umamo.runtime.keyform.fanOutRotation
import org.umamo.runtime.keyform.fanOutWarp
import org.umamo.runtime.keyform.withChannelsCompacted
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.BlendWeightLimitPoint
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GlueForm
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.deriveRenderRoot

/**
 * Maps a parsed CMO3 model graph (`:format`) into the concrete [PuppetModel] (`:runtime`).
 *
 * This is the boundary that keeps CMO3's serialization quirks out of the deformation core. The
 * raw graph is loosely typed (object fields are `Any?` holding already-resolved instances) and its
 * structure is encoded as GUID strings (`targetDeformerGuid`, `parentGuid`, `clipGuidList`), not
 * object links. So mapping is two passes: (1) index every source by its GUID uuid → runtime id;
 * (2) build the runtime entities, resolving each cross-reference through those indices.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Model object graph</a>
 */
object Cmo3Import {
	/**
	 * Builds a [PuppetModel] from a CMO3 `CModelSource` root (e.g. from `Cmo3.read(file).root`).
	 *
	 * @param CModelSource modelSource The parsed model root.
	 * @param Boolean compactChannels Whether to run the post-import channel compaction (on by default).
	 *   Off imports the fanned-out tracks verbatim, which is the reference an equivalence test diffs
	 *   against and the switch to flip when bisecting a suspected compaction fault.
	 * @return PuppetModel The concrete runtime puppet.
	 */
	fun fromModelSource(modelSource: CModelSource, compactChannels: Boolean = true): PuppetModel {
		val parameterSources =
			elementsOf((modelSource.parameterSourceSet as? CParameterSourceSet)?._sources)
				.filterIsInstance<CParameterSource>()
		val partSources =
			elementsOf((modelSource.partSourceSet as? CPartSourceSet)?._sources)
				.filterIsInstance<CPartSource>()
		val deformerSources =
			elementsOf((modelSource.deformerSourceSet as? CDeformerSourceSet)?._sources)
				.filterIsInstance<ACDeformerSource>()
		val drawableSources =
			elementsOf((modelSource.drawableSourceSet as? CDrawableSourceSet)?._sources)
				.filterIsInstance<CArtMeshSource>()

		// CMO3: rootPart is the synthetic __RootPart__ tree anchor - the editor excludes it from its
		// part count, and entities directly under it are top-level (partId == null). Drop it from the
		// user-facing parts so the model matches the editor and null cleanly means "at the root".
		val rootPart = modelSource.rootPart
		val rootPartUuid =
			when (rootPart) {
				is CPartSource -> uuidOf(rootPart.guid)
				else -> uuidOf(rootPart)
			}
		val userPartSources = partSources.filter { uuidOf(it.guid) != rootPartUuid }

		// Pass 1 - GUID uuid → runtime id, per category. Cross-references resolve through these.
		val partIdByUuid =
			buildMap {
				for (part in userPartSources) {
					val uuid = uuidOf(part.guid) ?: continue
					val id = idStrOf(part.id) ?: continue
					put(uuid, PartId(id))
				}
			}
		val deformerIdByUuid =
			buildMap {
				for (deformer in deformerSources) {
					val uuid = uuidOf(deformer.guid) ?: continue
					val id = idStrOf(deformer.id) ?: continue
					put(uuid, DeformerId(id))
				}
			}
		val drawableIdByUuid =
			buildMap {
				for (drawable in drawableSources) {
					val uuid = uuidOf(drawable.guid) ?: continue
					val id = idStrOf(drawable.id) ?: continue
					put(uuid, DrawableId(id))
				}
			}
		val paramIdByUuid =
			buildMap {
				for (param in parameterSources) {
					val uuid = uuidOf(param.guid) ?: continue
					val id = idStrOf(param.id) ?: continue
					put(uuid, ParameterId(id))
				}
			}
		// Pass 2 - build runtime entities, resolving references through the indices above.
		val parameters =
			parameterSources.map { source ->
				Parameter(
					id = ParameterId(idStrOf(source.id).orEmpty()),
					// CMO3: CParameterSource.name is the display label; fall back to the id (ParamAngleX).
					name = source.name ?: idStrOf(source.id).orEmpty(),
					min = source.minValue,
					max = source.maxValue,
					default = source.defaultValue,
					// CMO3: CParameterSource field paramType - MORPH_TARGET marks a blend-shape axis.
					kind = if ((source.paramType as? Type) == Type.MORPH_TARGET) ParameterKind.BLEND_SHAPE else ParameterKind.NORMAL,
				)
			}

		// CMO3: CParameterSource.combined is true ONLY on the first (horizontal/X) member of a LINKED
		// ("combined") pair; the next parameter in document order is its vertical (Y) member.  Walk the
		// sources in order and, when a combined head has a following source, emit the (X, Y) link and skip
		// the consumed partner so an adjacent second pair is not mis-read as starting on that partner.  A
		// trailing combined head with no following member is ignored - there is no Y axis to pair.
		val parameterLinks =
			buildList {
				var sourceIndex = 0
				while (sourceIndex < parameterSources.size) {
					val source = parameterSources[sourceIndex]
					val nextSource = parameterSources.getOrNull(sourceIndex + 1)
					if (source.combined && nextSource != null) {
						val horizontalId = idStrOf(source.id)
						val verticalId = idStrOf(nextSource.id)
						if (horizontalId != null && verticalId != null) {
							add(ParameterLink(ParameterId(horizontalId), ParameterId(verticalId)))
						}
						sourceIndex += 2
					} else {
						sourceIndex += 1
					}
				}
			}

		// CMO3: CParameterGroup is the parameter-panel group (the editor's collapsible groups).
		// CModelSource.rootParameterGroup is the hidden top group; its _childGuids interleaves parameter and
		// subgroup GUIDs in panel order, and each subgroup nests the same way - mirroring the parts tree's
		// _childGuids walk.  Each CParameterSource also carries a redundant parentGroupGuid, but the
		// rootParameterGroup walk is the authoritative panel order.  See docs/format/CMO3.md §3.
		val parameterGroupByUuid =
			buildMap {
				val groups = (modelSource.parameterGroupSet as? CParameterGroupSet)?._groups
				for (group in elementsOf(groups).filterIsInstance<CParameterGroup>()) {
					val uuid = uuidOf(group.guid) ?: continue
					put(uuid, group)
				}
			}
		val parameterTree =
			buildParameterTree(modelSource.rootParameterGroup as? CParameterGroup, parameterGroupByUuid, paramIdByUuid)

		// Parts-panel pre-order index over the part tree, used to order the flat drawables list back-to-front
		// (the empty-renderRoot fallback / storage order; the derived renderRoot is the draw-order authority).
		val renderIndexByUuid = partsTreeRenderOrder(modelSource, partSources)

		// CMO3: CPartSource._childGuids is the parts-panel order - sub-parts and drawables interleaved (plus
		// deformers, which we route to the Armature instead). Resolve each child guid to a part or drawable
		// org child, skipping anything else; this ordered list is the org tree's single source of truth.
		fun orgChildrenOf(childGuids: Any?): List<OrgChild> =
			elementsOf(childGuids).mapNotNull { childGuid ->
				val childUuid = uuidOf(childGuid) ?: return@mapNotNull null
				drawableIdByUuid[childUuid]?.let { return@mapNotNull OrgChild.Drawable(it) }
				partIdByUuid[childUuid]?.let { return@mapNotNull OrgChild.Part(it) }
				null
			}

		// CMO3: part draw order is CPartForm.drawOrder (a keyform); the static first-cell value (else
		// defaultOrder_forEditor) is the fallback, the grid carries the per-pose blend for a group part.
		fun partStaticDrawOrder(part: CPartSource): Int =
			elementsOf(part.keyforms).filterIsInstance<CPartForm>().firstOrNull()?.drawOrder ?: part.defaultOrder_forEditor

		// A 5.3-authored part is marked by a present colorComposition (the offscreen probe pins this on every
		// corpus file); a pre-5.3 part has it null.
		fun isPre53Part(part: CPartSource): Boolean = part.colorComposition == null

		// CMO3: CPartForm.opacity is @DontSerializeIfDefault with a 0f default, and pre-5.3 parts never author
		// the float, so it parses as 0f meaning "unset -> fully opaque" (Cubism's semantic default is 100%),
		// NOT 0%.  A 5.3 part authors opacity explicitly, so a genuine 0 there (e.g. a draw-order crossfade
		// keyform) is a real value and is left alone - only pre-5.3's unset 0f is remapped to opaque.  Without
		// this a pre-5.3 model's whole part subtree renders invisible under the part-opacity cascade.
		fun resolvedPartOpacity(rawOpacity: Float, isPre53: Boolean): Float =
			if (isPre53 && rawOpacity == 0f) {
				1f
			} else {
				rawOpacity
			}

		fun partChannelsOf(part: CPartSource): ChannelGrids {
			val bundled =
				buildGrid(part.keyformGridSource, part.keyforms, paramIdByUuid) { form ->
					// CMO3: CPartForm carries the part's keyformed channels - drawOrder always, and (5.3)
					// opacity/multiplyColor/screenColor for the layer composite.
					(form as? CPartForm)?.let {
						PartForm(
							drawOrder = it.drawOrder.toFloat(),
							opacity = resolvedPartOpacity(it.opacity, isPre53Part(part)),
							multiplyColor = colorRgbOf(it.multiplyColor) ?: ColorRgb.MultiplyIdentity,
							screenColor = colorRgbOf(it.screenColor) ?: ColorRgb.ScreenIdentity,
						)
					}
				}
					// A grid with no parameter axes is a single static cell - it carries nothing the eval reads
					// beyond Part.drawOrder (kept separately) and the opacity/color channels, which then fall back
					// to the part's PartComposite.  Dropping it means an isolated static part honors an edited
					// composite opacity/color, and it matches Moc3Import, which yields no track for an unbound
					// part.  A part with real animation keeps its tracks (non-empty axes).
					?.takeIf { it.axes.isNotEmpty() } ?: return ChannelGrids.Empty
			// Fan the one bundled grid out into per-channel tracks sharing its axes: a pure re-shape, so the
			// blended values are bit-identical to what the bundled cell produced.
			return channelGridsOf(
				FormChannel.DRAW_ORDER to bundled.asChannelTrack { form -> ChannelValue.Scalar(form.drawOrder) },
				FormChannel.OPACITY to bundled.asChannelTrack { form -> ChannelValue.Scalar(form.opacity) },
				FormChannel.MULTIPLY_COLOR to bundled.asChannelTrack { form -> ChannelValue.Color(form.multiplyColor) },
				FormChannel.SCREEN_COLOR to bundled.asChannelTrack { form -> ChannelValue.Color(form.screenColor) },
			)
		}

		// CMO3: the composition/clip fields are latent - they survive unchecking offscreen, so this is
		// called for every part (not just offscreen ones) to capture the settings regardless of mode; the
		// composite is only applied while the part is Isolated.  Each field defaults gracefully when absent
		// (colorComposition/alphaComposition -> Normal/Over via the token maps; pre-5.3 CPartForms carry no
		// opacity/color elements, so firstForm's channels fall back to identity).
		fun partCompositeOf(source: CPartSource): PartComposite {
			val firstForm = elementsOf(source.keyforms).filterIsInstance<CPartForm>().firstOrNull()
			return PartComposite(
				blendMode = colorBlendOfToken((source.colorComposition as? ColorComposition)?.name),
				alphaBlendMode = alphaBlendOfToken((source.alphaComposition as? AlphaComposition)?.name),
				// CMO3: CPartSource.clipGuidList - always drawable GUIDs (a part picked as Clipping ID is
				// expanded to its drawables at authoring time).
				maskedBy = resolveGuids(source.clipGuidList, drawableIdByUuid),
				// CMO3: CPartSource.invertClippingMask - show outside the mask instead of inside.
				invertMask = source.invertClippingMask,
				// No form at all is already opaque; a pre-5.3 form's unset 0f is remapped to opaque too.
				opacity = firstForm?.let { resolvedPartOpacity(it.opacity, isPre53Part(source)) } ?: 1f,
				multiplyColor = colorRgbOf(firstForm?.multiplyColor) ?: ColorRgb.MultiplyIdentity,
				screenColor = colorRgbOf(firstForm?.screenColor) ?: ColorRgb.ScreenIdentity,
			)
		}

		val parts =
			userPartSources.map { source ->
				Part(
					id = PartId(idStrOf(source.id).orEmpty()),
					// CMO3: localName is the user-facing part name; fall back to the id.
					name = source.localName ?: idStrOf(source.id).orEmpty(),
					children = orgChildrenOf(source._childGuids),
					// CMO3: ACParameterControllableSource.isVisible = the Parts-panel eyeball (cascades to children);
					// CPartSource.isSketch = the Inspector's "Guide Image" checkbox (reference-only, non-export).
					isVisible = source.isVisible,
					isSketch = source.isSketch,
					// CMO3: ACParameterControllableSource.isLocked (inverted: Cubism lock = not selectable).
					isSelectable = !source.isLocked,
					// CMO3: CPartSource.useOffscreen = "offscreen drawing" (forces grouping on);
					// enableDrawOrderGroup = "Group by Draw Order"; CPartForm.drawOrder = the slot.
					groupMode =
						when {
							source.useOffscreen -> PartGroupMode.Isolated
							source.enableDrawOrderGroup -> PartGroupMode.Grouped
							else -> PartGroupMode.PassThrough
						},
					drawOrder = partStaticDrawOrder(source),
					channelGrids = partChannelsOf(source),
					// The composite is stored latently on every part (not just offscreen ones): the CMO3
					// composition/clip fields survive unchecking offscreen, so we capture them regardless of the
					// mode and apply them only while Isolated.  partCompositeOf defaults gracefully when absent.
					composite = partCompositeOf(source),
				)
			}

		val deformers =
			deformerSources.mapNotNull { source ->
				val id = idStrOf(source.id)?.let(::DeformerId) ?: return@mapNotNull null
				// CMO3: ACParameterControllableSource.localName is the user-facing deformer name; fall back to the id.
				val name = source.localName ?: idStrOf(source.id).orEmpty()
				// CMO3: targetDeformerGuid = parent deformer (transform tree); parentGuid = part (org tree).
				val parent = deformerIdByUuid[uuidOf(source.targetDeformerGuid)]
				val partId = partIdByUuid[uuidOf(source.parentGuid)]
				when (source) {
					is CWarpDeformerSource -> {
						val fanned = buildGrid(source.keyformGridSource, source.keyforms, paramIdByUuid, ::warpForm)?.fanOutWarp()
						Deformer.Warp(
							id,
							name,
							parent,
							partId,
							rows = source.row,
							columns = source.col,
							isQuadTransform = source.isQuadTransform,
							geometryGrid = fanned?.geometry,
							channelGrids = fanned?.channels ?: ChannelGrids.Empty,
							// CMO3: ACParameterControllableSource.isLocked (inverted: Cubism lock = not selectable).
							isSelectable = !source.isLocked,
							// CMO3: ACParameterControllableSource.isVisible - the deformer's own eyeball.  CMO3 has
							// no analogue of the MOC3's second flag, so isEnabled keeps its default.
							isVisible = source.isVisible,
							blendShapes =
								blendShapeBindingsOf(source.keyformMorphTargetSet, source.keyforms, paramIdByUuid, ::warpForm),
						)
					}
					is CRotationDeformerSource -> {
						val fanned = buildGrid(source.keyformGridSource, source.keyforms, paramIdByUuid, ::rotationForm)?.fanOutRotation()
						Deformer.Rotation(
							id,
							name,
							parent,
							partId,
							baseAngle = source.baseAngle,
							geometryGrid = fanned?.geometry,
							channelGrids = fanned?.channels ?: ChannelGrids.Empty,
							// CMO3: ACParameterControllableSource.isLocked (inverted: Cubism lock = not selectable).
							isSelectable = !source.isLocked,
							// CMO3: ACParameterControllableSource.isVisible - the deformer's own eyeball.  CMO3 has
							// no analogue of the MOC3's second flag, so isEnabled keeps its default.
							isVisible = source.isVisible,
							blendShapes =
								blendShapeBindingsOf(source.keyformMorphTargetSet, source.keyforms, paramIdByUuid, ::rotationForm),
						)
					}
					else -> null
				}
			}

		// Render order: drawables painted back-to-front. The parts-tree DFS (panel order, top = front)
		// is front-to-back, so the drawable list is its reverse - the last panel entry is the backmost,
		// drawn first. drawOrder (all 500 in the corpus) and its animation refine this later; the tree
		// walk is the param-independent base. CMO3: CPartSource._childGuids holds child parts / deformers
		// / meshes in panel order; the drawableSourceSet is creation order, not stacking order. The shared
		// renderIndexByUuid (computed above for parts) is the source of both this order and panelOrder.
		val orderedDrawableSources = drawableSources.sortedByDescending { renderIndexByUuid[uuidOf(it.guid)] ?: Int.MAX_VALUE }

		val drawables =
			orderedDrawableSources.map { source ->
				val mesh = meshOf(source)
				// One bundled grid, then split into per-vertex deltas and the render channels.
				val fannedMesh =
					buildGrid(source.keyformGridSource, source.keyforms, paramIdByUuid) { form ->
						meshForm(form, mesh?.positions)
					}?.fanOutMesh()
				Drawable(
					id = DrawableId(idStrOf(source.id).orEmpty()),
					// CMO3: ACParameterControllableSource.localName is the user-facing drawable name; fall back to the id.
					name = source.localName ?: idStrOf(source.id).orEmpty(),
					parentDeformerId = deformerIdByUuid[uuidOf(source.targetDeformerGuid)],
					// CMO3: colorComposition/alphaComposition - the full 5.3 blend surface (bare legacy
					// tokens on pre-5.3 meshes; every mode token maps, see BlendModeMapping).
					blendMode = colorBlendOfToken((source.colorComposition as? ColorComposition)?.name),
					alphaBlendMode = alphaBlendOfToken((source.alphaComposition as? AlphaComposition)?.name),
					maskedBy = resolveGuids(source.clipGuidList, drawableIdByUuid),
					// CMO3: ACDrawableSource.invertClippingMask - show outside the mask instead of inside.
					invertMask = source.invertClippingMask,
					// CMO3: CArtMeshSource.culling - back-face culling (default false = double-sided).
					culling = source.culling,
					// CMO3: ACParameterControllableSource.isVisible - the drawable's own eyeball (ANDed with parents).
					isVisible = source.isVisible,
					// CMO3: ACParameterControllableSource.isLocked (inverted: Cubism lock = not selectable).
					isSelectable = !source.isLocked,
					mesh = mesh,
					geometryGrid = fannedMesh?.geometry,
					channelGrids = fannedMesh?.channels ?: ChannelGrids.Empty,
					blendShapes =
						blendShapeBindingsOf(source.keyformMorphTargetSet, source.keyforms, paramIdByUuid) { form ->
							meshForm(form, mesh?.positions)
						},
				)
			}

		// rootPartId records the synthetic root's id (for tree reconstruction / write-back) even though
		// it is intentionally absent from [parts]; entities under it carry partId == null.
		val rootPartId =
			partSources.firstOrNull { uuidOf(it.guid) == rootPartUuid }
				?.let { idStrOf(it.id) }
				?.let(::PartId)

		// Glue affecters: seam-weld vertex pairs after deformation. CMO3 references vertices by stable UID;
		// build uid→index per drawable from its editable mesh (positions[i] == editableMesh.point[i]).
		val uidToIndexByDrawableId =
			buildMap {
				for (source in drawableSources) {
					val id = idStrOf(source.id)?.let(::DrawableId) ?: continue
					val pointUid = editableMeshOf(source)?.pointUid as? IntArray ?: continue
					put(id, buildMap { pointUid.forEachIndexed { vertexIndex, uid -> put(uid, vertexIndex) } })
				}
			}
		val glues =
			elementsOf((modelSource.affecterSourceSet as? CAffecterSourceSet)?._sources)
				.filterIsInstance<CGlueSource>()
				.mapNotNull { glue -> glueOf(glue, drawableIdByUuid, uidToIndexByDrawableId, paramIdByUuid) }

		// The org tree's top level: the synthetic root part's _childGuids (parts + drawables interleaved).
		val rootChildrenFromTree = orgChildrenOf((rootPart as? CPartSource)?._childGuids)
		// Safety net: any drawable the panel walk never placed (e.g. one reachable only under a deformer) is
		// appended at the root, so no mesh is dropped from the outliner or the derived render order.
		val placedDrawableIds =
			buildSet {
				rootChildrenFromTree.forEach { child -> if (child is OrgChild.Drawable) add(child.id) }
				parts.forEach { part -> part.children.forEach { child -> if (child is OrgChild.Drawable) add(child.id) } }
			}
		val rootChildren =
			rootChildrenFromTree + drawables.mapNotNull { drawable -> if (drawable.id in placedDrawableIds) null else OrgChild.Drawable(drawable.id) }

		// CMO3: CModelSource.canvas -> CImageCanvas fields pixelWidth / pixelHeight - the document canvas
		// size.  Drawable rest positions live in this canvas px space (Y down, the canvas rect spanning
		// [0, width] x [0, height]; art may overhang it); the deform eval NEGATES Y into world space.
		// A model without a canvas keeps the (0, 0) defaults.
		val canvas = modelSource.canvas as? CImageCanvas
		val canvasWidth = (canvas?.pixelWidth ?: 0).toFloat()
		val canvasHeight = (canvas?.pixelHeight ?: 0).toFloat()

		// The world origin is the CANVAS CENTER - the same origin a MOC3 export computes for CanvasInfo
		// (MOC3 §5.3 originX/originY = canvasWidth/2, canvasHeight/2 across the whole corpus).  It is NOT
		// CModelSource.modelInfo's CModelInfo.originInPixels: that CPoint sits at its (0, 0) default in
		// every corpus model, so reading it collapses both axis lines onto the canvas corner.  The center
		// is canvas-space, so its y negates into world space like every vertex (a positive canvas y stored
		// raw would float a full canvas above the art - the "X axis above the head" bug).  The viewport
		// axes cross here and snap.cursorToWorldOrigin lands here.
		val originCanvasX = canvasWidth / 2f
		val originCanvasY = canvasHeight / 2f

		// Draw order is a pure derivation of the org tree + the parts' draw-order group flags / values, so
		// the renderer's render-order tree is computed, never a parallel source of truth that can drift.
		val model =
			PuppetModel(
				parameters = parameters,
				parts = parts,
				deformers = deformers,
				drawables = drawables,
				rootChildren = rootChildren,
				rootPartId = rootPartId,
				glues = glues,
				parameterLinks = parameterLinks,
				parameterTree = parameterTree,
				canvasWidth = canvasWidth,
				canvasHeight = canvasHeight,
				worldOriginX = originCanvasX,
				worldOriginY = -originCanvasY,
				// CMO3: CModelSource field targetVersionNo - the authored SDK target.  The SDK(N/A)/Latest
				// sentinel, unknown values, and an absent field all map to NoTarget, so nothing is
				// restricted.
				runtimeTarget = runtimeTargetOfCmo3Target(Cmo3TargetVersion.fromVersionNo(modelSource.targetVersionNo as? Int)),
				// CMO3: CTextureManager field isTextureInputModelImageMode - true displays the combined
				// layer images, false the packed atlas.  The texture manager is mandatory even when empty
				// (docs/format/CMO3.md §3), so an absent one falls back to the atlas default.
				rendersFromSourceLayers = (modelSource.textureManager as? CTextureManager)?.isTextureInputModelImageMode ?: false,
			)
		val withRenderRoot = model.copy(renderRoot = model.deriveRenderRoot())
		return if (compactChannels) withRenderRoot.withChannelsCompacted() else withRenderRoot
	}

	/**
	 * Builds the parameter-panel group tree from the CMO3 root CParameterGroup, returning the root's
	 * children in panel order.  Walks each group's `_childGuids`, resolving every entry to either a leaf
	 * parameter (uuid in [paramIdByUuid]) or a nested group (uuid in [groupByUuid]), recursing into the
	 * latter.  A `visited` set guards against a malformed cyclic group reference, mirroring the parts-tree
	 * walk.  Returns an empty list when there is no root group (a group-less model), so callers fall back
	 * to the flat parameter list.
	 *
	 * @param CParameterGroup? rootGroup     The hidden top group (CModelSource.rootParameterGroup), or null.
	 * @param Map              groupByUuid   uuid → CParameterGroup, for resolving nested-group references.
	 * @param Map              paramIdByUuid uuid → ParameterId, for resolving leaf parameters.
	 * @return List<ParameterNode> The root group's children in panel order (empty when no root group).
	 */
	private fun buildParameterTree(
		rootGroup: CParameterGroup?,
		groupByUuid: Map<String, CParameterGroup>,
		paramIdByUuid: Map<String, ParameterId>,
	): List<ParameterNode> {
		if (rootGroup == null) {
			return emptyList()
		}
		val visited = HashSet<String>()

		fun childrenOf(group: CParameterGroup): List<ParameterNode> =
			buildList {
				for (childGuid in elementsOf(group._childGuids)) {
					val uuid = uuidOf(childGuid) ?: continue
					val paramId = paramIdByUuid[uuid]
					if (paramId != null) {
						add(ParameterNode.Param(paramId))
						continue
					}
					val childGroup = groupByUuid[uuid] ?: continue
					if (!visited.add(uuid)) {
						continue
					}
					add(
						ParameterNode.Group(
							id = ParameterGroupId(idStrOf(childGroup.id).orEmpty()),
							// CMO3: CParameterGroup.name is the group label; fall back to the id.  Names are
							// not unique (group names can repeat), so identity stays on the id.
							name = childGroup.name ?: idStrOf(childGroup.id).orEmpty(),
							// CMO3: CParameterGroup.folderIsOpened - the editor's saved expand/collapse state.
							initiallyOpen = childGroup.folderIsOpened,
							children = childrenOf(childGroup),
						),
					)
				}
			}
		return childrenOf(rootGroup)
	}

	/**
	 * The editable mesh holding a drawable's stable per-vertex UIDs (`pointUid[i]` is vertex `i`'s UID,
	 * since `editableMesh.point[i] == CArtMeshSource.positions[i]`).
	 *
	 * @param CArtMeshSource source The art-mesh source.
	 * @return GEditableMesh2? The editable mesh, or null if the extension is absent.
	 */
	internal fun editableMeshOf(source: CArtMeshSource): GEditableMesh2? =
		elementsOf(source._extensions).filterIsInstance<CEditableMeshExtension>().firstOrNull()?.editableMesh as? GEditableMesh2

	/**
	 * Maps a CMO3 `CGlueSource` to a runtime [Glue], resolving its target meshes and converting each
	 * UID-keyed vertex pair to mesh-local indices. CMO3: `weights`/`bindVertexUids` are `[A,B]`-interleaved
	 * per pair; `keyforms` (CGlueForm.intensity) drive the weld strength.
	 *
	 * @param CGlueSource glue                  The glue source.
	 * @param Map         drawableIdByUuid      uuid → DrawableId.
	 * @param Map         uidToIndexByDrawableId DrawableId → (vertex uid → index).
	 * @param Map         paramIdByUuid         uuid → ParameterId (for the intensity grid).
	 * @return Glue? The runtime glue, or null when a target/extension is missing.
	 */
	private fun glueOf(
		glue: CGlueSource,
		drawableIdByUuid: Map<String, DrawableId>,
		uidToIndexByDrawableId: Map<DrawableId, Map<Int, Int>>,
		paramIdByUuid: Map<String, ParameterId>,
	): Glue? {
		val meshA = drawableIdByUuid[uuidOf(glue.targetArtMeshA_guid)] ?: return null
		val meshB = drawableIdByUuid[uuidOf(glue.targetArtMeshB_guid)] ?: return null
		val weights = glue.weights as? FloatArray ?: return null
		val uids = glue.bindVertexUids as? LongArray ?: return null
		val uidToIndexA = uidToIndexByDrawableId[meshA] ?: return null
		val uidToIndexB = uidToIndexByDrawableId[meshB] ?: return null
		val pairCount = minOf(weights.size, uids.size) / 2
		val pairs = ArrayList<GluePair>(pairCount)
		for (pairIndex in 0 until pairCount) {
			val indexA = uidToIndexA[uids[pairIndex * 2].toInt()] ?: continue
			val indexB = uidToIndexB[uids[pairIndex * 2 + 1].toInt()] ?: continue
			pairs.add(GluePair(indexA, indexB, weights[pairIndex * 2], weights[pairIndex * 2 + 1]))
		}
		val intensityTrack =
			buildGrid(glue.keyformGridSource, glue.keyforms, paramIdByUuid, ::glueForm)
				?.asChannelTrack { form -> ChannelValue.Scalar(form.intensity) }
		// A glue with no keyed intensity welds fully, which is the runtime's long-standing fallback.
		return Glue(meshA, meshB, pairs, channelGridsOf(FormChannel.GLUE_INTENSITY to intensityTrack), intensity = 1f)
	}

	/**
	 * Glue cell payload: the weld intensity captured at one grid cell.
	 *
	 * @param Any form The form object (expected CGlueForm).
	 * @return GlueForm? The intensity form, or null if absent.
	 */
	private fun glueForm(form: Any): GlueForm? = (form as? CGlueForm)?.let { GlueForm(it.intensity) }

	/**
	 * Flattens a CMO3 collection field (a `CArrayList`/`ArrayList`, or a `CHashMap`) to a plain list.
	 *
	 * @param Any? collection The raw `_sources`/`_childGuids`/etc. field, held as `Any?`.
	 * @return List<Any?> The contained elements, or empty when the field is null/unrecognised.
	 */
	internal fun elementsOf(collection: Any?): List<Any?> =
		when (collection) {
			is Map<*, *> -> collection.values.toList()
			is Iterable<*> -> collection.toList()
			is Array<*> -> collection.toList()
			else -> emptyList()
		}

	/**
	 * Assigns each entity a pre-order index from a depth-first walk of the parts tree, following every
	 * part's ordered `_childGuids`. Drawables sorted by their index render in the editor's panel
	 * stacking order. Non-part children (deformers, meshes) are leaves and get an index without
	 * recursing; the gaps they leave do not matter since only relative order is used.
	 *
	 * @param CModelSource      modelSource The model root (for `rootPart`).
	 * @param List<CPartSource> partSources All part sources (including the synthetic root).
	 * @return Map<String,Int> uuid → render-order index.
	 */
	private fun partsTreeRenderOrder(modelSource: CModelSource, partSources: List<CPartSource>): Map<String, Int> {
		val partByUuid = partSources.mapNotNull { part -> uuidOf(part.guid)?.let { it to part } }.toMap()
		val rootPart = modelSource.rootPart
		val rootUuid = (rootPart as? CPartSource)?.let { uuidOf(it.guid) } ?: uuidOf(rootPart)
		val order = HashMap<String, Int>()
		val visited = HashSet<String>()
		var nextIndex = 0

		fun walk(uuid: String) {
			if (!visited.add(uuid)) {
				return
			}
			order[uuid] = nextIndex++
			val part = partByUuid[uuid] ?: return
			for (child in elementsOf(part._childGuids)) {
				val childUuid = uuidOf(child) ?: continue
				walk(childUuid)
			}
		}

		if (rootUuid != null) {
			walk(rootUuid)
		}
		return order
	}

	/**
	 * Resolves a CMO3 GUID list (e.g. `_childGuids`, `clipGuidList`) to runtime ids via [index].
	 *
	 * @param Any?         guidList A field holding a collection of `Guid` objects.
	 * @param Map<String,T> index   uuid → runtime id, from pass 1.
	 * @return List<T> The resolved ids, skipping any that do not resolve.
	 */
	private fun <T> resolveGuids(guidList: Any?, index: Map<String, T>): List<T> =
		elementsOf(guidList).mapNotNull { index[uuidOf(it)] }

	/**
	 * Extracts the uuid string from a CMO3 `Guid` object, or null if absent/empty.
	 *
	 * @param Any? value A field expected to hold a `Guid`.
	 * @return String? The uuid, or null.
	 */
	internal fun uuidOf(value: Any?): String? = (value as? Guid)?.uuid?.takeIf { it.isNotEmpty() }

	/**
	 * Extracts the id string from a CMO3 `Id` object, or null if absent/empty.
	 *
	 * @param Any? value A field expected to hold an `Id`.
	 * @return String? The idstr (e.g. `ParamAngleX`, `ArtMesh82`), or null.
	 */
	internal fun idStrOf(value: Any?): String? = (value as? Id)?.idstr?.takeIf { it.isNotEmpty() }

	/**
	 * Converts a CMO3 `CFloatColor` field to the runtime [ColorRgb].
	 *
	 * @param Any? value A field expected to hold a `CFloatColor` (channels = editor hex / 255).
	 * @return ColorRgb? The color, or null when absent (pre-5.3 forms carry no color elements).
	 */
	private fun colorRgbOf(value: Any?): ColorRgb? =
		// CMO3: CFloatColor red/green/blue attributes; the alpha attribute is 1.0 in every observed
		// sample and is not modeled.
		(value as? CFloatColor)?.let { ColorRgb(it.red, it.green, it.blue) }

	/**
	 * Reads an art mesh's rest-pose geometry. CMO3: `CArtMeshSource.positions`/`uvs` are `float-array`
	 * (interleaved x,y), `indices` is an `int-array` (3 per triangle).
	 *
	 * An UNPACKED drawable stores its `uvs` in the model-image LOGICAL [0,1] frame, not the sampled image's
	 * own frame, so those are remapped through [imageResourceUvs]; a PACKED drawable's UVs already index the
	 * image and are taken verbatim (see [hasAtlasRegion]).
	 *
	 * @param CArtMeshSource source The art-mesh source.
	 * @return DrawableMesh? The base mesh, or null when the source carries no positions.
	 */
	private fun meshOf(source: CArtMeshSource): DrawableMesh? {
		val positions = source.positions as? FloatArray ?: return null
		val storedUvs = source.uvs as? FloatArray ?: FloatArray(0)
		val indices = source.indices as? IntArray ?: IntArray(0)
		val uvs =
			if (hasAtlasRegion(source)) {
				storedUvs
			} else {
				imageResourceUvs(storedUvs, source.texture as? GTexture2D)
			}
		return DrawableMesh(positions, uvs, indices)
	}

	/**
	 * Whether a drawable carries a `CTextureInput_TextureAtlasRegion` in its texture-input extension - i.e.
	 * it was packed into a texture atlas at some point.  This is the join key for the UV frame convention:
	 * packing rewrites a mesh's `uvs` into its source-image [0,1] frame (and they stay there even when the
	 * model is toggled back to combined-layer display), while a never-packed drawable keeps its `uvs` in the
	 * model-image logical frame.  A model with no atlas at all (MultiplyScreenColors) has only model-image
	 * inputs, so every drawable returns false; a model built with an atlas but shown in combined-layer mode
	 * (modelA) returns true for its packed drawables and false for any left unpacked (a stray guide layer).
	 *
	 * CMO3: CArtMeshSource _extensions -> CTextureInputExtension._textureInputs holds a
	 * CTextureInput_ModelImage and, once packed, a CTextureInput_TextureAtlasRegion.  See docs/format/CMO3.md §4.
	 *
	 * @param CArtMeshSource source The art-mesh source.
	 * @return Boolean True when the drawable has an atlas-region texture input.
	 */
	internal fun hasAtlasRegion(source: CArtMeshSource): Boolean {
		val extension = elementsOf(source._extensions).filterIsInstance<CTextureInputExtension>().firstOrNull() ?: return false
		return elementsOf(extension._textureInputs).any { it is CTextureInput_TextureAtlasRegion }
	}

	/**
	 * Remaps an UNPACKED drawable's UVs from the model-image logical [0,1] frame into the [0,1] frame of the
	 * per-layer image it samples (`GTexture2D.srcImageResource`).  Only ever called for drawables with no
	 * atlas region ([hasAtlasRegion] is false); a packed drawable's UVs already index its image and must not
	 * pass through here.
	 *
	 * `GTexture2D` carries the affine `transformImageResource01toLogical01` mapping the image's [0,1] into the
	 * logical frame, so sampling the image needs the INVERSE of it.  Without it the smaller image overhangs
	 * its UV region and the art renders enlarged with its outer margin clipped (the MultiplyScreenColors
	 * "white border cut off" symptom).  A near-identity affine (image already fills the logical frame) or a
	 * degenerate one leaves the UVs untouched.  See docs/format/CMO3.md §4.
	 *
	 * CMO3: GTexture2D field transformImageResource01toLogical01 - a CAffine, imageResource[0,1] to logical[0,1].
	 *
	 * @param FloatArray  logicalUvs The interleaved (u, v) UVs in the logical model-image frame.
	 * @param GTexture2D? texture    The drawable's texture, holding the imageResource to logical affine.
	 * @return FloatArray The UVs in the sampled image's [0,1] frame (the same array when no remap applies).
	 */
	private fun imageResourceUvs(logicalUvs: FloatArray, texture: GTexture2D?): FloatArray {
		val affine = texture?.transformImageResource01toLogical01 as? CAffine ?: return logicalUvs
		val determinant = affine.m00 * affine.m11 - affine.m01 * affine.m10
		// Identity (image already fills the logical frame) or a degenerate affine: leave the UVs as-is -
		// inverting a zero-determinant affine would yield NaN/Inf.
		val isIdentity =
			affine.m00 == 1f && affine.m01 == 0f && affine.m02 == 0f && affine.m10 == 0f && affine.m11 == 1f && affine.m12 == 0f
		if (isIdentity || determinant == 0f) {
			return logicalUvs
		}
		// Apply the inverse of the 2x3 affine: subtract the translation, then the inverse 2x2 linear part.
		val result = FloatArray(logicalUvs.size)
		var componentIndex = 0
		while (componentIndex + 1 < logicalUvs.size) {
			val logicalU = logicalUvs[componentIndex] - affine.m02
			val logicalV = logicalUvs[componentIndex + 1] - affine.m12
			result[componentIndex] = (affine.m11 * logicalU - affine.m01 * logicalV) / determinant
			result[componentIndex + 1] = (-affine.m10 * logicalU + affine.m00 * logicalV) / determinant
			componentIndex += 2
		}
		return result
	}

	/**
	 * Builds the keyform grid for a controllable source. CMO3: `keyformGridSource` holds the axes
	 * (`keyformBindings`: parameterGuid + key values) and the cells (`keyformsOnGrid`: an accessKey
	 * coordinate + a `keyformGuid` referencing one of the source's `keyforms` form objects).
	 *
	 * @param Any?     gridSource    The source's `keyformGridSource`.
	 * @param Any?     forms         The source's `keyforms` collection (the form objects).
	 * @param Map      paramIdByUuid uuid → ParameterId, from pass 1.
	 * @param Function cellPayload   Builds the typed form payload from a raw form object.
	 * @return KeyformGrid<TForm>? The grid, or null when the source carries no grid.
	 */
	private fun <TForm : Any> buildGrid(
		gridSource: Any?,
		forms: Any?,
		paramIdByUuid: Map<String, ParameterId>,
		cellPayload: (Any) -> TForm?,
	): KeyformGrid<TForm>? {
		val grid = gridSource as? KeyformGridSource ?: return null
		val bindings = elementsOf(grid.keyformBindings).filterIsInstance<KeyformBindingSource>()
		val axes =
			bindings.map { binding ->
				KeyformAxis(
					parameterId = paramIdByUuid[uuidOf(binding.parameterGuid)] ?: ParameterId(""),
					keys = floatKeys(binding.keys),
				)
			}
		// accessKey entries point at a binding object (resolved by identity), giving each cell's coord.
		val axisOfBinding = bindings.withIndex().associate { (index, binding) -> binding to index }
		val formByUuid = formPool(forms)
		val cells =
			elementsOf(grid.keyformsOnGrid).filterIsInstance<KeyformOnGrid>().mapNotNull { cell ->
				val form = formByUuid[uuidOf(cell.keyformGuid)] ?: return@mapNotNull null
				val payload = cellPayload(form) ?: return@mapNotNull null
				val coordinate = IntArray(axes.size)
				val keyList = (cell.accessKey as? KeyformGridAccessKey)?._keyOnParameterList
				for (keyOnParameter in elementsOf(keyList).filterIsInstance<KeyOnParameter>()) {
					val axisIndex = axisOfBinding[keyOnParameter.binding] ?: continue
					coordinate[axisIndex] = keyOnParameter.keyIndex
				}
				KeyformCell(coordinate, payload)
			}
		return KeyformGrid(axes, cells)
	}

	/**
	 * Decodes a binding's `keys` (an `ArrayList<Float>` of parameter values) to a FloatArray.
	 *
	 * @param Any? keys The binding's `keys` field.
	 * @return FloatArray The key parameter values (empty when absent).
	 */
	private fun floatKeys(keys: Any?): FloatArray {
		val values = elementsOf(keys)
		return FloatArray(values.size) { (values[it] as? Number)?.toFloat() ?: 0f }
	}

	/**
	 * Reads a form's own guid (`ACForm.guid`), used to resolve a cell's `keyformGuid` reference.
	 *
	 * @param Any? form A form object (CArtMeshForm / CWarpDeformerForm / CRotationDeformerForm).
	 * @return Any? The form's `guid` (a `Guid`), or null.
	 */
	private fun formGuid(form: Any?): Any? = (form as? ACForm)?.guid

	/**
	 * Indexes a source's `keyforms` pool by each form's own guid uuid. Grid cells and morph
	 * targets both resolve their form references through this pool.
	 *
	 * @param Any? forms The source's `keyforms` collection (the form objects).
	 * @return Map The uuid → form object index.
	 */
	private fun formPool(forms: Any?): Map<String?, Any?> = elementsOf(forms).associateBy { uuidOf(formGuid(it)) }

	/**
	 * Builds a source's blend-shape bindings from its morph-target set: one binding per driving
	 * parameter, keys sorted with the neutral key inserted at value 0.
	 *
	 * CMO3: ACParameterControllableSource field keyformMorphTargetSet - a KeyFormMorphTargetSet
	 * whose _morphTargets each bind one form (keyformGuid, from the source's shared keyforms pool)
	 * at one keyValue on one driving parameter (parameterGuid).  CMO3 stores no morph target at
	 * value 0 in the usual case; the baked MOC3 records carry sorted(keys + 0) with the neutral at
	 * value 0, so ingest inserts it here with a null form (corpus-verified rule - see
	 * MorphTargetJoinProbeTest).  The editBaseParameterMap field is ignored: it is null in every
	 * corpus instance, its semantics unresolved.
	 *
	 * @param Any?     morphTargetSet    The source's `keyformMorphTargetSet`.
	 * @param Any?     forms             The source's `keyforms` collection (the form objects).
	 * @param Map      paramIdByUuid     uuid → ParameterId, from pass 1.
	 * @param Function formPayload       Builds the typed form payload from a raw form object.
	 * @return List The bindings (empty when the source carries no morph targets).
	 */
	private fun <TForm : Any> blendShapeBindingsOf(
		morphTargetSet: Any?,
		forms: Any?,
		paramIdByUuid: Map<String, ParameterId>,
		formPayload: (Any) -> TForm?,
	): List<BlendShapeBinding<TForm>> {
		val set = morphTargetSet as? KeyFormMorphTargetSet ?: return emptyList()
		val targets = elementsOf(set._morphTargets).filterIsInstance<KeyFormMorphTarget>()
		if (targets.isEmpty()) {
			return emptyList()
		}
		val formByUuid = formPool(forms)
		val constraints =
			elementsOf((set.blendWeightConstraintSet as? MorphTargetBlendWeightConstraintSet)?._constraints)
				.filterIsInstance<MorphTargetBlendWeightConstraint>()
		return targets.groupBy { uuidOf(it.parameterGuid) }.mapNotNull { (parameterUuid, group) ->
			val parameterId = parameterUuid?.let(paramIdByUuid::get) ?: return@mapNotNull null
			val neutralValue = 0f
			val payloadByKey =
				group.mapNotNull { target ->
					val form = formByUuid[uuidOf(target.keyformGuid)] ?: return@mapNotNull null
					val payload = formPayload(form) ?: return@mapNotNull null
					target.keyValue to payload
				}.toMap()
			if (payloadByKey.isEmpty()) {
				return@mapNotNull null
			}
			val keys = (payloadByKey.keys + neutralValue).sorted()
			BlendShapeBinding(
				parameterId = parameterId,
				keys = keys.toFloatArray(),
				neutralIndex = keys.indexOf(neutralValue),
				forms = keys.map { key -> payloadByKey[key] },
				limits = blendWeightLimitsOf(constraints, parameterUuid, paramIdByUuid),
			)
		}
	}

	/**
	 * Groups a morph-target set's flat constraint records into per-parameter limit curves for one
	 * driving parameter.
	 *
	 * CMO3: MorphTargetBlendWeightConstraint - (morphTargetParameterGuid, constraintParameterGuid,
	 * constraintParameterValue, blendWeight); one curve point per record, grouped by the
	 * constraint parameter, sorted by its value.
	 *
	 * @param List collection    The set's constraint records.
	 * @param String morphTargetParameterUuid The driving parameter whose limits are wanted.
	 * @param Map  paramIdByUuid uuid → ParameterId, from pass 1.
	 * @return List The limit curves (empty when the set has none for this parameter).
	 */
	private fun blendWeightLimitsOf(
		collection: List<MorphTargetBlendWeightConstraint>,
		morphTargetParameterUuid: String,
		paramIdByUuid: Map<String, ParameterId>,
	): List<BlendWeightLimit> =
		collection
			.filter { uuidOf(it.morphTargetParameterGuid) == morphTargetParameterUuid }
			.groupBy { uuidOf(it.constraintParameterGuid) }
			.mapNotNull { (constraintUuid, records) ->
				val parameterId = constraintUuid?.let(paramIdByUuid::get) ?: return@mapNotNull null
				BlendWeightLimit(
					parameterId = parameterId,
					points =
						records
							.sortedBy { it.constraintParameterValue }
							.map { BlendWeightLimitPoint(it.constraintParameterValue, it.blendWeight) },
				)
			}

	/**
	 * Mesh cell payload: the form's absolute positions converted to deltas vs the mesh [base].
	 *
	 * @param Any         form The form object (expected CArtMeshForm).
	 * @param FloatArray? base The mesh base positions.
	 * @return MeshForm? The delta form, or null if the form has no positions.
	 */
	private fun meshForm(form: Any, base: FloatArray?): MeshForm? {
		val artForm = form as? CArtMeshForm ?: return null
		val positions = artForm.positions as? FloatArray ?: return null
		// CMO3: ACDrawableForm.drawOrder (Cubism default 500) + opacity (0..1) + the 5.3 per-art-mesh
		// multiply/screen color ride on the art-mesh keyform (pre-5.3 forms carry no color -> identity).
		return MeshForm(
			deltaVsBase(base, positions),
			artForm.drawOrder.toFloat(),
			artForm.opacity,
			multiplyColor = colorRgbOf(artForm.multiplyColor) ?: ColorRgb.MultiplyIdentity,
			screenColor = colorRgbOf(artForm.screenColor) ?: ColorRgb.ScreenIdentity,
		)
	}

	/**
	 * Warp cell payload: the form's absolute FFD control-point positions plus its render channels.
	 *
	 * The channels live on the shared `ACDeformerForm` base, so warp and rotation read them the same
	 * way. They cascade onto every drawable under the deformer - see `DeformerCascade`.
	 *
	 * @param Any form The form object (expected CWarpDeformerForm).
	 * @return WarpForm? The control points, or null if absent.
	 */
	private fun warpForm(form: Any): WarpForm? {
		val warp = form as? CWarpDeformerForm ?: return null
		val positions = warp.positions as? FloatArray ?: return null
		// CMO3: ACDeformerForm opacity / multiplyColor / screenColor.
		return WarpForm(
			positions,
			opacity = warp.opacity,
			multiplyColor = colorRgbOf(warp.multiplyColor) ?: ColorRgb.MultiplyIdentity,
			screenColor = colorRgbOf(warp.screenColor) ?: ColorRgb.ScreenIdentity,
		)
	}

	/**
	 * Rotation cell payload: the form's absolute pivot transform plus its render channels.
	 *
	 * @param Any form The form object (expected CRotationDeformerForm).
	 * @return RotationForm? The transform, or null if absent.
	 */
	private fun rotationForm(form: Any): RotationForm? {
		val rotation = form as? CRotationDeformerForm ?: return null
		// CMO3: ACDeformerForm opacity / multiplyColor / screenColor.
		return RotationForm(
			rotation.originX,
			rotation.originY,
			rotation.angle,
			rotation.scale,
			rotation.isReflectX,
			rotation.isReflectY,
			opacity = rotation.opacity,
			multiplyColor = colorRgbOf(rotation.multiplyColor) ?: ColorRgb.MultiplyIdentity,
			screenColor = colorRgbOf(rotation.screenColor) ?: ColorRgb.ScreenIdentity,
		)
	}

	/**
	 * Per-vertex deltas of [positions] vs [base] (`positions − base`), or a copy of positions when
	 * there is no size-matching base, so the form is kept absolute rather than dropped.
	 *
	 * @param FloatArray? base      The mesh base positions.
	 * @param FloatArray  positions The form's absolute positions.
	 * @return FloatArray The deltas, or a copy of positions.
	 */
	private fun deltaVsBase(base: FloatArray?, positions: FloatArray): FloatArray {
		if (base == null || base.size != positions.size) {
			return positions.copyOf()
		}
		return FloatArray(positions.size) { positions[it] - base[it] }
	}
}