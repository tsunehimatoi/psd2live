package io.github.psd2live.tools

import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.keyform.ChannelValueInterpolator
import org.umamo.runtime.keyform.CompactionResult
import org.umamo.runtime.keyform.compacted
import org.umamo.runtime.keyform.refinedToUnion
import org.umamo.runtime.keyform.withChannelsCompacted
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test

/**
 * How long the post-import channel compaction takes on Cubism runtime models, and whether its result is
 * still the same rig. Writes build/tools/compaction-perf/report.txt.
 *
 * For each .model3.json PSD2LIVE_MODEL3 names (a file, or a directory searched recursively): the import
 * time without compaction, the largest channel grids and the slowest ones to compact, how many compacted
 * grids do not refine back to their source bit-for-bit, a digest of every compacted grid (to compare two
 * builds), the whole model's compaction time, and the largest difference between the compacted and
 * uncompacted puppets over PSD2LIVE_POSES random poses (64 by default; the default pose first).
 *
 * Each grid compacts on its own thread; one still running after PSD2LIVE_GRID_TIMEOUT_MS (10 s by default)
 * is reported as STALLED with its keys and values and ends the run: the thread cannot be stopped, so going
 * on would leave a core spinning per stall.
 *
 * PSD2LIVE_TOOLS=1 PSD2LIVE_MODEL3=<file or directory> ./gradlew test --tests '*CompactionPerfTool'
 */
class CompactionPerfTool {
	@Test fun compaction() {
		requireTools()
		val path = File(setting("PSD2LIVE_MODEL3", ""))
		val files = if (path.isDirectory) path.walk().filter { it.name.endsWith(".model3.json") }.sortedBy { it.path }.toList() else listOf(path)
		require(files.isNotEmpty() && files.all { it.isFile }) { "Set PSD2LIVE_MODEL3 to a .model3.json file or a directory of them" }
		val poseCount = setting("PSD2LIVE_POSES", "64").toInt()
		// Written line by line, so a compaction that never finishes still shows how far it got.
		File(output("compaction-perf"), "report.txt").printWriter().use { writer ->
			val emit = { line: String ->
				println(line)
				writer.println(line)
				writer.flush()
			}
			for (file in files) {
				// A downloaded corpus holds models this reader rejects; they say why and the scan goes on.
				runCatching { profile(file, poseCount, emit) }.onFailure { failure -> if (failure is Stalled) throw failure else emit("  skipped: $failure") }
			}
		}
	}

