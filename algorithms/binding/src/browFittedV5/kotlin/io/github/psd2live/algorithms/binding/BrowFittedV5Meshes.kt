package io.github.psd2live.algorithms.binding

import kotlinx.serialization.json.*
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Native local mesh tracks before the common lowering pass, for the source-only brow v5 prefix.
 * All materials are PRESET layers, the project has a closed mouth, and no eye/brow/mouth module or
 * mouth outline is enabled. An empty [build] brow map is the ordinary before phase.
 */
object BrowFittedV5Meshes {
    data class Material(
        val id: String,
        val sourceId: String,
        val name: String,
        val tag: String,
        val role: String?,
        val side: Side,
        val drawOrder: Float,
        val opacity: Float,
        val geometry: JsonObject,
    )

    private val mouthForm = ParameterId("ParamMouthForm")
    private val mouthOpen = ParameterId("ParamMouthOpenY")
    private val jelly = ParameterId("ParamEyeBallForm")
    private val leftOpen = ParameterId("ParamEyeLOpen")
    private val rightOpen = ParameterId("ParamEyeROpen")
    private val zeroTags = setOf(
        "BACK_HAIR", "FRONT_HAIR", "HEADWEAR", "FACE", "FACE_DETAIL", "EYE_CLOSE", "EYEWEAR",
        "EARS", "EARWEAR", "NOSE", "MOUTH_CLOSE", "TOOTH_T", "TOOTH_B", "TONGUE", "NECK",
        "NECKWEAR", "TOPWEAR", "HANDWEAR", "BOTTOMWEAR", "LEGWEAR", "FOOTWEAR", "TAIL",
        "WINGS", "OBJECTS", "UNKNOWN",
    )

