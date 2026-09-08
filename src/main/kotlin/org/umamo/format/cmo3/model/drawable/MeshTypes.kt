package org.umamo.format.cmo3.model.drawable

import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.cmo3.serialize.annotations.SerialTag

/**
 * A named coordinate space. Fields derived from the serialized
 * form: a single `coordName` string.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
@SerialTag("CoordType")
public class CoordType {
	public var coordName: String = ""
}

/**
 * A barycentric reference into an art-mesh triangle (3 point indices + weights).
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
@SerialTag("PointInTriangle")
public class PointInTriangle {
	public var ptIndex1: Int = 0
	public var weight1: Float = 0f
	public var ptIndex2: Int = 0
	public var weight2: Float = 0f
	public var ptIndex3: Int = 0
	public var weight3: Float = 0f
}

/**
 * A point sampled along a controller curve. Mirrors the serialized form: a curve reference, the
 * normalized/total parameter, distance, and a local position.
 *
 * EN: [curveId] references a CControllerCurveGuid (a [Guid]).
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
@SerialTag("PointOnCurve")
public class PointOnCurve {
	public var curveId: Guid? = null
	public var totalT: Float = 0f
	public var distance: Float = 0f
	public var _posOnLocal: GVector2? = null
}

/**
 * A reference from a deformer/affecter to a point on an art-mesh keyform.
 *
 * EN: Object fields reference shared objects; not-yet-typed targets (keyForm CArtMeshForm,
 *     _artMeshSource CArtMeshSource) are held as Any? and round-trip as references regardless.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
@SerialTag("MeshPointRef")
public class MeshPointRef {
	public var pointUid: Long = 0
	public var coordType: CoordType? = null
	public var keyForm: Any? = null
	public var positions: FloatArray? = null
	public var step: Int = 0
	public var _artMeshSource: Any? = null
	public var _index: Int = 0
	public var artMeshGuid: Guid? = null
}