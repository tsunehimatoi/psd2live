package org.umamo.format.moc3.json

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `*.userdata3.json` - per-object user strings attached to drawables.
 *
 * EN: Keys/order match the `userdata3.json` the editor exports (`Meta` is `UserDataCount` +
 *     `TotalUserDataSize`; the SDK only consumes `Target == "ArtMesh"` entries).
 * JA: ユーザーデータ。
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §userdata3.json</a>
 */
@Serializable
public data class UserData3Json(
	@SerialName("Version") val version: Int,
	@SerialName("Meta") val meta: UserDataMeta,
	@SerialName("UserData") val userData: List<UserDataEntry>,
)

/** Counts for a [UserData3Json]: number of entries and total byte size of their values. */
@Serializable
public data class UserDataMeta(
	@SerialName("UserDataCount") val userDataCount: Int,
	@SerialName("TotalUserDataSize") val totalUserDataSize: Int,
)

/** A single user-data entry: target kind, target ID, and the user string. */
@Serializable
public data class UserDataEntry(
	@SerialName("Target") val target: String,
	@SerialName("Id") val id: String,
	@SerialName("Value") val value: String,
)