    fun build(
        raw: ScaffoldMesh.RawMesh,
        parent: ScaffoldArms.Parent,
        material: Material,
        eyes: FittedEyeMotion,
        brows: Map<Side, BrowRig>,
        faceCenterX: Float,
        teethFade: Float = 15f,
        checkpoint: () -> Unit = {},
    ): Drawable {
        checkpoint()
        require(material.id.isNotBlank() && material.sourceId.isNotBlank() &&
            material.drawOrder.isFinite() && material.opacity.isFinite() &&
            faceCenterX.isFinite() && teethFade.isFinite()) { "Invalid brow v5 material measurements" }
        val frame = validateBounds(parent.frame)
        require(frame.width > 0f && frame.height > 0f) { "Brow v5 requires a nonempty parent frame" }
        val options = eyes.options
        require(!options.smile && !options.wide && !options.gaze && !options.emotion && !options.lidFade &&
            !options.aftershock && !options.highlight && !options.squash && !options.browCoupling &&
            !options.cheekPush) { "Brow v5 local meshes do not support eye modules" }
        require(Side.NONE !in eyes.rigs && Side.NONE !in brows) { "Brow v5 requires sided fitted features" }
        val geometry = decode(material.geometry)
        require(when (material.tag) {
            "EYEWHITE" -> geometry is ScaffoldMesh.Geometry.Eye && !geometry.lash
            "EYELASH" -> geometry is ScaffoldMesh.Geometry.Eye && geometry.lash
            "IRIDES" -> geometry is ScaffoldMesh.Geometry.Iris
            "EYEBROW" -> geometry is ScaffoldMesh.Geometry.Brow
            "MOUTH", "MOUTH_OPEN" -> geometry is ScaffoldMesh.Geometry.Mouth
            in zeroTags -> geometry == ScaffoldMesh.Geometry.Zero
            else -> false
        }) { "Missing or unsupported brow v5 geometry for ${material.id}: ${material.tag}" }
        if (material.tag in setOf("EYEWHITE", "EYELASH", "IRIDES", "EYE_CLOSE", "EYEBROW"))
            require(material.side != Side.NONE) { "Brow v5 does not support unsided eye or brow material" }
        validateRaw(raw, checkpoint)
        val points = raw.rigPositions
        val width = frame.width.coerceAtLeast(1e-4f)
        val height = frame.height.coerceAtLeast(1e-4f)
        val positions = FloatArray(points.size)
        for (i in points.indices step 2) {
            checkpoint()
            positions[i] = (points[i] - frame.left) / width
            positions[i + 1] = (points[i + 1] - frame.top) / height
        }
        val eye = eyes.rigs[material.side]
        val grid = when (material.tag) {
            "EYEWHITE", "EYELASH" -> if (eye != null) EyeModules.lidGrid(
                requireNotNull(EyeModules.meshOf(material.tag, material.role)), material.side,
                points, frame, eye, options, material.sourceId,
            ) else closure(points, frame, material.side, geometry as ScaffoldMesh.Geometry.Eye, faceCenterX, checkpoint)
            "EYE_CLOSE" -> eye?.let { EyeModules.closedLineGrid(material.side, points, frame, it, options) }
                ?: zero(points.size)
            "IRIDES" -> {
                val iris = geometry as ScaffoldMesh.Geometry.Iris
                eye?.let { EyeModules.irisGrid(material.side, points, frame, bounds(points), it, options) { x, y, value ->
                    irisPoint(x, y, value, iris)
                } } ?: oneDim(jelly, floatArrayOf(-1f, 0f, 1f), checkpoint) { value ->
                    deltas(points, frame, checkpoint) { x, y -> irisPoint(x, y, value, iris) }
                }
            }
            "EYEBROW" -> {
                val parameter = ParameterId(if (material.side == Side.LEFT) "ParamBrowLY" else "ParamBrowRY")
                val brow = brows[material.side]?.takeIf { material.role == null }
                if (brow != null) {
                    val browOptions = brow.cacheKey.last() as? BrowOptions
                    require(browOptions != null && !browOptions.shape && !browOptions.blink && !browOptions.spring) {
                        "Brow v5 local meshes do not support brow modules"
                    }
                    brow.grid(points, frame, parameter,
                        ParameterId(if (material.side == Side.LEFT) "ParamBrowLAngle" else "ParamBrowRAngle"))
                } else {
                    val fallback = geometry as ScaffoldMesh.Geometry.Brow
                    oneDim(parameter, floatArrayOf(-1f, 0f, 1f), checkpoint) { value ->
                        val delta = FloatArray(points.size)
                        // Do not clamp this denominator or replace the -0f produced by the neutral key.
                        val dy = -value * fallback.height * fallback.lift / 100f / frame.height
                        for (i in 1 until delta.size step 2) delta[i] = dy
                        MeshDeltaForm(delta)
                    }
                }
            }
            "MOUTH", "MOUTH_OPEN" -> mouth(points, frame, geometry as ScaffoldMesh.Geometry.Mouth, checkpoint)
            else -> zero(points.size)
        }
        checkpoint()
        return Drawable(
            id = DrawableId(material.id), name = material.name, parentDeformerId = parent.id,
            blendMode = BlendMode.Normal, maskedBy = emptyList(),
            mesh = DrawableMesh(positions, raw.atlasUvs, raw.triangles), geometryGrid = grid,
            channelGrids = channels(material, eye, teethFade, checkpoint),
            drawOrder = material.drawOrder.coerceIn(0f, 1000f), opacity = material.opacity.coerceIn(0f, 1f),
        )
    }

    private fun channels(material: Material, eye: EyeRig?, teethFade: Float, checkpoint: () -> Unit): ChannelGrids {
        val swap = eye?.fit?.closed != null && (material.tag == "EYE_CLOSE" ||
            material.tag == "EYELASH" && (material.role == null || material.role == "LOWER"))
        val fade = (teethFade / 100f).coerceAtLeast(0.01f)
        val opacity = when (material.tag) {
            "EYE_CLOSE" -> EyeModules.drawingOpacity(material.side, true, swap, 1f)
            "EYELASH" -> if (swap) EyeModules.drawingOpacity(material.side, false, true, 1f) else null
            "MOUTH_CLOSE" -> scalar(mouthOpen, floatArrayOf(0f, 1f), checkpoint) { 1f - it }
            "MOUTH_OPEN", "TONGUE", "TOOTH_T", "TOOTH_B" ->
                scalar(mouthOpen, floatArrayOf(0f, fade, 1f), checkpoint) { (it / fade).coerceIn(0f, 1f) }
            else -> null
        }
        // Native ordinary tracks contain their absolute values, not values scaled by source opacity.
        // The source opacity remains the drawable's static fallback, including for unkeyed materials.
        return opacity?.let { ChannelGrids(mapOf(FormChannel.OPACITY to it)) } ?: ChannelGrids.Empty
    }

