package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.model.drawable.CoordType
import org.umamo.format.cmo3.model.drawable.MeshPointRef
import org.umamo.format.cmo3.model.drawable.PointInTriangle
import org.umamo.format.cmo3.model.drawable.PointOnCurve

/**
 * Registers the mesh/geometry subsystem (all reflective). These are frequent shared-pool defs, so
 * typing them is validated directly by the whole-file byte-identity gate.
 *
 * @param SerializerRegistry registry The registry to populate.
 */
internal fun registerMeshSubsystem(registry: SerializerRegistry) {
	registry.register(CoordType::class)
	registry.register(PointInTriangle::class)
	registry.register(PointOnCurve::class)
	registry.register(MeshPointRef::class)
}