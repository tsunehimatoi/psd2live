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

/** The pendulum that drives one direction of a swing. One vertex per segment; outputs are relative segment angles. */
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

/** How the target moves in one direction. */
data class SwingShape(
    /** Tip travel at ±1, as a fraction of the pinned-edge-to-tip length. */
    val magnitude: Float = 0.25f,
    /** Extra rise (positive) or droop (negative) of the tip at ±1, as a fraction of the length. */
    val lift: Float = 0f,
    /** 0 bends evenly along the length; 1 keeps the root stiff and moves mostly the tip. */
    val softness: Float = 0.5f,
    /** Width change at ±1 toward the tip; negative narrows. */
    val zoom: Float = 0f,
    /**
     * 0 swings the whole lattice like one board, its tip edge tilting with the bend; 1 keeps the tip edge
     * level, every column swaying alongside the others as separate strands would.
     */
    val parallel: Float = 0f,
    val flip: Boolean = false,
) {
    init {
        require(listOf(magnitude, lift, softness, zoom, parallel).all(Float::isFinite)) { "Swing values must be finite" }
        require(magnitude in 0f..RigSwingEdit.MAX_MAGNITUDE) { "Swing magnitude must be within 0..${RigSwingEdit.MAX_MAGNITUDE}" }
        require(lift in -0.5f..0.5f && softness in 0f..1f && zoom in -0.5f..0.5f && parallel in 0f..1f) {
            "Swing lift, softness, zoom or parallel out of range"
        }
    }

    fun deformer(kind: SwingKind, fulcrum: SwingFulcrum, segments: Int, placement: SwingDeformer.Placement = SwingDeformer.Placement()) =
        SwingDeformer.Shape(kind, fulcrum, flip, magnitude, lift, softness, zoom, segments, parallel, placement)

    internal fun write(o: JsonObjectBuilder) {
        o.put("magnitude", magnitude); o.put("lift", lift); o.put("softness", softness); o.put("zoom", zoom)
        o.put("parallel", parallel)
        if (flip) o.put("flip", true)
    }

    companion object {
        /** Reads the shape fields of [o], each falling back to [defaults]. */
        fun fromJson(o: JsonObject, defaults: SwingShape) = SwingShape(o.number("magnitude", defaults.magnitude),
            o.number("lift", defaults.lift), o.number("softness", defaults.softness), o.number("zoom", defaults.zoom),
            o.number("parallel", defaults.parallel), o["flip"]?.jsonPrimitive?.booleanOrNull ?: defaults.flip)
    }
}

/** One direction of travel: its segment parameters (root first), shape and pendulum. */
data class SwingMotion(
    val kind: SwingKind,
    val parameterIds: List<String>,
    val shape: SwingShape = SwingShape(),
    val physics: SwingPhysics? = SwingPhysics(),
) {
    val segments: Int get() = parameterIds.size
}

/**
 * A regenerating sway: every target Warp gets one -1/0/1 axis per parameter, computed from its current
 * forms each time the rig is rebuilt, so changing a setting replaces the whole motion. The swing owns
 * those axes; [baked] keeps only the physics once the forms were written into the journal.
 *
 * [motions] can combine left/right with up/down on the same Warp: the stretch applies before the bend,
 * so the combined keys are exact rather than a sum.
 */
