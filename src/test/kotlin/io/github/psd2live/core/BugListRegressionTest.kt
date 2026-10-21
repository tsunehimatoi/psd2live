package io.github.psd2live.core

import io.github.psd2live.commandArguments
import io.github.psd2live.fitWindowSize
import io.github.psd2live.format.compile.ClipSampler
import io.github.psd2live.format.model.Clip
import io.github.psd2live.format.model.Curve
import io.github.psd2live.format.model.CurveSegment
import io.github.psd2live.targets.raster.gifCentisecondDelays
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BugListRegressionTest {
	@Test
	fun exportCommandKeepsItsArgumentsWhenLanguageIsSet() {
		assertEquals(listOf("export", "model.psd2live", "--target", "gif"),
			commandArguments(arrayOf("--lang", "zh", "export", "model.psd2live", "--target", "gif")))
		assertEquals(listOf("export", "model.psd", "--target", "png-sequence"),
			commandArguments(arrayOf("export", "model.psd", "--lang", "en", "--target", "png-sequence")))
	}

	@Test
	fun aSavedWindowLargerThanTheScreenOpensWithinIt() {
		val usable = java.awt.Rectangle(0, 0, 1707, 1019)
		val fitted = fitWindowSize(2560f, 1500f, usable)
		assertEquals(1707f, fitted.width.value); assertEquals(1019f, fitted.height.value)
		val small = fitWindowSize(1280f, 820f, usable)
		assertEquals(1280f, small.width.value); assertEquals(820f, small.height.value)
		assertEquals(2560f, fitWindowSize(2560f, 1500f, null).width.value)
	}

	@Test
	fun twoBoneSurvivesACoincidentParentJoint() {
		val joint = SkeletonIk.twoBone(0.0, 0.0, 0.0, 10.0, 6.0, 8.0, 1.0)
		assertTrue(joint[0].isFinite() && joint[1].isFinite())
		val folded = SkeletonIk.twoBone(1.0, 2.0, 0.0, 0.0, 3.0, 4.0, -1.0)
		assertEquals(1.0, folded[0])
		assertEquals(2.0, folded[1])
	}

	@Test
	fun gifDelaysTrackTheRequestedFrameRate() {
		val delays = gifCentisecondDelays(6, 60f)
		assertEquals(10, delays.sum())
		assertTrue(delays.all { it in 1..2 })
		assertEquals(intArrayOf(1, 1, 1).toList(), gifCentisecondDelays(3, 120f).toList())
	}

	@Test
	fun bezierExportFollowsTimeHandles() {
		val curve = Curve("Param", 0f, 0f, listOf(CurveSegment.Bezier(0.9f, 0f, 0.95f, 0f, 1f, 100f)))
		assertTrue(ClipSampler.valueAt(curve, 0.5f) < 5f)
	}

	@Test
	fun oneShotFrameTimesIncludeTheClipEnd() {
		val clip = Clip("id", "name", "", "", 0.99f, 30f, loop = false, curves = emptyList())
		val times = ClipSampler.frameTimes(clip, 30f)
		assertEquals(0f, times.first())
		assertEquals(0.99f, times.last())
		val exact = Clip("id", "name", "", "", 1f, 30f, loop = false, curves = emptyList())
		assertEquals(31, ClipSampler.frameTimes(exact, 30f).size)
		assertEquals(30, ClipSampler.frameTimes(exact.copy(loop = true), 30f).size)
	}

	@Test
	fun shorteningAClipKeepsTheBezierThatRemains() {
		val curve = MotionCurve("Param", listOf(
			MotionKey(0f, 0f, outHandle = MotionHandle(0.1f, 80f)),
			MotionKey(1f, 100f, inHandle = MotionHandle(0.1f, -80f)),
		))
		val clip = MotionClip("id", "name", duration = 1f, curves = listOf(curve))
		val shortened = MotionKeyEdits.withDuration(clip, 0.5f)
		val kept = shortened.curves.single().keys
		assertEquals(2, kept.size)
		assertEquals(0.5f, kept.last().time)
		val before = MotionClips.sample(curve, 0.25f)
		val after = MotionClips.sample(shortened.curves.single(), 0.25f)
		assertTrue(kotlin.math.abs(before - after) < 0.5f, "before $before after $after")
	}

	@Test
	fun floodFillHonorsOpacity() {
		val image = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB)
		image.setRGB(0, 0, 0xFFFF0000.toInt())
		image.setRGB(1, 0, 0xFFFF0000.toInt())
		RasterPaintEngine.floodFill(image, 0, 0, 0xFF00FF00.toInt(), 0, opacity = 0.5f)
		val alpha = (image.getRGB(0, 0) ushr 24) and 0xFF
		assertTrue(alpha in 120..135, "alpha $alpha")
	}

	@Test
	fun exportPublishReplacesThePreviousSetAndRollsBack() {
		val root = Files.createTempDirectory("psd2live-export")
		val stage = Files.createTempDirectory(root, "stage-")
		Files.writeString(root.resolve("a.png"), "old-a")
		Files.writeString(root.resolve("b.png"), "old-b")
		Files.writeString(root.resolve("notes.txt"), "keep")
		Files.writeString(root.resolve("model.gif.report.json"),
			"""{"target":"gif","compiler":"test","files":["a.png","b.png"],"losses":[]}""")
		Files.writeString(stage.resolve("a.png"), "new-a")
		ExportPublication.commit(root, stage, listOf("a.png"), "model.gif.report.json",
			"""{"target":"gif","compiler":"test","files":["a.png"],"losses":[]}""")
		assertEquals("new-a", Files.readString(root.resolve("a.png")))
		assertTrue(!Files.exists(root.resolve("b.png")))
		assertEquals("keep", Files.readString(root.resolve("notes.txt")))

		Files.writeString(root.resolve("a.png"), "kept-a")
		Files.writeString(root.resolve("b.png"), "kept-b")
		val again = Files.createTempDirectory(root, "stage-")
		Files.writeString(again.resolve("a.png"), "failed-a")
		Files.writeString(again.resolve("b.png"), "failed-b")
		var moved = 0
		assertFailsWith<IllegalStateException> {
			ExportPublication.commit(root, again, listOf("a.png", "b.png"), "model.gif.report.json",
				"""{"target":"gif","compiler":"test","files":["a.png","b.png"],"losses":[]}""") { source, target ->
				moved++
				if (moved > 1) throw IllegalStateException("disk full")
				Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
			}
		}
		assertEquals("kept-a", Files.readString(root.resolve("a.png")))
		assertEquals("kept-b", Files.readString(root.resolve("b.png")))
	}
}
