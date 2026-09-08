package org.umamo.interop.moc3.export

import org.umamo.format.moc3.model.KeyformBinding
import org.umamo.format.moc3.model.Rgb
import org.umamo.interop.KeyformBundle
import org.umamo.interop.KeyformBundleResult
import org.umamo.interop.OutOfSpanPolicy
import org.umamo.interop.buildKeyformBundle
import org.umamo.runtime.keyform.FormInterpolator
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformGrid
import org.umamo.format.moc3.model.KeyformAxis as MocKeyformAxis

/**
 * The keyform-binding pool a `.moc3` addresses every object's forms through, plus the per-object
 * bundles that ride it.
 *
 * The runtime keeps geometry and each channel on their own axes; a MOC3 stores ONE dense grid per
 * object and every object points at a shared, deduplicated binding record describing that grid's
 * parameter axes.  So an export has to re-bundle each object (shared with the CMO3 path - see
 * [buildKeyformBundle]) and then intern the resulting axis signature into a pool.
 *
 * BINDING 0 IS RESERVED for the zero-axis (static) binding and emitted whether or not anything
 * references it.  That is not tidiness: it is corpus-universal across all 26 samples, and the
 * mesh-less `ModelWithOffscreen` family proves the runtime expects the record to exist even when
 * only static parts point at it.
 *
 * The pool's NUMBERING will not match the source file's on a round trip.  A MOC3's binding indices
 * are the editor's internal creation order - first-reference order over the object lists is
 * non-ascending in 9 of 10 non-trivial corpus models - and [org.umamo.runtime.model.PuppetModel]
 * deliberately does not carry them, since format indices in the runtime model would invert the
 * module graph.  The pool is therefore semantically equivalent, not byte-identical, and every
 * round-trip gate canonicalizes before comparing.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
class Moc3KeyformPool(private val parameterIndexOf: (org.umamo.runtime.model.ParameterId) -> Int) {
	/** Signature → binding index, so two objects on the same axes share one record. */
	private val indexBySignature = LinkedHashMap<List<Pair<Int, List<Float>>>, Int>()
	private val bindings = ArrayList<KeyformBinding>()

	init {
		// Reserve index 0 for the static binding before anything else can claim it.
		intern(emptyList())
	}

	/** Every interned binding, in index order - what `MocDocument.keyformBindings` is built from. */
	fun bindings(): List<KeyformBinding> = bindings.toList()

	/**
	 * The binding index for [bundle]'s axes, interning it when new.
	 *
	 * @param KeyformBundle bundle The re-bundled grid.
	 * @return Int The pool index.
	 */
	fun indexOf(bundle: KeyformBundle): Int =
		intern(
			bundle.axes.map { axis -> parameterIndexOf(axis.parameterId) to axis.keys.toList() },
		)

	/**
	 * Interns an axis signature, returning its stable index.
	 *
	 * @param List signature Per axis, its parameter index and key positions.
	 * @return Int The pool index.
	 */
	private fun intern(signature: List<Pair<Int, List<Float>>>): Int =
		indexBySignature.getOrPut(signature) {
			val index = bindings.size
			bindings.add(
				KeyformBinding(
					index,
					signature.map { (parameterIndex, keys) -> MocKeyformAxis(parameterIndex, keys.toFloatArray()) },
				),
			)
			index
		}
}

/**
 * One object's lowered keyforms: the binding it points at and its dense cell list.
 *
 * @property Int           bindingIndex The pool index this object's grid uses.
 * @property KeyformBundle bundle       The bundled grid, whose cells are already in the moc's
 *                                      first-axis-fastest order.
 */
class Moc3ObjectKeyforms(
	val bindingIndex: Int,
	val bundle: KeyformBundle,
) {
	/**
	 * Channels the bundling dropped to their static, which the caller turns into notices.
	 *
	 * Delegated rather than copied into a field of its own: two stores of one fact let a later change
	 * filter the demotions in one place and leave the other reading the unfiltered list, with nothing
	 * in the type system to catch the divergence.
	 */
	val demotedChannels: List<FormChannel> get() = bundle.demotedChannels
}

