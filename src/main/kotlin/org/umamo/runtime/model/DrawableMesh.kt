package org.umamo.runtime.model

/**
 * An art mesh's static (rest-pose) geometry: interleaved [positions] and [uvs] (x then y per vertex)
 * and the triangle [indices] (three per polygon). Deformation deltas live in the drawable's keyform
 * grid; this is the base the deltas are applied to in `p = base + Σ wᵢ·Δᵢ`.
 *
 * A plain `class`, not a `data class`: the fields are arrays, whose identity-based `equals` would make
 * a generated structural `equals` quietly wrong (the same trap [Keyform] sidesteps by hand). A mesh is
 * referenced, not value-compared, so reference identity is the honest default here.
 */
class DrawableMesh(
	val positions: FloatArray,
	val uvs: FloatArray,
	val indices: IntArray,
) {
	/** Vertex count - two floats (x, y) per vertex. */
	val vertexCount: Int get() = positions.size / 2

	/** Triangle (polygon) count - three indices per triangle. */
	val triangleCount: Int get() = indices.size / 3
}