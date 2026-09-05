package io.github.psd2live.agent

import io.github.psd2live.core.Bounds
import kotlinx.serialization.json.*
import java.awt.geom.AffineTransform
import kotlin.math.*

data class AgentPoint(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite()) { "Point coordinates must be finite" } }
    fun json() = buildJsonObject { put("x", x); put("y", y) }
}

/** Absolute pixel-edge -> source canvas transform. Reflection is never inferred. */
data class AgentPlacementTransform(
    val x: Double, val y: Double, val scaleX: Double, val scaleY: Double = scaleX,
    val rotationDegrees: Double = 0.0, val mirrorX: Boolean = false, val mirrorY: Boolean = false,
) {
    init {
        require(listOf(x, y, scaleX, scaleY, rotationDegrees).all { it.isFinite() })
        require(scaleX > 0 && scaleY > 0) { "Scale must be positive; request reflection explicitly" }
    }
    fun affine(): AffineTransform = AffineTransform().also {
        it.translate(x, y); it.rotate(Math.toRadians(rotationDegrees))
        it.scale(scaleX * if (mirrorX) -1 else 1, scaleY * if (mirrorY) -1 else 1)
    }
    fun point(p: AgentPoint): AgentPoint {
        val xy = doubleArrayOf(p.x, p.y); affine().transform(xy, 0, xy, 0, 1)
        return AgentPoint(xy[0], xy[1])
    }
    fun bounds(width: Int, height: Int): Bounds {
        val points = listOf(AgentPoint(0.0, 0.0), AgentPoint(width.toDouble(), 0.0),
            AgentPoint(0.0, height.toDouble()), AgentPoint(width.toDouble(), height.toDouble())).map(::point)
        return Bounds(points.minOf { it.x }.toFloat(), points.minOf { it.y }.toFloat(),
            points.maxOf { it.x }.toFloat(), points.maxOf { it.y }.toFloat())
    }
    fun json() = buildJsonObject {
        put("x", x); put("y", y); put("scale_x", scaleX); put("scale_y", scaleY)
        put("rotation_degrees", rotationDegrees); put("mirror_x", mirrorX); put("mirror_y", mirrorY)
    }
    companion object {
        fun parse(o: JsonObject) = AgentPlacementTransform(o.number("x"), o.number("y"), o.number("scale_x"),
            o.number("scale_y", o.number("scale_x")), o.number("rotation_degrees", 0.0),
            o.flag("mirror_x"), o.flag("mirror_y"))
    }
}

data class AgentRegistrationResult(val transform: AgentPlacementTransform, val rmsError: Double, val orientationConflict: Boolean)

/** Least squares similarity fit, with declared reflection factored out before solving rotation. */
fun registerAgentLandmarks(source: List<AgentPoint>, target: List<AgentPoint>, mirrorX: Boolean = false, mirrorY: Boolean = false): AgentRegistrationResult {
    require(source.size >= 2 && source.size == target.size) { "At least two corresponding anchors are required" }
    val reflected = source.map { AgentPoint(it.x * if (mirrorX) -1 else 1, it.y * if (mirrorY) -1 else 1) }
    val sx = reflected.map { it.x }.average(); val sy = reflected.map { it.y }.average()
    val tx = target.map { it.x }.average(); val ty = target.map { it.y }.average()
    var dot = 0.0; var cross = 0.0; var denominator = 0.0
    for (i in source.indices) {
        val x = reflected[i].x - sx; val y = reflected[i].y - sy
        val u = target[i].x - tx; val v = target[i].y - ty
        dot += x * u + y * v; cross += x * v - y * u; denominator += x * x + y * y
    }
    require(denominator > 1e-8 && hypot(dot, cross) > 1e-8) { "Degenerate anchors; choose distinct root and tip points" }
    val a = dot / denominator; val b = cross / denominator
    val scale = hypot(a, b)
    val transform = AgentPlacementTransform(tx - a * sx + b * sy, ty - b * sx - a * sy, scale,
        rotationDegrees = Math.toDegrees(atan2(b, a)), mirrorX = mirrorX, mirrorY = mirrorY)
    val rms = sqrt(source.indices.sumOf { i ->
        val p = transform.point(source[i]); (p.x - target[i].x).pow(2) + (p.y - target[i].y).pow(2)
    } / source.size)
    fun area(a: AgentPoint, b: AgentPoint, c: AgentPoint) = (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
    val conflict = source.size >= 3 && (2 until source.size).any { i ->
        val a1 = area(reflected[0], reflected[1], reflected[i]); val a2 = area(target[0], target[1], target[i])
        abs(a1) > 1e-6 && abs(a2) > 1e-6 && a1 * a2 < 0
    }
    return AgentRegistrationResult(transform, rms, conflict)
}

/** Declared rectangle of generated pixels (e.g. content inside a letterbox) maps to source canvas. */
fun registerAgentFrame(generated: Bounds, canvas: Bounds, allowStretch: Boolean = false): AgentPlacementTransform {
    require(generated.width > 0 && generated.height > 0 && canvas.width > 0 && canvas.height > 0)
    val sx = canvas.width.toDouble() / generated.width; val sy = canvas.height.toDouble() / generated.height
    require(allowStretch || abs(sx - sy) / max(sx, sy) <= .005) { "Frame aspect mismatch; declare generated_pixel_rect/source_canvas_rect or use landmarks. Stretch requires allow_stretch=true" }
    return AgentPlacementTransform(canvas.left - generated.left * sx, canvas.top - generated.top * sy, sx, sy)
}

internal fun JsonObject.text(key: String): String = requireNotNull(this[key]?.jsonPrimitive?.contentOrNull) { "$key is required" }
internal fun JsonObject.number(key: String, default: Double? = null): Double =
    (this[key]?.jsonPrimitive?.doubleOrNull ?: default ?: throw IllegalArgumentException("$key must be a number")).also { require(it.isFinite()) }
internal fun JsonObject.flag(key: String, default: Boolean = false) = this[key]?.jsonPrimitive?.boolean ?: default
internal fun JsonObject.strings(key: String) = this[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
internal fun JsonObject.points(key: String): Map<String, AgentPoint> = (this[key] as? JsonObject).orEmpty().mapValues { (_, v) ->
    val o = v.jsonObject; AgentPoint(o.number("x"), o.number("y"))
}
internal fun Bounds.json() = buildJsonObject { put("left", left); put("top", top); put("width", width); put("height", height) }
internal fun JsonObject.rect(): Bounds = Bounds(number("left").toFloat(), number("top").toFloat(),
    (number("left") + number("width")).toFloat(), (number("top") + number("height")).toFloat()).also {
    require(it.width > 0 && it.height > 0) { "Rectangle size must be positive" }
}