/**
 * Re-bundles one object and interns its binding.
 *
 * MOC3 takes [OutOfSpanPolicy.DemoteChannel] rather than CMO3's reject: every moc object must carry at
 * least one keyform, so declining an owner would mean writing nothing for it and producing a file the
 * runtime cannot load.  Losing one channel's animation is the strictly smaller loss, and the caller
 * turns the demotion into a notice so it is never silent.
 *
 * @param Moc3KeyformPool  pool            The shared binding pool.
 * @param KeyformGrid?     geometryGrid    The object's geometry grid, or null when it has none.
 * @param FormInterpolator geometryBlend   The geometry interpolator.
 * @param ChannelGrids     channels        The object's channel tracks.
 * @param Map              statics         Fallback value per channel the object owns.
 * @param Boolean          requireGeometry Whether a geometry-less object is an error.
 * @return Moc3ObjectKeyforms? The lowered keyforms, or null when unrepresentable.
 */
fun <TGeometry> lowerObjectKeyforms(
	pool: Moc3KeyformPool,
	geometryGrid: KeyformGrid<TGeometry>?,
	geometryBlend: FormInterpolator<TGeometry>,
	channels: ChannelGrids,
	statics: Map<FormChannel, ChannelValue>,
	requireGeometry: Boolean,
): Moc3ObjectKeyforms? {
	val result =
		buildKeyformBundle(
			geometryGrid,
			geometryBlend,
			channels,
			statics,
			requireGeometry,
			OutOfSpanPolicy.DemoteChannel,
		)
	if (result !is KeyformBundleResult.Bundled) {
		return null
	}
	val bundle = result.bundle
	return Moc3ObjectKeyforms(pool.indexOf(bundle), bundle)
}

/**
 * The scalar value of [channel] in [cellIndex], falling back to [fallback].
 *
 * @param KeyformBundle bundle    The bundled grid.
 * @param Int           cellIndex The cell ordinal.
 * @param FormChannel   channel   The channel to read.
 * @param Float         fallback  The value when the channel is absent or not a scalar.
 * @return Float The cell's value.
 */
fun scalarOf(bundle: KeyformBundle, cellIndex: Int, channel: FormChannel, fallback: Float): Float =
	(bundle.cells.getOrNull(cellIndex)?.channels?.get(channel) as? ChannelValue.Scalar)?.value ?: fallback

/**
 * The flag value of [channel] in [cellIndex], falling back to [fallback].
 *
 * @param KeyformBundle bundle    The bundled grid.
 * @param Int           cellIndex The cell ordinal.
 * @param FormChannel   channel   The channel to read.
 * @param Boolean       fallback  The value when the channel is absent or not a flag.
 * @return Boolean The cell's value.
 */
fun flagOf(bundle: KeyformBundle, cellIndex: Int, channel: FormChannel, fallback: Boolean): Boolean =
	(bundle.cells.getOrNull(cellIndex)?.channels?.get(channel) as? ChannelValue.Flag)?.flag ?: fallback

/**
 * The color of [channel] in [cellIndex] as the format's [Rgb], or null when the target version has no
 * color tables.
 *
 * A null return is meaningful rather than a failure: the lowering writes the color sections only when
 * some object carries a color, so a pre-4.2 export must produce nulls throughout or it would
 * synthesize tables the version cannot address.
 *
 * @param KeyformBundle? bundle    The bundled grid, or null for an unkeyed owner.
 * @param Int            cellIndex The cell ordinal.
 * @param FormChannel    channel   The color channel to read.
 * @param ColorRgb       fallback  The owner's static color.
 * @param Boolean        enabled   Whether the target version carries color tables.
 * @return Rgb? The cell's color, or null when colors are not written.
 */
fun colorOf(
	bundle: KeyformBundle?,
	cellIndex: Int,
	channel: FormChannel,
	fallback: ColorRgb,
	enabled: Boolean,
): Rgb? {
	if (!enabled) {
		return null
	}
	val value =
		(bundle?.cells?.getOrNull(cellIndex)?.channels?.get(channel) as? ChannelValue.Color)?.color ?: fallback
	return Rgb(value.red, value.green, value.blue)
}