	private fun profile(file: File, poseCount: Int, emit: (String) -> Unit) {
		emit("== ${file.name}")
		val manifest = Moc3.readModel3(file.readText())
		val moc = Moc3.read(File(file.parentFile, manifest.fileReferences.moc).readBytes())
		val cdi = manifest.fileReferences.displayInfo?.let { File(file.parentFile, it) }?.takeIf { it.isFile }?.let { Moc3.readCdi3(it.readText()) }
		var started = System.nanoTime()
		val plain = Moc3Import.fromMocDocument(moc, cdi, compactChannels = false)
		emit("  import without compaction ${millis(started)} ms")

		val grids = channelGrids(plain).sortedByDescending { it.second.cells.size }
		emit("  channel grids ${grids.size}, cells ${grids.sumOf { it.second.cells.size }}")
		emit("  largest:")
		for ((label, grid) in grids.take(5)) {
			emit("    $label ${shape(grid)}")
		}
		// The same span test withChannelsCompacted applies, so each grid compacts here exactly as it does there.
		val rangeById = plain.parameters.associate { it.id to (minOf(it.min, it.max) to maxOf(it.min, it.max)) }
		val axisSpansRange = { axis: KeyformAxis ->
			val range = rangeById[axis.parameterId]
			range != null && axis.keys.isNotEmpty() && axis.keys.first() <= range.first + EPS_KEY && axis.keys.last() >= range.second - EPS_KEY
		}
		// Each grid on its own thread with a time limit, so a compaction that never returns names its grid.
		val timeoutMillis = setting("PSD2LIVE_GRID_TIMEOUT_MS", "10000").toLong()
		var notRestored = 0
		var digest = 1L
		val timings = ArrayList<Pair<String, Double>>()
		for ((label, grid) in grids) {
			var result: CompactionResult<ChannelValue>? = null
			val gridStarted = System.nanoTime()
			val worker = Thread { result = grid.compacted(ChannelValueInterpolator, label.substringAfterLast('.').let(FormChannel::valueOf).valueKind, axisSpansRange) }
			worker.isDaemon = true
			worker.start()
			worker.join(timeoutMillis)
			val elapsed = (System.nanoTime() - gridStarted) / 1_000_000.0
			val finished = result
			if (finished == null) {
				emit("  STALLED after $timeoutMillis ms: $label ${shape(grid)}")
				for (axis in grid.axes) {
					emit("    axis ${axis.parameterId.raw} keys ${axis.keys.joinToString()} range ${rangeById[axis.parameterId]} spans ${axisSpansRange(axis)}")
				}
				emit("    cells ${grid.cells.sortedBy { grid.linearIndexOf(it.coordinate) }.joinToString { it.form.toString() }}")
				throw Stalled(label)
			}
			val reduced = (finished as? CompactionResult.Reduced)?.grid
			digest = digest * 31 + digestOf(finished)
			val description = "$label ${shape(grid)} -> ${reduced?.let(::shape) ?: "constant"} in ${"%.1f".format(elapsed)} ms"
			if (elapsed > 100.0) {
				emit("    slow: $description")
			}
			timings += description to elapsed
			// A dropped axis is not re-added by refinement, so only grids that kept every axis compare.
			if (reduced != null && reduced.axes.size == grid.axes.size) {
				val restored = reduced.refinedToUnion(grid.axes.associate { it.parameterId to it.keys }, emptyMap(), ChannelValueInterpolator)
				if (!sameCells(grid, restored)) {
					notRestored++
				}
			}
		}
		emit("  slowest grids:")
		for ((description, _) in timings.sortedByDescending { it.second }.take(5)) {
			emit("    $description")
		}
		emit("  grids that kept every axis but do not refine back bit-exact: $notRestored")
		emit("  digest of every compacted grid ${java.lang.Long.toHexString(digest)}")

		started = System.nanoTime()
		val compacted = plain.withChannelsCompacted()
		emit("  model compaction ${millis(started)} ms")

		val evaluator = CpuDeformationEvaluator()
		val random = Random(1)
		var worst = Difference(0f, "")
		var differingPoses = 0
		repeat(poseCount) { poseIndex ->
			val pose =
				if (poseIndex == 0) {
					emptyMap()
				} else {
					plain.parameters.associate { it.id to (it.min + random.nextFloat() * (it.max - it.min)) }
				}
			val difference = difference(evaluator.evaluate(plain, pose), evaluator.evaluate(compacted, pose))
			if (difference.amount > 0f) {
				differingPoses++
			}
			if (difference.amount > worst.amount) {
				worst = Difference(difference.amount, difference.where, difference.drawable, pose)
			}
		}
		emit("  compacted vs uncompacted over $poseCount poses: $differingPoses differ, largest difference ${worst.amount} ${worst.where}")
		// Past float rounding: the drawable's tracks before and after, and where the pose sits on their axes.
		val drawableId = worst.drawable
		if (worst.amount > 0.01f && drawableId != null) {
			for ((label, model) in listOf("uncompacted" to plain, "compacted" to compacted)) {
				val drawable = model.drawables.first { it.id == drawableId }
				emit("    $label ${drawableId.raw}: static draw order ${drawable.drawOrder}, opacity ${drawable.opacity}")
				for ((channel, grid) in drawable.channelGrids.gridsByChannel) {
					if (channel != FormChannel.DRAW_ORDER && channel != FormChannel.OPACITY) {
						continue
					}
					emit("      $channel ${shape(grid)} ${grid.cells.sortedBy { grid.linearIndexOf(it.coordinate) }.joinToString { it.form.toString() }}")
					for (axis in grid.axes) {
						val parameter = plain.parameters.first { it.id == axis.parameterId }
						val value = worst.pose[axis.parameterId] ?: parameter.default
						emit("        ${axis.parameterId.raw} at $value (${parameter.min}..${parameter.max}) keys ${axis.keys.joinToString()}")
					}
				}
			}
		}
	}

