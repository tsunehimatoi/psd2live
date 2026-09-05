package io.github.psd2live.agent

import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.serialization.json.*

/** Conservative matte cleanup: only explicit near-matte pixels connected to the image border. */
internal fun cleanGeneratedMatte(image: BufferedImage, hex: String, tolerance: Int): BufferedImage {
    require(Regex("#[0-9a-fA-F]{6}").matches(hex)) { "solid_background must be #RRGGBB" }
    require(tolerance in 0..64) { "background_tolerance must be within 0..64" }
    val color = hex.drop(1).toInt(16)
    val w = image.width
    val h = image.height
    val pixels = image.getRGB(0, 0, w, h, null, 0, w)
    val visited = BooleanArray(pixels.size)
    val queue = IntArray(pixels.size)
    var head = 0
    var tail = 0
    fun enqueue(i: Int) {
        if (visited[i]) return
        visited[i] = true
        val p = pixels[i]
        val distance = max(abs((p shr 16 and 255) - (color shr 16 and 255)),
            max(abs((p shr 8 and 255) - (color shr 8 and 255)), abs((p and 255) - (color and 255))))
        if (p ushr 24 == 0 || distance <= tolerance) queue[tail++] = i
    }
    for (x in 0 until w) { enqueue(x); enqueue((h - 1) * w + x) }
    for (y in 0 until h) { enqueue(y * w); enqueue(y * w + w - 1) }
    while (head < tail) {
        val i = queue[head++]
        pixels[i] = pixels[i] and 0x00ffffff
        if (i % w > 0) enqueue(i - 1)
        if (i % w < w - 1) enqueue(i + 1)
        if (i >= w) enqueue(i - w)
        if (i < pixels.size - w) enqueue(i + w)
    }
    require(pixels.any { it ushr 24 > 0 }) { "Matte cleanup removed the entire asset; regenerate with a distinct background" }
    return BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, w, h, pixels, 0, w) }
}

internal data class AgentMatteResult(val image: BufferedImage, val diagnostics: JsonObject)

