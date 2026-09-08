package org.umamo.format.moc3.moc

/** Element width of a section's array. */
public enum class ElementType(public val size: Int) {
	/** unsigned 8-bit (flag bytes). */
	U8(1),

	/** signed 16-bit (glue vertex indices). */
	I16(2),

	/** signed 32-bit. */
	I32(4),

	/** unsigned 32-bit (CountInfo). */
	U32(4),

	/** unsigned 64-bit (the per-object runtime slots, zero on disk). */
	U64(8),

	/** IEEE-754 32-bit float. */
	F32(4),

	/** fixed 64-byte NUL-terminated ID record. */
	ID(64),
}

/**
 * How a section's element count is determined.
 *
 * Per-object arrays are sized by a CountInfo count; "table" arrays (keyform values, key
 * positions, glue pairs, …) have no single count field and are read across the whole section
 * slice (the runtime indexes them via base/offset tables, not a length). Reading the full slice
 * is lossless - trailing zero padding becomes trailing zero elements that the semantic layer
 * never dereferences.
 */
public enum class Sizing {
	PER_PART,
	PER_DEFORMER,
	PER_WARP,
	PER_ROTATION,
	PER_DRAWABLE,
	PER_PARAMETER,
	PER_GLUE,
	PER_RENDER_ORDER_GROUP,
	PER_RENDER_ORDER_CHILD,
	PER_OFFSCREEN,
	PER_BLENDSHAPE_WARP,
	PER_BLENDSHAPE_MESH,
	PER_BLENDSHAPE_ROTATION,
	PER_BLENDSHAPE_PART,
	PER_BLENDSHAPE_GLUE,

	// One entry per KEYFORM of the object kind, not per object: the flat form arrays each object
	// indexes into through its keyform base.  Sized by CountInfo 6-9 and 36 respectively.
	PER_PART_FORM,
	PER_WARP_FORM,
	PER_ROTATION_FORM,
	PER_ARTMESH_FORM,
	PER_OFFSCREEN_FORM,

	/** Read across the whole section slice (a table indexed elsewhere). */
	TABLE,
}

/**
 * EVERY section of a `.moc3`, with its element type, sizing rule, and section-table index per moc
 * version - the single registry the decoder, the lowering, and the lossless gate all read.
 *
 * The per-version indices are on-disk facts (which table slot holds which data, recovered from the
 * runtime). `index[version-1]` gives the slot; `-1` means the section is absent in that version.
 *
 * Completeness is enforced, not aspirational: `SectionRegistryTest` asserts that every slot each
 * version's table defines has exactly one entry here, that no two entries claim a slot, and that an
 * index never moves once introduced.  That matters because a section modeled nowhere can only be
 * carried verbatim from a reference container - which is precisely what stops a document being baked
 * from scratch.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §section map</a>
 */
