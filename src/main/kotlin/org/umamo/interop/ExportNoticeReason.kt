package org.umamo.interop

import org.umamo.runtime.model.FormChannel

/**
 * Why one [ExportNotice.UnsupportedChange] fired: the closed vocabulary of things an export lowering
 * cannot carry into the written file.
 *
 * Closed rather than free text so the reason can be LOCALIZED.  The prose a rigger reads lives in the
 * UI's string catalog, keyed off the case; this module stays presentation-free and carries only the
 * structured facts a sentence needs.  That is the same split [ExportNotice.FeatureStripped] already
 * uses for its [org.umamo.runtime.model.RuntimeFeature], generalized to every finding.
 *
 * Two rules hold across every case, and both are load-bearing:
 *
 * - Every case is a `data object` or `data class`, never a bare `object`.  The log mirror in the app
 *   prints the reason with `toString()`, so a bare object would emit an identity hash instead of a
 *   name.  ExportNoticeReasonTest pins this.
 * - Ids are plain [String], not the typed `ParameterId`/`DrawableId` wrappers.  A report is a display
 *   carrier rather than a model - [ExportNotice.UnsupportedChange.subject] and
 *   [ExportNotice.FeatureStripped.subjects] are already plain strings - and a value class would render
 *   as `ParameterId(raw=ParamAngleX)` in every log line.  [FormChannel] is the one typed field, because
 *   it is a localizable enum rather than user data.
 *
 * The cases are declared here in commonMain even where only the CMO3 lowering produces them: :interop
 * builds an iosArm64 target that never sees jvmAndroidMain, so a member declared there would break the
 * exhaustive `when` the UI does over this hierarchy.
 */
sealed interface ExportNoticeReason {
	// Shared - fired by both export paths.

	/** The drawable carries no mesh, so there is no geometry to write. */
	data object DrawableHasNoMesh : ExportNoticeReason

	// MOC3 lowering.

	/**
	 * A channel keyed over a narrower span than its object's grid, written as a constant instead.
	 *
	 * @property FormChannel channel The demoted channel.
	 */
	data class ChannelDemotedToStatic(val channel: FormChannel) : ExportNoticeReason

	/** A guide-image (sketch) part holds authoring aids, not runtime content. */
	data object SketchPartIsNotRuntimeContent : ExportNoticeReason

	/** A hidden part's subtree was left out because the export options omit hidden parts. */
	data object HiddenPartOmittedByExportOption : ExportNoticeReason

	/** A hidden art mesh was left out because the export options omit hidden drawables. */
	data object HiddenDrawableOmittedByExportOption : ExportNoticeReason

	/** An unkeyed drawable under a deformer has no parent-space geometry the moc could store. */
	data object UnkeyedDrawableUnderDeformerHasNoParentGeometry : ExportNoticeReason

	/**
	 * The canvas-to-parent conversion returned a different coordinate count than the mesh has.
	 *
	 * @property Int convertedCoordinateCount The count the conversion returned.
	 * @property Int expectedCoordinateCount  The count the mesh carries.
	 */
	data class RestMeshConversionSizeMismatch(
		val convertedCoordinateCount: Int,
		val expectedCoordinateCount: Int,
	) : ExportNoticeReason

	/** No atlas page is bound to the drawable, so its texture index fell back to page 0. */
	data object NoAtlasPageBound : ExportNoticeReason

	/**
	 * A clipping mask names drawables this export does not contain.
	 *
	 * @property List maskDrawableIds The masking drawables that were left out.
	 */
	data class ClippingMaskNotInExport(val maskDrawableIds: List<String>) : ExportNoticeReason

	/** An offscreen's clipping mask names a drawable that could not be written. */
	data object OffscreenMaskNotInExport : ExportNoticeReason

	/** A warp deformer carries no control-point grid, so it has no lattice to write. */
	data object WarpDeformerHasNoLattice : ExportNoticeReason

	/** A rotation deformer carries no pivot grid, so it has no transform to write. */
	data object RotationDeformerHasNoPivot : ExportNoticeReason

