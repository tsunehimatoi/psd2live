package org.umamo.format.moc3

import org.umamo.format.moc3.moc.CanvasInfo
import org.umamo.format.moc3.moc.MocParameter
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.model.ArtMesh
import org.umamo.format.moc3.model.BlendShape
import org.umamo.format.moc3.model.Deformer
import org.umamo.format.moc3.model.Glue
import org.umamo.format.moc3.model.KeyformBinding
import org.umamo.format.moc3.model.Offscreen
import org.umamo.format.moc3.model.Part
import org.umamo.format.moc3.model.RenderOrderGroup

/**
 * The fully decoded semantic model of a `.moc3` - the agnostic intermediate for editor
 * reconstruction and (later) the bake.
 *
 * Objects reference each other and parameters by index (their position in these lists). Each
 * deformable object names a `keyformBindingIndex`; look the binding up with [keyformBinding] to
 * learn the controlling parameters and key positions for its keyforms. The per-frame
 * interpolation/deformation math is intentionally not modelled - this is the stored data.
 * Blend shapes (moc 4+) and offscreens (moc 6) are assembled into [blendShapes]/[offscreens];
 * every section index is modeled in `Section`, though 149-151 (glue blend shapes)
 * decode to nothing because no corpus sample carries one.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5</a>
 */
public class MocDocument(
	public val version: MocVersion,
	public val canvas: CanvasInfo?,
	public val parameters: List<MocParameter>,
	private val keyformBindings: Map<Int, KeyformBinding>,
	public val parts: List<Part>,
	public val deformers: List<Deformer>,
	public val artMeshes: List<ArtMesh>,
	public val glues: List<Glue>,
	public val renderOrderGroups: List<RenderOrderGroup>,
	/** Blend-shape records (moc 4+); empty on older versions. */
	public val blendShapes: List<BlendShape> = emptyList(),
	/** Offscreen render targets (moc 6); empty on older versions. */
	public val offscreens: List<Offscreen> = emptyList(),
	/**
	 * Whether a blend-free file's KEY_POSITIONS (section 77) carries the trailing per-parameter
	 * sorted-union key region.  This is an editor-version artifact: some Cubism editor builds append
	 * the union of each parameter's main-grid axis keys after the parameter-binding dedup region,
	 * others omit it, and no other section or header field distinguishes the two (probed across the
	 * v1/v3 corpus - the section tables and headers are byte-identical).  Carried so the lowering can
	 * reproduce section 77 byte-exact; the runtime recomputes the same unions when the region is
	 * absent (a model with multi-keyset parameters loads correctly either way).  A blend model always
	 * carries the region unconditionally, so this flag only disambiguates the blend-free path.
	 */
	public val keyPositionsHasParameterUnion: Boolean = false,
) {
	/** All resolved keyform bindings referenced by objects, ascending by index. */
	public val bindings: List<KeyformBinding> get() = keyformBindings.values.sortedBy { it.index }

	/**
	 * The keyform binding with the given index, or null if no object references it.
	 *
	 * @param Int index A `keyformBindingIndex` from an object.
	 * @return KeyformBinding? The resolved binding.
	 */
	public fun keyformBinding(index: Int): KeyformBinding? = keyformBindings[index]
}