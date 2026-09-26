package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.edit.withParameterCreated
import org.umamo.edit.withParameterKeys
import org.umamo.render.eval.DeformerWorld
import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.keyform.isDense
import org.umamo.runtime.model.*
import kotlin.math.abs
import kotlin.math.hypot

/** Which screen direction the tip travels: left/right, or up/down. */
enum class SwingKind { LATERAL, VERTICAL }

/**
 * The lattice edge that stays pinned. Edges are lattice rows/columns (row 0 is the top edge), so the
 * choice follows the warp even when its parent space is rotated or y-flipped.
 */
enum class SwingFulcrum { AUTO, TOP, BOTTOM, LEFT, RIGHT }

/** Starting values for the sliders and the pendulum; only the defaults differ. */
enum class SwingPreset { HAIR, ACCESSORY, CLOTH }

/** The pendulum that drives a swing. One vertex per segment; outputs are relative segment angles. */
data class SwingPhysics(
    val length: Float = 10f,
    val mobility: Float = 0.9f,
    val delay: Float = 0.9f,
    val acceleration: Float = 1.2f,
    val outputScale: Float = 1.5f,
) {
    init {
        require(listOf(length, mobility, delay, acceleration, outputScale).all(Float::isFinite)) { "Swing physics values must be finite" }
        require(length > 0f && mobility in 0f..1f && delay > 0f && acceleration >= 0f) { "Swing physics values out of range" }
    }

    fun toJson() = buildJsonObject {
        put("length", length); put("mobility", mobility); put("delay", delay)
        put("acceleration", acceleration); put("output_scale", outputScale)
    }

    companion object {
        fun fromJson(o: JsonObject) = SwingPhysics(o.number("length", 10f), o.number("mobility", .9f),
            o.number("delay", .9f), o.number("acceleration", 1.2f), o.number("output_scale", 1.5f))
    }
}

/**
 * A regenerating sway: every target Warp gets one -1/0/1 axis per segment parameter, computed from its
 * current forms each time the rig is rebuilt, so changing a setting replaces the whole motion. The
 * swing owns those axes; [baked] keeps only the physics once the forms were written into the journal.
 */
