package io.github.psd2live.format.eval

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.compile.PoseGeometry
import io.github.psd2live.format.compile.render.IrColors
import io.github.psd2live.format.compile.render.SoftwareRasterizer
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.runtime.P2lrt
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

/**
 * The runtime's software renderer (`p2l_render`) against PSD2Live's reference rasterizer, pixel for pixel, on a rig
 * covering every color blend with every alpha blend, isolated groups (channel opacity, colors, masks by mesh and by
 * part, inversion, nesting), masks and culling. Needs the runtime built.
 */
class SoftwareRenderParityTest {
	@Suppress("FunctionName")
	private interface Native13 : Library {
		fun p2l_rig_load(bytes: ByteArray, len: Long, error: ByteArray, errorCapacity: Long): Pointer?
		fun p2l_rig_free(rig: Pointer)
		fun p2l_abi_version(): Int
		fun p2l_set_parameter(rig: Pointer, index: Int, value: Float)
		fun p2l_evaluate(rig: Pointer)
		fun p2l_mesh_vertices(rig: Pointer, index: Int): Pointer?
		fun p2l_mesh_vertex_count(rig: Pointer, index: Int): Int
		fun p2l_mesh_opacity(rig: Pointer, index: Int): Float
		fun p2l_mesh_draw_order(rig: Pointer, index: Int): Float
		fun p2l_render(rig: Pointer, rgba: ByteArray, width: Int, height: Int, transform: FloatArray?, flags: Int): Byte
	}

	private fun native(): Native13 {
		val native = Native.load(nativeRuntimeLibrary().absolutePath, Native13::class.java)
		val abi = native.p2l_abi_version()
		assertEquals(1, abi ushr 16)
		assertTrue((abi and 0xffff) >= 3, "Rendering needs runtime ABI 1.3 or newer")
		return native
	}

	/** A 16 x 16 page of gradients: hue across, alpha rising down and across, a fully clear corner. */
	private fun page(): TexturePage {
		val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
		for (y in 0 until 16) for (x in 0 until 16) {
			val a = if (x < 2 && y < 2) 0 else (40 + (x + y) * 7).coerceAtMost(255)
			image.setRGB(x, y, (a shl 24) or ((x * 16) shl 16) or ((y * 16) shl 8) or (255 - x * 13))
		}
		val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
		return TexturePage(16, 16, Bytes.of(bytes))
	}

	/** A slanted quad at ([x], [y]) of size [s], sampling the page around ([u], [v]); [flip] reverses its winding. */
	private fun quad(id: String, x: Float, y: Float, s: Float, u: Float, v: Float, order: Float, blend: ColorBlend = ColorBlend.NORMAL,
	                 alpha: AlphaBlend = AlphaBlend.OVER, flip: Boolean = false, culling: Boolean = false, maskedBy: List<String> = emptyList(),
	                 invert: Boolean = false, visible: Boolean = true, channels: Channels = emptyMap()) =
		Mesh(id, id, null, blend = blend, alphaBlend = alpha, maskedBy = maskedBy, invertMask = invert, culling = culling, visible = visible,
			drawOrder = order, page = 0, channels = channels, offsets = null,
			geometry = MeshGeometry(
				Floats.values(x + 0.4f, y, x + s, y + 0.7f, x, y + s - 0.3f, x + s - 0.2f, y + s),
				Floats.values(u, v, u + 0.3f, v, u, v + 0.35f, u + 0.3f, v + 0.35f),
				if (flip) Ints.values(0, 2, 1, 1, 2, 3) else Ints.values(0, 1, 2, 1, 3, 2)))

	private fun scalar(parameter: String, low: Float, high: Float): KeyGrid<ChannelValue> =
		KeyGrid(listOf(KeyAxis(parameter, Floats.values(0f, 1f))), listOf(
			KeyCell(Ints.values(0), ChannelValue.Scalar(low) as ChannelValue), KeyCell(Ints.values(1), ChannelValue.Scalar(high) as ChannelValue)))

