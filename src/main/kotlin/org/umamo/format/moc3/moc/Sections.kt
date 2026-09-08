package org.umamo.format.moc3.moc

/**
 * `.moc3` container constants and the CountInfo field indices.
 *
 * Section-table INDICES do not live here - every one of them is a [Section] enum entry carrying
 * its element type, sizing rule, and per-version index, so `MocSections` can decode it and the
 * lossless gate can check it.  A bare index constant sitting alongside that enum would name a
 * section neither of them can see, so what remains here is the container geometry and the
 * CountInfo field numbers, which are not sections at all.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §section map</a>
 */
public object Sections {
	/**
	 * Where the data region (and section index 0, CountInfo) begins on a moc <= 5 file; the
	 * header+table region fills [0, this).  A moc 6 file reserves a larger region and starts its data
	 * at 0x16C0 instead, so a reader takes the first section's offset from the table rather than this.
	 * MOC3 §4.
	 */
	public const val DATA_SECTION_BEGIN: Int = 1984 // 0x7C0

	/** Fixed width, in bytes, of every ID record (ASCII, NUL-terminated, zero-padded). */
	public const val ID_STRIDE: Int = 64

	// ---- CountInfo (section 0) u32 indices ----
	public const val CI_PARTS: Int = 0
	public const val CI_DEFORMERS: Int = 1
	public const val CI_WARPS: Int = 2
	public const val CI_ROTATIONS: Int = 3
	public const val CI_DRAWABLES: Int = 4
	public const val CI_PARAMETERS: Int = 5

	// MOC3 §5.1 CountInfo fields 6-9: the flat per-KEYFORM array lengths, one entry per form rather
	// than per object.  Each object indexes its own run through its keyform base (sections 5/20/26/35).
	public const val CI_PART_FORMS: Int = 6
	public const val CI_WARP_FORMS: Int = 7
	public const val CI_ROTATION_FORMS: Int = 8
	public const val CI_ARTMESH_FORMS: Int = 9

	// MOC3 §5.1 CountInfo field 12: the stored keyform-binding record count.  Authoritative over
	// object references - a mesh-less model carries one EMPTY binding (0 axes) that only static
	// parts point at (probed on the ModelWithOffscreen family).
	public const val CI_KEYFORM_BINDINGS: Int = 12
	public const val CI_RENDER_ORDER_GROUPS: Int = 18
	public const val CI_RENDER_ORDER_CHILDREN: Int = 19
	public const val CI_GLUES: Int = 20
	public const val CI_BLENDSHAPE_WARPS: Int = 27
	public const val CI_BLENDSHAPE_MESHES: Int = 28

	// MOC3 v4+ §5.6: total blend-shape sub-binding corner refs (Σ section 124) and the
	// deduplicated sub-binding pool size (section 132's real element count).
	public const val CI_BLENDSHAPE_SUB_CORNERS: Int = 29
	public const val CI_BLENDSHAPE_SUB_BINDINGS: Int = 30

	// MOC3 v5+ §5.6: blend-shape part objects. Note CountInfo is NOT hard-capped at 32 words on
	// v5 - Model C (v5) carries CI 32/33; the reader is slice-length-driven, so these decode fine.
	public const val CI_BLENDSHAPE_PARTS: Int = 32
	public const val CI_BLENDSHAPE_ROTATIONS: Int = 33

	// MOC3 v5+ §5.6: glue blend-shape objects (sections 149-151).  Zero in every corpus sample, and
	// the lowering deliberately writes zero here, so nothing downstream models a glue blend shape yet.
	public const val CI_BLENDSHAPE_GLUES: Int = 34

	// MOC3 v6 §5.1 CountInfo field 35: offscreen render targets (sections 152-163).
	public const val CI_OFFSCREENS: Int = 35

	// MOC3 v6 §5.6: total offscreen keyforms (Σ owner-part grid sizes; sizes section 161 and the
	// color tables' offscreen prefix).
	public const val CI_OFFSCREEN_KEYFORMS: Int = 36
}