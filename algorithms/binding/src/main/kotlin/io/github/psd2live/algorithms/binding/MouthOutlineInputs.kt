package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private val topologies: Map<String, ModifierAlgorithmResource> = emptyMap(),
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
        val sourcePositions: List<Float> = emptyList(),
        val sourceTriangles: List<Int> = emptyList(),
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
        val topology: String? = null,
    ) {
        init {
            require(canonical.isNotBlank() && frame.right > frame.left && frame.bottom > frame.top)
            if (topology == null) {
                require(validSize(sourcePositions.size, sourceTriangles.size) && sourcePositions.all(Float::isFinite) &&
                    sourceTriangles.all { it in 0 until sourcePositions.size / 2 }) { "Invalid inline outline topology" }
            } else require(sourcePositions.isEmpty() && sourceTriangles.isEmpty()) { "Mixed outline topology encodings" }
            require(rasterColumns.isEmpty() || rasterColumns.size == MouthContour.DEFAULT_SEGMENTS + 1)
            require(rasterColumns.all { it.top.first.isFinite() && it.top.second.isFinite() &&
                it.bottom.first.isFinite() && it.bottom.second.isFinite() })
            require(sides.all { it == 0 || it == 1 })
        }

        fun toCanvas(x: Float, y: Float) = alignment?.toCanvas(x, y) ?: (x to y)
        fun rasterToCanvas(x: Float, y: Float) = rasterAlignment?.toCanvas(x, y) ?: (x to y)
    }

    class Topology(val positions: FloatArray, val triangles: IntArray) {
        init {
            require(validSize(positions.size, triangles.size) && positions.all(Float::isFinite) &&
                triangles.all { it in 0 until positions.size / 2 }) { "Invalid outline topology" }
        }

        fun resource(): ModifierAlgorithmResource {
            val bytes = ByteBuffer.allocate(8 + (positions.size + triangles.size) * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.putInt(positions.size).putInt(triangles.size)
            positions.forEach { bytes.putFloat(it) }
            triangles.forEach { bytes.putInt(it) }
            return ModifierAlgorithmResource(bytes.array())
        }

        companion object {
            fun decode(resource: ModifierAlgorithmResource, checkpoint: () -> Unit): Topology {
                val bytes = ByteBuffer.wrap(resource.read()).order(ByteOrder.LITTLE_ENDIAN)
                require(bytes.remaining() >= 8) { "Truncated outline topology" }
                val positionCount = bytes.int
                val indexCount = bytes.int
                require(validSize(positionCount, indexCount) && bytes.remaining() == (positionCount + indexCount) * 4) {
                    "Invalid outline topology length"
                }
                val positions = FloatArray(positionCount) { index ->
                    if (index % 1024 == 0) checkpoint()
                    bytes.float
                }
                val triangles = IntArray(indexCount) { index ->
                    if (index % 1024 == 0) checkpoint()
                    bytes.int
                }
                return Topology(positions, triangles)
            }
        }
    }

    init {
        require(alphaThreshold in 0..255)
        require(targets.keys == artwork.targets.keys)
        require(targets.values.none { it.fittedProvider != null } || fitted != null)
        require(targets.values.none { it.headOnMesh } || head != null)
        require((topologies.isEmpty() || targets.values.all { it.topology != null }) &&
            topologies.keys == targets.values.mapNotNullTo(hashSetOf()) { it.topology } &&
            topologies.all { (id, resource) -> id == resource.digest }) { "Invalid outline topology resources" }
        require(listOf(tuning.closedNarrow, tuning.formWiden, tuning.seam, tuning.smileBase, tuning.smile).all(Float::isFinite))
    }

    fun topology(target: Target, checkpoint: () -> Unit): Topology = target.topology?.let {
        Topology.decode(topologies.getValue(it), checkpoint)
    } ?: Topology(target.sourcePositions.toFloatArray(), target.sourceTriangles.toIntArray())

    fun encode(): ModifierAlgorithmContext {
        val source = artwork.context()
        val mouth = fitted?.encode()
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", if (targets.values.all { it.topology != null }) SCHEMA else LEGACY_SCHEMA)
            put("artwork", source.values)
            put("targets", Json.encodeToJsonElement(targets))
            put("alpha", alphaThreshold)
            put("curve", Json.encodeToJsonElement(curve.points.map { it.x to it.y }))
            put("tuning", Json.encodeToJsonElement(tuning))
            mouth?.let { put("mouth", it.values) }
            head?.let { put("head", Json.encodeToJsonElement(it)) }
        }, source.resources + mouth?.resources.orEmpty() + topologies)
    }

    companion object {
        const val SCHEMA = "mouth-outline/2"
        private const val LEGACY_SCHEMA = "mouth-outline/1"

        private fun validSize(positions: Int, indices: Int) =
            positions in 6..200_000 && positions % 2 == 0 && indices in 3..600_000 && indices % 3 == 0

        fun decode(context: ModifierAlgorithmContext): MouthOutlineInputs {
            val values = context.values
            val required = setOf("schema", "artwork", "targets", "alpha", "curve", "tuning")
            val schema = values["schema"]?.jsonPrimitive?.content
            require(schema in setOf(SCHEMA, LEGACY_SCHEMA) && values.keys.containsAll(required) &&
                (values.keys - required).all { it == "mouth" || it == "head" }) { "Invalid mouth outline schema" }
            val artwork = values.getValue("artwork").jsonObject
            val rasters = Json.decodeFromJsonElement<List<ModifierSourceLayer>>(artwork.getValue("layers")).mapTo(hashSetOf()) { it.rgba }
            val targets = Json.decodeFromJsonElement<Map<String, Target>>(values.getValue("targets"))
            require(targets.values.all { (it.topology != null) == (schema == SCHEMA) }) { "Outline topology does not match its schema" }
            val topologies = targets.values.mapNotNullTo(hashSetOf()) { it.topology }
            val mouth = values["mouth"]?.jsonObject
            val measurement = mouth?.getValue("measurement")?.jsonPrimitive?.content
            require(context.resources.keys == rasters + listOfNotNull(measurement) + topologies) { "Invalid outline resources" }
            return MouthOutlineInputs(
                ModifierSourceInputs.from(ModifierAlgorithmContext(artwork, context.resources.filterKeys { it in rasters })),
                targets, values.getValue("alpha").jsonPrimitive.int,
                MouthCurve(Json.decodeFromJsonElement<List<Pair<Float, Float>>>(values.getValue("curve")).map { MouthCurvePoint(it.first, it.second) }),
                Json.decodeFromJsonElement(values.getValue("tuning")),
                mouth?.let { MouthSourceContext.decode(ModifierAlgorithmContext(it, mapOf(measurement!! to context.resources.getValue(measurement)))) },
                values["head"]?.let { Json.decodeFromJsonElement<MouthFittedInputs.Head>(it) },
                context.resources.filterKeys { it in topologies },
            )
        }
    }
}
