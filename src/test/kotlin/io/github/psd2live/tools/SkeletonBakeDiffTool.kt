package io.github.psd2live.tools

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.SkeletonRig
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.bridge.PuppetIr
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test

/**
 * The skeleton bake of a sample, kept so another build's can be compared with it: where every skinned mesh
 * lands, in canvas pixels, at each cell of its keyform grid and each key of its blend shapes, and the limbs
 * rendered at their extreme poses.
 *
 * PSD2LIVE_TOOLS=1 PSD2LIVE_LABEL=before ./gradlew test --tests '*SkeletonBakeDiffTool'
 * then on the other build PSD2LIVE_LABEL=after PSD2LIVE_REFERENCE=before. PSD2LIVE_SAMPLE picks the PSD (tml by
 * default), PSD2LIVE_SCALE (default 2) enlarges it as [SkeletonCommitTool] does. Writes build/tools/skeleton-bake/
 * <label>.bin, <label>-limbs.png and, against a reference, <label>-vs-<reference>.png (the absolute pixel
 * difference, eight times brighter) and the largest vertex distance per mesh.
 */
class SkeletonBakeDiffTool {
	@Test fun bake() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val factor = setting("PSD2LIVE_SCALE", "2").toInt()
		val out = output("skeleton-bake")
		val label = setting("PSD2LIVE_LABEL", "current")
		val path = if (factor == 1) sample.path.toFile() else scaledSample(sample, factor, output("skeleton-commit"))
		SkeletonRig.clearCache()
		var t = System.nanoTime()
		val built = build(sample.at(path.toPath()))
		println("build: %.0f ms".format((System.nanoTime() - t) / 1e6))
		val puppet = built.skeletal.rig.puppet
		println("rig IR: ${ContentHash.of(PuppetIr.toIr(puppet))}")
		val bound = built.spec.bones.flatMap { it.drawableIds }.toSet()
		val skinned = puppet.drawables.filter { it.id.raw in bound && it.mesh != null }.sortedBy { it.id.raw }
		val evaluator = CpuDeformationEvaluator()
		t = System.nanoTime()
		val positions = LinkedHashMap<String, FloatArray>()
		for (drawable in skinned) {
			val sets = LinkedHashSet<Map<ParameterId, Float>>()
			drawable.geometryGrid?.let { grid ->
				for (cell in grid.cells) sets += grid.axes.indices.associate { grid.axes[it].parameterId to grid.axes[it].keys[cell.coordinate[it]] }
			}
			for (binding in drawable.blendShapes) for (key in binding.keys) sets += mapOf(binding.parameterId to key)
			for (values in sets) {
				val name = drawable.id.raw + " " + values.entries.sortedBy { it.key.raw }.joinToString(",") { "${it.key.raw}=${it.value}" }
				positions[name] = evaluator.evaluate(puppet, values).worldPositions.getValue(drawable.id)
			}
			println("${drawable.id.raw}: ${drawable.mesh!!.positions.size / 2} vertices, ${sets.size} keyforms")
		}
		println("evaluated ${positions.size} keyforms: %.0f ms".format((System.nanoTime() - t) / 1e6))
		DataOutputStream(File(out, "$label.bin").outputStream().buffered()).use { o ->
			o.writeInt(positions.size)
			for ((name, p) in positions) { o.writeUTF(name); o.writeInt(p.size); p.forEach(o::writeFloat) }
		}

		// The limbs at their extreme poses.
		val renderer = Renderer(built.skeletal, 480)
		val limbs = built.spec.bones.filter { !it.role.body && !it.role.anchor }
		val rest = evaluator.evaluate(puppet, emptyMap()).worldPositions
		val frames = skinned.filter { d -> limbs.any { d.id.raw in it.drawableIds } }
			.groupBy { d -> limbs.first { d.id.raw in it.drawableIds }.side }.flatMap { (side, meshes) ->
			val xs = meshes.flatMap { d -> rest.getValue(d.id).filterIndexed { i, _ -> i % 2 == 0 } }
			val ys = meshes.flatMap { d -> rest.getValue(d.id).filterIndexed { i, _ -> i % 2 == 1 } }
			val cx = (xs.min() + xs.max()) / 2; val cy = (ys.min() + ys.max()) / 2
			val half = maxOf(xs.max() - xs.min(), ys.max() - ys.min()) * 0.9f
			val rect = Bounds(cx - half, cy - half, cx + half, cy + half)
			val bones = limbs.filter { it.side == side }
			val poses = listOf(emptyMap<String, Float>(), bones.associate { it.parameterId to it.minAngle }, bones.associate { it.parameterId to it.maxAngle }) +
				bones.flatMap { b -> listOf(mapOf(b.parameterId to b.minAngle), mapOf(b.parameterId to b.maxAngle)) }
			poses.map { values -> "$side ${values.entries.joinToString { "${it.key}=${it.value.toInt()}" }}".take(60) to renderer.render(values, rect) }
		}
		sheet(frames, File(out, "$label-limbs.png"), 6)

		val reference = System.getenv("PSD2LIVE_REFERENCE")?.takeIf { it.isNotBlank() } ?: return
		val previous = LinkedHashMap<String, FloatArray>()
		DataInputStream(File(out, "$reference.bin").inputStream().buffered()).use { i ->
			repeat(i.readInt()) { previous[i.readUTF()] = FloatArray(i.readInt()) { i.readFloat() } }
		}
		val missing = positions.keys - previous.keys
		val gone = previous.keys - positions.keys
		println("keyforms: ${positions.size} now, ${previous.size} in $reference, ${missing.size} new, ${gone.size} gone")
		val worst = HashMap<String, Pair<Double, String>>()
		for ((name, now) in positions) {
			val before = previous[name] ?: continue
			var most = 0.0
			for (v in 0 until now.size / 2) most = maxOf(most, hypot((now[v * 2] - before[v * 2]).toDouble(), (now[v * 2 + 1] - before[v * 2 + 1]).toDouble()))
			val mesh = name.substringBefore(' ')
			if (most >= (worst[mesh]?.first ?: -1.0)) worst[mesh] = most to name
		}
		for ((mesh, w) in worst.toSortedMap()) println("$mesh: largest vertex difference %.5f px (${w.second})".format(w.first))
		println("overall largest vertex difference: %.5f px".format(worst.values.maxOfOrNull { it.first } ?: 0.0))
		val before = ImageIO.read(File(out, "$reference-limbs.png"))
		val after = ImageIO.read(File(out, "$label-limbs.png"))
		if (before.width == after.width && before.height == after.height) {
			val diff = BufferedImage(after.width, after.height, BufferedImage.TYPE_INT_RGB)
			var largest = 0
			for (y in 0 until after.height) for (x in 0 until after.width) {
				val a = before.getRGB(x, y); val b = after.getRGB(x, y)
				val d = IntArray(3) { abs((a shr (16 - it * 8) and 255) - (b shr (16 - it * 8) and 255)) }
				largest = maxOf(largest, d.max())
				diff.setRGB(x, y, d.fold(0) { acc, c -> (acc shl 8) or minOf(255, c * 8) })
			}
			ImageIO.write(diff, "png", File(out, "$label-vs-$reference.png"))
			println("largest channel difference of the renders: $largest / 255")
		}
	}
}