data class RigSwingEdit(
    val id: String,
    val name: String,
    val targets: List<String>,
    val motions: List<SwingMotion>,
    val fulcrum: SwingFulcrum = SwingFulcrum.AUTO,
    val preset: SwingPreset = SwingPreset.HAIR,
    val baked: Boolean = false,
    /**
     * Degrees the swing rectangle turns about the pinned edge's midpoint, for art that hangs at a slant.
     * Positive turns the tip toward the pinned edge's last lattice point (the right end of a top or bottom
     * edge, the bottom end of a side one).
     */
    val tilt: Float = 0f,
    /**
     * How far the swing rectangle's pinned edge sits from the lattice's: [offsetAlong] in lengths toward the tip,
     * [offsetAcross] in widths toward the pinned edge's last lattice point. Turning happens about the moved point.
     */
    val offsetAlong: Float = 0f,
    val offsetAcross: Float = 0f,
) {
    init {
        require(tilt.isFinite() && abs(tilt) <= MAX_TILT) { "Swing tilt must be within ±$MAX_TILT degrees" }
        require(listOf(offsetAlong, offsetAcross).all { it.isFinite() && abs(it) <= MAX_OFFSET }) { "Swing offset must be within ±$MAX_OFFSET" }
        require(listOf(id, name).all { it.isNotBlank() && it.none(Char::isISOControl) }) { "Swing ID and name are required" }
        require(targets.isNotEmpty() && targets.distinct().size == targets.size && targets.all { it.isNotBlank() }) { "Swing needs distinct targets" }
        require(motions.size in 1..2 && motions.map { it.kind }.distinct().size == motions.size) { "Swing needs one motion per direction" }
        require(motions.all { it.segments in 1..MAX_SEGMENTS }) { "Swing needs 1..$MAX_SEGMENTS parameters per direction" }
        require(parameterIds.distinct().size == parameterIds.size && parameterIds.all { it.isNotBlank() && it.none(Char::isISOControl) }) {
            "Swing parameters must be distinct"
        }
    }

    /** Every parameter the swing drives: direction by direction, root segment first. */
    val parameterIds: List<String> get() = motions.flatMap { it.parameterIds }

    val hasPhysics: Boolean get() = motions.any { it.physics != null }

    val placement: SwingDeformer.Placement get() = SwingDeformer.Placement(tilt, offsetAlong, offsetAcross)

    /** The shape of motion [motion] passed through [change]. */
    fun withShape(motion: Int, change: (SwingShape) -> SwingShape) =
        copy(motions = motions.mapIndexed { m, entry -> if (m == motion) entry.copy(shape = change(entry.shape)) else entry })

    /** The pendulums passed through [change]; [motion] limits it to one direction. */
    fun withPhysics(motion: Int? = null, change: (SwingKind, SwingPhysics?) -> SwingPhysics?) =
        copy(motions = motions.mapIndexed { m, entry -> if (motion != null && m != motion) entry else entry.copy(physics = change(entry.kind, entry.physics)) })

    fun toJson() = buildJsonObject {
        put("id", id); put("name", name)
        putJsonArray("targets") { targets.forEach { add(it) } }
        put("fulcrum", fulcrum.name); put("preset", preset.name)
        if (tilt != 0f) put("tilt", tilt)
        if (offsetAlong != 0f) put("offset_along", offsetAlong)
        if (offsetAcross != 0f) put("offset_across", offsetAcross)
        putJsonArray("motions") {
            for (motion in motions) addJsonObject {
                put("kind", motion.kind.name)
                putJsonArray("parameters") { motion.parameterIds.forEach { add(it) } }
                motion.shape.write(this)
                put("physics", motion.physics?.toJson() ?: JsonNull)
            }
        }
        if (baked) put("baked", true)
    }

    companion object {
        const val MAX_SEGMENTS = 3
        const val MAX_MAGNITUDE = 0.7f
        /** Beyond this the axis runs nearly along the pinned edge and there is no length left to swing. */
        const val MAX_TILT = 75f
        /** The swing rectangle moves at most one length along and one width across. */
        const val MAX_OFFSET = 1f

        /** A swing in one direction, the shape every swing had before directions could combine. */
        fun single(id: String, name: String, kind: SwingKind, targets: List<String>, parameterIds: List<String>,
            fulcrum: SwingFulcrum = SwingFulcrum.AUTO, shape: SwingShape = SwingPresets.shape(SwingPreset.HAIR, kind),
            preset: SwingPreset = SwingPreset.HAIR, physics: SwingPhysics? = SwingPhysics(), baked: Boolean = false) =
            RigSwingEdit(id, name, targets, listOf(SwingMotion(kind, parameterIds, shape, physics)), fulcrum, preset, baked)

        fun fromJson(o: JsonObject): RigSwingEdit {
            val preset = o["preset"]?.jsonPrimitive?.contentOrNull?.let { SwingPreset.valueOf(it.uppercase()) } ?: SwingPreset.HAIR
            val fulcrum = o["fulcrum"]?.jsonPrimitive?.contentOrNull?.let { SwingFulcrum.valueOf(it.uppercase()) } ?: SwingFulcrum.AUTO
            val id = o.text("id")
            fun motion(m: JsonObject): SwingMotion {
                val kind = SwingKind.valueOf(m.text("kind").uppercase())
                // Swings saved before the parallel setting swung like one board.
                val shape = SwingShape.fromJson(m, SwingPresets.shape(preset, kind).copy(parallel = 0f))
                val physics = when (val p = m["physics"]) {
                    null -> SwingPresets.physics(preset, kind, null)
                    is JsonNull -> null
                    else -> SwingPhysics.fromJson(p.jsonObject)
                }
                return SwingMotion(kind, m.getValue("parameters").jsonArray.map { it.jsonPrimitive.content }, shape, physics)
            }
            // Before directions could combine, the one motion's fields sat at the top level.
            val motions = o["motions"]?.jsonArray?.map { motion(it.jsonObject) } ?: listOf(motion(o))
            return RigSwingEdit(id, o["name"]?.jsonPrimitive?.contentOrNull ?: id, o.getValue("targets").jsonArray.map { it.jsonPrimitive.content },
                motions, fulcrum, preset, o["baked"]?.jsonPrimitive?.booleanOrNull ?: false, o.number("tilt", 0f),
                o.number("offset_along", 0f), o.number("offset_across", 0f))
        }
    }
}

