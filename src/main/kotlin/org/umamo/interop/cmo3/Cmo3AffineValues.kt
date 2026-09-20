package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.type.CAffine

/*
 * The CMO3 2x3 affine as the runtime's six-float array (m00, m01, m02, m10, m11, m12), in both
 * directions.  Every atlas lowering reads and writes a CAffine through these, so a transposed index
 * cannot be written in one site and not another - a wrong transform in the file is exactly the
 * corruption the reconcile exists to prevent.
 */

/**
 * This affine's six components in the runtime's row-major order.
 *
 * @return FloatArray The components (m00, m01, m02, m10, m11, m12).
 */
internal fun CAffine.toAffineArray(): FloatArray = floatArrayOf(m00, m01, m02, m10, m11, m12)

/**
 * Overwrites this affine's six components from the runtime's row-major order.
 *
 * @param FloatArray values The components (m00, m01, m02, m10, m11, m12).
 */
internal fun CAffine.setFromAffineArray(values: FloatArray) {
	m00 = values[0]
	m01 = values[1]
	m02 = values[2]
	m10 = values[3]
	m11 = values[4]
	m12 = values[5]
}

/**
 * A fresh affine holding [values].
 *
 * @param FloatArray values The components (m00, m01, m02, m10, m11, m12).
 * @return CAffine The new affine.
 */
internal fun affineOf(values: FloatArray): CAffine = CAffine().apply { setFromAffineArray(values) }

/**
 * An independent copy of this affine.  The writer must not hoist one shared instance across the
 * entry, the region input, and the model image - the editor writes separate elements.
 *
 * @return CAffine The copy.
 */
internal fun CAffine.copyAffine(): CAffine = affineOf(toAffineArray())