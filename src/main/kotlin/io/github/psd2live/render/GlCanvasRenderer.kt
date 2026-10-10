package io.github.psd2live.render

import io.github.psd2live.core.CanvasViewport
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.opengl.GL33
import org.lwjgl.system.MemoryUtil
import org.umamo.runtime.model.DrawableId
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.IdentityHashMap

/**
 * Draws canvas scenes with OpenGL. Every method runs with its context current: in the app Skia's own, during a
 * [WindowGpu.turn]; in tests a headless one.
 *
 * The CPU deforms (the UI already has the geometry for picking), so the GPU only fills pixels: one VAO per mesh
 * with static UVs and indices and a dynamic position buffer, re-uploaded only when the scene hands over a
 * different array; the atlas pages as premultiplied textures; masks through the stencil buffer. The camera is a
 * uniform, so pan and zoom upload nothing.
 *
 * The texture atlas page ([AtlasScene]) goes through the same guide pass: its tiles are textured rectangles of
 * their layers' rasters, uploaded once per raster, and its wireframes line batches.
 */
internal class GlCanvasRenderer(
	/**
	 * Mipmapped atlas pages: a zoomed-out canvas samples a smaller level instead of skipping texels of the full
	 * page, which keeps edges from shimmering and the GPU's texture cache from thrashing. Off only to compare
	 * pixel for pixel with the Skia painter, which samples the full page.
	 */
	private val mipmaps: Boolean = true,
) : AutoCloseable {
	private class Program(val id: Int) {
		private val locations = HashMap<String, Int>()
		fun uniform(name: String): Int = locations.getOrPut(name) { GL20.glGetUniformLocation(id, name) }
	}

	private class MeshBuffers(val vao: Int, val positions: Int, val uvs: Int, val indices: Int) {
		var indexCount = 0
		var positionCapacity = 0
		var positionSource: FloatArray? = null
		var uvSource: FloatArray? = null
		var indexSource: IntArray? = null
	}

	/** One canvas's framebuffer and meshes. Meshes are per view: two canvases can show two poses at once. */
	private class View {
		var framebuffer = 0
		var color = 0
		var depthStencil = 0
		var capacityWidth = 0
		var capacityHeight = 0
		val meshes = HashMap<DrawableId, MeshBuffers>()
		/** Stencil reference of the last masked draw; cleared and restarted when it would overflow. */
		var stencilRef = 0
		/** The paint session's raster on the GPU, and the session it holds. */
		var paintTexture = 0
		var paintSession: Any? = null
		var paintWidth = 0
		var paintHeight = 0
		/** Layer rasters the atlas page draws, by array; those a frame no longer draws are freed after it. */
		val rasters = IdentityHashMap<ByteArray, Int>()
		val rastersDrawn: MutableSet<ByteArray> = java.util.Collections.newSetFromMap(IdentityHashMap())
		/** The page images of the last scene drawn: their textures stay while this view may draw them again. */
		var pages: List<BufferedImage> = emptyList()
	}

	private val artwork = program(Shaders.ARTWORK_VERTEX, Shaders.ARTWORK_FRAGMENT)
	private val lines = program(Shaders.LINE_VERTEX, Shaders.LINE_FRAGMENT)
	private val points = program(Shaders.POINT_VERTEX, Shaders.POINT_FRAGMENT)
	private val textures = IdentityHashMap<BufferedImage, Int>()
	private val views = HashMap<String, View>()
	private val lineVao: Int
	private val lineInstances: Int
	private val pointVao: Int
	private val pointInstances: Int
	private val fillVao: Int = GL30.glGenVertexArrays()
	private val fillVertices: Int = GL15.glGenBuffers()
	/** One textured quad: positions per draw, the texture's corners fixed. */
	private val quadVao: Int = GL30.glGenVertexArrays()
	private val quadPositions: Int = GL15.glGenBuffers()
	private val quadUvs: Int = GL15.glGenBuffers()
	/** One textured quad with its own texture corners, for the atlas page's tiles. */
	private val tileVao: Int = GL30.glGenVertexArrays()
	private val tilePositions: Int = GL15.glGenBuffers()
	private val tileUvs: Int = GL15.glGenBuffers()
	private val worldUniform = FloatArray(4)

	init {
		fun quad(corners: FloatArray, instanceComponents: Int): Pair<Int, Int> {
			val vao = GL30.glGenVertexArrays()
			GL30.glBindVertexArray(vao)
			val cornerBuffer = GL15.glGenBuffers()
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cornerBuffer)
			GL15.glBufferData(GL15.GL_ARRAY_BUFFER, corners, GL15.GL_STATIC_DRAW)
			GL20.glEnableVertexAttribArray(0)
			GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L)
			val instances = GL15.glGenBuffers()
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instances)
			GL20.glEnableVertexAttribArray(1)
			GL20.glVertexAttribPointer(1, instanceComponents, GL11.GL_FLOAT, false, 0, 0L)
			GL33.glVertexAttribDivisor(1, 1)
			GL30.glBindVertexArray(0)
			return vao to instances
		}
		quad(floatArrayOf(0f, -1f, 1f, -1f, 0f, 1f, 1f, 1f), 4).let { (vao, buffer) -> lineVao = vao; lineInstances = buffer }
		quad(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), 2).let { (vao, buffer) -> pointVao = vao; pointInstances = buffer }
		GL30.glBindVertexArray(fillVao)
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, fillVertices)
		GL20.glEnableVertexAttribArray(0)
		GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L)
		GL30.glBindVertexArray(quadVao)
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadPositions)
		GL20.glEnableVertexAttribArray(0)
		GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L)
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadUvs)
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), GL15.GL_STATIC_DRAW)
		GL20.glEnableVertexAttribArray(1)
		GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 0, 0L)
		GL30.glBindVertexArray(tileVao)
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, tilePositions)
		GL20.glEnableVertexAttribArray(0)
		GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L)
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, tileUvs)
		GL20.glEnableVertexAttribArray(1)
		GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 0, 0L)
		GL30.glBindVertexArray(0)
	}

	/**
	 * Draws [scene] for [viewId] into the bound framebuffer, whose first row is the frame's top: in the app a
	 * [GpuTarget] in Skia's context, which Skia then samples as it is.
	 */
	fun draw(viewId: String, scene: GpuScene) {
		val width = scene.width.coerceAtLeast(1)
		val height = scene.height.coerceAtLeast(1)
		val view = views.getOrPut(viewId) { View() }
		GL11.glViewport(0, 0, width, height)
		GL11.glDisable(GL11.GL_DEPTH_TEST)
		GL11.glDisable(GL11.GL_CULL_FACE)
		GL11.glDisable(GL11.GL_SCISSOR_TEST)
		GL11.glColorMask(true, true, true, true)
		GL11.glClearColor(0f, 0f, 0f, 0f)
		GL11.glClearStencil(0)
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_STENCIL_BUFFER_BIT)
		view.stencilRef = 0
		GL11.glEnable(GL11.GL_BLEND)
		GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
		worldTransform(scene.viewport, width, height)
		holdPages(view, scene)

		when (scene) {
			is CanvasScene -> {
				drawArtwork(view, scene)
				drawOverlay(view, scene.overlay, width, height)
				drawPaint(view, scene.paint)
			}
			is AtlasScene -> {
				view.rastersDrawn.clear()
				drawOverlay(view, scene.overlay, width, height)
				// Rasters the page no longer shows (another page, a replaced image, the pixels hidden) give their textures back.
				if (view.rasters.size > view.rastersDrawn.size) {
					val iterator = view.rasters.entries.iterator()
					while (iterator.hasNext()) {
						val (rgba, texture) = iterator.next()
						if (rgba !in view.rastersDrawn) { GL11.glDeleteTextures(texture); iterator.remove() }
					}
				}
			}
		}

		GL30.glBindVertexArray(0)
	}

	/**
	 * Draws [scene] into [viewId]'s own framebuffer and reads it back as a premultiplied RGBA bitmap, top row first;
	 * for tests and tools on a headless context, which compare it with the software painter. The app never reads back.
	 */
	fun render(viewId: String, scene: GpuScene): Bitmap {
		val width = scene.width.coerceAtLeast(1)
		val height = scene.height.coerceAtLeast(1)
		val view = views.getOrPut(viewId) { View() }
		ensureTarget(view, width, height)
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, view.framebuffer)
		draw(viewId, scene)
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, view.framebuffer)
		// Straight into the bitmap's own memory: no direct buffer, no heap array, no second copy into Skia.
		val bitmap = Bitmap()
		bitmap.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
		val pixmap = checkNotNull(bitmap.peekPixels()) { "Frame bitmap has no pixels" }
		try {
			GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
			GL11.glPixelStorei(GL12.GL_PACK_ROW_LENGTH, pixmap.rowBytes / 4)
			GL11.nglReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixmap.addr)
			GL11.glPixelStorei(GL12.GL_PACK_ROW_LENGTH, 0)
		} finally {
			pixmap.close()
		}
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
		bitmap.notifyPixelsChanged()
		bitmap.setImmutable()
		return bitmap
	}

	/** Frees what [viewId] holds on the GPU; the next render for it starts over. */
	fun release(viewId: String) {
		val view = views.remove(viewId) ?: return
		view.meshes.values.forEach(::deleteMesh)
		if (view.framebuffer != 0) GL30.glDeleteFramebuffers(view.framebuffer)
		if (view.color != 0) GL30.glDeleteRenderbuffers(view.color)
		if (view.depthStencil != 0) GL30.glDeleteRenderbuffers(view.depthStencil)
		if (view.paintTexture != 0) GL11.glDeleteTextures(view.paintTexture)
		view.rasters.values.forEach(GL11::glDeleteTextures)
		if (view.pages.isNotEmpty()) dropUnusedTextures()
	}

	/** How many atlas page textures are on the GPU. */
	internal val pageTextureCount: Int get() = textures.size

	/**
	 * Makes the page images [scene] may draw, its model's atlas and the atlas page's tiles, the ones [view] holds.
	 * Every atlas rebuild makes new page images, so once no view's latest scene shows the old ones their textures,
	 * and the images they keep alive, are freed.
	 */
	private fun holdPages(view: View, scene: GpuScene) {
		val pages = ArrayList<BufferedImage>()
		if (scene is CanvasScene) scene.model.atlas.pages.mapTo(pages) { it.image }
		val overlay = when (scene) {
			is CanvasScene -> scene.overlay
			is AtlasScene -> scene.overlay
		}
		for (item in overlay.items) {
			val image = ((item as? TextureQuad)?.texture as? ImageTexture)?.image ?: continue
			if (pages.none { it === image }) pages += image
		}
		val changed = pages.size != view.pages.size || pages.indices.any { pages[it] !== view.pages[it] }
		if (!changed) return
		view.pages = pages
		dropUnusedTextures()
	}

	/** Deletes the page textures no view holds; two views can show two atlases at once. */
	private fun dropUnusedTextures() {
		if (textures.isEmpty()) return
		val held = java.util.Collections.newSetFromMap(IdentityHashMap<BufferedImage, Boolean>())
		for (view in views.values) held += view.pages
		val iterator = textures.entries.iterator()
		while (iterator.hasNext()) {
			val (image, texture) = iterator.next()
			if (image !in held) { GL11.glDeleteTextures(texture); iterator.remove() }
		}
	}

	override fun close() {
		views.keys.toList().forEach(::release)
		textures.values.forEach(GL11::glDeleteTextures)
		textures.clear()
		GL20.glDeleteProgram(artwork.id); GL20.glDeleteProgram(lines.id); GL20.glDeleteProgram(points.id)
		GL30.glDeleteVertexArrays(lineVao); GL30.glDeleteVertexArrays(pointVao); GL30.glDeleteVertexArrays(fillVao)
		GL30.glDeleteVertexArrays(quadVao); GL30.glDeleteVertexArrays(tileVao)
		GL15.glDeleteBuffers(lineInstances); GL15.glDeleteBuffers(pointInstances); GL15.glDeleteBuffers(fillVertices)
		GL15.glDeleteBuffers(quadPositions); GL15.glDeleteBuffers(quadUvs)
		GL15.glDeleteBuffers(tilePositions); GL15.glDeleteBuffers(tileUvs)
	}

	private fun worldTransform(viewport: CanvasViewport, width: Int, height: Int) {
		// screen = offset + world * scale (y: offsetY - world.y * scale), then screen -> clip with the top row at
		// clip -1, which is the framebuffer's first row and so the first row glReadPixels returns.
		worldUniform[0] = (2.0 * viewport.scale / width).toFloat()
		worldUniform[1] = (2.0 * viewport.offsetX / width - 1.0).toFloat()
		worldUniform[2] = (-2.0 * viewport.scale / height).toFloat()
		worldUniform[3] = (2.0 * viewport.offsetY / height - 1.0).toFloat()
	}

	private fun drawArtwork(view: View, scene: CanvasScene) {
		val model = scene.model
		val drawables = model.rig.puppet.drawables.associateBy { it.id }
		val pages = model.atlas.pages
		GL20.glUseProgram(artwork.id)
		GL20.glUniform4fv(artwork.uniform("u_world"), worldUniform)
		GL20.glUniform1i(artwork.uniform("u_texture"), 0)
		GL13.glActiveTexture(GL13.GL_TEXTURE0)
		val solid = artwork.uniform("u_solid")
		val opacity = artwork.uniform("u_opacity")
		val used = HashSet<DrawableId>()
		fun meshFor(id: DrawableId): MeshBuffers? {
			val drawable = drawables[id] ?: return null
			val mesh = drawable.mesh ?: return null
			val world = scene.geometry.worldPositions[id] ?: return null
			used += id
			return syncMesh(view, id, world, mesh.uvs, mesh.indices)
		}
		for (draw in scene.draws) {
			val buffers = meshFor(draw.drawableId) ?: continue
			if (buffers.indexCount == 0) continue
			val page = pages.getOrNull(draw.page) ?: continue
			val masked = draw.maskIds.isNotEmpty()
			if (masked) {
				if (view.stencilRef == 255) {
					GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT)
					view.stencilRef = 0
				}
				val ref = ++view.stencilRef
				// Mask triangles write the reference without colour; the part then draws where it matches.
				GL11.glEnable(GL11.GL_STENCIL_TEST)
				GL11.glColorMask(false, false, false, false)
				GL11.glStencilFunc(GL11.GL_ALWAYS, ref, 0xff)
				GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE)
				GL20.glUniform4f(solid, 0f, 0f, 0f, 1f)
				for (maskId in draw.maskIds) {
					val mask = meshFor(maskId) ?: continue
					drawMesh(mask)
				}
				GL11.glColorMask(true, true, true, true)
				GL11.glStencilFunc(GL11.GL_EQUAL, ref, 0xff)
				GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP)
			}
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture(page.image))
			GL20.glUniform4f(solid, 0f, 0f, 0f, -1f)
			GL20.glUniform1f(opacity, draw.opacity)
			drawMesh(buffers)
			if (draw.tintColor != 0) {
				val a = draw.tintAlpha.coerceIn(0f, 1f)
				val c = draw.tintColor
				GL20.glUniform4f(solid, (c ushr 16 and 0xff) / 255f * a, (c ushr 8 and 0xff) / 255f * a, (c and 0xff) / 255f * a, a)
				drawMesh(buffers)
			}
			if (masked) GL11.glDisable(GL11.GL_STENCIL_TEST)
		}
		GL30.glBindVertexArray(0)
		// Meshes that left the scene (deleted, hidden for good) give their buffers back.
		if (view.meshes.size > used.size) {
			val gone = view.meshes.keys.filter { it !in used }
			for (id in gone) view.meshes.remove(id)?.let(::deleteMesh)
		}
	}

	private fun drawMesh(buffers: MeshBuffers) {
		GL30.glBindVertexArray(buffers.vao)
		GL11.glDrawElements(GL11.GL_TRIANGLES, buffers.indexCount, GL11.GL_UNSIGNED_INT, 0L)
	}

	private fun drawOverlay(view: View, overlay: OverlayScene, width: Int, height: Int) {
		if (overlay.items.isEmpty()) return
		// The artwork's masks leave their references behind; the fills count from a clean stencil.
		GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT)
		for (item in overlay.items) when (item) {
			is PolylineBatch -> drawPolyline(item, width, height)
			is LineBatch -> {
				if (item.segments.size < 4) continue
				GL20.glUseProgram(lines.id)
				GL20.glUniform1f(lines.uniform("u_once"), 0f)
				GL20.glUniform4fv(lines.uniform("u_world"), worldUniform)
				GL20.glUniform2f(lines.uniform("u_viewport"), width.toFloat(), height.toFloat())
				GL30.glBindVertexArray(lineVao)
				GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, lineInstances)
				GL15.glBufferData(GL15.GL_ARRAY_BUFFER, item.segments, GL15.GL_STREAM_DRAW)
				GL20.glUniform1f(lines.uniform("u_width"), item.width)
				setColor(lines.uniform("u_color"), item.argb)
				GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP, 0, 4, item.segments.size / 4)
			}
			is PointBatch -> {
				if (item.centers.size < 2) continue
				GL20.glUseProgram(points.id)
				GL20.glUniform1f(points.uniform("u_once"), 0f)
				GL20.glUniform4fv(points.uniform("u_world"), worldUniform)
				GL20.glUniform2f(points.uniform("u_viewport"), width.toFloat(), height.toFloat())
				GL30.glBindVertexArray(pointVao)
				GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, pointInstances)
				GL15.glBufferData(GL15.GL_ARRAY_BUFFER, item.centers, GL15.GL_STREAM_DRAW)
				GL20.glUniform1f(points.uniform("u_radius"), item.radius)
				GL20.glUniform1f(points.uniform("u_ring"), item.ring)
				setColor(points.uniform("u_fill"), item.fillArgb)
				setColor(points.uniform("u_stroke"), item.strokeArgb)
				GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP, 0, 4, item.centers.size / 2)
			}
			is FillBatch -> drawFill(item)
			is TextureQuad -> drawQuad(view, item)
		}
		GL30.glBindVertexArray(0)
	}

	/**
	 * The paint session's raster: its changed areas written into the view's texture, then the texture drawn as
	 * one quad over the document, where document pixel (x, y) is world (x, -y). Above the guides, as the
	 * software canvas draws its tiles.
	 */
	private fun drawPaint(view: View, paint: PaintScene?) {
		if (paint == null) {
			if (view.paintTexture != 0) { GL11.glDeleteTextures(view.paintTexture); view.paintTexture = 0 }
			view.paintSession = null
			return
		}
		if (view.paintTexture == 0 || view.paintSession !== paint.session ||
			view.paintWidth != paint.width || view.paintHeight != paint.height) {
			if (view.paintTexture == 0) view.paintTexture = GL11.glGenTextures()
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, view.paintTexture)
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, paint.width, paint.height, 0, GL11.GL_RGBA,
				GL11.GL_UNSIGNED_BYTE, null as java.nio.ByteBuffer?)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
			view.paintSession = paint.session
			view.paintWidth = paint.width
			view.paintHeight = paint.height
		}
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, view.paintTexture)
		GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1)
		for (upload in paint.uploads) {
			if (upload.width <= 0 || upload.height <= 0 || upload.x < 0 || upload.y < 0 ||
				upload.x + upload.width > paint.width || upload.y + upload.height > paint.height) continue
			val pixels = MemoryUtil.memAlloc(upload.rgba.size)
			try {
				pixels.put(upload.rgba).flip()
				GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, upload.x, upload.y, upload.width, upload.height,
					GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
			} finally {
				MemoryUtil.memFree(pixels)
			}
		}
		// Document (x, y) is world (x, -y).
		val c = paint.corners
		GL20.glUseProgram(artwork.id)
		GL20.glUniform4fv(artwork.uniform("u_world"), worldUniform)
		GL20.glUniform1i(artwork.uniform("u_texture"), 0)
		GL20.glUniform1f(artwork.uniform("u_opacity"), 1f)
		GL20.glUniform4f(artwork.uniform("u_solid"), 0f, 0f, 0f, -1f)
		GL13.glActiveTexture(GL13.GL_TEXTURE0)
		GL30.glBindVertexArray(quadVao)
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadPositions)
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, floatArrayOf(c[0], -c[1], c[2], -c[3], c[4], -c[5], c[6], -c[7]), GL15.GL_STREAM_DRAW)
		GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4)
		GL30.glBindVertexArray(0)
	}

	/**
	 * A textured rectangle, inside its clip rectangles when it has them: they are written to the stencil first and
	 * the texels drawn only where it is set.
	 */
	private fun drawQuad(view: View, quad: TextureQuad) {
		val texture = when (val source = quad.texture) {
			is ImageTexture -> texture(source.image)
			is RasterTexture -> rasterTexture(view, source) ?: return
		}
		val clip = quad.clip
		if (clip != null) {
			if (clip.size < 4) return
			val vertices = FloatArray(clip.size / 4 * 12)
			for (r in 0 until clip.size / 4) {
				val x0 = clip[r * 4]; val y0 = clip[r * 4 + 1]; val x1 = clip[r * 4 + 2]; val y1 = clip[r * 4 + 3]
				floatArrayOf(x0, y0, x1, y0, x0, y1, x1, y0, x1, y1, x0, y1).copyInto(vertices, r * 12)
			}
			GL20.glUseProgram(artwork.id)
			GL20.glUniform4fv(artwork.uniform("u_world"), worldUniform)
			GL20.glUniform4f(artwork.uniform("u_solid"), 0f, 0f, 0f, 1f)
			GL30.glBindVertexArray(fillVao)
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, fillVertices)
			GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STREAM_DRAW)
			GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT)
			GL11.glEnable(GL11.GL_STENCIL_TEST)
			GL11.glColorMask(false, false, false, false)
			GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xff)
			GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE)
			GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertices.size / 2)
			GL11.glColorMask(true, true, true, true)
			GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xff)
			GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP)
		}
		GL20.glUseProgram(artwork.id)
		GL20.glUniform4fv(artwork.uniform("u_world"), worldUniform)
		GL20.glUniform1i(artwork.uniform("u_texture"), 0)
		GL20.glUniform1f(artwork.uniform("u_opacity"), quad.alpha)
		GL20.glUniform4f(artwork.uniform("u_solid"), 0f, 0f, 0f, -1f)
		GL13.glActiveTexture(GL13.GL_TEXTURE0)
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, if (quad.nearest) GL11.GL_NEAREST else GL11.GL_LINEAR)
		GL30.glBindVertexArray(tileVao)
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, tilePositions)
		// A strip of top left, top right, bottom left, bottom right; a turned quad gives its own corners.
		val c = quad.corners
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, if (c != null) floatArrayOf(c[0], c[1], c[2], c[3], c[6], c[7], c[4], c[5])
			else floatArrayOf(quad.x0, quad.y0, quad.x1, quad.y0, quad.x0, quad.y1, quad.x1, quad.y1), GL15.GL_STREAM_DRAW)
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, tileUvs)
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, floatArrayOf(quad.u0, quad.v0, quad.u1, quad.v0, quad.u0, quad.v1, quad.u1, quad.v1), GL15.GL_STREAM_DRAW)
		GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4)
		if (clip != null) GL11.glDisable(GL11.GL_STENCIL_TEST)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
	}

	/** [raster] as a premultiplied, mipmapped texture of [view], uploaded once per pixel array; null when it is empty. */
	private fun rasterTexture(view: View, raster: RasterTexture): Int? {
		val width = raster.width
		val height = raster.height
		if (width <= 0 || height <= 0 || raster.rgba.size < width * height * 4) return null
		view.rastersDrawn += raster.rgba
		return view.rasters.getOrPut(raster.rgba) {
			val rgba = raster.rgba
			val pixels = MemoryUtil.memAlloc(width * height * 4)
			try {
				for (i in 0 until width * height) {
					val o = i * 4
					val a = rgba[o + 3].toInt() and 0xff
					// Rounded like Skia's own premultiply, as the atlas pages are.
					if (a == 255) pixels.put(rgba[o]).put(rgba[o + 1]).put(rgba[o + 2]).put(rgba[o + 3])
					else pixels.put((((rgba[o].toInt() and 0xff) * a + 127) / 255).toByte())
						.put((((rgba[o + 1].toInt() and 0xff) * a + 127) / 255).toByte())
						.put((((rgba[o + 2].toInt() and 0xff) * a + 127) / 255).toByte()).put(a.toByte())
				}
				pixels.flip()
				val texture = GL11.glGenTextures()
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
				GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1)
				GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
				if (mipmaps) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D)
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, if (mipmaps) GL11.GL_LINEAR_MIPMAP_LINEAR else GL11.GL_LINEAR)
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
				texture
			} finally {
				MemoryUtil.memFree(pixels)
			}
		}
	}

	/**
	 * The segments, then a disc at every vertex for the round joins and caps, all under one stencil rule: a pixel
	 * is painted by the first piece that covers it at least half and by none after.
	 */
	private fun drawPolyline(batch: PolylineBatch, width: Int, height: Int) {
		val n = batch.points.size / 2
		if (n < 2) return
		val count = if (batch.closed) n else n - 1
		val segments = FloatArray(count * 4)
		for (i in 0 until count) {
			val j = (i + 1) % n
			segments[i * 4] = batch.points[i * 2]; segments[i * 4 + 1] = batch.points[i * 2 + 1]
			segments[i * 4 + 2] = batch.points[j * 2]; segments[i * 4 + 3] = batch.points[j * 2 + 1]
		}
		GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT)
		GL11.glEnable(GL11.GL_STENCIL_TEST)
		GL11.glStencilFunc(GL11.GL_EQUAL, 0, 0xff)
		GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_INCR)
		GL20.glUseProgram(lines.id)
		GL20.glUniform1f(lines.uniform("u_once"), 1f)
		GL20.glUniform4fv(lines.uniform("u_world"), worldUniform)
		GL20.glUniform2f(lines.uniform("u_viewport"), width.toFloat(), height.toFloat())
		GL20.glUniform1f(lines.uniform("u_width"), batch.width)
		setColor(lines.uniform("u_color"), batch.argb)
		GL30.glBindVertexArray(lineVao)
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, lineInstances)
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, segments, GL15.GL_STREAM_DRAW)
		GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP, 0, 4, count)
		GL20.glUseProgram(points.id)
		GL20.glUniform1f(points.uniform("u_once"), 1f)
		GL20.glUniform4fv(points.uniform("u_world"), worldUniform)
		GL20.glUniform2f(points.uniform("u_viewport"), width.toFloat(), height.toFloat())
		GL20.glUniform1f(points.uniform("u_radius"), batch.width * 0.5f)
		GL20.glUniform1f(points.uniform("u_ring"), 0f)
		setColor(points.uniform("u_fill"), batch.argb)
		setColor(points.uniform("u_stroke"), batch.argb)
		GL30.glBindVertexArray(pointVao)
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, pointInstances)
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, batch.points, GL15.GL_STREAM_DRAW)
		GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP, 0, 4, n)
		GL11.glDisable(GL11.GL_STENCIL_TEST)
	}

	/**
	 * Stencil, then cover: each outline's triangle fan inverts the stencil, which leaves exactly the even-odd
	 * interior set, and one quad over the outlines' bounds paints it and clears the stencil behind itself.
	 */
	private fun drawFill(batch: FillBatch) {
		val contours = batch.contours.filter { it.size >= 6 }
		if (contours.isEmpty()) return
		val total = contours.sumOf { it.size }
		val vertices = FloatArray(total + 12)
		var n = 0
		var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
		for (contour in contours) {
			contour.copyInto(vertices, n)
			n += contour.size
			for (i in contour.indices step 2) {
				minX = minOf(minX, contour[i]); maxX = maxOf(maxX, contour[i])
				minY = minOf(minY, contour[i + 1]); maxY = maxOf(maxY, contour[i + 1])
			}
		}
		floatArrayOf(minX, minY, maxX, minY, minX, maxY, maxX, minY, maxX, maxY, minX, maxY).copyInto(vertices, n)
		GL20.glUseProgram(artwork.id)
		GL20.glUniform4fv(artwork.uniform("u_world"), worldUniform)
		GL30.glBindVertexArray(fillVao)
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, fillVertices)
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STREAM_DRAW)
		GL11.glEnable(GL11.GL_STENCIL_TEST)
		GL11.glColorMask(false, false, false, false)
		GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xff)
		GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_INVERT)
		var first = 0
		for (contour in contours) {
			GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, first, contour.size / 2)
			first += contour.size / 2
		}
		GL11.glColorMask(true, true, true, true)
		GL11.glStencilFunc(GL11.GL_NOTEQUAL, 0, 0xff)
		GL11.glStencilOp(GL11.GL_ZERO, GL11.GL_ZERO, GL11.GL_ZERO)
		setColor(artwork.uniform("u_solid"), batch.argb)
		GL11.glDrawArrays(GL11.GL_TRIANGLES, first, 6)
		GL11.glDisable(GL11.GL_STENCIL_TEST)
	}

	/** Premultiplied from unpremultiplied ARGB. */
	private fun setColor(location: Int, argb: Int) {
		val a = (argb ushr 24 and 0xff) / 255f
		GL20.glUniform4f(location, (argb ushr 16 and 0xff) / 255f * a, (argb ushr 8 and 0xff) / 255f * a, (argb and 0xff) / 255f * a, a)
	}

	private fun syncMesh(view: View, id: DrawableId, world: FloatArray, uvs: FloatArray, indices: IntArray): MeshBuffers {
		val buffers = view.meshes.getOrPut(id) {
			val vao = GL30.glGenVertexArrays()
			MeshBuffers(vao, GL15.glGenBuffers(), GL15.glGenBuffers(), GL15.glGenBuffers()).also { created ->
				GL30.glBindVertexArray(vao)
				GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, created.positions)
				GL20.glEnableVertexAttribArray(0)
				GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L)
				GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, created.uvs)
				GL20.glEnableVertexAttribArray(1)
				GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 0, 0L)
				GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, created.indices)
				GL30.glBindVertexArray(0)
			}
		}
		// Identity, not contents: the editor's copy-on-write keeps an untouched mesh's arrays the same instances.
		if (buffers.positionSource !== world) {
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffers.positions)
			if (world.size > buffers.positionCapacity) {
				GL15.glBufferData(GL15.GL_ARRAY_BUFFER, world, GL15.GL_DYNAMIC_DRAW)
				buffers.positionCapacity = world.size
			} else GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, world)
			buffers.positionSource = world
		}
		if (buffers.uvSource !== uvs) {
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffers.uvs)
			GL15.glBufferData(GL15.GL_ARRAY_BUFFER, uvs, GL15.GL_STATIC_DRAW)
			buffers.uvSource = uvs
		}
		if (buffers.indexSource !== indices) {
			// Indices past the vertex count would read outside the buffers; such a mesh draws nothing.
			val vertexCount = minOf(world.size, uvs.size) / 2
			val valid = indices.size % 3 == 0 && indices.all { it in 0 until vertexCount }
			GL30.glBindVertexArray(buffers.vao)
			GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, buffers.indices)
			GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, if (valid) indices else IntArray(0), GL15.GL_STATIC_DRAW)
			GL30.glBindVertexArray(0)
			buffers.indexCount = if (valid) indices.size else 0
			buffers.indexSource = indices
		}
		return buffers
	}

	private fun deleteMesh(buffers: MeshBuffers) {
		GL30.glDeleteVertexArrays(buffers.vao)
		GL15.glDeleteBuffers(buffers.positions)
		GL15.glDeleteBuffers(buffers.uvs)
		GL15.glDeleteBuffers(buffers.indices)
	}

	/** [image] as a premultiplied RGBA texture with linear filtering, uploaded once per page instance. */
	private fun texture(image: BufferedImage): Int = textures.getOrPut(image) {
		val width = image.width
		val height = image.height
		val raster = (image.raster.dataBuffer as? DataBufferInt)?.data?.takeIf { it.size == width * height }
		val premultipliedInput = raster != null && image.type == BufferedImage.TYPE_INT_ARGB_PRE
		val source = if (raster != null && (premultipliedInput || image.type == BufferedImage.TYPE_INT_ARGB)) raster
			else image.getRGB(0, 0, width, height, null, 0, width)
		val pixels = MemoryUtil.memAlloc(width * height * 4)
		try {
			for (i in 0 until width * height) {
				val c = source[i]
				val a = c ushr 24
				if (premultipliedInput || a == 255) {
					pixels.put((c ushr 16 and 0xff).toByte()).put((c ushr 8 and 0xff).toByte()).put((c and 0xff).toByte()).put(a.toByte())
				} else {
					// Rounded like Skia's own premultiply, so the two painters sample the same texels.
					pixels.put((((c ushr 16 and 0xff) * a + 127) / 255).toByte()).put((((c ushr 8 and 0xff) * a + 127) / 255).toByte())
						.put((((c and 0xff) * a + 127) / 255).toByte()).put(a.toByte())
				}
			}
			pixels.flip()
			val texture = GL11.glGenTextures()
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
			GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
			if (mipmaps) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
				if (mipmaps) GL11.GL_LINEAR_MIPMAP_LINEAR else GL11.GL_LINEAR)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
			texture
		} finally {
			MemoryUtil.memFree(pixels)
		}
	}

	private fun ensureTarget(view: View, width: Int, height: Int) {
		if (view.framebuffer != 0 && width <= view.capacityWidth && height <= view.capacityHeight) return
		// Grow-only: a dock drag resizes every frame, and reallocating each time would stall on every step.
		val capacityWidth = maxOf(width, view.capacityWidth)
		val capacityHeight = maxOf(height, view.capacityHeight)
		if (view.framebuffer == 0) view.framebuffer = GL30.glGenFramebuffers()
		if (view.color != 0) GL30.glDeleteRenderbuffers(view.color)
		if (view.depthStencil != 0) GL30.glDeleteRenderbuffers(view.depthStencil)
		view.color = GL30.glGenRenderbuffers()
		GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, view.color)
		GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, capacityWidth, capacityHeight)
		view.depthStencil = GL30.glGenRenderbuffers()
		GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, view.depthStencil)
		GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, capacityWidth, capacityHeight)
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, view.framebuffer)
		GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, view.color)
		GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, view.depthStencil)
		val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
		check(status == GL30.GL_FRAMEBUFFER_COMPLETE) { "Canvas framebuffer incomplete: 0x${Integer.toHexString(status)}" }
		view.capacityWidth = capacityWidth
		view.capacityHeight = capacityHeight
	}

	private fun program(vertex: String, fragment: String): Program {
		fun compile(type: Int, source: String): Int {
			val shader = GL20.glCreateShader(type)
			GL20.glShaderSource(shader, source)
			GL20.glCompileShader(shader)
			check(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE) {
				"Shader compile failed: ${GL20.glGetShaderInfoLog(shader)}"
			}
			return shader
		}
		val vs = compile(GL20.GL_VERTEX_SHADER, vertex)
		val fs = compile(GL20.GL_FRAGMENT_SHADER, fragment)
		val id = GL20.glCreateProgram()
		GL20.glAttachShader(id, vs)
		GL20.glAttachShader(id, fs)
		GL20.glLinkProgram(id)
		GL20.glDeleteShader(vs)
		GL20.glDeleteShader(fs)
		check(GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) == GL11.GL_TRUE) { "Program link failed: ${GL20.glGetProgramInfoLog(id)}" }
		return Program(id)
	}

}
