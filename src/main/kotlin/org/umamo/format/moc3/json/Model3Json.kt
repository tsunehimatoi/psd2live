package org.umamo.format.moc3.json

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `*.model3.json` - the model manifest referencing the `.moc3`, textures, and other sidecars.
 *
 * EN: Field order and names match the `model3.json` the editor exports (the SDK's documented
 *     manifest schema). Optional references are nullable and omitted when absent, so re-emission
 *     drops nothing.
 * JA: モデルマニフェスト。
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §model3.json</a>
 */
@Serializable
public data class Model3Json(
	@SerialName("Version") val version: Int,
	@SerialName("FileReferences") val fileReferences: FileReferences,
	@SerialName("Groups") val groups: List<Model3Group>? = null,
	@SerialName("HitAreas") val hitAreas: List<Model3HitArea>? = null,
)

/** File references within a [Model3Json]; all but [moc]/[textures] are optional. */
@Serializable
public data class FileReferences(
	@SerialName("Moc") val moc: String,
	@SerialName("Textures") val textures: List<String>,
	@SerialName("Pose") val pose: String? = null,
	@SerialName("Physics") val physics: String? = null,
	@SerialName("UserData") val userData: String? = null,
	@SerialName("DisplayInfo") val displayInfo: String? = null,
	@SerialName("Expressions") val expressions: List<Model3Expression>? = null,
	@SerialName("Motions") val motions: Map<String, List<Model3Motion>>? = null,
)

/** An auto-wiring group (e.g. `EyeBlink`, `LipSync`) of parameter or part IDs. */
@Serializable
public data class Model3Group(
	@SerialName("Target") val target: String,
	@SerialName("Name") val name: String,
	@SerialName("Ids") val ids: List<String>,
)

/** A hit-test area mapping a display name to a drawable ID. */
@Serializable
public data class Model3HitArea(
	@SerialName("Name") val name: String,
	@SerialName("Id") val id: String,
)

/** An expression reference (`*.exp3.json`) with optional fade times. */
@Serializable
public data class Model3Expression(
	@SerialName("Name") val name: String,
	@SerialName("File") val file: String,
	@SerialName("FadeInTime") val fadeInTime: kotlinx.serialization.json.JsonPrimitive? = null,
	@SerialName("FadeOutTime") val fadeOutTime: kotlinx.serialization.json.JsonPrimitive? = null,
)

/** A motion reference (`*.motion3.json`) within a named group, with optional sound and fade times. */
@Serializable
public data class Model3Motion(
	@SerialName("File") val file: String,
	@SerialName("Sound") val sound: String? = null,
	@SerialName("FadeInTime") val fadeInTime: kotlinx.serialization.json.JsonPrimitive? = null,
	@SerialName("FadeOutTime") val fadeOutTime: kotlinx.serialization.json.JsonPrimitive? = null,
)