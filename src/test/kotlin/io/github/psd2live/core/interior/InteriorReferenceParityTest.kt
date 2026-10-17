package io.github.psd2live.core.interior

import java.util.Base64
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

/**
 * Independent oracle data produced by the original JS compiler/evaluator, not by this Kotlin code.
 * The explicitly removed attachment weight is neutralized in the oracle.
 */
class InteriorReferenceParityTest {
	@Test
	fun staticMaterialProjectionMatchesTheReferenceBeforeAndAfterAreaConstraints() {
		val text =
			requireNotNull(javaClass.getResourceAsStream("/interior-reference/static-cases.json"))
				.bufferedReader()
				.use { it.readText() }
		val result = compareInteriorReference(Json.parseToJsonElement(text).jsonObject)
		println(result.json())
		assertTrue(result.rawError <= 1e-7, result.toString())
		assertTrue(result.positionError <= 1e-6, result.toString())
		assertTrue(result.gainError <= 1e-8, result.toString())
	}
}

internal data class InteriorParityResult(
	val frames: Int,
	val rawError: Double,
	val positionError: Double,
	val gainError: Double,
	val worst: String,
) {
	fun json() = buildJsonObject {
		put("frames", frames)
		put("maxRawDisplacementError", rawError)
		put("maxPositionError", positionError)
		put("maxSafetyGainError", gainError)
		put("worst", worst)
	}
}

internal fun compareInteriorReference(reference: JsonObject): InteriorParityResult {
	require(reference["attachmentRemoved"]?.jsonPrimitive?.boolean == true) {
		"Use the explicit no-attachment oracle fixture"
	}
	var frames = 0
	var rawError = 0.0
	var positionError = 0.0
	var gainError = 0.0
	var worst = ""
	for (datasetValue in reference.getValue("datasets").jsonArray) {
		val d = datasetValue.jsonObject
		val m = d.getValue("mesh").jsonObject
		val xyz = m.getValue("positions").jsonArray.map { it.jsonPrimitive.float }
		val xy = xyz.chunked(3).flatMap { listOf(it[0], it[1]) }.toFloatArray()
		val mesh =
			InteriorMesh(
				xy,
				m.getValue("triangles").jsonArray.map { it.jsonPrimitive.int }.toIntArray(),
			)
		val raster =
			InteriorRaster(
				d.getValue("width").jsonPrimitive.int,
				d.getValue("height").jsonPrimitive.int,
				Base64.getDecoder().decode(d.getValue("rgba").jsonPrimitive.content),
			)
		for (scenarioValue in d.getValue("scenarios").jsonArray) {
			val s = scenarioValue.jsonObject
			val options = s.getValue("settings").jsonObject
			val settings =
				InteriorSurfaceSettings(
					options.getValue("horizontal").jsonPrimitive.float,
					options.getValue("vertical").jsonPrimitive.float,
					options.getValue("bulge").jsonPrimitive.float,
					options.getValue("press").jsonPrimitive.float,
					options.getValue("folds").jsonPrimitive.float,
					options.getValue("contour").jsonPrimitive.float,
				)
			val guides =
				s.getValue("guides").jsonArray.map { value ->
					val g = value.jsonObject
					val p = g.getValue("points").jsonArray
					InteriorGuide(
						g.getValue("id").jsonPrimitive.content,
						p.chunked(2).map {
							InteriorPoint(it[0].jsonPrimitive.float, it[1].jsonPrimitive.float)
						},
						InteriorGuidePurpose.valueOf(g.getValue("purpose").jsonPrimitive.content),
						g.getValue("radius").jsonPrimitive.float,
						g.getValue("amount").jsonPrimitive.float,
						g["pitchOnly"]?.jsonPrimitive?.boolean ?: false,
					)
				}
			val plan = InteriorSurface.compile(mesh, raster, settings, guides)
			for (value in s.getValue("frames").jsonArray) {
				val frame = value.jsonObject
				val x = frame.getValue("x").jsonPrimitive.float
				val y = frame.getValue("y").jsonPrimitive.float
				val actual = plan.evaluate(x, y)
				val raw = actual.rawDisplacements()
				val positions = actual.precisePositions()
				val expectedRaw = frame.getValue("raw").jsonArray
				val expectedPositions = frame.getValue("positions").jsonArray
				require(expectedRaw.size == raw.size && expectedPositions.size == positions.size)
				for (i in raw.indices) {
					val error = abs(raw[i] - expectedRaw[i].jsonPrimitive.double)
					if (error > rawError) {
						rawError = error
						worst = "${d["id"]}/${s["id"]} pose=$x,$y vertex=${i / 2} axis=${i % 2}"
					}
					positionError =
						max(
							positionError,
							abs(positions[i] - expectedPositions[i].jsonPrimitive.double),
						)
				}
				gainError =
					max(
						gainError,
						abs(actual.safetyGain - frame.getValue("gain").jsonPrimitive.double),
					)
				frames++
			}
		}
	}
	return InteriorParityResult(frames, rawError, positionError, gainError, worst)
}