	private fun channelGrids(model: PuppetModel): List<Pair<String, KeyformGrid<ChannelValue>>> = buildList {
		fun add(owner: String, grids: ChannelGrids) {
			for ((channel: FormChannel, grid) in grids.gridsByChannel) {
				add("$owner.$channel" to grid)
			}
		}
		model.drawables.forEach { add("drawable ${it.id.raw}", it.channelGrids) }
		model.deformers.forEach { add("deformer ${it.id.raw}", it.channelGrids) }
		model.parts.forEach { add("part ${it.id.raw}", it.channelGrids) }
		model.glues.forEach { add("glue ${it.id ?: (it.meshA.raw + "+" + it.meshB.raw)}", it.channelGrids) }
	}

	/** A hash of a compaction result's axes, keys, cell order, coordinates and values. */
	private fun digestOf(result: CompactionResult<ChannelValue>): Long {
		val grid = (result as? CompactionResult.Reduced)?.grid ?: return (result as CompactionResult.Constant).form.hashCode().toLong()
		var hash = 7L
		for (axis in grid.axes) {
			hash = hash * 31 + axis.parameterId.hashCode()
			hash = hash * 31 + axis.keys.contentHashCode()
		}
		for (cell in grid.cells) {
			hash = hash * 31 + cell.coordinate.contentHashCode()
			hash = hash * 31 + cell.form.hashCode()
		}
		return hash
	}

	private fun shape(grid: KeyformGrid<*>): String = grid.axes.joinToString("x", postfix = " (${grid.cells.size} cells)") { it.keys.size.toString() }

	private fun sameCells(original: KeyformGrid<ChannelValue>, restored: KeyformGrid<ChannelValue>): Boolean {
		if (original.cells.size != restored.cells.size) {
			return false
		}
		val restoredByIndex = restored.cellsByLinearIndex
		return original.cells.all { cell -> restoredByIndex[original.linearIndexOf(cell.coordinate)]?.form == cell.form }
	}

	/** The largest gap between two evaluations of one pose, and the drawable and quantity it is in. */
	private class Difference(val amount: Float, val where: String, val drawable: DrawableId? = null, val pose: Map<ParameterId, Float> = emptyMap())

	private fun difference(left: DeformedGeometry, right: DeformedGeometry): Difference {
		if (left.worldPositions.keys != right.worldPositions.keys) {
			return Difference(Float.POSITIVE_INFINITY, "(different visible drawables)")
		}
		var worst = Difference(0f, "")
		fun consider(amount: Float, drawable: DrawableId, where: () -> String) {
			if (amount > worst.amount) {
				worst = Difference(amount, where(), drawable)
			}
		}
		for ((id, positions) in left.worldPositions) {
			val other = right.worldPositions.getValue(id)
			for (index in positions.indices) {
				consider(abs(positions[index] - other[index]), id) { "(${id.raw} position)" }
			}
			consider(abs((left.opacity[id] ?: 0f) - (right.opacity[id] ?: 0f)), id) { "(${id.raw} opacity ${left.opacity[id]} vs ${right.opacity[id]})" }
			consider(abs((left.drawOrder[id] ?: 0f) - (right.drawOrder[id] ?: 0f)), id) { "(${id.raw} draw order ${left.drawOrder[id]} vs ${right.drawOrder[id]})" }
		}
		return worst
	}

	/** A grid that did not finish compacting in time; its thread is still running, so the scan stops. */
	private class Stalled(label: String) : Exception("compaction of $label did not finish")

	private fun millis(started: Long): Long = (System.nanoTime() - started) / 1_000_000
}
