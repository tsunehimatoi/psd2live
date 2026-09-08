package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3GraphEditor
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.custom.GEditableMesh2
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CEditableMeshExtension
import org.umamo.format.cmo3.model.gen.CParameterGroup
import org.umamo.format.cmo3.model.gen.CParameterGroupSet
import org.umamo.format.cmo3.model.gen.CParameterSource
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_ModelImage
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.gen.Type
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.partByDrawable

/*
 * The structural half of the CMO3 export reconcile: set membership.  Creations synthesize an
 * IDENTITY SHELL only (fresh guid/id, the texture/extension chain a duplicate shares with its
 * source) and are appended to their source set; the caller then routes the new entity through the
 * ordinary Changed lowering with every field flagged, so field writes flow through the one tested
 * path rather than a parallel synthesis copy.  Deletions remove the source AND strip its guid from
 * every parts-panel child list (a removed guid the org rebuild would otherwise preserve as an
 * unknown entry); the dangling cross-references (masks, axes, glues) fall out of the model-driven
 * Changed diffs, and the shared pool is pruned once at the end.
 *
 * Topology rewrites and glue vertex re-binding also live here: they regenerate the GEditableMesh2
 * identity surface (point mirror, fresh vertex uids, the triangle edge set) that the rest of the
 * document hangs off.
 */