	private fun color(parameter: String, low: Rgb, high: Rgb): KeyGrid<ChannelValue> =
		KeyGrid(listOf(KeyAxis(parameter, Floats.values(0f, 1f))), listOf(
			KeyCell(Ints.values(0), ChannelValue.Color(low) as ChannelValue), KeyCell(Ints.values(1), ChannelValue.Color(high) as ChannelValue)))

	private fun rig(): RigIR {
		val meshes = ArrayList<Mesh>()
		val root = ArrayList<RenderNode>()
		// Every color blend over every alpha blend, each over a backdrop of its own.
		for (b in ColorBlend.entries) for (a in AlphaBlend.entries) {
			val (x, y) = (b.ordinal * 7f + 1f) to (a.ordinal * 7f + 1f)
			meshes += quad("back_${b}_$a", x, y, 6f, 0.1f, 0.5f, 400f)
			meshes += quad("top_${b}_$a", x + 1f, y + 1f, 5f, 0.55f, 0.2f, 450f, b, a)
		}
		// Masks and culling: a hidden mask, a masked mesh and its inverse; two culled quads of opposite winding.
		meshes += quad("mask", 2f, 40f, 9f, 0.6f, 0.6f, 300f, visible = false)
		meshes += quad("masked", 1f, 38f, 12f, 0.3f, 0.3f, 460f, maskedBy = listOf("mask"))
		meshes += quad("unmasked", 14f, 38f, 12f, 0.3f, 0.3f, 460f, maskedBy = listOf("mask"), invert = true)
		meshes += quad("front", 28f, 38f, 8f, 0.5f, 0.5f, 460f, culling = true, flip = true)
		meshes += quad("back", 38f, 38f, 8f, 0.5f, 0.5f, 460f, culling = true)
		meshes += quad("colored", 48f, 38f, 8f, 0.5f, 0.2f, 460f, channels = mapOf(
			Channel.MULTIPLY_COLOR to color("A", Rgb(1f, 0.2f, 0.4f), Rgb(0.5f, 1f, 1f)), Channel.SCREEN_COLOR to color("A", Rgb.Black, Rgb(0f, 0.3f, 0f))))
		meshes.forEach { root += RenderMesh(it.id) }
		// Isolated groups: overlay atop with channel opacity and colors, masked by a mesh; a nested multiply group masked
		// by a part, inverted; a disjoint group whose children use extended blends.
		val g1 = listOf(quad("g1a", 60f, 36f, 10f, 0.2f, 0.6f, 500f), quad("g1b", 64f, 39f, 10f, 0.6f, 0.1f, 510f, ColorBlend.MULTIPLY))
		val g2 = listOf(quad("g2a", 66f, 42f, 7f, 0.4f, 0.4f, 500f))
		val g3 = listOf(quad("g3a", 80f, 36f, 10f, 0.1f, 0.1f, 500f), quad("g3b", 83f, 39f, 10f, 0.5f, 0.5f, 510f, ColorBlend.SOFT_LIGHT, AlphaBlend.ATOP))
		meshes += quad("bg", 58f, 34f, 40f, 0.2f, 0.2f, 100f)
		root.add(0, RenderMesh("bg"))
		meshes += g1 + g2 + g3
		val c1 = Composite(ColorBlend.OVERLAY, AlphaBlend.ATOP, listOf("mask2"), opacity = 0.8f, multiply = Rgb(1f, 0.8f, 0.6f), screen = Rgb(0f, 0f, 0.2f))
		val c2 = Composite(ColorBlend.MULTIPLY_PREMULTIPLIED, AlphaBlend.OVER, maskedByParts = listOf("P3"), invertMask = true, opacity = 0.9f)
		val c3 = Composite(ColorBlend.SCREEN, AlphaBlend.DISJOINT, opacity = 0.7f)
		meshes += quad("mask2", 59f, 35f, 14f, 0.8f, 0.8f, 300f, visible = false)
		val p2 = Part("P2", "P2", g2.map { ChildRef.MeshRef(it.id) }, groupMode = GroupMode.ISOLATED, composite = c2)
		val p1 = Part("P1", "P1", g1.map { ChildRef.MeshRef(it.id) } + ChildRef.PartRef("P2"), groupMode = GroupMode.ISOLATED, composite = c1)
		val p3 = Part("P3", "P3", g3.map { ChildRef.MeshRef(it.id) }, groupMode = GroupMode.ISOLATED, composite = c3)
		val group1 = RenderGroup("P1", 600, g1.map { RenderMesh(it.id) } + RenderGroup("P2", 700, g2.map { RenderMesh(it.id) }, composite = c2),
			channels = mapOf(Channel.OPACITY to scalar("A", 0.3f, 0.9f), Channel.MULTIPLY_COLOR to color("A", Rgb(1f, 1f, 1f), Rgb(0.4f, 0.9f, 1f))),
			composite = c1)
		val group3 = RenderGroup("P3", 650, g3.map { RenderMesh(it.id) }, composite = c3)
		root += RenderMesh("mask2")
		root += group1
		root += group3
		return RigIR(
			canvas = Canvas(130f, 52f),
			parameters = listOf(Parameter("A", "A", 0f, 1f, 0f)),
			parts = listOf(p1, p2, p3),
			meshes = meshes,
			renderRoot = RenderGroup(null, 500, root),
			textures = Textures(pages = listOf(page())),
		)
	}