	/** A glue names a drawable that is not in this export. */
	data object GlueNamesAnUnknownDrawable : ExportNoticeReason

	/**
	 * An id too long for a moc's fixed-width id record, written shortened.
	 *
	 * @property Int    recordByteWidth The record's width in bytes.
	 * @property String writtenId       The id that was actually written.
	 */
	data class IdTruncated(val recordByteWidth: Int, val writtenId: String) : ExportNoticeReason

	/**
	 * An id too long for the record whose truncation ALSO collided with another object's id, so a
	 * disambiguating suffix was fitted into the same width.
	 *
	 * @property Int    recordByteWidth The record's width in bytes.
	 * @property String writtenId       The id that was actually written.
	 */
	data class IdTruncatedAndDisambiguated(
		val recordByteWidth: Int,
		val writtenId: String,
	) : ExportNoticeReason

	// CMO3 reconcile - an edit with no counterpart in the retained graph.

	/** The edited entity has no matching CMO3 source to reconcile the change onto. */
	data object NoMatchingSourceToReconcile : ExportNoticeReason

	/** A parameter changed between normal and blend-shape kind, which the lowering cannot express. */
	data object ParameterKindChangeNotLowered : ExportNoticeReason

	/** A deformer changed kind, which the lowering cannot express. */
	data object DeformerKindChangeNotLowered : ExportNoticeReason

	/** A warp-only edit (bilinear mode, lattice, keyforms) whose CMO3 source is not a warp deformer. */
	data object DeformerEditNeedsWarpSource : ExportNoticeReason

	/** A rotation-only edit (base angle, keyforms) whose CMO3 source is not a rotation deformer. */
	data object DeformerEditNeedsRotationSource : ExportNoticeReason

	/** Which texture a drawable samples is editor-only state that CMO3 does not carry. */
	data object TextureSourceRebindingIsEditorOnly : ExportNoticeReason

	/** The edited base geometry has a different vertex count than its CMO3 source. */
	data object BaseGeometryVertexCountMismatch : ExportNoticeReason

	/** The drawable has no UVs to reconcile. */
	data object NoUvsToReconcile : ExportNoticeReason

	/** The deformer was reparented to a part that has no CMO3 counterpart. */
	data object DeformerHasNoPartToMoveTo : ExportNoticeReason

	/**
	 * A static draw order that disagrees with its own keyform track.
	 *
	 * CMO3 re-derives the static from the track's head cell on import, so a disagreeing static has
	 * nowhere to live independently.
	 */
	data object StaticDrawOrderShadowedByKeyforms : ExportNoticeReason

	/** A composite's static values disagree with the part's keyform tracks, which shadow them. */
	data object CompositeStaticsShadowedByKeyforms : ExportNoticeReason

	/** Part-typed composite masks are an Umamo extension; CMO3 carries only drawable-typed masks. */
	data object PartMasksFlattenToDrawables : ExportNoticeReason

	// CMO3 structural creation - a new entity with nowhere to be created.

	/** The CMO3 model has no set of this kind to create the new entity into. */
	data object NoSourceSetToCreateInto : ExportNoticeReason

	/** The entity was created this session and has no CMO3 source yet, so panel order skipped it. */
	data object CreatedEntityHasNoSourceYet : ExportNoticeReason

	/** The created group is not in the edited parameter tree. */
	data object CreatedGroupIsNotInTheEditedTree : ExportNoticeReason

	/** The created drawable has no texture source to clone a CMO3 image chain from. */
	data object CreatedDrawableHasNoTextureSource : ExportNoticeReason

	/** A glued drawable has no CMO3 source, so the glue could not be synthesized. */
	data object GluedDrawableHasNoSource : ExportNoticeReason

	// CMO3 mesh topology and glue re-binding.

	/**
	 * A mesh with more vertices than the editable mesh's short-indexed edge table can address.
	 *
	 * @property Int vertexCount        The mesh's vertex count.
	 * @property Int maximumVertexCount The most the edge table can index.
	 */
	data class VertexCountExceedsEdgeTable(
		val vertexCount: Int,
		val maximumVertexCount: Int,
	) : ExportNoticeReason