/** Solid-matte unmixing. Seeds and the edge band are in ORIGINAL generated pixel coordinates. */
internal fun processGeneratedMatte(image: BufferedImage, hex: String, tolerance: Int, hints: JsonObject): AgentMatteResult {
    require(Regex("#[0-9a-fA-F]{6}").matches(hex)) { "solid_background must be #RRGGBB" }
    require(tolerance in 0..64)
    val w = image.width; val h = image.height; val count = w * h
    val pixels = image.getRGB(0, 0, w, h, null, 0, w)
    val original = pixels.copyOf()
    val bg = hex.drop(1).toInt(16)
    val protect = BooleanArray(count)
    fun seedIndices(key: String, radius: Int): List<Int> = buildList {
        hints[key]?.jsonArray?.forEach { value ->
            val p = value.jsonObject
            val x = p.number("x").roundToInt(); val y = p.number("y").roundToInt()
            require(x in 0 until w && y in 0 until h) { "$key point is outside the generated PNG" }
            for (dy in -radius..radius) for (dx in -radius..radius) {
                if (x+dx in 0 until w && y+dy in 0 until h) add((y+dy)*w+x+dx)
            }
        }
    }
    seedIndices("foreground_points", 2).forEach { protect[it] = true }
    fun distance(p: Int) = max(abs((p shr 16 and 255)-(bg shr 16 and 255)),
        max(abs((p shr 8 and 255)-(bg shr 8 and 255)), abs((p and 255)-(bg and 255))))
    val removed = BooleanArray(count)
    val queue = IntArray(count); var head = 0; var tail = 0
    fun push(i: Int) {
        if (!removed[i] && !protect[i] && (pixels[i] ushr 24 == 0 || distance(pixels[i]) <= tolerance)) {
            removed[i] = true; queue[tail++] = i
        }
    }
    val border = buildSet { for (x in 0 until w) { add(x); add((h-1)*w+x) }; for (y in 0 until h) { add(y*w); add(y*w+w-1) } }
    val borderMatch = border.count { distance(pixels[it]) <= tolerance || pixels[it] ushr 24 == 0 }.toDouble() / border.size
    border.forEach(::push)
    seedIndices("background_points", 0).forEach {
        require(!protect[it]) { "Conflicting foreground/background seeds" }
        require(distance(pixels[it]) <= tolerance) { "Background seed differs from declared matte; choose actual matte or increase tolerance" }
        push(it)
    }
    fun neighbours(i: Int, action: (Int) -> Unit) {
        if (i%w>0) action(i-1); if (i%w<w-1) action(i+1)
        if (i>=w) action(i-w); if (i<count-w) action(i+w)
    }
    while (head < tail) neighbours(queue[head++], ::push)
    require(removed.any { !it }) { "No foreground remains; choose a different matte or foreground protection points" }
    // Distance from known background, without crossing between separate foreground components.
    val edgeDistance = IntArray(count) { if (removed[it]) 0 else Int.MAX_VALUE }
    head = 0; tail = 0
    for (i in 0 until count) if (removed[i]) queue[tail++] = i
    while (head < tail) {
        val i = queue[head++]
        neighbours(i) { j -> if (edgeDistance[j] == Int.MAX_VALUE) { edgeDistance[j] = edgeDistance[i]+1; queue[tail++] = j } }
    }
    val edgeWidth = hints["edge_width"]?.jsonPrimitive?.int ?: 3
    require(edgeWidth in 0..8) { "edge_width must be within 0..8" }
    val nearest = IntArray(count) { -1 }
    head = 0; tail = 0
    for (i in 0 until count) if (!removed[i] && (protect[i] || (edgeDistance[i] > edgeWidth && distance(pixels[i]) > max(32, tolerance*3)))) {
        nearest[i] = i; queue[tail++] = i
    }
    while (head < tail) {
        val i = queue[head++]
        neighbours(i) { j -> if (!removed[j] && nearest[j] < 0) { nearest[j] = nearest[i]; queue[tail++] = j } }
    }
    fun linear(c: Int): Double { val v=c/255.0; return if (v<=.04045) v/12.92 else ((v+.055)/1.055).pow(2.4) }
    fun srgb(v: Double): Int { val c=v.coerceIn(0.0,1.0); return ((if(c<=.0031308) c*12.92 else 1.055*c.pow(1/2.4)-.055)*255).roundToInt().coerceIn(0,255) }
    fun rgb(p: Int) = doubleArrayOf(linear(p shr 16 and 255), linear(p shr 8 and 255), linear(p and 255))
    val b = rgb(bg); var unmixed = 0; var uncertain = 0
    for (i in 0 until count) {
        if (removed[i]) { pixels[i] = pixels[i] and 0xffffff; continue }
        if (edgeDistance[i] > edgeWidth || protect[i] || pixels[i] ushr 24 < 255) continue
        if (nearest[i] < 0) { uncertain++; continue }
        val c=rgb(original[i]); val f=rgb(original[nearest[i]])
        val denominator=(0..2).sumOf { (f[it]-b[it]).pow(2) }
        if (denominator < .01) { uncertain++; continue }
        val alpha=((0..2).sumOf { (c[it]-b[it])*(f[it]-b[it]) }/denominator).coerceIn(0.0,1.0)
        val residual=(0..2).sumOf { (c[it]-(alpha*f[it]+(1-alpha)*b[it])).pow(2) }
        if (alpha in .02..0.98 && residual < .025) {
            val restored=(0..2).map { srgb((c[it]-(1-alpha)*b[it])/alpha) }
            pixels[i]=((alpha*255).roundToInt() shl 24) or (restored[0] shl 16) or (restored[1] shl 8) or restored[2]
            unmixed++
        }
    }
    val nearMatte = pixels.indices.count { !removed[it] && !protect[it] && distance(original[it]) <= tolerance }
    val mismatch = borderMatch < .6
    val diagnostic = buildJsonObject {
        put("status", if (mismatch) "background_mismatch" else if (uncertain>0 || nearMatte>0) "review_edges" else "processed")
        put("matte_color", hex); put("border_match_fraction", borderMatch)
        put("removed_pixels", removed.count { it }); put("unmixed_edge_pixels", unmixed)
        put("unresolved_edge_pixels", uncertain); put("possible_enclosed_matte_pixels", nearMatte)
        put("orientation_changed", false)
        put("advice", if(mismatch) "Possible checkerboard/nonuniform matte or foreground touching frame. Inspect original; regenerate with declared flat matte and padding." else "Inspect assembled appearance. Seed enclosed background explicitly; protect same-color foreground. Counts alone do not certify quality.")
    }
    return AgentMatteResult(BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0,0,w,h,pixels,0,w) }, diagnostic)
}
