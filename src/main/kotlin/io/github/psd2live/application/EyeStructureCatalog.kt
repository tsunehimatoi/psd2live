package io.github.psd2live.application

import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.fit.EyePoint
import kotlinx.serialization.json.*

/** Captured, regenerable observations used when authoring source-bound structural corrections. */
internal object EyeStructureCatalog {
	private fun point(p: EyePoint) = buildJsonArray { add(p.x); add(p.y) }
	fun describe(model: RigPreviewModel): JsonArray {
		val eyes = RigBuilder.rigContext(model.analysis, model.config).fittedEyes(model.config) ?: return JsonArray(emptyList())
		return JsonArray(eyes.rigs.values.map { eye -> buildJsonObject {
			val fit = eye.fit
			put("side", fit.side.name.lowercase()); put("width", fit.width)
			put("origin", point(EyePoint(fit.originX, fit.originY))); put("axis", point(EyePoint(fit.axisX, fit.axisY)))
			val art = requireNotNull(fit.artwork)
			put("upper", JsonArray(art.upper.points.map(::point))); put("lower", JsonArray(art.lower.points.map(::point)))
			put("openingLayers", JsonArray(art.openingLayers.map(::JsonPrimitive)))
			put("components", JsonArray(art.components.map { c -> buildJsonObject {
				put("id", c.id); put("layerId", c.layerId); put("sourceStamp", c.sourceStamp)
				put("region", c.region.name.lowercase()); put("primitive", c.primitive.name.lowercase()); put("confidence", c.confidence)
				put("contacts", JsonArray(c.contacts.map { contact -> buildJsonObject {
					put("region", contact.region.name.lowercase()); put("from", contact.from); put("to", contact.to); put("centre", point(contact.centre))
				} }))
				put("branches", JsonArray(c.branches.map { branch -> buildJsonObject {
					put("root", point(branch.root)); put("tip", point(branch.tip)); put("path", JsonArray(branch.points.map(::point)))
				} }))
			} }))
		} })
	}
}