/** Default slider values and pendulums per preset; the pendulum length follows the target size. */
object SwingPresets {
    fun shape(preset: SwingPreset, kind: SwingKind): SwingShape = when (preset) {
        SwingPreset.HAIR -> if (kind == SwingKind.LATERAL) SwingShape(0.22f, 0.04f, 0.6f, 0f, 0.7f) else SwingShape(0.10f, 0f, 0.5f, 0.04f, 0.5f)
        SwingPreset.ACCESSORY -> if (kind == SwingKind.LATERAL) SwingShape(0.30f, 0.06f, 0.1f, 0f, 0f) else SwingShape(0.14f, 0f, 0.2f, 0f, 0f)
        SwingPreset.CLOTH -> if (kind == SwingKind.LATERAL) SwingShape(0.16f, 0.02f, 0.8f, 0.04f, 0.6f) else SwingShape(0.08f, 0f, 0.7f, 0.06f, 0.5f)
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
object SwingGenerator {
    fun asciiStem(text: String): String = text.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }.take(24).ifEmpty { "Swing" }

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

    /**
     * [model] without [swing]'s forms: its axes collapsed to their defaults. A baked swing's forms are
     * ordinary keys by then, so they stay.
     */
    fun strip(model: PuppetModel, swing: RigSwingEdit): PuppetModel {
        if (swing.baked) return model
        val parameters = model.parameters.filter { it.id.raw in swing.parameterIds }
        return model.copy(deformers = model.deformers.map { d ->
            if (d !is Deformer.Warp) d else d.geometryGrid?.let { grid -> d.copy(geometryGrid = parameters.fold(grid) { g, p -> g.collapsed(p) }) } ?: d
        })
    }

    /** The swing's name, followed by what sets this parameter apart from its others (direction, segment). */
    fun parameterName(swing: RigSwingEdit, raw: String): String {
        val stem = "ParamSwing${asciiStem(swing.id)}"
        val suffix = if (raw.startsWith(stem)) raw.removePrefix(stem).trim('_').replace('_', ' ') else ""
        return if (swing.parameterIds.size == 1 || suffix.isEmpty()) swing.name else "${swing.name} $suffix"
    }

    private class Axis(val motion: Int, val segment: Int, val parameter: Parameter, val keys: List<Float>)

    fun applyOne(model: PuppetModel, swing: RigSwingEdit): Pair<PuppetModel, List<String>> {
        if (swing.baked) return model to emptyList()
        val issues = mutableListOf<String>()
        var current = model
        val parameters = swing.parameterIds.map { raw ->
            val id = ParameterId(raw)
            current = current.withParameterCreated(id, parameterName(swing, raw))
            current.parameters.single { it.id == id }
        }
        if (parameters.any { it.kind != ParameterKind.NORMAL }) return model to listOf("${swing.id}: swing parameters must be normal parameters")
        val byId = parameters.associateBy { it.id.raw }
        val keys = parameters.associate { p -> p.id.raw to listOf(p.min, p.default, p.max).distinct().sorted() }
        val axes = swing.motions.flatMapIndexed { m, motion ->
            motion.parameterIds.mapIndexed { j, raw -> Axis(m, j, byId.getValue(raw), keys.getValue(raw)) }
        }
        val combinations = axes.fold(1L) { n, a -> n * a.keys.size }
        for (target in swing.targets) {
            val warp = current.deformers.firstOrNull { it.id.raw == target } as? Deformer.Warp
            if (warp == null) { issues += "${swing.id}: target Warp not found: $target"; continue }
            val grid = warp.geometryGrid
            if (grid == null || !grid.isDense) { issues += "${swing.id}: $target has no dense Warp geometry"; continue }
            // The swing owns its axes: earlier keys on them collapse to the parameter default.
            var base: KeyformGrid<WarpLatticeForm> = grid
            for (p in parameters) base = base.collapsed(p)
            if (base.cells.size * combinations > 1_000_000L) { issues += "${swing.id}: $target has too many keyform cells"; continue }
            val space = SwingSpace(current, warp.copy(geometryGrid = base))
            val rest = space.rest ?: continue
            val fulcrum = if (swing.fulcrum == SwingFulcrum.AUTO) space.autoFulcrum(rest) else swing.fulcrum
            val shapes = swing.motions.map { it.shape.deformer(it.kind, fulcrum, it.segments, swing.placement) }
            val cells = ArrayList<KeyformCell<WarpLatticeForm>>(base.cells.size * combinations.toInt())
            val combo = IntArray(axes.size)
            repeat(combinations.toInt()) {
                val values = swing.motions.map { FloatArray(it.segments) }
                for ((i, a) in axes.withIndex()) values[a.motion][a.segment] = normalized(a.parameter, a.keys[combo[i]])
                val still = values.all { v -> v.all { it == 0f } }
                for (cell in base.cells) {
                    val lattice = cell.form.controlPoints
                    val points = if (still) lattice else SwingDeformer.compose(shapes, lattice) { m, targets ->
                        SwingDeformer.transform(lattice, warp.rows, warp.columns, space.sx, space.sy, shapes[m], values[m], targets)
                    }
                    cells += KeyformCell(cell.coordinate + combo, WarpLatticeForm(points))
                }
                for (k in combo.indices) { if (++combo[k] < axes[k].keys.size) break; combo[k] = 0 }
            }
            val gridAxes = base.axes + axes.map { KeyformAxis(it.parameter.id, it.keys.toFloatArray()) }
            current = current.copy(deformers = current.deformers.map { deformer ->
                if (deformer.id == warp.id && deformer is Deformer.Warp) deformer.copy(geometryGrid = KeyformGrid(gridAxes, cells)) else deformer
            })
        }
        for (p in parameters) {
            val authored = current.parameters.single { it.id == p.id }.keys ?: continue
            current = current.withParameterKeys(p.id, (authored + keys.getValue(p.id.raw)).distinct().sorted())
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
class SwingSpace(
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