data class RigSwingEdit(
    val id: String,
    val name: String,
    val kind: SwingKind,
    val targets: List<String>,
    val parameterIds: List<String>,
    val fulcrum: SwingFulcrum = SwingFulcrum.AUTO,
    val flip: Boolean = false,
    /** Tip travel at ±1, as a fraction of the pinned-edge-to-tip length. */
    val magnitude: Float = 0.25f,
    /** Extra rise (positive) or droop (negative) of the tip at ±1, as a fraction of the length. */
    val lift: Float = 0f,
    /** 0 bends evenly along the length; 1 keeps the root stiff and moves mostly the tip. */
    val softness: Float = 0.5f,
    /** Width change at ±1 toward the tip; negative narrows. */
    val zoom: Float = 0f,
    val preset: SwingPreset = SwingPreset.HAIR,
    val physics: SwingPhysics? = SwingPhysics(),
    val baked: Boolean = false,
) {
    init {
        require(listOf(id, name).all { it.isNotBlank() && it.none(Char::isISOControl) }) { "Swing ID and name are required" }
        require(targets.isNotEmpty() && targets.distinct().size == targets.size && targets.all { it.isNotBlank() }) { "Swing needs distinct targets" }
        require(parameterIds.size in 1..MAX_SEGMENTS && parameterIds.distinct().size == parameterIds.size &&
            parameterIds.all { it.isNotBlank() && it.none(Char::isISOControl) }) { "Swing needs 1..$MAX_SEGMENTS distinct parameters" }
        require(listOf(magnitude, lift, softness, zoom).all(Float::isFinite)) { "Swing values must be finite" }
        require(magnitude in 0f..MAX_MAGNITUDE) { "Swing magnitude must be within 0..$MAX_MAGNITUDE" }
        require(lift in -0.5f..0.5f && softness in 0f..1f && zoom in -0.5f..0.5f) { "Swing lift, softness or zoom out of range" }
    }

    val segments: Int get() = parameterIds.size

    fun toJson() = buildJsonObject {
        put("id", id); put("name", name); put("kind", kind.name)
        putJsonArray("targets") { targets.forEach { add(it) } }
        putJsonArray("parameters") { parameterIds.forEach { add(it) } }
        put("fulcrum", fulcrum.name); put("flip", flip); put("magnitude", magnitude); put("lift", lift)
        put("softness", softness); put("zoom", zoom); put("preset", preset.name)
        physics?.let { put("physics", it.toJson()) }
        if (baked) put("baked", true)
    }

    companion object {
        const val MAX_SEGMENTS = 3
        const val MAX_MAGNITUDE = 0.7f

        fun fromJson(o: JsonObject): RigSwingEdit {
            val kind = SwingKind.valueOf(o.text("kind").uppercase())
            val preset = o["preset"]?.jsonPrimitive?.contentOrNull?.let { SwingPreset.valueOf(it.uppercase()) } ?: SwingPreset.HAIR
            val defaults = SwingPresets.shape(preset, kind)
            return RigSwingEdit(
                id = o.text("id"),
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: o.text("id"),
                kind = kind,
                targets = o.getValue("targets").jsonArray.map { it.jsonPrimitive.content },
                parameterIds = o.getValue("parameters").jsonArray.map { it.jsonPrimitive.content },
                fulcrum = o["fulcrum"]?.jsonPrimitive?.contentOrNull?.let { SwingFulcrum.valueOf(it.uppercase()) } ?: SwingFulcrum.AUTO,
                flip = o["flip"]?.jsonPrimitive?.booleanOrNull ?: false,
                magnitude = o.number("magnitude", defaults.magnitude),
                lift = o.number("lift", defaults.lift),
                softness = o.number("softness", defaults.softness),
                zoom = o.number("zoom", defaults.zoom),
                preset = preset,
                physics = when (val p = o["physics"]) {
                    null -> SwingPresets.physics(preset, kind, null)
                    is JsonNull -> null
                    else -> SwingPhysics.fromJson(p.jsonObject)
                },
                baked = o["baked"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }
}

/** Default slider values and pendulums per preset; the pendulum length follows the target size. */
object SwingPresets {
    data class Shape(val magnitude: Float, val lift: Float, val softness: Float, val zoom: Float)

    fun shape(preset: SwingPreset, kind: SwingKind): Shape = when (preset) {
        SwingPreset.HAIR -> if (kind == SwingKind.LATERAL) Shape(0.22f, 0.04f, 0.6f, 0f) else Shape(0.10f, 0f, 0.5f, 0.04f)
        SwingPreset.ACCESSORY -> if (kind == SwingKind.LATERAL) Shape(0.30f, 0.06f, 0.1f, 0f) else Shape(0.14f, 0f, 0.2f, 0f)
        SwingPreset.CLOTH -> if (kind == SwingKind.LATERAL) Shape(0.16f, 0.02f, 0.8f, 0.04f) else Shape(0.08f, 0f, 0.7f, 0.06f)
    }

    /** [lengthPx] is the target's pinned-edge-to-tip length in canvas pixels, when known. */
    fun physics(preset: SwingPreset, kind: SwingKind, lengthPx: Float?): SwingPhysics {
        // The same pixel-to-pendulum scale the skeleton tails use.
        val length = lengthPx?.let { (it / 30f).coerceIn(3f, 16f) }
        return when (preset) {
            SwingPreset.HAIR -> SwingPhysics(length ?: 10f, 0.92f, 0.9f, 1.3f, if (kind == SwingKind.LATERAL) 1.6f else 1.4f)
            SwingPreset.ACCESSORY -> SwingPhysics(length ?: 6f, 0.96f, 0.6f, 1.8f, if (kind == SwingKind.LATERAL) 1.8f else 1.6f)
            SwingPreset.CLOTH -> SwingPhysics(length ?: 12f, 0.85f, 1.2f, 0.9f, if (kind == SwingKind.LATERAL) 1.3f else 1.2f)
        }
    }
}

/**
 * Replays swings onto a built rig. A swing that no longer fits (target deleted, blend parameter, sparse
 * grid) is skipped and reported by [issues] instead of failing the whole rebuild.
 */
internal object SwingGenerator {
    fun apply(model: PuppetModel, swings: List<RigSwingEdit>): PuppetModel =
        swings.fold(model) { current, swing -> applyOne(current, swing).first }

    fun issues(model: PuppetModel, swing: RigSwingEdit): List<String> = applyOne(model, swing).second

    /** The resolved pivot of [warpId] and its pinned-edge-to-tip length in canvas pixels, at defaults. */
    fun measure(model: PuppetModel, warpId: String, fulcrum: SwingFulcrum = SwingFulcrum.AUTO): Pair<SwingFulcrum, Float>? {
        val warp = model.deformers.firstOrNull { it.id.raw == warpId } as? Deformer.Warp ?: return null
        val space = SwingSpace(model, warp)
        val rest = space.rest ?: return null
        val resolved = if (fulcrum == SwingFulcrum.AUTO) space.autoFulcrum(rest) else fulcrum
        val (root, tip) = space.edges(rest, resolved)
        return resolved to hypot(tip.first - root.first, tip.second - root.second)
    }

    fun applyOne(model: PuppetModel, swing: RigSwingEdit): Pair<PuppetModel, List<String>> {
        if (swing.baked) return model to emptyList()
        val issues = mutableListOf<String>()
        var current = model
        val parameters = swing.parameterIds.mapIndexed { index, raw ->
            val id = ParameterId(raw)
            val name = if (swing.segments == 1) swing.name else "${swing.name} ${index + 1}"
            current = current.withParameterCreated(id, name)
            current.parameters.single { it.id == id }
        }
        if (parameters.any { it.kind != ParameterKind.NORMAL }) return model to listOf("${swing.id}: swing parameters must be normal parameters")
        val keys = parameters.map { p -> listOf(p.min, p.default, p.max).distinct().sorted() }
        for (target in swing.targets) {
            val warp = current.deformers.firstOrNull { it.id.raw == target } as? Deformer.Warp
            if (warp == null) { issues += "${swing.id}: target Warp not found: $target"; continue }
            val grid = warp.geometryGrid
            if (grid == null || !grid.isDense) { issues += "${swing.id}: $target has no dense Warp geometry"; continue }
            // The swing owns its axes: earlier keys on them collapse to the parameter default.
            var base: KeyformGrid<WarpLatticeForm> = grid
            for (p in parameters) base = base.collapsed(p)
            val combinations = keys.fold(1L) { n, k -> n * k.size }
            if (base.cells.size * combinations > 1_000_000L) { issues += "${swing.id}: $target has too many keyform cells"; continue }
            val space = SwingSpace(current, warp.copy(geometryGrid = base))
            val rest = space.rest ?: continue
            val shape = SwingDeformer.Shape(swing.kind,
                if (swing.fulcrum == SwingFulcrum.AUTO) space.autoFulcrum(rest) else swing.fulcrum,
                swing.flip, swing.magnitude, swing.lift, swing.softness, swing.zoom, swing.segments)
            val cells = ArrayList<KeyformCell<WarpLatticeForm>>(base.cells.size * combinations.toInt())
            val combo = IntArray(parameters.size)
            repeat(combinations.toInt()) {
                val values = FloatArray(parameters.size) { k -> normalized(parameters[k], keys[k][combo[k]]) }
                for (cell in base.cells) {
                    val points = if (values.all { it == 0f }) cell.form.controlPoints
                        else SwingDeformer.deform(cell.form.controlPoints, warp.rows, warp.columns, space.sx, space.sy, shape, values)
                    cells += KeyformCell(cell.coordinate + combo, WarpLatticeForm(points))
                }
                for (k in combo.indices) { if (++combo[k] < keys[k].size) break; combo[k] = 0 }
            }
            val axes = base.axes + parameters.mapIndexed { k, p -> KeyformAxis(p.id, keys[k].toFloatArray()) }
            current = current.withReplacedGeometryGrid(KeyformOwner.Deformer(warp.id), KeyformGrid(axes, cells))
        }
        for ((k, p) in parameters.withIndex()) {
            val authored = current.parameters.single { it.id == p.id }.keys ?: continue
            current = current.withParameterKeys(p.id, (authored + keys[k]).distinct().sorted())
        }
        return current to issues
    }

    /** -1 at the minimum, 0 at the default, 1 at the maximum. */
    private fun normalized(p: Parameter, value: Float): Float = when {
        value < p.default -> -(p.default - value) / (p.default - p.min)
        value > p.default -> (value - p.default) / (p.max - p.default)
        else -> 0f
    }

    private fun <T> KeyformGrid<T>.collapsed(p: Parameter): KeyformGrid<T> {
        val axis = axes.indexOfFirst { it.parameterId == p.id }
        if (axis < 0) return this
        val keep = axes[axis].keys.indices.minBy { abs(axes[axis].keys[it] - p.default) }
        fun IntArray.without() = IntArray(size - 1) { if (it < axis) this[it] else this[it + 1] }
        return KeyformGrid(axes.filterIndexed { i, _ -> i != axis },
            cells.filter { it.coordinate[axis] == keep }.map { KeyformCell(it.coordinate.without(), it.form) })
    }
}

/** A Warp's rest lattice, its parent's world mapping and the local-to-proportional scale of that space. */
internal class SwingSpace(
    val model: PuppetModel,
    val warp: Deformer.Warp,
    /** The pose to measure in; generation uses the defaults, the canvas handles the pose on screen. */
    values: ((ParameterId) -> Float)? = null,
) {
    private val defaults: (ParameterId) -> Float = { id -> model.parameters.firstOrNull { it.id == id }?.default ?: 0f }
    private val pose = values ?: defaults
    private val parent: DeformerWorld? = warp.parent?.let { buildDeformerWorlds(model.deformers, pose, defaults)[it] }

    /** The lattice blended at [values], in the parent space. */
    val rest: FloatArray? = warp.geometryGrid?.let { grid ->
        val corners = gridCorners(grid, pose) ?: return@let grid.cells.firstOrNull()?.form?.controlPoints
        val cells = grid.cellsByLinearIndex
        val points = FloatArray((warp.rows + 1) * (warp.columns + 1) * 2)
        for (corner in corners) {
            val form = cells[corner.linearIndex]?.form?.controlPoints ?: continue
            for (i in 0 until minOf(points.size, form.size)) points[i] += corner.weight * form[i]
        }
        points
    }

    fun world(x: Float, y: Float): Pair<Float, Float> {
        val p = parent ?: return x to y
        val out = FloatArray(2); p.apply(x, y, out, 0)
        return out[0] to out[1]
    }

    val sx: Float
    val sy: Float

    init {
        val points = rest
        if (parent == null || points == null) { sx = 1f; sy = 1f } else {
            val xs = points.filterIndexed { i, _ -> i % 2 == 0 }; val ys = points.filterIndexed { i, _ -> i % 2 == 1 }
            val cx = (xs.min() + xs.max()) / 2f; val cy = (ys.min() + ys.max()) / 2f
            val du = ((xs.max() - xs.min()) * 0.05f).coerceAtLeast(1e-4f)
            val dv = ((ys.max() - ys.min()) * 0.05f).coerceAtLeast(1e-4f)
            val c = world(cx, cy); val u = world(cx + du, cy); val v = world(cx, cy + dv)
            sx = (hypot(u.first - c.first, u.second - c.second) / du).takeIf { it.isFinite() && it > 1e-6f } ?: 1f
            sy = (hypot(v.first - c.first, v.second - c.second) / dv).takeIf { it.isFinite() && it > 1e-6f } ?: 1f
        }
    }

    private fun mid(points: FloatArray, indices: List<Int>): Pair<Float, Float> {
        val ws = indices.map { world(points[it * 2], points[it * 2 + 1]) }
        return ws.map { it.first }.average().toFloat() to ws.map { it.second }.average().toFloat()
    }
    private fun row(r: Int) = (0..warp.columns).map { r * (warp.columns + 1) + it }
    private fun column(c: Int) = (0..warp.rows).map { it * (warp.columns + 1) + c }

    /** World midpoints of the pinned edge and the opposite edge. */
    fun edges(points: FloatArray, fulcrum: SwingFulcrum): Pair<Pair<Float, Float>, Pair<Float, Float>> = when (fulcrum) {
        SwingFulcrum.TOP, SwingFulcrum.AUTO -> mid(points, row(0)) to mid(points, row(warp.rows))
        SwingFulcrum.BOTTOM -> mid(points, row(warp.rows)) to mid(points, row(0))
        SwingFulcrum.LEFT -> mid(points, column(0)) to mid(points, column(warp.columns))
        SwingFulcrum.RIGHT -> mid(points, column(warp.columns)) to mid(points, column(0))
    }

    /** Tall things hang from the top; wide ones pivot on the side nearer the body's center line. */
    fun autoFulcrum(points: FloatArray): SwingFulcrum {
        val top = mid(points, row(0)); val bottom = mid(points, row(warp.rows))
        val left = mid(points, column(0)); val right = mid(points, column(warp.columns))
        val height = hypot(bottom.first - top.first, bottom.second - top.second)
        val width = hypot(right.first - left.first, right.second - left.second)
        if (height >= width * 0.8f) return SwingFulcrum.TOP
        val center = model.worldOriginX
        return if (abs(left.first - center) <= abs(right.first - center)) SwingFulcrum.LEFT else SwingFulcrum.RIGHT
    }
}

private fun JsonObject.text(key: String) = requireNotNull(get(key)?.jsonPrimitive?.contentOrNull) { "$key is required" }
private fun JsonObject.number(key: String, fallback: Float) = get(key)?.jsonPrimitive?.floatOrNull ?: fallback