public enum class Section(
	public val element: ElementType,
	public val sizing: Sizing,
	private vararg val indexByVersion: Int, // [v1,v2,v3,v4,v5,v6]; -1 = absent
) {
	// --- global ---

	/** The object-count array itself (section 0); every `PER_*` sizing rule reads its fields. */
	COUNT_INFO(ElementType.U32, Sizing.TABLE, 0, 0, 0, 0, 0, 0),

	/** Canvas `f32[6]`: pixels-per-unit, origin x/y, width, height, and a trailing zero. */
	CANVAS(ElementType.F32, Sizing.TABLE, 1, 1, 1, 1, 1, 1),

	// --- deformers (unified list) ---

	/**
	 * Per-deformer 8-byte runtime slot, the block's leading field.  Zero in every corpus file (the
	 * runtime fills it after the memory-cast), so it carries no authored data - it is modeled only so
	 * a bake sizes it to the deformer count instead of carrying a stale array from the source file.
	 * Every object block opens with the same slot: parts 2, art meshes 29, parameters 49, glues 89,
	 * offscreens 154.  All are corpus-verified all-zero (UnmodeledSectionIdentityProbeTest).
	 */
	DEFORMER_RUNTIME_SLOT(ElementType.U64, Sizing.PER_DEFORMER, 10, 10, 10, 10, 10, 10),

	/** Per-deformer authored id (e.g. "Warp349", "B_LEG_01"), unique within the model. */
	DEFORMER_ID(ElementType.ID, Sizing.PER_DEFORMER, 11, 11, 11, 11, 11, 11),

	/**
	 * Per-deformer keyform-binding index, in unified deformer order.  Duplicates the per-type
	 * [WARP_KEYFORM_BINDING] / [ROTATION_KEYFORM_BINDING] value for the same deformer (corpus-verified
	 * identical); both are written because the runtime addresses each by a different path.
	 */
	DEFORMER_KEYFORM_BINDING(ElementType.I32, Sizing.PER_DEFORMER, 12, 12, 12, 12, 12, 12),

	/**
	 * Per-deformer visibility flag (`0`/`1`), the editor's eye toggle.  The only deformer flag that
	 * ever deviates from 1 on the corpus.  See [DEFORMER_IS_ENABLED] for the pairing caveat.
	 */
	DEFORMER_IS_VISIBLE(ElementType.I32, Sizing.PER_DEFORMER, 13, 13, 13, 13, 13, 13),

	/**
	 * Per-deformer second flag, `1` on every deformer of every corpus model - so it is not the
	 * visibility toggle; [DEFORMER_IS_VISIBLE] is.
	 *
	 * That split is an inference rather than a join result.  The art-mesh pair (37/38) has the same
	 * shape and WAS pinned by joining miku_verycursed against its CMO3 twin, and the only deformer
	 * deviation anywhere (Azxiana, 7 of 623) sits in the first column exactly as the art-mesh
	 * deviations do - but Azxiana has no twin, so nothing proves it directly.
	 */
	DEFORMER_IS_ENABLED(ElementType.I32, Sizing.PER_DEFORMER, 14, 14, 14, 14, 14, 14),

	/** Per-deformer owning part (the organizational tree), -1 at the root. */
	DEFORMER_PARENT_PART(ElementType.I32, Sizing.PER_DEFORMER, 15, 15, 15, 15, 15, 15),
	DEFORMER_PARENT(ElementType.I32, Sizing.PER_DEFORMER, 16, 16, 16, 16, 16, 16),
	DEFORMER_TYPE(ElementType.I32, Sizing.PER_DEFORMER, 17, 17, 17, 17, 17, 17),

	/** Per-deformer 0-based index within its type group (warps vs rotations), in deformer-list order. */
	DEFORMER_LOCAL_INDEX(ElementType.I32, Sizing.PER_DEFORMER, 18, 18, 18, 18, 18, 18),

	// --- warp deformers ---
	WARP_KEYFORM_BINDING(ElementType.I32, Sizing.PER_WARP, 19, 19, 19, 19, 19, 19),
	WARP_KEYFORM_BASE(ElementType.I32, Sizing.PER_WARP, 20, 20, 20, 20, 20, 20),

	/** Per-warp keyform count (its binding's grid size); the run length starting at [WARP_KEYFORM_BASE]. */
	WARP_KEYFORM_COUNT(ElementType.I32, Sizing.PER_WARP, 21, 21, 21, 21, 21, 21),

	/** Per-warp control-point count `(rows+1)·(columns+1)` (the runtime validates it against rows/cols). */
	WARP_CONTROL_POINT_COUNT(ElementType.I32, Sizing.PER_WARP, 22, 22, 22, 22, 22, 22),
	WARP_ROWS(ElementType.I32, Sizing.PER_WARP, 23, 23, 23, 23, 23, 23),
	WARP_COLUMNS(ElementType.I32, Sizing.PER_WARP, 24, 24, 24, 24, 24, 24),

	/**
	 * Warp interpolation mode (0 = triangle split, non-zero = bilinear quad).
	 *
	 * Gated from MOC3 v2 (Cubism 3.3), which is where slot 101 first exists at all -
	 * [org.umamo.format.moc3.encode.MocEncoder.sectionCount] gives v1 101 slots and v2 102.  A v3 gate
	 * would leave v2's slot 101 modeled by nothing, which
	 * `SectionRegistryTest.everyTableSlotIsModeledForEveryVersion` rejects.  UNVERIFIED against a
	 * sample: the corpus has no v2 file (v1, v3, v4, v5, v6 only), so this rests on the two agreeing
	 * sources rather than on bytes.  Harmless if wrong - an absent section reads as absent.
	 */
	WARP_MODE(ElementType.I32, Sizing.PER_WARP, -1, 101, 101, 101, 101, 101),
	WARP_COLOR_BASE(ElementType.I32, Sizing.PER_WARP, -1, -1, -1, 105, 105, 105),
	WARP_OPACITY(ElementType.F32, Sizing.TABLE, 59, 59, 59, 59, 59, 59),

	/** Keyform → packed-position offset table for warps/rotations (distinct from art meshes' [KEYFORM_POSITION_INDEX]). */
	WARP_KEYFORM_INDEX(ElementType.I32, Sizing.TABLE, 60, 60, 60, 60, 60, 60),

	/**
	 * Per-warp-keyform multiply / screen color row refs (moc 5+), each `colorBase[warp] + gridIndex`.
	 * The two arrays are BIT-IDENTICAL on every corpus sample, the same shape the offscreen keyform
	 * rows (162/163) have - so both are derivable and neither carries independent data.
	 */
	WARP_FORM_MULTIPLY_ROW(ElementType.I32, Sizing.PER_WARP_FORM, -1, -1, -1, -1, 137, 137),
	WARP_FORM_SCREEN_ROW(ElementType.I32, Sizing.PER_WARP_FORM, -1, -1, -1, -1, 138, 138),

	// --- rotation deformers ---
	ROTATION_KEYFORM_BINDING(ElementType.I32, Sizing.PER_ROTATION, 25, 25, 25, 25, 25, 25),
	ROTATION_KEYFORM_BASE(ElementType.I32, Sizing.PER_ROTATION, 26, 26, 26, 26, 26, 26),

	/** Per-rotation keyform count (its binding's grid size). */
	ROTATION_KEYFORM_COUNT(ElementType.I32, Sizing.PER_ROTATION, 27, 27, 27, 27, 27, 27),

	/** Per-rotation-keyform color row refs (moc 5+); see [WARP_FORM_MULTIPLY_ROW]. */
	ROTATION_FORM_MULTIPLY_ROW(ElementType.I32, Sizing.PER_ROTATION_FORM, -1, -1, -1, -1, 139, 139),
	ROTATION_FORM_SCREEN_ROW(ElementType.I32, Sizing.PER_ROTATION_FORM, -1, -1, -1, -1, 140, 140),
	ROTATION_COLOR_BASE(ElementType.I32, Sizing.PER_ROTATION, -1, -1, -1, 106, 106, 106),
	ROTATION_BASE_ANGLE(ElementType.F32, Sizing.PER_ROTATION, 28, 28, 28, 28, 28, 28),
	ROTATION_OPACITY(ElementType.F32, Sizing.TABLE, 61, 61, 61, 61, 61, 61),
	ROTATION_ANGLE(ElementType.F32, Sizing.TABLE, 62, 62, 62, 62, 62, 62),
	ROTATION_ORIGIN_X(ElementType.F32, Sizing.TABLE, 63, 63, 63, 63, 63, 63),
	ROTATION_ORIGIN_Y(ElementType.F32, Sizing.TABLE, 64, 64, 64, 64, 64, 64),
	ROTATION_SCALE(ElementType.F32, Sizing.TABLE, 65, 65, 65, 65, 65, 65),
	ROTATION_REFLECT_X(ElementType.I32, Sizing.TABLE, 66, 66, 66, 66, 66, 66),
	ROTATION_REFLECT_Y(ElementType.I32, Sizing.TABLE, 67, 67, 67, 67, 67, 67),

	// --- art meshes ---

	/**
	 * The art-mesh block's four leading 8-byte runtime slots (all-zero on disk, like every other
	 * object block's).  Lina's research marks these header and unknown A, B, and C.
	 */
	ARTMESH_RUNTIME_SLOT(ElementType.U64, Sizing.PER_DRAWABLE, 29, 29, 29, 29, 29, 29),
	ARTMESH_RUNTIME_SLOT_A(ElementType.U64, Sizing.PER_DRAWABLE, 30, 30, 30, 30, 30, 30),
	ARTMESH_RUNTIME_SLOT_B(ElementType.U64, Sizing.PER_DRAWABLE, 31, 31, 31, 31, 31, 31),
	ARTMESH_RUNTIME_SLOT_C(ElementType.U64, Sizing.PER_DRAWABLE, 32, 32, 32, 32, 32, 32),

	/** Per-drawable authored id, e.g. "ArtMesh42" or "D_HAIR_BACK_00". */
	ARTMESH_ID(ElementType.ID, Sizing.PER_DRAWABLE, 33, 33, 33, 33, 33, 33),

	/**
	 * Per-drawable visibility flag (`0`/`1`) - the editor's eye toggle, PROVEN by joining
	 * miku_verycursed against its CMO3 twin: it exports its hidden art meshes rather than dropping
	 * them, and exactly its 3 hidden meshes carry `0` here.  See [ARTMESH_IS_ENABLED] for the pairing.
	 */
	ARTMESH_IS_VISIBLE(ElementType.I32, Sizing.PER_DRAWABLE, 37, 37, 37, 37, 37, 37),

	/**
	 * Per-drawable second flag, `1` on every drawable of every corpus sample - so whatever it means, it
	 * is not the visibility toggle ([ARTMESH_IS_VISIBLE] is).  Lina's research indicates that this is
	 * the visibility flag, but probing indicates that it is not.
	 */
	ARTMESH_IS_ENABLED(ElementType.I32, Sizing.PER_DRAWABLE, 38, 38, 38, 38, 38, 38),

	/** Per-drawable owning part (the organizational tree), -1 at the root. */
	ARTMESH_PARENT_PART(ElementType.I32, Sizing.PER_DRAWABLE, 39, 39, 39, 39, 39, 39),

	/** Per-drawable texture (atlas page) index. */
	ARTMESH_TEXTURE(ElementType.I32, Sizing.PER_DRAWABLE, 41, 41, 41, 41, 41, 41),

	/**
	 * Per-drawable render-config bitmask - ONE BYTE per drawable, not an i32.  Carries the legacy
	 * 2-bit blend mode, the double-sided bit, and the invert-mask bit; see
	 * [org.umamo.format.moc3.moc.ConstantFlag].
	 */
	ARTMESH_CONSTANT_FLAGS(ElementType.U8, Sizing.PER_DRAWABLE, 42, 42, 42, 42, 42, 42),
	ARTMESH_VERTEX_COUNT(ElementType.I32, Sizing.PER_DRAWABLE, 43, 43, 43, 43, 43, 43),

	/** Per-drawable start into the UV float table (section 78): the prefix sum of 2 x vertex count. */
	ARTMESH_UV_START(ElementType.I32, Sizing.PER_DRAWABLE, 44, 44, 44, 44, 44, 44),
	ARTMESH_INDEX_COUNT(ElementType.I32, Sizing.PER_DRAWABLE, 46, 46, 46, 46, 46, 46),
	ARTMESH_MASK_COUNT(ElementType.I32, Sizing.PER_DRAWABLE, 48, 48, 48, 48, 48, 48),

	/** Per-drawable start into the triangle-index table (section 79): the prefix sum of index count. */
	ARTMESH_INDEX_START(ElementType.I32, Sizing.PER_DRAWABLE, 45, 45, 45, 45, 45, 45),

	/**
	 * Per-drawable start into the mask-index table (section 80): the prefix sum of mask count, BASED at
	 * the offscreen mask block that precedes the drawable masks, so entry 0 is not generally zero.
	 */
	ARTMESH_MASK_START(ElementType.I32, Sizing.PER_DRAWABLE, 47, 47, 47, 47, 47, 47),

	/** Per-art-mesh-keyform color row refs (moc 5+); see [WARP_FORM_MULTIPLY_ROW]. */
	ARTMESH_FORM_MULTIPLY_ROW(ElementType.I32, Sizing.PER_ARTMESH_FORM, -1, -1, -1, -1, 141, 141),
	ARTMESH_FORM_SCREEN_ROW(ElementType.I32, Sizing.PER_ARTMESH_FORM, -1, -1, -1, -1, 142, 142),

	// --- art-mesh deformation refs ---
	ARTMESH_KEYFORM_BINDING(ElementType.I32, Sizing.PER_DRAWABLE, 34, 34, 34, 34, 34, 34),
	ARTMESH_KEYFORM_BASE(ElementType.I32, Sizing.PER_DRAWABLE, 35, 35, 35, 35, 35, 35),

	/** Per-drawable keyform count (its keyform-binding grid size). */
	ARTMESH_KEYFORM_COUNT(ElementType.I32, Sizing.PER_DRAWABLE, 36, 36, 36, 36, 36, 36),
	ARTMESH_COLOR_BASE(ElementType.I32, Sizing.PER_DRAWABLE, -1, -1, -1, 107, 107, 107),
	ARTMESH_PARENT_DEFORMER(ElementType.I32, Sizing.PER_DRAWABLE, 40, 40, 40, 40, 40, 40),
	ARTMESH_OPACITY(ElementType.F32, Sizing.TABLE, 68, 68, 68, 68, 68, 68),
	ARTMESH_DRAW_ORDER(ElementType.F32, Sizing.TABLE, 69, 69, 69, 69, 69, 69),

	/**
	 * Per-drawable packed extended blend, colorMode | (alphaMode shl 8) - the 5.3 blend surface
	 * (color 0-17, alpha 0-4, same encoding as [OFFSCREEN_BLEND_MODE]).  0 = legacy blend, which
	 * stays in the constant-flags 2-bit field.  MOC3 v6 §5.6 s153; pinned by the PartClipping bake
	 * (authored Screen/Out drawable stored 522 = 10 or (2 shl 8)).
	 */
	ARTMESH_EXTENDED_BLEND(ElementType.I32, Sizing.PER_DRAWABLE, -1, -1, -1, -1, -1, 153),

	// --- shared static geometry tables ---

	/** All drawables' UVs, concatenated as interleaved `u,v`; each indexes in at [ARTMESH_UV_START]. */
	ARTMESH_UV_DATA(ElementType.F32, Sizing.TABLE, 78, 78, 78, 78, 78, 78),

	/** All drawables' triangle indices (`u16`), concatenated; each starts at [ARTMESH_INDEX_START]. */
	ARTMESH_INDEX_DATA(ElementType.I16, Sizing.TABLE, 79, 79, 79, 79, 79, 79),

	/**
	 * Clipping mask references.  The OFFSCREEN masks form the prefix of this table and the drawable
	 * masks follow, which is why [ARTMESH_MASK_START] is based rather than starting at zero.
	 */
	MASK_INDEX_DATA(ElementType.I32, Sizing.TABLE, 80, 80, 80, 80, 80, 80),

	// --- shared keyform value tables ---
	KEYFORM_POSITION_INDEX(ElementType.I32, Sizing.TABLE, 70, 70, 70, 70, 70, 70),
	KEYFORM_POSITION_VALUES(ElementType.F32, Sizing.TABLE, 71, 71, 71, 71, 71, 71),

	// --- parameters ---

	/** The parameter block's leading 8-byte runtime slot; all-zero on disk, sized only. */
	PARAM_RUNTIME_SLOT(ElementType.U64, Sizing.PER_PARAMETER, 49, 49, 49, 49, 49, 49),

	/** Per-parameter authored id, e.g. "ParamAngleX" - a format-level identifier, never localized. */
	PARAM_ID(ElementType.ID, Sizing.PER_PARAMETER, 50, 50, 50, 50, 50, 50),

	// NB: the file stores MAX before MIN.
	PARAM_MAX(ElementType.F32, Sizing.PER_PARAMETER, 51, 51, 51, 51, 51, 51),
	PARAM_MIN(ElementType.F32, Sizing.PER_PARAMETER, 52, 52, 52, 52, 52, 52),
	PARAM_DEFAULT(ElementType.F32, Sizing.PER_PARAMETER, 53, 53, 53, 53, 53, 53),

	/** Per-parameter repeat flag: true wraps the value into `[min, max)` instead of clamping. */
	PARAM_REPEAT(ElementType.I32, Sizing.PER_PARAMETER, 54, 54, 54, 54, 54, 54),

	/**
	 * Per-parameter snap type.  The constant `3` on every parameter of every corpus sample, v1 through
	 * v6, so it needs no model field - a writer emits 3.
	 */
	PARAM_SNAP_TYPE(ElementType.I32, Sizing.PER_PARAMETER, 55, 55, 55, 55, 55, 55),

	/**
	 * Per-parameter start into the binding-set pool: the running prefix sum of
	 * [PARAMETER_BINDING_COUNT] over parameters that HAVE bindings.  A parameter with none stores
	 * `-1` when it is NORMAL and `0` when it is BLEND_SHAPE (`Parameter.Types`, section 114) - both
	 * values occur in one file, and only the parameter type separates them.
	 */
	PARAM_BINDING_START(ElementType.I32, Sizing.PER_PARAMETER, 56, 56, 56, 56, 56, 56),

	/** Per-parameter second 8-byte runtime slot (moc 4+); all-zero on disk, sized only. */
	PARAM_RUNTIME_SLOT_A(ElementType.U64, Sizing.PER_PARAMETER, -1, -1, -1, 102, 102, 102),

	/**
	 * Per-parameter start into the key-position table (moc 4+): the prefix sum of the per-parameter
	 * key count (section 104), BASED at the end of the preceding key region rather than at zero.
	 */
	PARAM_KEY_START(ElementType.I32, Sizing.PER_PARAMETER, -1, -1, -1, 103, 103, 103),

	/** Per-parameter key count, the run length starting at [PARAM_KEY_START] (moc 4+). */
	PARAM_KEY_COUNT(ElementType.I32, Sizing.PER_PARAMETER, -1, -1, -1, 104, 104, 104),

	/** Per-parameter kind (normal vs blend shape); see [ParameterType].  Absent before moc 4. */
	PARAM_TYPE(ElementType.I32, Sizing.PER_PARAMETER, -1, -1, -1, 114, 114, 114),

	// --- keyform-binding grid ---
	PARAMETER_BINDING_COUNT(ElementType.I32, Sizing.PER_PARAMETER, 57, 57, 57, 57, 57, 57),
	BINDING_KEY_OFFSET(ElementType.I32, Sizing.TABLE, 75, 75, 75, 75, 75, 75),
	BINDING_KEY_COUNT(ElementType.I32, Sizing.TABLE, 76, 76, 76, 76, 76, 76),
	KEYFORM_BINDING_SLOT(ElementType.I32, Sizing.TABLE, 72, 72, 72, 72, 72, 72),
	KEYFORM_BINDING_START(ElementType.I32, Sizing.TABLE, 73, 73, 73, 73, 73, 73),
	KEYFORM_BINDING_COUNT(ElementType.I32, Sizing.TABLE, 74, 74, 74, 74, 74, 74),
	KEY_POSITIONS(ElementType.F32, Sizing.TABLE, 77, 77, 77, 77, 77, 77),

	// --- parts ---

	/** The part block's leading 8-byte runtime slot; all-zero on disk, sized only. */
	PART_RUNTIME_SLOT(ElementType.U64, Sizing.PER_PART, 2, 2, 2, 2, 2, 2),

	/** Per-part authored id, e.g. "PartHead". */
	PART_ID(ElementType.ID, Sizing.PER_PART, 3, 3, 3, 3, 3, 3),
	PART_KEYFORM_BINDING(ElementType.I32, Sizing.PER_PART, 4, 4, 4, 4, 4, 4),
	PART_KEYFORM_BASE(ElementType.I32, Sizing.PER_PART, 5, 5, 5, 5, 5, 5),

	/** Per-part keyform count (its binding's grid size). */
	PART_KEYFORM_COUNT(ElementType.I32, Sizing.PER_PART, 6, 6, 6, 6, 6, 6),

	/**
	 * The part's two visibility flags - read as "show my art meshes" and "show my deformers".
	 * Both are `1` on every part of every corpus sample and no CMO3 twin keeps a
	 * hidden part, so which is which is UNPINNED; nothing downstream needs the distinction, and a
	 * writer sets both from the part's single visibility.
	 */
	PART_VISIBLE_ARTMESHES(ElementType.I32, Sizing.PER_PART, 7, 7, 7, 7, 7, 7),
	PART_VISIBLE_DEFORMERS(ElementType.I32, Sizing.PER_PART, 8, 8, 8, 8, 8, 8),

	/** Per-part parent in the organizational tree, -1 at the root. */
	PART_PARENT(ElementType.I32, Sizing.PER_PART, 9, 9, 9, 9, 9, 9),
	PART_DRAW_ORDER(ElementType.F32, Sizing.TABLE, 58, 58, 58, 58, 58, 58),

	// --- color channel tables (v4+) ---
	COLOR_MULTIPLY_R(ElementType.F32, Sizing.TABLE, -1, -1, -1, 108, 108, 108),
	COLOR_MULTIPLY_G(ElementType.F32, Sizing.TABLE, -1, -1, -1, 109, 109, 109),
	COLOR_MULTIPLY_B(ElementType.F32, Sizing.TABLE, -1, -1, -1, 110, 110, 110),
	COLOR_SCREEN_R(ElementType.F32, Sizing.TABLE, -1, -1, -1, 111, 111, 111),
	COLOR_SCREEN_G(ElementType.F32, Sizing.TABLE, -1, -1, -1, 112, 112, 112),
	COLOR_SCREEN_B(ElementType.F32, Sizing.TABLE, -1, -1, -1, 113, 113, 113),

	// --- render-order group tree ---
	RENDER_ORDER_CHILD_COUNT(ElementType.I32, Sizing.PER_RENDER_ORDER_GROUP, 82, 82, 82, 82, 82, 82),

	/** Per-group total render-index count (recursive: drawables + offscreen slots + sub-group totals). */
	RENDER_ORDER_GROUP_RENDER_COUNT(ElementType.I32, Sizing.PER_RENDER_ORDER_GROUP, 83, 83, 83, 83, 83, 83),

	/** Per-group maximum child draw order (drawable draw order, or a sub-group's part draw order). */
	RENDER_ORDER_GROUP_MAX_DRAW_ORDER(ElementType.I32, Sizing.PER_RENDER_ORDER_GROUP, 84, 84, 84, 84, 84, 84),

	/** Per-group minimum child draw order. */
	RENDER_ORDER_GROUP_MIN_DRAW_ORDER(ElementType.I32, Sizing.PER_RENDER_ORDER_GROUP, 85, 85, 85, 85, 85, 85),
	RENDER_ORDER_CHILD_KIND(ElementType.I32, Sizing.TABLE, 86, 86, 86, 86, 86, 86),
	RENDER_ORDER_CHILD_INDEX(ElementType.I32, Sizing.TABLE, 87, 87, 87, 87, 87, 87),
	RENDER_ORDER_GROUP_INDEX(ElementType.I32, Sizing.TABLE, 88, 88, 88, 88, 88, 88),

	/** Per-group start into the child tables (86/87/88): the prefix sum of [RENDER_ORDER_CHILD_COUNT]. */
	RENDER_ORDER_CHILD_START(ElementType.I32, Sizing.PER_RENDER_ORDER_GROUP, 81, 81, 81, 81, 81, 81),

	// --- glue ---

	/** The glue block's leading 8-byte runtime slot; all-zero on disk, sized only. */
	GLUE_RUNTIME_SLOT(ElementType.U64, Sizing.PER_GLUE, 89, 89, 89, 89, 89, 89),

	/** Per-glue authored id, e.g. "Glue__ArtMesh48__ArtMesh49" (older bakes use "Glue_0_1_"). */
	GLUE_ID(ElementType.ID, Sizing.PER_GLUE, 90, 90, 90, 90, 90, 90),
	GLUE_KEYFORM_BINDING(ElementType.I32, Sizing.PER_GLUE, 91, 91, 91, 91, 91, 91),
	GLUE_KEY_OFFSET(ElementType.I32, Sizing.PER_GLUE, 92, 92, 92, 92, 92, 92),
	GLUE_KEY_COUNT(ElementType.I32, Sizing.PER_GLUE, 93, 93, 93, 93, 93, 93),
	GLUE_MESH_A(ElementType.I32, Sizing.PER_GLUE, 94, 94, 94, 94, 94, 94),
	GLUE_MESH_B(ElementType.I32, Sizing.PER_GLUE, 95, 95, 95, 95, 95, 95),
	GLUE_VERTEX_START(ElementType.I32, Sizing.PER_GLUE, 96, 96, 96, 96, 96, 96),
	GLUE_VERTEX_COUNT(ElementType.I32, Sizing.PER_GLUE, 97, 97, 97, 97, 97, 97),
	GLUE_WEIGHTS(ElementType.F32, Sizing.TABLE, 98, 98, 98, 98, 98, 98),
	GLUE_VERTEX_INDICES(ElementType.I16, Sizing.TABLE, 99, 99, 99, 99, 99, 99),
	GLUE_INTENSITIES(ElementType.F32, Sizing.TABLE, 100, 100, 100, 100, 100, 100),

	// --- blend shapes (moc 4+ positions / 5+ scalar fields) ---
	BLENDSHAPE_WARP_OBJECT(ElementType.I32, Sizing.PER_BLENDSHAPE_WARP, -1, -1, -1, 125, 125, 125),
	BLENDSHAPE_WARP_RECORD_START(ElementType.I32, Sizing.PER_BLENDSHAPE_WARP, -1, -1, -1, 126, 126, 126),
	BLENDSHAPE_WARP_RECORD_COUNT(ElementType.I32, Sizing.PER_BLENDSHAPE_WARP, -1, -1, -1, 127, 127, 127),
	BLENDSHAPE_MESH_OBJECT(ElementType.I32, Sizing.PER_BLENDSHAPE_MESH, -1, -1, -1, 128, 128, 128),
	BLENDSHAPE_MESH_RECORD_START(ElementType.I32, Sizing.PER_BLENDSHAPE_MESH, -1, -1, -1, 129, 129, 129),
	BLENDSHAPE_MESH_RECORD_COUNT(ElementType.I32, Sizing.PER_BLENDSHAPE_MESH, -1, -1, -1, 130, 130, 130),

	// v6 index is 146 (same as v5); the Umamo C++ Runtime's kSrcLayout maps it to the degenerate/empty slot 143.
	BLENDSHAPE_ROTATION_OBJECT(ElementType.I32, Sizing.PER_BLENDSHAPE_ROTATION, -1, -1, -1, -1, 146, 146),
	BLENDSHAPE_ROTATION_RECORD_START(ElementType.I32, Sizing.PER_BLENDSHAPE_ROTATION, -1, -1, -1, -1, 147, 147),
	BLENDSHAPE_ROTATION_RECORD_COUNT(ElementType.I32, Sizing.PER_BLENDSHAPE_ROTATION, -1, -1, -1, -1, 148, 148),
	BLENDSHAPE_RECORD_BINDING(ElementType.I32, Sizing.TABLE, -1, -1, -1, 120, 120, 120),
	BLENDSHAPE_RECORD_BASE(ElementType.I32, Sizing.TABLE, -1, -1, -1, 121, 121, 121),
	BLENDSHAPE_RECORD_SUBSTART(ElementType.I32, Sizing.TABLE, -1, -1, -1, 123, 123, 123),
	BLENDSHAPE_RECORD_CORNER_COUNT(ElementType.I32, Sizing.TABLE, -1, -1, -1, 124, 124, 124),

	// MOC3 v4+ §5.6: redundant per-record copy of the record's binding key count (corpus-confirmed
	// against Model A/Model B/Model C including v6 - see MorphTargetJoinProbeTest).
	BLENDSHAPE_RECORD_KEY_COUNT(ElementType.I32, Sizing.TABLE, -1, -1, -1, 122, 122, 122),

	// MOC3 v4+ §5.6: blend-weight limit ("blend shape limit") sub-binding tables. A record's
	// SUBSTART/CORNER_COUNT range into SUB_INDEX yields refs into a DEDUPLICATED sub-binding pool;
	// each pool entry maps another parameter (SUB_PARAMETER) through an end-clamped piecewise-linear
	// (SUB_KEYS, SUB_WEIGHT_VALUES) curve addressed by SUB_KEY_OFFSET/COUNT. The record's weight
	// multiplier is the MINIMUM over its refs. v4/v5 indices confirmed against Model B/Model C (v5) and
	// the C-runtime layout; the v6 column is assumed = v5 pending a v6 constraint bake (every confirmed
	// blend section is index-identical v5 -> v6, incl. section 122 above).
	BLENDSHAPE_SUB_INDEX(ElementType.I32, Sizing.TABLE, -1, -1, -1, 131, 131, 131),
	BLENDSHAPE_SUB_PARAMETER(ElementType.I32, Sizing.TABLE, -1, -1, -1, 132, 132, 132),
	BLENDSHAPE_SUB_KEY_OFFSET(ElementType.I32, Sizing.TABLE, -1, -1, -1, 133, 133, 133),
	BLENDSHAPE_SUB_KEY_COUNT(ElementType.I32, Sizing.TABLE, -1, -1, -1, 134, 134, 134),
	BLENDSHAPE_SUB_KEYS(ElementType.F32, Sizing.TABLE, -1, -1, -1, 135, 135, 135),
	BLENDSHAPE_SUB_WEIGHT_VALUES(ElementType.F32, Sizing.TABLE, -1, -1, -1, 136, 136, 136),

	// MOC3 v5+ §5.6: part blend shapes (Model C carries one). v6 column assumed = v5 (same caveat).
	BLENDSHAPE_PART_OBJECT(ElementType.I32, Sizing.PER_BLENDSHAPE_PART, -1, -1, -1, -1, 143, 143),
	BLENDSHAPE_PART_RECORD_START(ElementType.I32, Sizing.PER_BLENDSHAPE_PART, -1, -1, -1, -1, 144, 144),
	BLENDSHAPE_PART_RECORD_COUNT(ElementType.I32, Sizing.PER_BLENDSHAPE_PART, -1, -1, -1, -1, 145, 145),
	BLENDSHAPE_BINDING_KEY_OFFSET(ElementType.I32, Sizing.TABLE, -1, -1, -1, 117, 117, 117),
	BLENDSHAPE_BINDING_KEY_COUNT(ElementType.I32, Sizing.TABLE, -1, -1, -1, 118, 118, 118),
	BLENDSHAPE_BINDING_NEUTRAL(ElementType.I32, Sizing.TABLE, -1, -1, -1, 119, 119, 119),
	BLENDSHAPE_PARAMETER_BEGIN(ElementType.I32, Sizing.PER_PARAMETER, -1, -1, -1, 115, 115, 115),
	BLENDSHAPE_PARAMETER_COUNT(ElementType.I32, Sizing.PER_PARAMETER, -1, -1, -1, 116, 116, 116),

	/**
	 * MOC3 v5+ §5.6: glue blend shapes, the fourth object kind alongside warp/mesh/rotation/part.
	 * CountInfo 34 is zero on every corpus sample, so these are modeled for completeness (and so a
	 * bake SIZES them) but no glue blend shape is decoded into the document - nothing exercises them.
	 */
	BLENDSHAPE_GLUE_OBJECT(ElementType.I32, Sizing.PER_BLENDSHAPE_GLUE, -1, -1, -1, -1, 149, 149),
	BLENDSHAPE_GLUE_RECORD_START(ElementType.I32, Sizing.PER_BLENDSHAPE_GLUE, -1, -1, -1, -1, 150, 150),
	BLENDSHAPE_GLUE_RECORD_COUNT(ElementType.I32, Sizing.PER_BLENDSHAPE_GLUE, -1, -1, -1, -1, 151, 151),

	// --- offscreen rendering (moc 6) ---
	OFFSCREEN_OWNER_PART(ElementType.I32, Sizing.PER_OFFSCREEN, -1, -1, -1, -1, -1, 155),
	OFFSCREEN_CONSTANT_FLAGS(ElementType.U8, Sizing.PER_OFFSCREEN, -1, -1, -1, -1, -1, 156),
	OFFSCREEN_BLEND_MODE(ElementType.I32, Sizing.PER_OFFSCREEN, -1, -1, -1, -1, -1, 157),
	OFFSCREEN_MASK_COUNT(ElementType.I32, Sizing.PER_OFFSCREEN, -1, -1, -1, -1, -1, 159),
	OFFSCREEN_OPACITY(ElementType.F32, Sizing.TABLE, -1, -1, -1, -1, -1, 161),

	/** Per-part index of the offscreen it owns, -1 when none (the inverse of [OFFSCREEN_OWNER_PART]). */
	OFFSCREEN_BY_PART(ElementType.I32, Sizing.PER_PART, -1, -1, -1, -1, -1, 152),

	/**
	 * Per-offscreen cumulative base into the offscreen mask PREFIX of [MASK_INDEX_DATA], offset from
	 * the block start (successive diffs equal [OFFSCREEN_MASK_COUNT]; Model A probe,
	 * OffscreenKeyformProbeTest).
	 */
	OFFSCREEN_MASK_BASE(ElementType.I32, Sizing.PER_OFFSCREEN, -1, -1, -1, -1, -1, 158),

	/**
	 * Offscreen keyform → color-table row maps (multiply / screen). Observed identity on the corpus
	 * (the offscreen keyforms ARE the color tables' prefix rows, in offscreen order); the multiply
	 * vs screen split is assumed from the per-object color-base precedent, pending a discriminating
	 * sample. An offscreen's keyforms ride its owner part's keyform grid (Σ owner grid == CountInfo
	 * 36) - there is no offscreen keyform-binding section.
	 */
	OFFSCREEN_KEYFORM_MULTIPLY_ROW(ElementType.I32, Sizing.TABLE, -1, -1, -1, -1, -1, 162),
	OFFSCREEN_KEYFORM_SCREEN_ROW(ElementType.I32, Sizing.TABLE, -1, -1, -1, -1, -1, 163),

	/** The offscreen block's 8-byte runtime slot; all-zero on disk, sized only. */
	OFFSCREEN_RUNTIME_SLOT(ElementType.U64, Sizing.PER_OFFSCREEN, -1, -1, -1, -1, -1, 154),

	/**
	 * A second per-part offscreen-index map, byte-identical to [OFFSCREEN_BY_PART] on every corpus
	 * sample BUT Model A, where 152 is the exact inverse of [OFFSCREEN_OWNER_PART] while 160 names a
	 * larger set of parts matching no owner - so 152 is the reconstructible column and 160 an
	 * editor-internal artifact whose rule is unknown (OffscreenSectionAliasProbeTest guards the
	 * divergence).  A writer emits the derivable inverse in both.
	 */
	OFFSCREEN_BY_PART_ALIAS(ElementType.I32, Sizing.PER_PART, -1, -1, -1, -1, -1, 160),

	/**
	 * The v6 table's trailing slots.  Zero-LENGTH in every corpus sample, so their element type and
	 * meaning are unknown; they are enumerated so the section map is complete and a coverage check can
	 * see them, and `U8`/`TABLE` is the neutral choice that asserts no stride.
	 */
	RESERVED_164(ElementType.U8, Sizing.TABLE, -1, -1, -1, -1, -1, 164),
	RESERVED_165(ElementType.U8, Sizing.TABLE, -1, -1, -1, -1, -1, 165),
	RESERVED_166(ElementType.U8, Sizing.TABLE, -1, -1, -1, -1, -1, 166),
	;

	/**
	 * Section-table index of this section for a moc [version], or -1 if absent.
	 *
	 * @param MocVersion version The moc version.
	 * @return Int The section index, or -1.
	 */
	public fun indexIn(version: MocVersion): Int = indexByVersion[version.byteValue - 1]
}