    private fun closure(points: FloatArray, frame: Bounds, side: Side, eye: ScaffoldMesh.Geometry.Eye,
                        faceCenterX: Float, checkpoint: () -> Unit): KeyformGrid<MeshDeltaForm> {
        val keys = floatArrayOf(0f, 1f)
        val axes = if (side == Side.NONE) listOf(KeyformAxis(leftOpen, keys), KeyformAxis(rightOpen, keys))
            else listOf(KeyformAxis(if (side == Side.LEFT) leftOpen else rightOpen, keys))
        return grid(axes, checkpoint) { values ->
            val delta = FloatArray(points.size)
            for (i in points.indices step 2) {
                checkpoint()
                val x = points[i]
                val y = points[i + 1]
                val openness = if (side == Side.NONE && x < faceCenterX) values[1] else values[0]
                val closed = eyePoint(x, y, eye)
                delta[i] = (closed.first - x) / frame.width.coerceAtLeast(1e-4f) * (1f - openness)
                delta[i + 1] = (closed.second - y) / frame.height.coerceAtLeast(1e-4f) * (1f - openness)
            }
            MeshDeltaForm(delta)
        }
    }

    private fun eyePoint(x: Float, y: Float, eye: ScaffoldMesh.Geometry.Eye): Pair<Float, Float> {
        val white = eye.whiteBounds.minByOrNull { abs(it.centerX - x) } ?: eye.bounds
        val halfWidth = (white.width * 0.5f).coerceAtLeast(1e-4f)
        val normalizedX = ((x - white.centerX) / halfWidth).coerceIn(-1f, 1f)
        val arch = max(0f, 1f - normalizedX * normalizedX)
        val edgeY = white.top + white.height * eye.blinkCorner / 100f
        val curveY = edgeY + max(1.5f, white.height * eye.blinkDepth / 100f) * arch
        val layerHeight = eye.bounds.height.coerceAtLeast(1f)
        val verticalScale = if (eye.lash) eye.eyelashSquash / 100f else (1.2f / layerHeight).coerceIn(0.015f, 0.55f)
        val anchorY = eye.centerline?.let { centerline ->
            val sourceX = (x - eye.sourceLeft).coerceIn(0f, (centerline.size - 1).toFloat())
            val left = sourceX.toInt()
            val right = (left + 1).coerceAtMost(centerline.lastIndex)
            centerline[left] + (centerline[right] - centerline[left]) * (sourceX - left)
        } ?: eye.centroidY
        return x to curveY + (y - anchorY) * verticalScale
    }

    private fun irisPoint(x: Float, y: Float, value: Float, iris: ScaffoldMesh.Geometry.Iris): Pair<Float, Float> {
        val amount = value.coerceIn(-1f, 1f)
        val scaleX = 1f - amount * iris.jellySquash / 100f
        val scaleY = 1f + amount * iris.jellyStretch / 100f
        return iris.centroidX + (x - iris.centroidX) * scaleX to iris.centroidY + (y - iris.centroidY) * scaleY
    }

    private fun mouth(points: FloatArray, frame: Bounds, mouth: ScaffoldMesh.Geometry.Mouth,
                      checkpoint: () -> Unit): KeyformGrid<MeshDeltaForm> {
        // The captured curve is already the native effective preset/custom curve.
        val curve = MouthCurve(mouth.curve.map { MouthCurvePoint(it.x, it.y) })
        return grid(listOf(KeyformAxis(mouthForm, floatArrayOf(-1f, 0f, 1f)),
            KeyformAxis(mouthOpen, floatArrayOf(0f, 0.5f, 1f))), checkpoint) { values ->
            deltas(points, frame, checkpoint) { x, y -> mouthPoint(x, y, mouth, curve, values[0], values[1]) }
        }
    }