	/** A glued mesh has no editable-mesh vertex-id table to re-bind the glue against. */
	data object GluedMeshHasNoUidTable : ExportNoticeReason

	/** A glue pair indexes past its mesh's vertex-id table. */
	data object GluePairIndexesPastUidTable : ExportNoticeReason

	// CMO3 keyforms.

	/**
	 * A keyform set that will not bundle into the single grid CMO3 stores per object.
	 *
	 * @property KeyformBundleRejection rejection Which bundling rule the keyforms broke.
	 */
	data class KeyformCannotBundle(val rejection: KeyformBundleRejection) : ExportNoticeReason

	/**
	 * A grid axis whose parameter has no CMO3 source.
	 *
	 * @property String parameterId The axis parameter's id.
	 */
	data class AxisParameterHasNoSource(val parameterId: String) : ExportNoticeReason

	/**
	 * A blend shape whose parameter has no CMO3 source.
	 *
	 * @property String parameterId The blend-shape parameter's id.
	 */
	data class BlendShapeParameterHasNoSource(val parameterId: String) : ExportNoticeReason

	/** Keyforms with no base mesh to bundle against. */
	data object KeyformsWithoutBaseMesh : ExportNoticeReason

	/** A fractional draw order, where CMO3 stores a whole number. */
	data object FractionalDrawOrderNotStorable : ExportNoticeReason

	/** A static glue intensity with no keyforms, which CMO3 has nowhere to put. */
	data object StaticGlueIntensityWithoutKeyforms : ExportNoticeReason

	// CMO3 document fields.  The notice's subject is null for these - the reason names its own field,
	// because the field name is chrome and would otherwise be untranslated English in the subject.

	/** CMO3 stores no authored world origin; reopening derives it from the canvas center. */
	data object NoAuthoredWorldOrigin : ExportNoticeReason

	/** The model has no root parameter group to rebuild the parameter tree against. */
	data object NoRootParameterGroup : ExportNoticeReason

	/** The model has no root part to rebuild the parts panel order against. */
	data object NoRootPart : ExportNoticeReason

	/** The model has no parameter set to write a parameter order into. */
	data object NoParameterSourceSet : ExportNoticeReason

	/**
	 * The parameter order was adjusted to keep a combined pair adjacent, as CMO3 requires.
	 *
	 * @property String horizontalParameterId The pair's horizontal parameter id.
	 * @property String verticalParameterId   The pair's vertical parameter id.
	 */
	data class CombinedPairReordered(
		val horizontalParameterId: String,
		val verticalParameterId: String,
	) : ExportNoticeReason

	/** The model has no canvas to reconcile a size against. */
	data object NoCanvasToReconcile : ExportNoticeReason

	/** The document carries no texture manager, so the source-artwork display mode has nowhere to go. */
	data object NoTextureManagerToReconcile : ExportNoticeReason

	/** The drawable lacks the texture input the chosen display mode samples, so its pointer was left as-is. */
	data object NoTextureInputForDisplayMode : ExportNoticeReason

	/** A fractional canvas size, where CMO3 stores whole pixels. */
	data object FractionalCanvasSizeNotStorable : ExportNoticeReason

	/** The tile has no packed entry on any atlas page, so there is no placement in the file to move. */
	data object NoAtlasEntryToReconcile : ExportNoticeReason

	/**
	 * A placement moved, but the page's stored pixels were not rebuilt to match.
	 *
	 * Where the art is RECORDED to sit is model state; the page image is the document's, and nothing
	 * here recomposes it.  The written file is a faithful export of what was authored, and its atlas
	 * page still shows the art at its old spot until a repack redraws it.
	 */
	data object AtlasPageNotRecomposed : ExportNoticeReason

	/** The tile's own art changed - its name, size, or source layer - which a repack cannot express. */
	data object AtlasTileMetadataNotReconcilable : ExportNoticeReason

	/** The drawable was rebound to different source art, which needs the art re-imported, not repacked. */
	data object AtlasTileRebindingNotLowered : ExportNoticeReason
}