	@Test fun theRuntimesRendererDrawsAsTheReferenceRasterizer() {
		val native = native()
		val ir = rig()
		val bytes = P2lrt.write(ir)
		val error = ByteArray(256)
		val handle = native.p2l_rig_load(bytes, bytes.size.toLong(), error, error.size.toLong())
			?: fail(String(error, 0, error.indexOf(0), Charsets.UTF_8))
		try {
			for ((value, scale) in listOf(0.5f to 2, 0f to 3, 1f to 1)) {
				native.p2l_set_parameter(handle, 0, value)
				native.p2l_evaluate(handle)
				val (w, h) = (130 * scale) to (52 * scale)
				val rgba = ByteArray(w * h * 4)
				assertEquals(1, native.p2l_render(handle, rgba, w, h, null, 1).toInt())
				// The same pose through the reference: the runtime's geometry, the IR's colors and groups.
				val positions = ir.meshes.withIndex().associate { (i, m) ->
					m.id to native.p2l_mesh_vertices(handle, i)!!.getFloatArray(0, native.p2l_mesh_vertex_count(handle, i) * 2)
				}
				val pose = PoseGeometry(positions,
					ir.meshes.withIndex().associate { (i, m) -> m.id to native.p2l_mesh_opacity(handle, i) },
					ir.meshes.withIndex().associate { (i, m) -> m.id to native.p2l_mesh_draw_order(handle, i) })
				val colors = IrColors(ir)
				val values = mapOf("A" to value)
				val reference = SoftwareRasterizer(ir).render(pose, colors.at(values), FrameSpec(0f, 0f, 130f, 52f, w, h), parts = colors.parts(values)).argb
				var worst = 0
				var drawn = 0
				for (i in reference.indices) {
					val want = reference[i]
					val got = intArrayOf(rgba[i * 4 + 3].toInt() and 0xff, rgba[i * 4].toInt() and 0xff, rgba[i * 4 + 1].toInt() and 0xff, rgba[i * 4 + 2].toInt() and 0xff)
					if (want != 0) drawn++
					for ((k, shift) in listOf(24, 16, 8, 0).withIndex()) worst = maxOf(worst, abs((want ushr shift and 0xff) - got[k]))
				}
				assertTrue(drawn > w * h / 4, "the rig draws most of the image")
				assertTrue(worst <= 1, "largest channel difference $worst at A = $value, scale $scale")
			}
		} finally {
			native.p2l_rig_free(handle)
		}
	}
}