internal class Cmo3StructureLowering(
	private val modelSource: CModelSource,
	private val index: Cmo3GraphIndex,
	private val editor: Cmo3GraphEditor,
	private val edited: PuppetModel,
	private val notices: MutableList<ExportNotice>,
	private val drawableBindings: Map<String, Cmo3DrawableTextureBinding> = emptyMap(),
) {
	/** True once any deletion ran - the caller prunes the shared pool exactly once at the end. */
	var deletedAnything: Boolean = false
		private set

	private fun unsupported(
		category: ExportEntityCategory,
		subject: String?,
		reason: ExportNoticeReason,
	) {
		notices.add(ExportNotice.UnsupportedChange(category, subject, reason))
	}

	private fun freshGuidLike(template: Guid?, fallbackKind: String): Guid =
		Guid(template?.kind ?: fallbackKind).apply {
			uuid = java.util.UUID.randomUUID().toString()
			note = template?.note ?: "(no debug info)"
		}

	/**
	 * The shared CoordType every fresh editable mesh gets.
	 *
	 * CMO3: GEditableMesh2 field coordType - corpus meshes always carry a CoordType with coordName
	 * "Basic Coord"; one instance is reused so the writer hoists a single shared def.
	 */
	private val basicCoordType: org.umamo.format.cmo3.model.drawable.CoordType by lazy {
		org.umamo.format.cmo3.model.drawable.CoordType().apply { coordName = "Basic Coord" }
	}

	private fun freshIdLike(template: Id?, fallbackKind: String, idStr: String): Id =
		Id(template?.kind ?: fallbackKind).apply { idstr = idStr }

	private fun appendToCollection(owner: Any, ownerTag: String, property: String, current: Any?, assign: (MutableList<Any?>) -> Unit, element: Any) {
		val mutable = mutableGraphListOf(current)
		if (mutable != null) {
			mutable.add(element)
			return
		}
		// Every field this helper serves (_sources, _groups, _childGuids) is carray_list in every
		// corpus model; the official editor's deserializers cast such fields to CArrayList, so an
		// array_list tag fails its load.
		val fresh: MutableList<Any?> = CArrayList()
		fresh.add(element)
		assign(fresh)
		editor.ensureChildSlot(owner, ownerTag, property)
	}

	private fun removeFromCollection(current: Any?, matches: (Any?) -> Boolean): Boolean {
		val mutable = mutableGraphListOf(current) ?: return false
		return mutable.removeAll(matches)
	}

	/** Strips [uuid] from every parts-panel child list (all user parts plus the synthetic root). */
	private fun stripFromChildLists(uuid: String?) {
		if (uuid == null) {
			return
		}
		val parts = index.userPartSources + listOfNotNull(index.rootPartSource)
		for (part in parts) {
			mutableGraphListOf(part._childGuids)?.removeAll { entry -> Cmo3Import.uuidOf(entry) == uuid }
		}
	}

	/**
	 * Synthesizes the identity shell for a created parameter and appends it in document order (the
	 * PARAMETER_ORDER lowering re-sorts afterwards).
	 *
	 * @param Parameter editedParameter The created parameter.
	 * @return Boolean True on success.
	 */
	fun synthesizeParameter(editedParameter: Parameter): Boolean {
		val sourceSet = modelSource.parameterSourceSet as? CParameterSourceSet
		if (sourceSet == null) {
			unsupported(ExportEntityCategory.Parameter, editedParameter.id.raw, ExportNoticeReason.NoSourceSetToCreateInto)
			return false
		}
		val template = index.parameterSources.firstOrNull()
		val fresh =
			CParameterSource().apply {
				// CMO3: CParameterSource - identity plus the editor conventions cloned from a sibling
				// (decimal places, snap epsilon, the explicit paramType).
				guid = freshGuidLike(template?.guid as? Guid, "CParameterGuid")
				id = freshIdLike(template?.id as? Id, "CParameterId", editedParameter.id.raw)
				decimalPlaces = template?.decimalPlaces ?: 3 // Documented default from surveying 50+ CMO3 files.
				snapEpsilon = template?.snapEpsilon ?: 0.001f // Documented default from surveying 50+ CMO3 files.
				minValue = editedParameter.min
				maxValue = editedParameter.max
				defaultValue = editedParameter.default
				isRepeat = false
				// CMO3: CParameterSource fields paramType / description - a Type element plus the
				// (possibly empty) description string on every corpus parameter, never null.
				paramType = if (editedParameter.kind == ParameterKind.BLEND_SHAPE) Type.MORPH_TARGET else template?.paramType ?: Type.NORMAL
				description = ""
				name = editedParameter.name
				combined = false
				parentGroupGuid = index.rootParameterGroup?.guid
			}
		appendToCollection(sourceSet, "CParameterSourceSet", "_sources", sourceSet._sources, { sourceSet._sources = it }, fresh)
		return true
	}

	/** Removes a deleted parameter's source. */
	fun deleteParameter(parameterId: ParameterId) {
		val sourceSet = modelSource.parameterSourceSet as? CParameterSourceSet ?: return
		if (removeFromCollection(sourceSet._sources) { entry ->
				entry is CParameterSource && Cmo3Import.idStrOf(entry.id) == parameterId.raw
			}
		) {
			deletedAnything = true
		}
	}

	/**
	 * Synthesizes the identity shell for a created parameter group.
	 *
	 * @param ParameterGroupId groupId The created group's id.
	 * @return Boolean True on success.
	 */
	fun synthesizeParameterGroup(groupId: ParameterGroupId): Boolean {
		val groupSet = modelSource.parameterGroupSet as? CParameterGroupSet
		if (groupSet == null) {
			unsupported(ExportEntityCategory.ParameterGroup, groupId.raw, ExportNoticeReason.NoSourceSetToCreateInto)
			return false
		}
		val editedGroup = findEditedGroup(groupId)
		if (editedGroup == null) {
			unsupported(ExportEntityCategory.ParameterGroup, groupId.raw, ExportNoticeReason.CreatedGroupIsNotInTheEditedTree)
			return false
		}
		val template = index.groupSources.firstOrNull() ?: index.rootParameterGroup
		val fresh =
			CParameterGroup().apply {
				// CMO3: CParameterGroup - identity shell; name/open state/children flow through the
				// ordinary group lowering.
				guid = freshGuidLike(template?.guid as? Guid, "CParameterGroupGuid")
				id = freshIdLike(template?.id as? Id, "CParameterGroupId", groupId.raw)
				name = editedGroup.name
				// CMO3: CParameterGroup fields description / labelColor - present on every corpus
				// group, never null.
				description = ""
				labelColor = Cmo3SkeletonBuilder.undefinedLabelColor()
				folderIsOpened = editedGroup.initiallyOpen
				_childGuids = CArrayList<Any?>()
				parentGroupGuid = index.rootParameterGroup?.guid
			}
		appendToCollection(groupSet, "CParameterGroupSet", "_groups", groupSet._groups, { groupSet._groups = it }, fresh)
		return true
	}

	/** Removes a deleted parameter group's source. */
	fun deleteParameterGroup(groupId: ParameterGroupId) {
		val groupSet = modelSource.parameterGroupSet as? CParameterGroupSet ?: return
		if (removeFromCollection(groupSet._groups) { entry ->
				entry is CParameterGroup && Cmo3Import.idStrOf(entry.id) == groupId.raw
			}
		) {
			deletedAnything = true
		}
	}

	/**
	 * Synthesizes the identity shell for a session-created drawable (an Object-mode duplicate):
	 * fresh guid/id and editable mesh, the texture binding SHARED with its texture source (the
	 * atlas slot both render from; no new CAFF entry).  Geometry, keyforms, and every flat field
	 * flow through the ordinary Changed lowering afterwards.
	 *
	 * @param Drawable editedDrawable The created drawable.
	 * @return Boolean True on success.
	 */
	fun synthesizeDrawable(editedDrawable: Drawable): Boolean {
		val subject = editedDrawable.id.raw
		val sourceSet = modelSource.drawableSourceSet as? org.umamo.format.cmo3.model.gen.CDrawableSourceSet
		if (sourceSet == null) {
			unsupported(ExportEntityCategory.Drawable, subject, ExportNoticeReason.NoSourceSetToCreateInto)
			return false
		}
		val textureSource = editedDrawable.textureSourceId?.let { index.drawableByIdStr[it.raw] }
		val binding = drawableBindings[subject]
		if (textureSource == null && binding == null) {
			unsupported(ExportEntityCategory.Drawable, subject, ExportNoticeReason.CreatedDrawableHasNoTextureSource)
			return false
		}
		val templateMesh = textureSource?.let(Cmo3Import::editableMeshOf)
		val ownerPartId = edited.partByDrawable()[editedDrawable.id]
		val ownerGuid = ownerPartId?.let { index.partByIdStr[it.raw]?.guid } ?: index.rootPartSource?.guid
		val fresh = CArtMeshSource()
		fresh.apply {
			guid = freshGuidLike(textureSource?.guid as? Guid, "CDrawableGuid")
			id = freshIdLike(textureSource?.id as? Id, "CDrawableId", subject)
			parentGuid = ownerGuid
			// CMO3: ACParameterControllableSource fields keyformMorphTargetSet / labelColor and
			// CArtMeshSource field userData - present on every corpus drawable, never omitted.
			keyformMorphTargetSet = Cmo3SkeletonBuilder.emptyMorphTargetSet()
			labelColor = Cmo3SkeletonBuilder.undefinedLabelColor()
			userData = ""
			if (textureSource != null) {
				// CMO3: CArtMeshSource identity shell.  The texture and texture-input extension are
				// the SOURCE drawable's own objects (the writer hoists the shared references), so the
				// duplicate samples the same atlas slot without new image-chain entries.
				texture = textureSource.texture
				textureState = textureSource.textureState
			} else {
				// CMO3: the fresh-graph binding - the page's SHARED GTexture2D plus a per-drawable
				// CTextureInput_TextureAtlasRegion, so UVs stay in the atlas frame (hasAtlasRegion).
				texture = binding!!.texture
				textureState = org.umamo.format.cmo3.model.gen.TextureState.TEXTURE_ATLAS
			}
			_extensions =
				CArrayList<Any?>().apply {
					if (textureSource != null) {
						Cmo3Import.elementsOf(textureSource._extensions)
							.filterIsInstance<CTextureInputExtension>()
							.firstOrNull()
							?.let(::add)
					} else {
						add(freshTextureInputExtension(fresh, binding!!, editedDrawable.name))
					}
					add(
						CEditableMeshExtension().apply {
							guid = freshGuidLike(null, "CExtensionGuid")
							// CMO3: ACExtension field _owner - the owning drawable source; every
							// corpus extension carries the backref.
							_owner = fresh
							editableMesh =
								GEditableMesh2().apply {
									// CMO3: GEditableMesh2 field meshGuid - kind GEditableMeshGuid
									// (com.live2d.type.GEditableMeshGuid); no corpus file ever emits a
									// CMeshGuid tag, so that kind would author an unimportable class.
									meshGuid = freshGuidLike(templateMesh?.meshGuid as? Guid, "GEditableMeshGuid")
									// CMO3: GEditableMesh2 field coordType - corpus meshes always
									// carry "Basic Coord" (never null).
									coordType = templateMesh?.coordType ?: basicCoordType
									useDelaunayTriangulation = false
								}
						},
					)
				}
		}
		appendToCollection(sourceSet, "CDrawableSourceSet", "_sources", sourceSet._sources, { sourceSet._sources = it }, fresh)
		return true
	}

	/**
	 * Builds the per-drawable texture-input extension for a fresh-graph binding: both input kinds
	 * (model image + atlas region), with the one the document's display mode samples made active,
	 * mirroring the editor's packed drawables.
	 *
	 * @param CArtMeshSource             owner       The drawable source under construction.
	 * @param Cmo3DrawableTextureBinding binding     The drawable's texture web (page texture + patch).
	 * @param String                     subjectName The drawable's display name, naming it in the display-mode notice.
	 * @return CTextureInputExtension The fresh extension.
	 */
	private fun freshTextureInputExtension(
		owner: CArtMeshSource,
		binding: Cmo3DrawableTextureBinding,
		subjectName: String,
	): CTextureInputExtension {
		val extension = CTextureInputExtension()
		val atlasRegion =
			org.umamo.format.cmo3.model.gen.CTextureInput_TextureAtlasRegion().apply {
				// CMO3: CTextureInput_TextureAtlasRegion fields textureAtlasGuid +
				// inputImageLocalToCanvasTransform (ACTextureInput super carries the owner backref).
				// The transform places the atlas page's pixel frame on the canvas so this drawable's
				// texture patch coincides with its base mesh - the editor inverts it to draw the mesh
				// over the texture in the atlas and mesh-edit views.
				optionalTransformOnCanvas = CAffine()
				_owner = extension
				textureAtlasGuid = binding.textureAtlasGuid
				inputImageLocalToCanvasTransform = binding.inputImageLocalToCanvasTransform
			}
		val modelImage =
			binding.modelImageGuid?.let { imageGuid ->
				org.umamo.format.cmo3.model.gen.CTextureInput_ModelImage().apply {
					// CMO3: CTextureInput_ModelImage field _modelImageGuid.
					optionalTransformOnCanvas = CAffine()
					_owner = extension
					_modelImageGuid = imageGuid
				}
			}
		// A layer-mode document whose created drawable has no model image keeps the atlas region active:
		// it is the only input such a drawable has, and leaving the live pointer there is what keeps the
		// file self-consistent.  The notice names the gap, matching what the property lowering reports
		// for an EXISTING drawable in the same state - one reason, reported the same way from both paths.
		if (edited.rendersFromSourceLayers && modelImage == null) {
			unsupported(ExportEntityCategory.Drawable, subjectName, ExportNoticeReason.NoTextureInputForDisplayMode)
		}
		return extension.apply {
			guid = freshGuidLike(null, "CExtensionGuid")
			_owner = owner
			_textureInputs =
				CArrayList<Any?>().apply {
					modelImage?.let(::add)
					add(atlasRegion)
				}
			// CMO3: CTextureInputExtension field currentTextureInputData - which input the document's
			// display mode samples.  A drawable created in a layer-mode document must point at its model
			// image, or the file's flag and its drawables disagree.
			currentTextureInputData =
				if (edited.rendersFromSourceLayers) {
					modelImage ?: atlasRegion
				} else {
					atlasRegion
				}
		}
	}

	/**
	 * Synthesizes the identity shell for a created part: the super web plus a one-cell keyform grid
	 * with a single CPartForm, so static draw-order/opacity/composite writes have a form to land in
	 * (the field lowering only fills EXISTING part forms).  Panel placement flows from the parent's
	 * CHILDREN rebuild.
	 *
	 * @param Part editedPart The created part.
	 * @return Boolean True on success.
	 */
	fun synthesizePart(editedPart: org.umamo.runtime.model.Part): Boolean {
		val sourceSet = modelSource.partSourceSet as? org.umamo.format.cmo3.model.gen.CPartSourceSet
		if (sourceSet == null) {
			unsupported(ExportEntityCategory.Part, editedPart.id.raw, ExportNoticeReason.NoSourceSetToCreateInto)
			return false
		}
		val template = index.userPartSources.firstOrNull() ?: index.rootPartSource
		val part = org.umamo.format.cmo3.model.gen.CPartSource()
		val formGuid = freshGuidLike(null, "CFormGuid")
		val form =
			org.umamo.format.cmo3.model.gen.CPartForm().apply {
				// CMO3: CPartForm - the one default-cell form (drawOrder/opacity/colors).
				guid = formGuid
				_source = part
				notes = ""
				drawOrder = editedPart.drawOrder
				opacity = 1f
				multiplyColor = Cmo3SkeletonBuilder.identityMultiplyColor()
				screenColor = Cmo3SkeletonBuilder.identityScreenColor()
			}
		part.apply {
			localName = editedPart.name
			isVisible = true
			keyformGridSource =
				org.umamo.format.cmo3.model.gen.KeyformGridSource().apply {
					// CMO3: KeyformGridSource - one unkeyed cell (empty access key) like the blank's parts.
					keyformsOnGrid =
						ArrayList<Any?>(
							mutableListOf(
								org.umamo.format.cmo3.model.gen.KeyformOnGrid().apply {
									accessKey =
										org.umamo.format.cmo3.model.gen.KeyformGridAccessKey().apply {
											_keyOnParameterList = ArrayList<Any?>()
										}
									keyformGuid = formGuid
								},
							),
						)
					keyformBindings = ArrayList<Any?>()
				}
			keyformMorphTargetSet = Cmo3SkeletonBuilder.emptyMorphTargetSet()
			_extensions = CArrayList<Any?>()
			labelColor = Cmo3SkeletonBuilder.undefinedLabelColor()
			guid = freshGuidLike(template?.guid as? Guid, "CPartGuid")
			id = freshIdLike(template?.id as? Id, "CPartId", editedPart.id.raw)
			keyforms = CArrayList<Any?>(mutableListOf(form))
			defaultOrder_forEditor = editedPart.drawOrder
			partsEditColor = org.umamo.format.cmo3.model.type.CColor()
			_childGuids = CArrayList<Any?>()
			clipGuidList = CArrayList<Any?>()
			colorComposition = org.umamo.format.cmo3.model.gen.ColorComposition.NORMAL
			alphaComposition = org.umamo.format.cmo3.model.gen.AlphaComposition.OVER
		}
		appendToCollection(sourceSet, "CPartSourceSet", "_sources", sourceSet._sources, { sourceSet._sources = it }, part)
		return true
	}

	/**
	 * Synthesizes the identity shell for a created deformer (warp or rotation).  Lattice/angle
	 * fields, parent/part wiring, and the whole keyform web flow through the Changed lowering.
	 *
	 * @param Deformer editedDeformer The created deformer.
	 * @return Boolean True on success.
	 */
	fun synthesizeDeformer(editedDeformer: org.umamo.runtime.model.Deformer): Boolean {
		val sourceSet = modelSource.deformerSourceSet as? org.umamo.format.cmo3.model.gen.CDeformerSourceSet
		if (sourceSet == null) {
			unsupported(ExportEntityCategory.Deformer, editedDeformer.id.raw, ExportNoticeReason.NoSourceSetToCreateInto)
			return false
		}
		val fresh: org.umamo.format.cmo3.model.gen.ACDeformerSource =
			when (editedDeformer) {
				is org.umamo.runtime.model.Deformer.Warp -> {
					val template =
						index.deformerSources.filterIsInstance<org.umamo.format.cmo3.model.gen.CWarpDeformerSource>().firstOrNull()
					org.umamo.format.cmo3.model.gen.CWarpDeformerSource().apply {
						guid = freshGuidLike(template?.guid as? Guid, "CDeformerGuid")
						id = freshIdLike(template?.id as? Id, "CDeformerId", editedDeformer.id.raw)
						keyforms = CArrayList<Any?>()
					}
				}
				is org.umamo.runtime.model.Deformer.Rotation -> {
					val template =
						index.deformerSources.filterIsInstance<org.umamo.format.cmo3.model.gen.CRotationDeformerSource>().firstOrNull()
					org.umamo.format.cmo3.model.gen.CRotationDeformerSource().apply {
						guid = freshGuidLike(template?.guid as? Guid, "CDeformerGuid")
						id = freshIdLike(template?.id as? Id, "CDeformerId", editedDeformer.id.raw)
						keyforms = CArrayList<Any?>()
						// CMO3: CRotationDeformerSource fields handleLengthOnCanvas /
						// circleRadiusOnCanvas - editor gizmo sizing only; cloned from a sibling when
						// one exists, else nominal defaults (no runtime consumes them).
						handleLengthOnCanvas = template?.handleLengthOnCanvas ?: 100f
						circleRadiusOnCanvas = template?.circleRadiusOnCanvas ?: 20f
					}
				}
			}
		fresh.apply {
			localName = editedDeformer.name
			isVisible = true
			isLocked = !editedDeformer.isSelectable
			keyformMorphTargetSet = Cmo3SkeletonBuilder.emptyMorphTargetSet()
			_extensions = CArrayList<Any?>()
			labelColor = Cmo3SkeletonBuilder.undefinedLabelColor()
		}
		appendToCollection(sourceSet, "CDeformerSourceSet", "_sources", sourceSet._sources, { sourceSet._sources = it }, fresh)
		return true
	}

	/**
	 * Synthesizes the identity shell for a created glue.  Pair weights/uids and the intensity
	 * track flow through the Changed lowering (lowerGluePairsFor + lowerGlue).
	 *
	 * @param Glue editedGlue The created glue.
	 * @param Int  ordinal    The glue's ordinal among same-pair glues (the diff key).
	 * @return Boolean True on success.
	 */
	fun synthesizeGlue(editedGlue: Glue, ordinal: Int): Boolean {
		val subject = "${editedGlue.meshA.raw}~${editedGlue.meshB.raw}"
		val sourceSet = modelSource.affecterSourceSet as? org.umamo.format.cmo3.model.gen.CAffecterSourceSet
		if (sourceSet == null) {
			unsupported(ExportEntityCategory.Glue, subject, ExportNoticeReason.NoSourceSetToCreateInto)
			return false
		}
		val meshAGuid = index.drawableByIdStr[editedGlue.meshA.raw]?.guid as? Guid
		val meshBGuid = index.drawableByIdStr[editedGlue.meshB.raw]?.guid as? Guid
		if (meshAGuid == null || meshBGuid == null) {
			unsupported(ExportEntityCategory.Glue, subject, ExportNoticeReason.GluedDrawableHasNoSource)
			return false
		}
		val template = index.glueSources.firstOrNull()
		val ownerPartId = edited.partByDrawable()[editedGlue.meshA]
		val ownerPart = ownerPartId?.let { index.partByIdStr[it.raw] } ?: index.rootPartSource
		val ownerGuid = ownerPart?.guid
		val fresh = org.umamo.format.cmo3.model.gen.CGlueSource()
		val glueGuid = freshGuidLike(template?.guid as? Guid, "CAffecterGuid")
		val formGuid = freshGuidLike(null, "CFormGuid")
		val form =
			org.umamo.format.cmo3.model.gen.CGlueForm().apply {
				// CMO3: CGlueForm - the one default-cell form; every corpus glue carries at least
				// this cell, and the intensity lowering writes only into existing forms.
				guid = formGuid
				isAnimatedForm = false
				isLocalAnimatedForm = false
				_source = fresh
				notes = ""
				intensity = 1f
			}
		fresh.apply {
			localName = ""
			isVisible = true
			// CMO3: ACParameterControllableSource field parentGuid - the owning part, like the
			// glued drawables' own panel parent (corpus glues always sit under a part).
			parentGuid = ownerGuid
			keyformGridSource =
				org.umamo.format.cmo3.model.gen.KeyformGridSource().apply {
					// CMO3: KeyformGridSource - one unkeyed cell (empty access key), the corpus
					// glue default; an intensity track later rebuilds this web via writeGridWeb.
					keyformsOnGrid =
						ArrayList<Any?>(
							mutableListOf(
								org.umamo.format.cmo3.model.gen.KeyformOnGrid().apply {
									accessKey =
										org.umamo.format.cmo3.model.gen.KeyformGridAccessKey().apply {
											_keyOnParameterList = ArrayList<Any?>()
										}
									keyformGuid = formGuid
								},
							),
						)
					keyformBindings = ArrayList<Any?>()
				}
			keyformMorphTargetSet = Cmo3SkeletonBuilder.emptyMorphTargetSet()
			_extensions = CArrayList<Any?>()
			labelColor = Cmo3SkeletonBuilder.undefinedLabelColor()
			guid = glueGuid
			// The model keys glues by mesh pair + ordinal (they carry no id of their own), so the
			// minted idstr only needs uniqueness within the document.
			id = freshIdLike(template?.id as? Id, "CAffecterId", "Glue_${editedGlue.meshA.raw}_${editedGlue.meshB.raw}_$ordinal")
			// CMO3: ACAffecterSource field targetDeformerGuid - the editor's fixed root-deformer
			// sentinel (uuid identical in every corpus file; the editor writes it even for glues
			// whose meshes have real deformer parents).
			targetDeformerGuid = Cmo3SkeletonBuilder.rootDeformerSentinel()
			// CMO3: CGlueSource fields targetArtMeshA_guid / targetArtMeshB_guid - identity refs
			// to the glued drawables' own guid objects.
			targetArtMeshA_guid = meshAGuid
			targetArtMeshB_guid = meshBGuid
			// CMO3: CGlueSource field tabPosOnCanvas - the glue tab marker, never null; the editor
			// stacks them at x = -100 down the canvas's left margin.
			tabPosOnCanvas =
				org.umamo.format.cmo3.model.type.GVector2().apply {
					x = -100f
					y = 100f * (ordinal + 1)
				}
			keyforms = CArrayList<Any?>(mutableListOf(form))
		}
		appendToCollection(sourceSet, "CAffecterSourceSet", "_sources", sourceSet._sources, { sourceSet._sources = it }, fresh)
		// CMO3: CPartSource field _childGuids - the owning part lists the glue's CAffecterGuid
		// after its drawables and deformers; the editor's load-time verify repairs (and 5.4
		// crashes on) an affecter absent from the part hierarchy.
		if (ownerPart != null) {
			appendToCollection(ownerPart, "CPartSource", "_childGuids", ownerPart._childGuids, { ownerPart._childGuids = it }, glueGuid)
		}
		return true
	}

	/** Removes a deleted drawable's source and strips its guid from every panel child list. */
	fun deleteDrawable(drawableId: DrawableId) {
		val sourceSet = modelSource.drawableSourceSet as? org.umamo.format.cmo3.model.gen.CDrawableSourceSet ?: return
		val source = index.drawableByIdStr[drawableId.raw]
		if (removeFromCollection(sourceSet._sources) { entry -> entry === source }) {
			stripFromChildLists(Cmo3Import.uuidOf(source?.guid))
			deletedAnything = true
		}
	}

	/** Removes a deleted part's source and strips its guid from every panel child list. */
	fun deletePart(partIdRaw: String) {
		val sourceSet = modelSource.partSourceSet as? org.umamo.format.cmo3.model.gen.CPartSourceSet ?: return
		val source = index.partByIdStr[partIdRaw]
		if (removeFromCollection(sourceSet._sources) { entry -> entry === source }) {
			stripFromChildLists(Cmo3Import.uuidOf(source?.guid))
			deletedAnything = true
		}
	}

	/** Removes a deleted deformer's source and strips its guid from every panel child list. */
	fun deleteDeformer(deformerIdRaw: String) {
		val sourceSet = modelSource.deformerSourceSet as? org.umamo.format.cmo3.model.gen.CDeformerSourceSet ?: return
		val source = index.deformerByIdStr[deformerIdRaw]
		if (removeFromCollection(sourceSet._sources) { entry -> entry === source }) {
			stripFromChildLists(Cmo3Import.uuidOf(source?.guid))
			deletedAnything = true
		}
	}

	/** Removes a deleted glue's source, resolved by mesh pair plus ordinal. */
	fun deleteGlue(meshA: DrawableId, meshB: DrawableId, ordinal: Int) {
		val sourceSet = modelSource.affecterSourceSet as? org.umamo.format.cmo3.model.gen.CAffecterSourceSet ?: return
		val matching =
			index.glueSources.filter { glueSource ->
				index.drawableIdStrByUuid[Cmo3Import.uuidOf(glueSource.targetArtMeshA_guid)] == meshA.raw &&
					index.drawableIdStrByUuid[Cmo3Import.uuidOf(glueSource.targetArtMeshB_guid)] == meshB.raw
			}
		val victim = matching.getOrNull(ordinal) ?: return
		if (removeFromCollection(sourceSet._sources) { entry -> entry === victim }) {
			stripFromChildLists(Cmo3Import.uuidOf(victim.guid))
			deletedAnything = true
		}
	}

	/**
	 * Rewrites a drawable's topology surface: indices, base positions, UVs, and the full
	 * GEditableMesh2 rebuild (point mirror, freshly minted vertex uids, the unique triangle-edge
	 * set), then re-binds every glue touching the drawable to the new uid table.
	 *
	 * @param CArtMeshSource source         The drawable's graph source.
	 * @param Drawable       editedDrawable The edited drawable.
	 * @return Boolean True on success.
	 */
	fun lowerMeshTopology(source: CArtMeshSource, editedDrawable: Drawable): Boolean {
		val subject = editedDrawable.id.raw
		val mesh = editedDrawable.mesh
		if (mesh == null) {
			unsupported(ExportEntityCategory.Drawable, subject, ExportNoticeReason.DrawableHasNoMesh)
			return false
		}
		// CMO3: CArtMeshSource fields indices / positions / uvs.  The positions are CANVAS-frame
		// in every official file, and mesh.positions is canvas-frame by the runtime's contract:
		// the MOC3 document loader normalizes parent-local rest meshes through :render's
		// restMeshesToCanvasSpace at import (rendering never notices the base's frame - grids sum
		// to one - but the editor's atlas and mesh-edit views read this field as canvas geometry).
		source.indices = mesh.indices.copyOf()
		editor.ensureChildSlot(source, "CArtMeshSource", "indices", "keyforms")
		source.positions = mesh.positions.copyOf()
		editor.ensureChildSlot(source, "CArtMeshSource", "positions", "uvs")
		source.uvs = storedUvsFor(source, mesh.uvs)
		editor.ensureChildSlot(source, "CArtMeshSource", "uvs", "texture")

		val editableMesh = Cmo3Import.editableMeshOf(source)
		if (editableMesh != null) {
			val vertexCount = mesh.vertexCount
			// CMO3: GEditableMesh2 - point mirrors the positions as its own array; pointUid gets a
			// freshly minted 0..n-1 table (glues re-bind below, so re-minting is safe); edge is the
			// unique undirected triangle-edge set; the Delaunay flag drops (the triangulation is
			// authored by the indices now).
			editableMesh.point = mesh.positions.copyOf()
			editor.ensureChildSlot(editableMesh, "GEditableMesh2", "point", "pointPriority")
			editableMesh.pointUid = IntArray(vertexCount) { vertexOrdinal -> vertexOrdinal }
			editor.ensureChildSlot(editableMesh, "GEditableMesh2", "pointUid", "meshGuid")
			editableMesh.nextPointUid = vertexCount
			editor.ensurePresentAttr(editableMesh, "GEditableMesh2", "nextPointUid")
			editableMesh.useDelaunayTriangulation = false
			editor.ensurePresentAttr(editableMesh, "GEditableMesh2", "useDelaunayTriangulation")
			if (vertexCount <= Short.MAX_VALUE.toInt()) {
				editableMesh.edge = triangleEdges(mesh.indices)
				editor.ensureChildSlot(editableMesh, "GEditableMesh2", "edge", "edgePriority")
			} else {
				unsupported(
					ExportEntityCategory.Drawable,
					subject,
					ExportNoticeReason.VertexCountExceedsEdgeTable(vertexCount, Short.MAX_VALUE.toInt()),
				)
			}
			// Priority arrays resize to the new counts, keeping whichever array type was stored.
			editableMesh.pointPriority = resizedPriorityArray(editableMesh.pointPriority, vertexCount, freshDefault = 10)
			editor.ensureChildSlot(editableMesh, "GEditableMesh2", "pointPriority", "edge")
			(editableMesh.edge as? ShortArray)?.let { edges ->
				editableMesh.edgePriority = resizedPriorityArray(editableMesh.edgePriority, edges.size / 2, freshDefault = 30)
				editor.ensureChildSlot(editableMesh, "GEditableMesh2", "edgePriority", "pointUid")
			}
		}
		for (glue in edited.glues) {
			if (glue.meshA != editedDrawable.id && glue.meshB != editedDrawable.id) {
				continue
			}
			// A partner whose uid table is still missing is a fresh shell whose own topology pass is
			// later in this same export - ITS re-bind loop covers this glue with both tables minted,
			// so a premature attempt here would only raise a spurious notice.
			if (uidTableOf(glue.meshA) == null || uidTableOf(glue.meshB) == null) {
				continue
			}
			lowerGluePairsFor(glue)
		}
		return true
	}

	/**
	 * Rewrites a glue's vertex binding (weights + bindVertexUids) from the edited model's
	 * index-based pairs against both meshes' CURRENT uid tables.
	 *
	 * @param Glue editedGlue The edited glue.
	 * @return Boolean True on success.
	 */
	fun lowerGluePairsFor(editedGlue: Glue): Boolean {
		val subject = "${editedGlue.meshA.raw}+${editedGlue.meshB.raw}"
		val editedByPair = edited.glues.filter { it.meshA == editedGlue.meshA && it.meshB == editedGlue.meshB }
		val ordinal = editedByPair.indexOfFirst { it === editedGlue }.coerceAtLeast(0)
		val glueSource =
			index.glueSources.filter { candidate ->
				index.drawableIdStrByUuid[Cmo3Import.uuidOf(candidate.targetArtMeshA_guid)] == editedGlue.meshA.raw &&
					index.drawableIdStrByUuid[Cmo3Import.uuidOf(candidate.targetArtMeshB_guid)] == editedGlue.meshB.raw
			}.getOrNull(ordinal)
		if (glueSource == null) {
			unsupported(ExportEntityCategory.Glue, subject, ExportNoticeReason.NoMatchingSourceToReconcile)
			return false
		}
		val uidTableA = uidTableOf(editedGlue.meshA)
		val uidTableB = uidTableOf(editedGlue.meshB)
		if (uidTableA == null || uidTableB == null) {
			unsupported(ExportEntityCategory.Glue, subject, ExportNoticeReason.GluedMeshHasNoUidTable)
			return false
		}
		val pairCount = editedGlue.pairs.size
		val weights = FloatArray(pairCount * 2)
		val uids = LongArray(pairCount * 2)
		for (pairIndex in 0 until pairCount) {
			val pair = editedGlue.pairs[pairIndex]
			val uidA = uidTableA.getOrNull(pair.indexA)
			val uidB = uidTableB.getOrNull(pair.indexB)
			if (uidA == null || uidB == null) {
				unsupported(ExportEntityCategory.Glue, subject, ExportNoticeReason.GluePairIndexesPastUidTable)
				return false
			}
			// CMO3: CGlueSource fields weights / bindVertexUids - [A, B]-interleaved per pair.
			weights[pairIndex * 2] = pair.weightA
			weights[pairIndex * 2 + 1] = pair.weightB
			uids[pairIndex * 2] = uidA.toLong()
			uids[pairIndex * 2 + 1] = uidB.toLong()
		}
		glueSource.weights = weights
		editor.ensureChildSlot(glueSource, "CGlueSource", "weights", "keyforms")
		glueSource.bindVertexUids = uids
		editor.ensureChildSlot(glueSource, "CGlueSource", "bindVertexUids", "tabPosOnCanvas")
		return true
	}

	private fun uidTableOf(drawableId: DrawableId): IntArray? =
		index.drawableByIdStr[drawableId.raw]?.let { source -> Cmo3Import.editableMeshOf(source)?.pointUid as? IntArray }

	/** The unique undirected triangle edges as the editable mesh's short-indexed pair list. */
	private fun triangleEdges(indices: IntArray): ShortArray {
		val seen = LinkedHashSet<Int>()
		var triangleStart = 0
		while (triangleStart + 2 < indices.size) {
			for (cornerIndex in 0 until 3) {
				val endpointA = indices[triangleStart + cornerIndex]
				val endpointB = indices[triangleStart + (cornerIndex + 1) % 3]
				val low = minOf(endpointA, endpointB)
				val high = maxOf(endpointA, endpointB)
				seen.add(low shl 16 or high)
			}
			triangleStart += 3
		}
		val edges = ShortArray(seen.size * 2)
		var writeIndex = 0
		for (packed in seen) {
			edges[writeIndex++] = (packed shr 16).toShort()
			edges[writeIndex++] = (packed and 0xFFFF).toShort()
		}
		return edges
	}

	/**
	 * A priority array of [count] entries matching [existing]'s stored type, or a fresh byte
	 * array filled with [freshDefault] when none was stored.
	 *
	 * CMO3: GEditableMesh2 fields pointPriority / edgePriority - byte arrays on every corpus
	 * mesh, never null (the editor NPEs on a null); points carry 10, triangle edges 30.
	 */
	private fun resizedPriorityArray(existing: Any?, count: Int, freshDefault: Byte): Any? =
		when (existing) {
			is IntArray -> IntArray(count)
			is ShortArray -> ShortArray(count)
			is FloatArray -> FloatArray(count)
			is ByteArray -> ByteArray(count) { freshDefault }
			else -> ByteArray(count) { freshDefault }
		}

	/**
	 * The UVs as CMO3 stores them: verbatim for a packed (atlas-region) drawable, through the
	 * forward model-image affine for an unpacked one.
	 *
	 * @param CArtMeshSource source The drawable's graph source.
	 * @param FloatArray     uvs    The model-frame UVs.
	 * @return FloatArray The stored-frame UVs.
	 */
	private fun storedUvsFor(source: CArtMeshSource, uvs: FloatArray): FloatArray {
		if (Cmo3Import.hasAtlasRegion(source)) {
			return uvs.copyOf()
		}
		// CMO3: GTexture2D field transformImageResource01toLogical01 (the import applied its inverse).
		val affine = (source.texture as? GTexture2D)?.transformImageResource01toLogical01 as? CAffine ?: return uvs.copyOf()
		val isIdentity =
			affine.m00 == 1f &&
				affine.m01 == 0f &&
				affine.m02 == 0f &&
				affine.m10 == 0f &&
				affine.m11 == 1f &&
				affine.m12 == 0f
		if (isIdentity) {
			return uvs.copyOf()
		}
		val result = FloatArray(uvs.size)
		var component = 0
		while (component + 1 < uvs.size) {
			val u = uvs[component]
			val v = uvs[component + 1]
			result[component] = affine.m00 * u + affine.m01 * v + affine.m02
			result[component + 1] = affine.m10 * u + affine.m11 * v + affine.m12
			component += 2
		}
		return result
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
}