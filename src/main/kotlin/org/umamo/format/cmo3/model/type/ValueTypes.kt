package org.umamo.format.cmo3.model.type

import org.umamo.format.cmo3.serialize.annotations.DontSerialize
import org.umamo.format.cmo3.serialize.annotations.SerialTag

/**
 * A 2D vector, serialized reflectively as `<f xs.n="x">`
 * + `<f xs.n="y">` children.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Primitive & collection tags</a>
 */
@SerialTag("GVector2")
public class GVector2 {
	public var x: Float = 0f
	public var y: Float = 0f
}

/**
 * An integer rectangle: reflective `<i>` children in x, y, width,
 * height order.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Primitive & collection tags</a>
 */
@SerialTag("CRect")
public class CRect {
	public var x: Int = 0
	public var y: Int = 0
	public var width: Int = 0
	public var height: Int = 0
}

/**
 * A color whose backing value is @DontSerialize (not persisted, matching the editor), so
 * it serializes to an empty `<CColor/>` (the editor does not persist these UI colors). [argb] lets a
 * caller hold a value, but it is never written, matching the editor.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
@SerialTag("CColor")
public class CColor {
	@DontSerialize
	public var argb: Int = 0
}

/**
 * A 2x3 affine matrix, custom-serialized as the
 * attributes m00, m01, m02, m10, m11, m12. Defaults to the identity transform.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
@SerialTag("CAffine")
public class CAffine {
	public var m00: Float = 1f
	public var m01: Float = 0f
	public var m02: Float = 0f
	public var m10: Float = 0f
	public var m11: Float = 1f
	public var m12: Float = 0f
}