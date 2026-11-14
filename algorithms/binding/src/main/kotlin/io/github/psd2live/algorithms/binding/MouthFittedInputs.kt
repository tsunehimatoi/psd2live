package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierAlgorithmResource
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.pow

/** Source measurements and classification for fitted mouth binding. Mesh vertices come from the upstream rig. */
data class MouthFittedInputs(
    val source: MouthSourceContext?,
    val chin: Float,
    val variant: Int?,
    val closedMouth: Boolean,
    val separateInside: Boolean,
    val teethFade: Float,
    val layers: Map<String, Layer>,
    val maskSources: List<String>,
    val head: Head? = null,
    val shapeCarrierName: String = "{meshName} Shape",
    val turnCarrierName: String = "{meshName} Turn",
    val fallback: Fallback? = null,
    val sourceVertices: SourceVertices? = null,
) {
    init {
        require(chin.isFinite() && teethFade.isFinite())
        require(layers.size <= 20_000 && layers.keys.all { it.validIdentity() })
        require(maskSources.distinct().size == maskSources.size && maskSources.all {
            val layer = layers[it]
            layer != null && layer.tag in setOf("MOUTH", "MOUTH_OPEN") && layer.role == null && !layer.clipped
        }) { "Invalid fitted mouth mask source" }
        require(shapeCarrierName.length in 1..512 && turnCarrierName.length in 1..512)
        require(sourceVertices == null || sourceVertices.targets.keys.all { it in layers }) { "Unknown fitted mouth vertex target" }
    }

    /** References original scaffold coordinates using target and canonical mesh identities. */
    data class SourceVertices(val resource: ModifierAlgorithmResource, val targets: Map<String, SourceVertexTarget>)

    data class SourceVertexTarget(val canonical: String, val parent: String?) {
        init {
            require(canonical.validIdentity() && (parent == null || parent.validIdentity()))
        }
    }

    data class Layer(
        val tag: String,
        val role: String?,
        val side: String,
        val variant: Int?,
        val frame: Bounds,
        val bounds: Bounds,
        val centroidX: Float,
        val centroidY: Float,
        val opaque: Boolean,
        val clipped: Boolean,
        val preset: Boolean,
        val turnsWithHeadMesh: Boolean = false,
        val overridesDrawOrder: Boolean = false,
        val aperture: Bounds? = null,
    ) {
        init {
            require(tag in semanticTags && (role == null || role in semanticRoles) && Side.entries.any { it.name == side })
            require(frame.valid() && frame.width > 0f && frame.height > 0f && bounds.valid() && aperture?.valid() != false)
            require(centroidX.isFinite() && centroidY.isFinite())
        }
    }

    data class Fallback(
        val curve: MouthCurve,
        val exactClose: Boolean,
        val closedNarrow: Float,
        val formWiden: Float,
        val seam: Float,
        val smileBase: Float,
        val smile: Float,
    ) {
        init {
            require(listOf(closedNarrow, formWiden, seam, smileBase, smile).all(Float::isFinite))
        }

        fun point(sourceX: Float, sourceY: Float, aperture: Bounds, mouthForm: Float, mouthOpen: Float): Pair<Float, Float> {
            val open = mouthOpen.coerceIn(0f, 1f)
            val easedOpen = open * open * (3f - 2f * open)
            val form = mouthForm.coerceIn(-1f, 1f)
            val halfWidth = (aperture.width * 0.5f).coerceAtLeast(1e-4f)
            val normalizedX = ((sourceX - aperture.centerX) / halfWidth).coerceIn(-1.25f, 1.25f)
            val narrow = closedNarrow / 100f
            val horizontalScale = 1f - narrow + easedOpen * narrow + form * formWiden / 100f
            val targetX = aperture.centerX + (sourceX - aperture.centerX) * horizontalScale
            val seamY = aperture.top + aperture.height * seam / 100f
            val closedScale = if (exactClose) 0f else (1.25f / aperture.height.coerceAtLeast(1f)).coerceIn(0.018f, 0.12f)
            val verticalScale = closedScale + easedOpen * (1f - closedScale)
            val cornerWeight = abs(normalizedX).toDouble().pow(1.55).toFloat().coerceAtMost(1.35f)
            val expressionY = -form * aperture.height * (smileBase + cornerWeight * smile) / 100f * (0.72f + easedOpen * 0.28f)
            val presetY = curve.yAt((normalizedX + 1f) * 0.5f) * aperture.height * (1f - easedOpen)
            return targetX to (seamY + (sourceY - seamY) * verticalScale + expressionY + presetY)
        }
    }

    /** Immutable provider identity and the raw values from which its depth turn was constructed. */
    @kotlinx.serialization.Serializable
    data class Head(
        val provider: String,
        val centerX: Float,
        val centerY: Float,
        val radiusX: Float,
        val radiusY: Float,
        val turn: Float,
        val depth: Float,
        val chinY: Float,
    ) {
        init {
            require(provider.validIdentity() && radiusX > 0f && radiusY > 0f &&
                listOf(centerX, centerY, radiusX, radiusY, turn, depth, chinY).all(Float::isFinite))
        }

        fun rig(checkpoint: () -> Unit) = HeadTurn(centerX, centerY, radiusX, radiusY, turn, depth, chinY, checkpoint)
    }

    fun encode(): ModifierAlgorithmContext {
        val measurement = source?.encode()
        val resources = measurement?.resources.orEmpty() + sourceVertices?.resource?.let { mapOf(it.digest to it) }.orEmpty()
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", SCHEMA)
            measurement?.let { put("mouth", it.values) }
            put("chin", chin)
            variant?.let { put("variant", it) }
            put("closed", closedMouth)
            put("inside", separateInside)
            put("fade", teethFade)
            put("shapeName", shapeCarrierName)
            put("turnName", turnCarrierName)
            put("masks", JsonArray(maskSources.map(::JsonPrimitive)))
            sourceVertices?.let { source ->
                putJsonObject("sourceVertices") {
                    put("digest", source.resource.digest)
                    putJsonObject("targets") {
                        source.targets.forEach { (id, target) ->
                            putJsonObject(id) {
                                put("canonical", target.canonical)
                                put("parent", target.parent?.let(::JsonPrimitive) ?: JsonNull)
                            }
                        }
                    }
                }
            }
            fallback?.let { f ->
                putJsonObject("fallback") {
                    put("curve", JsonArray(f.curve.points.map { floats(it.x, it.y) }))
                    put("exact", f.exactClose)
                    put("values", floats(f.closedNarrow, f.formWiden, f.seam, f.smileBase, f.smile))
                }
            }
            head?.let { h ->
                putJsonObject("head") {
                    put("provider", h.provider)
                    put("values", floats(h.centerX, h.centerY, h.radiusX, h.radiusY, h.turn, h.depth, h.chinY))
                }
            }
            putJsonObject("layers") {
                layers.forEach { (id, layer) ->
                    putJsonObject(id) {
                        put("tag", layer.tag)
                        layer.role?.let { put("role", it) }
                        put("side", layer.side)
                        layer.variant?.let { put("variant", it) }
                        put("frame", layer.frame.json())
                        put("bounds", layer.bounds.json())
                        put("center", floats(layer.centroidX, layer.centroidY))
                        put("opaque", layer.opaque)
                        put("clipped", layer.clipped)
                        put("preset", layer.preset)
                        put("turn", layer.turnsWithHeadMesh)
                        put("orderOverride", layer.overridesDrawOrder)
                        layer.aperture?.let { put("aperture", it.json()) }
                    }
                }
            }
        }, resources)
    }

    companion object {
        const val SCHEMA = "mouth-fitted/1"
        private val semanticTags = """
            BACK_HAIR FRONT_HAIR HEADWEAR FACE FACE_DETAIL IRIDES EYEBROW EYEWHITE EYELASH EYE_CLOSE
            EYEWEAR EARS EARWEAR NOSE MOUTH MOUTH_OPEN MOUTH_CLOSE TOOTH_T TOOTH_B TONGUE NECK
            NECKWEAR TOPWEAR HANDWEAR BOTTOMWEAR LEGWEAR FOOTWEAR TAIL WINGS OBJECTS UNKNOWN
        """.trim().split(Regex("\\s+")).toSet()
        private val semanticRoles = """
            HIGHLIGHT PUPIL LOWER LID SHADOW UPPER_LIP LOWER_LIP UPPER_LINE LOWER_LINE BLUSH SIDE AHOGE TAIL
            BRAID ANIMAL RIBBON PENDANT RIGID SLEEVE SKIRT PLAIN
        """.trim().split(Regex("\\s+")).toSet()

        fun decode(context: ModifierAlgorithmContext): MouthFittedInputs {
            val value = context.values
            require(value["schema"] == JsonPrimitive(SCHEMA)) { "Missing fitted mouth source inputs" }
            require(value.keys.all { it in setOf("schema", "mouth", "chin", "variant", "closed", "inside", "fade", "shapeName", "turnName",
                "masks", "head", "fallback", "layers", "sourceVertices") })
            val source = value["mouth"]?.jsonObject?.let { measurement ->
                val digest = measurement.string("measurement")
                MouthSourceContext.decode(ModifierAlgorithmContext(measurement, mapOf(digest to context.resources.getValue(digest))))
            }
            val head = value["head"]?.jsonObject?.let { h ->
                require(h.keys == setOf("provider", "values"))
                val v = h.getValue("values").finiteFloats(7)
                Head(h.string("provider"), v[0], v[1], v[2], v[3], v[4], v[5], v[6])
            }
            val fallback = value["fallback"]?.jsonObject?.let { f ->
                require(f.keys == setOf("curve", "exact", "values"))
                val curve = MouthCurve(f.getValue("curve").jsonArray.map { point ->
                    val (x, y) = point.finiteFloats(2)
                    MouthCurvePoint(x, y)
                })
                val v = f.getValue("values").finiteFloats(5)
                Fallback(curve, f.flag("exact"), v[0], v[1], v[2], v[3], v[4])
            }
            val vertices = value["sourceVertices"]?.jsonObject?.let { v ->
                require(v.keys == setOf("digest", "targets"))
                val digest = v.string("digest")
                val resource = context.resources.getValue(digest)
                require(resource.digest == digest) { "Invalid fitted mouth source vertex digest" }
                val targets = v.getValue("targets").jsonObject.mapValues { (_, encoded) ->
                    val target = encoded.jsonObject
                    require(target.keys == setOf("canonical", "parent"))
                    SourceVertexTarget(target.string("canonical"), target.getValue("parent").jsonPrimitive.contentOrNull)
                }
                SourceVertices(resource, targets)
            }
            val layers = value.getValue("layers").jsonObject.mapValues { (_, encoded) ->
                val layer = encoded.jsonObject
                require(layer.keys.all { it in setOf("tag", "role", "side", "variant", "frame", "bounds", "center", "opaque",
                    "clipped", "preset", "turn", "orderOverride", "aperture") })
                val center = layer.getValue("center").finiteFloats(2)
                Layer(layer.string("tag"), layer["role"]?.jsonPrimitive?.content, layer.string("side"),
                    layer["variant"]?.jsonPrimitive?.int, layer.getValue("frame").bounds(),
                    layer.getValue("bounds").bounds(), center[0], center[1], layer.flag("opaque"),
                    layer.flag("clipped"), layer.flag("preset"), layer.flag("turn"), layer.flag("orderOverride"),
                    layer["aperture"]?.bounds())
            }
            return MouthFittedInputs(source, value.number("chin"), value["variant"]?.jsonPrimitive?.int, value.flag("closed"),
                value.flag("inside"), value.number("fade"), layers, value.getValue("masks").jsonArray.map { it.jsonPrimitive.content },
                head, value.string("shapeName"), value.string("turnName"), fallback, vertices).also {
                if (head != null && context.upstream.isNotEmpty()) require(context.upstream.any { capability ->
                    capability.instanceId == head.provider &&
                        (capability.capability in setOf("face/fitted", "face.depth-turn") ||
                            capability.type in setOf("face/fitted", "face.depth-turn", "face"))
                }) { "Missing fitted mouth head-turn provider" }
            }
        }

        private fun String.validIdentity() = isNotBlank() && length <= 512 && none(Char::isISOControl)
        private fun Bounds.valid() = listOf(left, top, right, bottom, width, height).all(Float::isFinite) &&
            width >= 0f && height >= 0f
        private fun floats(vararg values: Float) = JsonArray(values.map(::JsonPrimitive))
        private fun Bounds.json() = floats(left, top, right, bottom)
        private fun JsonElement.finiteFloats(size: Int) = jsonArray.map { it.jsonPrimitive.float }.also {
            require(it.size == size && it.all(Float::isFinite)) { "Invalid fitted mouth source coordinates" }
        }
        private fun JsonElement.bounds(): Bounds = finiteFloats(4).let { Bounds(it[0], it[1], it[2], it[3]) }
        private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
        private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.boolean
        private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.float.also { require(it.isFinite()) }
    }
}
