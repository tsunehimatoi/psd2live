package io.github.psd2live.core

import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.ui.SkiaRigPainter
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Java2D painter draws masks, colours and blends as the preview does. */
class CanvasMaskPaintTest {
	private fun quad(x0: Float, x1: Float) = DrawableMesh(floatArrayOf(x0, 0f, x1, 0f, x0, 16f, x1, 16f),
		floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), intArrayOf(0, 1, 2, 1, 3, 2))

	/** A 4×4 page, [argb] where [opaque] says, transparent elsewhere. */
	private fun page(argb: Int, opaque: (Int) -> Boolean = { true }) = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB).apply {
		for (y in 0 until 4) for (x in 0 until 4) setRGB(x, y, if (opaque(x)) argb else 0)
	}

	/** Paints [drawables] (back to front) on pages by their texture page and returns the 16×16 image. */
	private fun paint(drawables: List<Drawable>, pages: List<BufferedImage>, only: Set<String>? = null): BufferedImage {
		val model = PuppetModel(emptyList(), emptyList(), emptyList(), drawables,
			drawables.map { OrgChild.Drawable(it.id) }, null, canvasWidth = 16f, canvasHeight = 16f)
		return paint(model, pages, only)
	}

	private fun paint(model: PuppetModel, pages: List<BufferedImage>, only: Set<String>? = null,
	                  parameters: Map<ParameterId, Float> = emptyMap()): BufferedImage {
		val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
		val g = image.createGraphics()
		try {
			RigCanvasSupport.paintPuppet(g, model, pages, CpuDeformationEvaluator().evaluate(model, parameters),
				CanvasViewport(1.0, 0.0, 0.0, 16f, 16f), only = only)
		} finally { g.dispose() }
		return image
	}

	/** Alpha of the clipped part at a pixel inside its mask's opaque texels and at one outside them. */
	private fun masked(invert: Boolean, maskColor: Int = 0xff00ff00.toInt()): Pair<Int, Int> {
		// The mask's mesh spans the whole canvas, but only its left half is opaque.
		val mask = Drawable(DrawableId("mask"), "Mask", null, BlendMode.Normal, emptyList(), quad(0f, 16f), null, texturePage = 1)
		val part = Drawable(DrawableId("part"), "Part", null, BlendMode.Normal, listOf(mask.id), quad(0f, 16f), null,
			texturePage = 0, invertMask = invert)
		val image = paint(listOf(mask, part), listOf(page(0xffff0000.toInt()), page(maskColor) { it < 2 }), only = setOf("part"))
		return (image.getRGB(4, 8) ushr 24) to (image.getRGB(12, 8) ushr 24)
	}

	@Test fun clippedPartShowsOnlyOverItsMasksOpaqueTexels() {
		val (inside, outside) = masked(invert = false)
		assertTrue(inside > 200 && outside == 0, "inside $inside, outside $outside")
	}

	@Test fun invertedMaskShowsThePartOnlyOutsideIt() {
		val (inside, outside) = masked(invert = true)
		assertTrue(inside == 0 && outside > 200, "inside $inside, outside $outside")
	}

	@Test fun featheredMeshMaskKeepsPartialCoverageInBothDirections() {
		val color = 0x4000ff00
		val (covered, outside) = masked(invert = false, maskColor = color)
		val (inverted, remainder) = masked(invert = true, maskColor = color)
		assertTrue(covered in 63..65)
		assertTrue(inverted in 190..192)
		assertEquals(0, outside)
		assertEquals(255, remainder)
	}

	@Test fun multiplyAndScreenColoursTintThePart() {
		val part = Drawable(DrawableId("part"), "Part", null, BlendMode.Normal, emptyList(), quad(0f, 16f), null, texturePage = 0,
			multiplyColor = ColorRgb(0.5f, 1f, 1f), screenColor = ColorRgb(0f, 0f, 1f))
		val rgb = paint(listOf(part), listOf(page(0xffff0000.toInt()))).getRGB(8, 8)
		// Red halved by the multiply colour, blue screened to full.
		assertTrue(rgb shr 16 and 0xff in 125..130 && rgb and 0xff == 255, Integer.toHexString(rgb))
	}

	@Test fun multiplyBlendDarkensWhatIsUnder() {
		val base = Drawable(DrawableId("base"), "Base", null, BlendMode.Normal, emptyList(), quad(0f, 16f), null, texturePage = 1, drawOrder = 400f)
		val part = Drawable(DrawableId("part"), "Part", null, BlendMode.Multiply, emptyList(), quad(0f, 16f), null, texturePage = 0, drawOrder = 600f)
		val rgb = paint(listOf(base, part), listOf(page(0xffff0000.toInt()), page(0xff808080.toInt()))).getRGB(8, 8)
		assertTrue(rgb shr 16 and 0xff in 126..130 && rgb shr 8 and 0xff == 0 && rgb and 0xff == 0, Integer.toHexString(rgb))
	}

	private fun isolatedFixture(): Pair<ParameterId, RigPreviewModel> {
		fun child(name: String, left: Float, right: Float, page: Int, opacity: Float = 1f) = Drawable(
			DrawableId(name), name, null, BlendMode.Normal, emptyList(), quad(left, right), null,
			drawOrder = 400f, opacity = opacity, texturePage = page,
		)
		val receiver = child("receiver", 0f, 16f, 0)
		val clip = child("clip", 0f, 16f, 1, opacity = 0f)
		val left = child("left", 0f, 12f, 2)
		val right = child("right", 4f, 16f, 2)
		val key = ParameterId("amount")
		val track = KeyformGrid<ChannelValue>(listOf(KeyformAxis(key, floatArrayOf(0f, 1f))), listOf(
			KeyformCell(intArrayOf(0), ChannelValue.Scalar(0f)), KeyformCell(intArrayOf(1), ChannelValue.Scalar(0.5f)),
		))
		val group = Part(PartId("shadows"), "Shadows", listOf(OrgChild.Drawable(left.id), OrgChild.Drawable(right.id)),
			groupMode = PartGroupMode.Isolated, drawOrder = 600,
			channelGrids = ChannelGrids(mapOf(FormChannel.OPACITY to track)),
			composite = PartComposite(blendMode = BlendMode.Multiply, maskedBy = listOf(clip.id), opacity = 1f),
		)
		val model = PuppetModel(listOf(Parameter(key, "Amount", 0f, 1f, 0f)), listOf(group), emptyList(),
			listOf(receiver, clip, left, right), listOf(OrgChild.Part(group.id), OrgChild.Drawable(receiver.id), OrgChild.Drawable(clip.id)),
			null, canvasWidth = 16f, canvasHeight = 16f).withDerivedRenderRoot()
		val pages = listOf(page(0xffffffff.toInt()), page(0x80ffffff.toInt()) { it < 2 }, page(0xff808080.toInt()))
		val bounds = Bounds(0f, 0f, 16f, 16f)
		val analysis = PipelineAnalysis(WorkspaceSourceArt(16, 16, emptyList(), emptyList()), emptyList(),
			RigAnchors(bounds, bounds, bounds, 8f, 8f, 8f, 12f, 12f, 14f), emptyList(), pages.first())
		return key to RigPreviewModel(analysis, PackedAtlas(pages.map { AtlasPage(it) }, emptyMap()),
			BuiltRig(model, emptyMap(), emptyMap(), emptyMap(), 8f, 8f, 8f, 8f, emptyList()), PipelineConfig(),
			CubismRuntimeBundle.deferred { error("This fixture draws directly from its atlas") })
	}

	@Test fun isolatedShadowsCompositeOnceAndUseTheReceiversAlpha() {
		val (key, model) = isolatedFixture()
		val pages = model.atlas.pages.map { it.image }
		assertIsolatedCoverage(paint(model.rig.puppet, pages, parameters = mapOf(key to 0f)),
			paint(model.rig.puppet, pages, parameters = mapOf(key to 1f)))
	}

	@Test fun editingCanvasCompositesIsolatedShadowsWithTheSameCoverage() {
		val (key, model) = isolatedFixture()
		SkiaRigPainter(model.atlas).use { painter ->
			fun render(amount: Float): BufferedImage = Surface.makeRasterN32Premul(16, 16).use { surface ->
				painter.paint(surface.canvas, model, RigCanvasSupport.evaluateExact(model, mapOf(key to amount)),
					CanvasViewport(1.0, 0.0, 0.0, 16f, 16f))
				surface.makeImageSnapshot().use { image ->
					requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data -> ImageIO.read(data.bytes.inputStream()) }
				}
			}
			assertIsolatedCoverage(render(0f), render(1f))
		}
	}

	private fun assertIsolatedCoverage(before: BufferedImage, after: BufferedImage) {
		fun red(image: BufferedImage, x: Int): Int = image.getRGB(x, 8) shr 16 and 0xff
		assertEquals(255, red(before, 6))
		assertTrue(red(after, 2) in 220..225)
		assertEquals(red(after, 2), red(after, 5), "two cast silhouettes must darken the overlap once")
		assertEquals(255, red(after, 14), "the composite must be clipped away outside its receiver")
	}
}
