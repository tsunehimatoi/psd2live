package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** All geometric measurements are in aligned canvas pixels. Source artwork remains in PSD canvas pixels. */
class MouthOutlineInputs(
    val artwork: ModifierSourceInputs,
    val targets: Map<String, Target>,
    val alphaThreshold: Int,
    val curve: MouthCurve,
    val tuning: MouthOutlineMotion.Tuning,
    val fitted: MouthSourceContext? = null,
    val head: MouthFittedInputs.Head? = null,
) {
    @Serializable
    data class Alignment(val degrees: Float, val pivotX: Float, val pivotY: Float) {
        init { require(listOf(degrees, pivotX, pivotY).all(Float::isFinite)) }
        private val radians = degrees * PI.toFloat() / 180f
        private val cosine = cos(radians)
        private val sine = sin(radians)

        fun toCanvas(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - pivotX
            val dy = y - pivotY
            return (pivotX + cosine * dx - sine * dy) to (pivotY + sine * dx + cosine * dy)
        }
    }

    @Serializable
    data class Target(
        val canonical: String,
        val frame: ModifierSourceBounds,
        val aperture: ModifierSourceBounds,
        val alignment: Alignment? = null,
        val sourcePositions: List<Float>,
        val sourceTriangles: List<Int>,
        /** Columns measured from the denser source raster trace used only for artwork. */
        val rasterColumns: List<MouthColumn>,
        val upperName: String,
        val lowerName: String,
        val groupPath: String,
        val sourceVisible: Boolean,
        val sides: Set<Int> = setOf(0, 1),
        val fittedProvider: String? = null,
        val headOnMesh: Boolean = false,
        val contourOwner: Boolean = true,
        val rasterAlignment: Alignment? = alignment,
    ) {
        init {
            require(canonical.isNotBlank() && frame.right > frame.left && frame.bottom > frame.top)
            require(sourcePositions.size in 6..200_000 && sourcePositions.size % 2 == 0 &&
                sourcePositions.all(Float::isFinite))
            require(sourceTriangles.size in 3..600_000 && sourceTriangles.size % 3 == 0 &&
                sourceTriangles.all { it in 0 until sourcePositions.size / 2 })
            require(rasterColumns.isEmpty() || rasterColumns.size == MouthContour.DEFAULT_SEGMENTS + 1)
            require(rasterColumns.all { it.top.first.isFinite() && it.top.second.isFinite() &&
                it.bottom.first.isFinite() && it.bottom.second.isFinite() })
            require(sides.all { it == 0 || it == 1 })
        }

        fun toCanvas(x: Float, y: Float) = alignment?.toCanvas(x, y) ?: (x to y)
        fun rasterToCanvas(x: Float, y: Float) = rasterAlignment?.toCanvas(x, y) ?: (x to y)
    }

    init {
        require(alphaThreshold in 0..255)
        require(targets.keys == artwork.targets.keys)
        require(targets.values.none { it.fittedProvider != null } || fitted != null)
        require(targets.values.none { it.headOnMesh } || head != null)
        require(listOf(tuning.closedNarrow, tuning.formWiden, tuning.seam, tuning.smileBase, tuning.smile).all(Float::isFinite))
    }

    fun encode(): ModifierAlgorithmContext {
        val source = artwork.context()
        val mouth = fitted?.encode()
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", SCHEMA)
            put("artwork", source.values)
            put("targets", Json.encodeToJsonElement(targets))
            put("alpha", alphaThreshold)
            put("curve", Json.encodeToJsonElement(curve.points.map { it.x to it.y }))
            put("tuning", Json.encodeToJsonElement(tuning))
            mouth?.let { put("mouth", it.values) }
            head?.let { put("head", Json.encodeToJsonElement(it)) }
        }, source.resources + mouth?.resources.orEmpty())
    }

    companion object {
        const val SCHEMA = "mouth-outline/1"

        fun decode(context: ModifierAlgorithmContext): MouthOutlineInputs {
            val values = context.values
            val required = setOf("schema", "artwork", "targets", "alpha", "curve", "tuning")
            require(values["schema"] == JsonPrimitive(SCHEMA) && values.keys.containsAll(required) &&
                (values.keys - required).all { it == "mouth" || it == "head" }) { "Invalid mouth outline schema" }
            val artwork = values.getValue("artwork").jsonObject
            val rasters = Json.decodeFromJsonElement<List<ModifierSourceLayer>>(artwork.getValue("layers")).mapTo(hashSetOf()) { it.rgba }
            val mouth = values["mouth"]?.jsonObject
            val measurement = mouth?.getValue("measurement")?.jsonPrimitive?.content
            require(context.resources.keys == rasters + listOfNotNull(measurement)) { "Invalid outline resources" }
            return MouthOutlineInputs(
                ModifierSourceInputs.from(ModifierAlgorithmContext(artwork, context.resources.filterKeys { it in rasters })),
                Json.decodeFromJsonElement(values.getValue("targets")), values.getValue("alpha").jsonPrimitive.int,
                MouthCurve(Json.decodeFromJsonElement<List<Pair<Float, Float>>>(values.getValue("curve")).map { MouthCurvePoint(it.first, it.second) }),
                Json.decodeFromJsonElement(values.getValue("tuning")),
                mouth?.let { MouthSourceContext.decode(ModifierAlgorithmContext(it, mapOf(measurement!! to context.resources.getValue(measurement)))) },
                values["head"]?.let { Json.decodeFromJsonElement<MouthFittedInputs.Head>(it) },
            )
        }
    }
}
