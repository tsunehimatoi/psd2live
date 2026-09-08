package org.umamo.format.moc3.json

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * `*.physics3.json` - the physics rig definition (input/output bindings, particle chains).
 *
 * EN: Physical quantities (weights, scales, positions, forces, normalization bounds) are kept as
 *     [JsonPrimitive] so the editor's exact numeric tokens round-trip (it prints integral floats
 *     without a trailing `.0`). The physics math is out of scope here; this only models the file.
 * JA: 物理演算リグ定義。数値はトークン保持。
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §physics3.json</a>
 */
@Serializable
public data class Physics3Json(
	@SerialName("Version") val version: Int,
	@SerialName("Meta") val meta: PhysicsMeta,
	@SerialName("PhysicsSettings") val physicsSettings: List<PhysicsSetting>,
)

/** Counts, effective forces, optional fps, and the names dictionary for a [Physics3Json]. */
@Serializable
public data class PhysicsMeta(
	@SerialName("PhysicsSettingCount") val physicsSettingCount: Int,
	@SerialName("TotalInputCount") val totalInputCount: Int,
	@SerialName("TotalOutputCount") val totalOutputCount: Int,
	@SerialName("VertexCount") val vertexCount: Int,
	@SerialName("Fps") val fps: JsonPrimitive? = null,
	@SerialName("EffectiveForces") val effectiveForces: EffectiveForces,
	@SerialName("PhysicsDictionary") val physicsDictionary: List<PhysicsDictionaryEntry>,
)

/** The constant gravity and wind vectors. */
@Serializable
public data class EffectiveForces(
	@SerialName("Gravity") val gravity: PhysicsVector2,
	@SerialName("Wind") val wind: PhysicsVector2,
)

/** A 2D vector with token-preserving components. */
@Serializable
public data class PhysicsVector2(
	@SerialName("X") val x: JsonPrimitive,
	@SerialName("Y") val y: JsonPrimitive,
)

/** A `(Id, Name)` pair naming a physics setting (parallel to [Physics3Json.physicsSettings] by index). */
@Serializable
public data class PhysicsDictionaryEntry(
	@SerialName("Id") val id: String,
	@SerialName("Name") val name: String,
)

/** One physics setting: its inputs, outputs, particle chain, and normalization. */
@Serializable
public data class PhysicsSetting(
	@SerialName("Id") val id: String,
	@SerialName("Input") val input: List<PhysicsInput>,
	@SerialName("Output") val output: List<PhysicsOutput>,
	@SerialName("Vertices") val vertices: List<PhysicsVertex>,
	@SerialName("Normalization") val normalization: PhysicsNormalization,
)

/** A parameter-driven input to the rig. */
@Serializable
public data class PhysicsInput(
	@SerialName("Source") val source: PhysicsTarget,
	@SerialName("Weight") val weight: JsonPrimitive,
	@SerialName("Type") val type: String,
	@SerialName("Reflect") val reflect: Boolean,
)

/** A particle-driven output to a parameter. */
@Serializable
public data class PhysicsOutput(
	@SerialName("Destination") val destination: PhysicsTarget,
	@SerialName("VertexIndex") val vertexIndex: Int,
	@SerialName("Scale") val scale: JsonPrimitive,
	@SerialName("Weight") val weight: JsonPrimitive,
	@SerialName("Type") val type: String,
	@SerialName("Reflect") val reflect: Boolean,
)

/** A target reference (`Target` kind + `Id`). */
@Serializable
public data class PhysicsTarget(
	@SerialName("Target") val target: String,
	@SerialName("Id") val id: String,
)

/** A particle in the chain (`[0]` is the anchor). */
@Serializable
public data class PhysicsVertex(
	@SerialName("Position") val position: PhysicsVector2,
	@SerialName("Mobility") val mobility: JsonPrimitive,
	@SerialName("Delay") val delay: JsonPrimitive,
	@SerialName("Acceleration") val acceleration: JsonPrimitive,
	@SerialName("Radius") val radius: JsonPrimitive,
)

/** Input normalization ranges for position and angle. */
@Serializable
public data class PhysicsNormalization(
	@SerialName("Position") val position: PhysicsNormalizationRange,
	@SerialName("Angle") val angle: PhysicsNormalizationRange,
)

/** A `(Minimum, Default, Maximum)` normalization range. */
@Serializable
public data class PhysicsNormalizationRange(
	@SerialName("Minimum") val minimum: JsonPrimitive,
	@SerialName("Default") val default: JsonPrimitive,
	@SerialName("Maximum") val maximum: JsonPrimitive,
)