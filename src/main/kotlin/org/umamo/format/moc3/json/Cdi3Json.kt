package org.umamo.format.moc3.json

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `*.cdi3.json` - display info: human-readable names and grouping for parameters/parts, plus
 * 2-axis "combined parameter" hints. Purely cosmetic (no runtime effect).
 *
 * Keys/order match the `cdi3.json` the editor exports, with one addition: `Drawables` is an Umamo
 * extension (MOC3.md §6.3 documents no such key) carrying drawable display names, which the `.moc3`
 * itself has no field for.  `CombinedParameters` is an array-of-pairs (`[horizontal, vertical]`).
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §cdi3.json</a>
 */
@Serializable
public data class Cdi3Json(
	@SerialName("Version") val version: Int,
	@SerialName("Parameters") val parameters: List<DisplayParameter>,
	@SerialName("ParameterGroups") val parameterGroups: List<DisplayParameterGroup>,
	@SerialName("Parts") val parts: List<DisplayPart>,
	@SerialName("CombinedParameters") val combinedParameters: List<List<String>>? = null,
	@SerialName("Drawables") val drawables: List<DisplayDrawable>? = null,
)

/** A parameter's display name and owning group. */
@Serializable
public data class DisplayParameter(
	@SerialName("Id") val id: String,
	@SerialName("GroupId") val groupId: String,
	@SerialName("Name") val name: String,
)

/** A parameter group's display name and (optional) parent group. */
@Serializable
public data class DisplayParameterGroup(
	@SerialName("Id") val id: String,
	@SerialName("GroupId") val groupId: String,
	@SerialName("Name") val name: String,
)

/** A drawable's display name (the Umamo extension - see [Cdi3Json.drawables]). */
@Serializable
public data class DisplayDrawable(
	@SerialName("Id") val id: String,
	@SerialName("Name") val name: String,
)

/** A part's display name. */
@Serializable
public data class DisplayPart(
	@SerialName("Id") val id: String,
	@SerialName("Name") val name: String,
)