    private fun mouthPoint(x: Float, y: Float, mouth: ScaffoldMesh.Geometry.Mouth, curve: MouthCurve,
                           formValue: Float, openValue: Float): Pair<Float, Float> {
        val aperture = mouth.aperture
        val open = openValue.coerceIn(0f, 1f)
        val easedOpen = open * open * (3f - 2f * open)
        val form = formValue.coerceIn(-1f, 1f)
        val halfWidth = (aperture.width * 0.5f).coerceAtLeast(1e-4f)
        val normalizedX = ((x - aperture.centerX) / halfWidth).coerceIn(-1.25f, 1.25f)
        val narrow = mouth.narrow / 100f
        val horizontalScale = 1f - narrow + easedOpen * narrow + form * mouth.widen / 100f
        val targetX = aperture.centerX + (x - aperture.centerX) * horizontalScale
        val seamY = aperture.top + aperture.height * mouth.seam / 100f
        val closedScale = (1.25f / aperture.height.coerceAtLeast(1f)).coerceIn(0.018f, 0.12f)
        val verticalScale = closedScale + easedOpen * (1f - closedScale)
        val cornerWeight = abs(normalizedX).toDouble().pow(1.55).toFloat().coerceAtMost(1.35f)
        val expressionY = -form * aperture.height * (mouth.smileBase + cornerWeight * mouth.smile) / 100f * (0.72f + easedOpen * 0.28f)
        val presetY = curve.yAt((normalizedX + 1f) * 0.5f) * aperture.height * (1f - easedOpen)
        val targetY = seamY + (y - seamY) * verticalScale + expressionY + presetY
        return targetX to targetY
    }

    private fun deltas(points: FloatArray, frame: Bounds, checkpoint: () -> Unit,
                       point: (Float, Float) -> Pair<Float, Float>): MeshDeltaForm {
        val delta = FloatArray(points.size)
        for (i in points.indices step 2) {
            checkpoint()
            val target = point(points[i], points[i + 1])
            delta[i] = (target.first - points[i]) / frame.width.coerceAtLeast(1e-4f)
            delta[i + 1] = (target.second - points[i + 1]) / frame.height.coerceAtLeast(1e-4f)
        }
        return MeshDeltaForm(delta)
    }

    private fun zero(size: Int) = KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), MeshDeltaForm(FloatArray(size)))))

    private fun scalar(parameter: ParameterId, keys: FloatArray, checkpoint: () -> Unit,
                       value: (Float) -> Float): KeyformGrid<ChannelValue> =
        oneDim(parameter, keys, checkpoint) { ChannelValue.Scalar(value(it)) }

    private fun <T> oneDim(parameter: ParameterId, keys: FloatArray, checkpoint: () -> Unit,
                          form: (Float) -> T): KeyformGrid<T> =
        KeyformGrid(listOf(KeyformAxis(parameter, keys)), keys.indices.map { index ->
            checkpoint()
            KeyformCell(intArrayOf(index), form(keys[index]))
        })

    /** Native ordinary grids enumerate the last axis fastest; fitted eye/brow grids own their ordering. */
    private fun <T> grid(axes: List<KeyformAxis>, checkpoint: () -> Unit, form: (FloatArray) -> T): KeyformGrid<T> {
        val cells = mutableListOf<KeyformCell<T>>()
        fun visit(axisIndex: Int, coordinate: IntArray, values: FloatArray) {
            checkpoint()
            if (axisIndex == axes.size) {
                cells += KeyformCell(coordinate.copyOf(), form(values.copyOf()))
                return
            }
            for (keyIndex in axes[axisIndex].keys.indices) {
                coordinate[axisIndex] = keyIndex
                values[axisIndex] = axes[axisIndex].keys[keyIndex]
                visit(axisIndex + 1, coordinate, values)
            }
        }
        visit(0, IntArray(axes.size), FloatArray(axes.size))
        return KeyformGrid(axes, cells)
    }

    private fun bounds(points: FloatArray): Bounds {
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (i in points.indices step 2) {
            left = minOf(left, points[i]); right = maxOf(right, points[i])
            top = minOf(top, points[i + 1]); bottom = maxOf(bottom, points[i + 1])
        }
        return Bounds(left, top, right, bottom)
    }

    private fun validateRaw(raw: ScaffoldMesh.RawMesh, checkpoint: () -> Unit) {
        val size = raw.rigPositions.size
        require(size > 0 && size % 2 == 0 && raw.atlasUvs.size == size && raw.triangles.size % 3 == 0) {
            "Invalid brow v5 source topology"
        }
        for (i in 0 until size) {
            if (i % 1024 == 0) checkpoint()
            require(raw.rigPositions[i].isFinite() && raw.atlasUvs[i].isFinite()) { "Non-finite brow v5 source coordinate" }
        }
        for (i in raw.triangles.indices) {
            if (i % 1024 == 0) checkpoint()
            require(raw.triangles[i] in 0 until size / 2) { "Brow v5 source triangle references an unknown vertex" }
        }
    }

    /** The scaffold's strict measurement decoder is private; retain its schema here without widening it. */
    private fun decode(geometry: JsonObject): ScaffoldMesh.Geometry {
        fun check(vararg keys: String) = fields(geometry, "kind", *keys)
        fun field(name: String) = geometry.getValue(name)
        fun float(name: String) = readFloat(field(name))
        return when (string(requireNotNull(geometry["kind"]) { "Missing scaffold mesh geometry kind" })) {
            "zero" -> {
                check()
                ScaffoldMesh.Geometry.Zero
            }
            "eye" -> {
                check("lash", "bounds", "white_bounds", "centroid_y", "source_left", "centerline",
                    "blink_corner", "blink_depth", "eyelash_squash")
                val centerline = field("centerline").takeUnless { it == JsonNull }?.let {
                    array(it).map(::readFloat).toFloatArray().also { points ->
                        require(points.isNotEmpty()) { "Empty scaffold eye centerline" }
                    }
                }
                ScaffoldMesh.Geometry.Eye(
                    requireNotNull(literal(field("lash")).booleanOrNull) { "Invalid scaffold eye type" },
                    readBounds(field("bounds")), array(field("white_bounds")).map(::readBounds), float("centroid_y"),
                    requireNotNull(literal(field("source_left")).intOrNull) { "Invalid scaffold eye origin" },
                    centerline, float("blink_corner"), float("blink_depth"), float("eyelash_squash"),
                )
            }
            "iris" -> {
                check("centroid_x", "centroid_y", "jelly_squash", "jelly_stretch")
                ScaffoldMesh.Geometry.Iris(float("centroid_x"), float("centroid_y"), float("jelly_squash"), float("jelly_stretch"))
            }
            "brow" -> {
                check("height", "lift")
                ScaffoldMesh.Geometry.Brow(float("height"), float("lift"))
            }
            "mouth" -> {
                check("aperture", "curve", "narrow", "seam", "smile_base", "smile", "widen")
                val curve = array(field("curve")).map {
                    val (x, y) = tuple(it, 2)
                    ScaffoldMesh.CurvePoint(readFloat(x), readFloat(y))
                }
                ScaffoldMesh.Geometry.Mouth(readBounds(field("aperture")), curve,
                    float("narrow"), float("seam"), float("smile_base"), float("smile"), float("widen"))
            }
            else -> throw IllegalArgumentException("Unknown scaffold mesh geometry kind")
        }
    }

    private fun readBounds(value: JsonElement): Bounds = tuple(value, 4).let {
        validateBounds(Bounds(readFloat(it[0]), readFloat(it[1]), readFloat(it[2]), readFloat(it[3])))
    }

    private fun validateBounds(value: Bounds): Bounds = value.also {
        require(it.width.isFinite() && it.height.isFinite() && it.width >= 0f && it.height >= 0f) {
            "Invalid scaffold mesh measurement bounds"
        }
    }

    private fun fields(value: JsonElement, vararg keys: String): JsonObject {
        require(value is JsonObject && value.keys == keys.toSet()) { "Invalid scaffold mesh fields" }
        return value
    }

    private fun array(value: JsonElement): JsonArray =
        requireNotNull(value as? JsonArray) { "Expected a scaffold mesh array" }

    private fun tuple(value: JsonElement, size: Int): JsonArray = array(value).also {
        require(it.size == size) { "Invalid scaffold mesh coordinate count" }
    }

    private fun string(value: JsonElement): String =
        requireNotNull((value as? JsonPrimitive)?.takeIf { it.isString }) { "Expected scaffold mesh text" }.content

    private fun literal(value: JsonElement): JsonPrimitive =
        requireNotNull((value as? JsonPrimitive)?.takeUnless { it.isString }) { "Expected scaffold mesh literal" }

    private fun readFloat(value: JsonElement): Float =
        requireNotNull(literal(value).floatOrNull?.takeIf(Float::isFinite)) { "Expected finite scaffold mesh measurement" }
}
