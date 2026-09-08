package org.umamo.render.gl

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL21
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.opengl.GL32
import org.umamo.format.raster.RasterImage
import org.umamo.render.device.DeformCapturePipeline
import org.umamo.render.device.DeformedPositionStore
import org.umamo.render.device.FrameEncoder
import org.umamo.render.device.GpuMesh
import org.umamo.render.device.GpuTexture
import org.umamo.render.device.MeshSpec
import org.umamo.render.device.PipelinePurpose
import org.umamo.render.device.ReadbackTicket
import org.umamo.render.device.RenderDevice
import org.umamo.render.device.RenderPipeline
import org.umamo.render.device.RenderPipelineSpec
import org.umamo.render.device.RenderTarget
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.ScissorRect
import org.umamo.render.device.TextureFilter
import org.umamo.render.device.TextureFormat
import org.umamo.render.device.TextureWrap
import org.umamo.render.glsl.GlslDialect
import org.umamo.render.glsl.atlasPageVertexShader
import org.umamo.render.glsl.axisFragmentShader
import org.umamo.render.glsl.axisVertexShader
import org.umamo.render.glsl.compositeFragmentShader
import org.umamo.render.glsl.compositeVertexShader
import org.umamo.render.glsl.glueVertexShader
import org.umamo.render.glsl.gridFragmentShader
import org.umamo.render.glsl.gridVertexShader
import org.umamo.render.glsl.puppetFragmentShader
import org.umamo.render.glsl.puppetVertexShader
import org.umamo.render.glsl.tfDeformVertexShader
import org.umamo.render.glsl.tfDiscardFragmentShader
import org.umamo.render.puppet.flipRowsVertically
import java.nio.ByteBuffer

/**
 * The desktop OpenGL 3.3 core [RenderDevice], over LWJGL.
 *
 * This is where the GL lives.  Everything above it - the puppet renderer, the eval, the pose resolution -
 * is backend-neutral, so a second backend is a second device rather than a second renderer.  Every method
 * assumes a GL context is current on the calling thread; the host makes it so.
 *
 * A near-transliteration of this becomes the Android GLES 3.0 device: the calls are the same, differing
 * only in binding style (LWJGL statics vs the GLES out-param form) and the one place GLES 3.0 lacks a
 * texture buffer (the glue store, [createDeformedPositionStore], where the GLES port repacks as a 2D
 * texture behind [DeformedPositionStore]).
 */
class GlRenderDevice : RenderDevice {
	// An empty VAO for the attribute-less draws (grid, axis lines, atlas page): a core profile still
	// requires a bound VAO even when the vertex shader synthesises its positions from gl_VertexID.
	private var emptyVao = 0

	// Pipelines are immutable and reused every frame, so they are cached by their spec rather than rebuilt.
	private val renderPipelines = HashMap<RenderPipelineSpec, GlRenderPipeline>()
	private var deformCapturePipeline: GlDeformCapturePipeline? = null

	private fun ensureEmptyVao(): Int {
		if (emptyVao == 0) {
			emptyVao = GL30.glGenVertexArrays()
		}
		return emptyVao
	}

	override fun createTexture(
		width: Int,
		height: Int,
		format: TextureFormat,
		filter: TextureFilter,
		pixels: ByteArray?,
		wrap: TextureWrap,
	): GpuTexture {
		val handle = GL11.glGenTextures()
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, handle)
		setTextureParameters(filter, wrap)
		val pixelBuffer =
			pixels?.let {
				BufferUtils.createByteBuffer(it.size).apply {
					put(it)
					flip()
				}
			}
		GL11.glTexImage2D(
			GL11.GL_TEXTURE_2D,
			0,
			internalFormatOf(format),
			width,
			height,
			0,
			pixelFormatOf(format),
			pixelTypeOf(format),
			pixelBuffer,
		)
		return GlTexture(handle)
	}

	override fun createFloatTexture(width: Int, height: Int, filter: TextureFilter, texels: FloatArray): GpuTexture {
		val handle = GL11.glGenTextures()
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, handle)
		setTextureParameters(filter, TextureWrap.ClampToEdge)
		val texelBuffer =
			BufferUtils.createFloatBuffer(texels.size).apply {
				put(texels)
				flip()
			}
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG32F, width, height, 0, GL30.GL_RG, GL11.GL_FLOAT, texelBuffer)
		return GlTexture(handle)
	}

	override fun updateFloatTexture(texture: GpuTexture, width: Int, height: Int, texels: FloatArray) {
		val glTexture = texture as GlTexture
		val texelBuffer =
			BufferUtils.createFloatBuffer(texels.size).apply {
				put(texels)
				flip()
			}
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTexture.handle)
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG32F, width, height, 0, GL30.GL_RG, GL11.GL_FLOAT, texelBuffer)
	}

	override fun createMesh(spec: MeshSpec): GpuMesh {
		val vao = GL30.glGenVertexArrays()
		GL30.glBindVertexArray(vao)

		val positionVbo = uploadArrayBuffer(spec.restPositions, GL15.GL_DYNAMIC_DRAW)
		GL20.glEnableVertexAttribArray(0)
		GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0L)

		val vertexCount = spec.restPositions.size / 2
		// A mesh whose UVs are shorter than its vertices gets a zero-filled UV buffer of the right length,
		// so an in-place UV re-upload later has a target of the expected size.
		val safeUvs = if (spec.uvs.size >= vertexCount * 2) spec.uvs else FloatArray(vertexCount * 2)
		val uvVbo = uploadArrayBuffer(safeUvs, GL15.GL_DYNAMIC_DRAW)
		GL20.glEnableVertexAttribArray(1)
		GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0L)

		var glueVbo = 0
		spec.glueAttributes?.let { attributes ->
			glueVbo = GL15.glGenBuffers()
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, glueVbo)
			GL15.glBufferData(GL15.GL_ARRAY_BUFFER, interleaveGlueAttributes(attributes), GL15.GL_STATIC_DRAW)
			val stride = 3 * Int.SIZE_BYTES
			GL20.glEnableVertexAttribArray(2)
			GL30.glVertexAttribIPointer(2, 1, GL11.GL_INT, stride, 0L)
			GL20.glEnableVertexAttribArray(3)
			GL30.glVertexAttribIPointer(3, 1, GL11.GL_INT, stride, Int.SIZE_BYTES.toLong())
			GL20.glEnableVertexAttribArray(4)
			GL20.glVertexAttribPointer(4, 1, GL11.GL_FLOAT, false, stride, (2 * Int.SIZE_BYTES).toLong())
		}

		var indexEbo = 0
		if (spec.indices.isNotEmpty()) {
			val indexBuffer =
				BufferUtils.createIntBuffer(spec.indices.size).apply {
					put(spec.indices)
					flip()
				}
			indexEbo = GL15.glGenBuffers()
			GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexEbo)
			GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL15.GL_STATIC_DRAW)
		}

		GL30.glBindVertexArray(0)
		return GlMesh(vao, positionVbo, uvVbo, glueVbo, indexEbo, vertexCount, spec.indices.size)
	}

	override fun updateMeshPositions(mesh: GpuMesh, restPositions: FloatArray) {
		subDataArrayBuffer((mesh as GlMesh).positionVbo, restPositions)
	}

	override fun updateMeshUvs(mesh: GpuMesh, uvs: FloatArray) {
		subDataArrayBuffer((mesh as GlMesh).uvVbo, uvs)
	}

	override fun createRenderTarget(spec: RenderTargetSpec): RenderTarget {
		val framebuffer = GL30.glGenFramebuffers()
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
		var colorTexture = 0
		var colorRenderbuffer = 0
		if (spec.sampled) {
			colorTexture = GL11.glGenTextures()
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTexture)
			setTextureParameters(TextureFilter.Linear, TextureWrap.ClampToEdge)
			GL11.glTexImage2D(
				GL11.GL_TEXTURE_2D,
				0,
				internalFormatOf(spec.format),
				spec.width,
				spec.height,
				0,
				pixelFormatOf(spec.format),
				pixelTypeOf(spec.format),
				null as ByteBuffer?,
			)
			GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTexture, 0)
		} else {
			// A write-only surface: cheaper, but cannot be sampled or read back directly.
			colorRenderbuffer = GL30.glGenRenderbuffers()
			GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, colorRenderbuffer)
			GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, internalFormatOf(spec.format), spec.width, spec.height)
			GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, colorRenderbuffer)
		}
		return GlRenderTarget(framebuffer, colorTexture, colorRenderbuffer, spec.width, spec.height)
	}

	override fun createDeformedPositionStore(vertexCapacity: Int): DeformedPositionStore {
		val buffer = GL15.glGenBuffers()
		GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buffer)
		GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, vertexCapacity.toLong() * 2 * Float.SIZE_BYTES, GL15.GL_DYNAMIC_COPY)
		val textureBuffer = GL11.glGenTextures()
		GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, textureBuffer)
		GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RG32F, buffer)
		return GlDeformedPositionStore(buffer, textureBuffer, vertexCapacity)
	}

	override fun createRenderPipeline(spec: RenderPipelineSpec): RenderPipeline =
		renderPipelines.getOrPut(spec) {
			val (vertexSource, fragmentSource) = sourcesFor(spec.purpose)
			val program = linkGlProgram(vertexSource, fragmentSource, spec.purpose.name)
			GlRenderPipeline(program, spec.blend, spec.cullBackFaces, GlUniformLocations(program))
		}

	override fun createDeformCapturePipeline(): DeformCapturePipeline =
		deformCapturePipeline ?: run {
			val program = attachGlShaders(tfDeformVertexShader(DIALECT), tfDiscardFragmentShader(DIALECT))
			GL30.glTransformFeedbackVaryings(program, arrayOf("outWorld"), GL30.GL_INTERLEAVED_ATTRIBS)
			GL20.glLinkProgram(program)
			check(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_FALSE) {
				"deform-capture program link failed: ${GL20.glGetProgramInfoLog(program)}"
			}
			GlDeformCapturePipeline(program, GlUniformLocations(program)).also { deformCapturePipeline = it }
		}

	override fun destroyTexture(texture: GpuTexture) {
		GL11.glDeleteTextures((texture as GlTexture).handle)
	}

	override fun destroyMesh(mesh: GpuMesh) {
		val glMesh = mesh as GlMesh
		GL30.glDeleteVertexArrays(glMesh.vao)
		GL15.glDeleteBuffers(glMesh.positionVbo)
		GL15.glDeleteBuffers(glMesh.uvVbo)
		if (glMesh.glueVbo != 0) {
			GL15.glDeleteBuffers(glMesh.glueVbo)
		}
		if (glMesh.indexEbo != 0) {
			GL15.glDeleteBuffers(glMesh.indexEbo)
		}
	}

	override fun destroyRenderTarget(target: RenderTarget) {
		val glTarget = target as GlRenderTarget
		GL30.glDeleteFramebuffers(glTarget.framebuffer)
		if (glTarget.colorTexture != 0) {
			GL11.glDeleteTextures(glTarget.colorTexture)
		}
		if (glTarget.colorRenderbuffer != 0) {
			GL30.glDeleteRenderbuffers(glTarget.colorRenderbuffer)
		}
	}

	override fun beginFrame(): FrameEncoder = GlFrameEncoder(ensureEmptyVao())

	override fun resolve(source: RenderTarget, destination: RenderTarget, region: ScissorRect?) {
		val glSource = source as GlRenderTarget
		val glDestination = destination as GlRenderTarget
		resolveUsed(glSource, glSource.width, glSource.height, glDestination, glDestination.width, glDestination.height, region)
	}

	override fun resolveUsed(
		source: RenderTarget,
		sourceUsedWidth: Int,
		sourceUsedHeight: Int,
		destination: RenderTarget,
		destinationUsedWidth: Int,
		destinationUsedHeight: Int,
		region: ScissorRect?,
	) {
		val glSource = source as GlRenderTarget
		val glDestination = destination as GlRenderTarget
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, glSource.framebuffer)
		GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, glDestination.framebuffer)
		// Content sits at GL (0, 0): every pass runs glViewport(0, 0, used, used), so the used region
		// IS the bottom-left rows.  GL_LINEAR makes the downscale a box filter - the supersample
		// resolve. On GL this whole primitive IS a blit; on Metal it cannot be (a blit encoder neither
		// filters nor scales), which is why the interface names the effect rather than the mechanism.
		if (region == null) {
			GL30.glBlitFramebuffer(
				0,
				0,
				sourceUsedWidth,
				sourceUsedHeight,
				0,
				0,
				destinationUsedWidth,
				destinationUsedHeight,
				GL11.GL_COLOR_BUFFER_BIT,
				GL11.GL_LINEAR,
			)
		} else {
			// A same-size sub-rectangle copy (the composite path's snapshot). The rect is
			// top-left-origin per the ScissorRect contract; GL blits bottom-up, so the flip is against
			// the USED height - flipping against a larger allocation would land above the content.
			val bottomUpY = sourceUsedHeight - region.y - region.height
			GL30.glBlitFramebuffer(
				region.x,
				bottomUpY,
				region.x + region.width,
				bottomUpY + region.height,
				region.x,
				bottomUpY,
				region.x + region.width,
				bottomUpY + region.height,
				GL11.GL_COLOR_BUFFER_BIT,
				GL11.GL_NEAREST,
			)
		}
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, glDestination.framebuffer)
	}

	/** A recyclable pixel-buffer object and its current byte capacity. */
	private class Pbo(val id: Int, var capacity: Int)

	/**
	 * An in-flight asynchronous read-back: the staging PBO, the fence gating it, and the read's extent.
	 * [spent] flips when the ticket is consumed or cancelled, making both one-shot and idempotent.
	 */
	private class GlReadbackTicket(
		val pbo: Pbo,
		val fence: Long,
		val width: Int,
		val height: Int,
		var spent: Boolean = false,
	) : ReadbackTicket

	// Staging PBOs are recycled rather than freed: a viewport read-back runs per frame, and reallocating
	// a multi-megabyte buffer 60x/sec is churn the pool exists to avoid.  Render-thread only.
	private val freePbos = ArrayDeque<Pbo>()

	override fun beginReadback(target: RenderTarget): ReadbackTicket {
		val glTarget = target as GlRenderTarget
		return beginReadback(glTarget, glTarget.width, glTarget.height)
	}

	override fun beginReadback(target: RenderTarget, usedWidth: Int, usedHeight: Int): ReadbackTicket {
		val glTarget = target as GlRenderTarget
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, glTarget.framebuffer)
		val pbo = acquirePbo(usedWidth * usedHeight * 4)
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo.id)
		// With a PBO bound, the last argument is a BUFFER OFFSET, not a client pointer - glReadPixels
		// returns immediately and the copy happens asynchronously on the GPU timeline.  The used
		// region is the bottom-left rows (passes viewport at the origin), so the read starts at (0, 0).
		GL11.glReadPixels(0, 0, usedWidth, usedHeight, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L)
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
		val fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
		GL11.glFlush() // submit the commands + fence so the fence can eventually signal
		return GlReadbackTicket(pbo, fence, usedWidth, usedHeight)
	}

	override fun pollReadback(ticket: ReadbackTicket): RasterImage? {
		val glTicket = ticket as GlReadbackTicket
		check(!glTicket.spent) { "pollReadback on a spent ticket" }
		val status = GL32.glClientWaitSync(glTicket.fence, 0, 0L)
		if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) {
			return null // still in flight; the ticket stays live to poll again
		}
		// The fence signaled, so the read is done and the ticket is consumed HERE - exactly once, whether or
		// not the map below succeeds. Returning null past this point would make the caller treat a consumed
		// ticket as still-in-flight and re-poll it, tripping the spent check and killing the render thread.
		glTicket.spent = true
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, glTicket.pbo.id)
		// Explicitly typed: the inferred type would carry LWJGL's jspecify @Nullable, which is not
		// on this module's classpath and fails compilation.
		val mapped: ByteBuffer? = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY)
		val rowBytes = glTicket.width * 4
		val topDown = ByteArray(rowBytes * glTicket.height)
		if (mapped != null) {
			// Flip the GL bottom-up rows straight out of the mapped PBO into the API's top-first contract -
			// one bulk get per row, the direct-buffer equivalent of System.arraycopy.
			for (row in 0 until glTicket.height) {
				mapped.position((glTicket.height - 1 - row) * rowBytes)
				mapped.get(topDown, row * rowBytes, rowBytes)
			}
			GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER) // only when the map actually succeeded
		}
		// A map failure (a driver error that should never happen) leaves topDown zero-filled: one black
		// frame rather than a permanently frozen viewport, and null keeps meaning "still in flight".
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
		GL32.glDeleteSync(glTicket.fence)
		freePbos.addLast(glTicket.pbo)
		return RasterImage(glTicket.width, glTicket.height, topDown)
	}

	override fun cancelReadback(ticket: ReadbackTicket) {
		val glTicket = ticket as GlReadbackTicket
		if (glTicket.spent) {
			return
		}
		glTicket.spent = true
		GL32.glDeleteSync(glTicket.fence)
		freePbos.addLast(glTicket.pbo)
	}

	/**
	 * Acquires a PBO with at least [capacity] bytes from the free pool (allocating/growing as needed).
	 * GL_STREAM_READ: written by GL, read once by the client.
	 *
	 * @param Int capacity The minimum byte capacity needed.
	 * @return Pbo The acquired PBO.
	 */
	private fun acquirePbo(capacity: Int): Pbo {
		val pbo = freePbos.removeFirstOrNull() ?: Pbo(GL15.glGenBuffers(), 0)
		if (pbo.capacity < capacity) {
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo.id)
			GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, capacity.toLong(), GL15.GL_STREAM_READ)
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
			pbo.capacity = capacity
		}
		return pbo
	}

	override fun readPixels(target: RenderTarget): RasterImage {
		val glTarget = target as GlRenderTarget
		return readPixels(glTarget, glTarget.width, glTarget.height)
	}

	override fun readPixels(target: RenderTarget, usedWidth: Int, usedHeight: Int): RasterImage {
		val glTarget = target as GlRenderTarget
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, glTarget.framebuffer)
		val pixels = BufferUtils.createByteBuffer(usedWidth * usedHeight * 4)
		// The used region is the bottom-left rows (passes viewport at the origin): read from (0, 0).
		GL11.glReadPixels(0, 0, usedWidth, usedHeight, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
		val bottomUp = ByteArray(usedWidth * usedHeight * 4)
		pixels.get(bottomUp)
		// GL reads bottom-up; RasterImage is top-first. The flip is this backend's; no caller asks.
		return RasterImage(usedWidth, usedHeight, flipRowsVertically(bottomUp, usedWidth, usedHeight))
	}

	override fun describeBackend(): String {
		val renderer = GL11.glGetString(GL11.GL_RENDERER)
		val version = GL11.glGetString(GL11.GL_VERSION)
		val vendor = GL11.glGetString(GL11.GL_VENDOR)
		val glsl = GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION)
		return "renderer=$renderer | version=$version | vendor=$vendor | glsl=$glsl"
	}

	/** The (vertex, fragment) GLSL for a pipeline purpose - this backend's shader library, keyed by purpose. */
	private fun sourcesFor(purpose: PipelinePurpose): Pair<String, String> =
		when (purpose) {
			PipelinePurpose.PuppetDeformDraw -> puppetVertexShader(DIALECT) to puppetFragmentShader(DIALECT)
			PipelinePurpose.PuppetGlueDraw -> glueVertexShader(DIALECT) to puppetFragmentShader(DIALECT)
			PipelinePurpose.AtlasPageDraw -> atlasPageVertexShader(DIALECT) to puppetFragmentShader(DIALECT)
			PipelinePurpose.GridBackdrop -> gridVertexShader(DIALECT) to gridFragmentShader(DIALECT)
			PipelinePurpose.WorldAxisLine -> axisVertexShader(DIALECT) to axisFragmentShader(DIALECT)
			PipelinePurpose.Composite -> compositeVertexShader(DIALECT) to compositeFragmentShader(DIALECT)
		}

	/**
	 * Sets nearest/linear filtering and the requested wrapping on the currently-bound 2D texture.
	 *
	 * The border color is set only for the border mode: it is per-texture state, so leaving it alone on the
	 * edge-clamp path costs one call less and changes nothing that path can observe.
	 *
	 * @param TextureFilter filter The filtering mode.
	 * @param TextureWrap   wrap   The wrapping mode, applied to both axes.
	 */
	private fun setTextureParameters(filter: TextureFilter, wrap: TextureWrap) {
		val glFilter = if (filter == TextureFilter.Nearest) GL11.GL_NEAREST else GL11.GL_LINEAR
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, glFilter)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, glFilter)
		val glWrap = if (wrap == TextureWrap.ClampToTransparentBorder) GL13.GL_CLAMP_TO_BORDER else GL12.GL_CLAMP_TO_EDGE
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, glWrap)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, glWrap)
		if (wrap == TextureWrap.ClampToTransparentBorder) {
			// Transparent AND black: under linear filtering the border's RGB joins the blend across the
			// art's outermost half-texel, so a border that is merely alpha-zero but colored tints that
			// fringe.  Zero everywhere matches what the packed atlas's own padding contributes there.
			GL11.glTexParameterfv(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_BORDER_COLOR, floatArrayOf(0f, 0f, 0f, 0f))
		}
	}

	private fun internalFormatOf(format: TextureFormat): Int = if (format == TextureFormat.Rgba8) GL11.GL_RGBA8 else GL30.GL_RG32F

	private fun pixelFormatOf(format: TextureFormat): Int = if (format == TextureFormat.Rgba8) GL11.GL_RGBA else GL30.GL_RG

	private fun pixelTypeOf(format: TextureFormat): Int = if (format == TextureFormat.Rgba8) GL11.GL_UNSIGNED_BYTE else GL11.GL_FLOAT

	/** Uploads a float array into a fresh GL_ARRAY_BUFFER with the given usage; leaves it bound. */
	private fun uploadArrayBuffer(values: FloatArray, usage: Int): Int {
		val buffer =
			BufferUtils.createFloatBuffer(values.size).apply {
				put(values)
				flip()
			}
		val vbo = GL15.glGenBuffers()
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo)
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, usage)
		return vbo
	}

	// Reusable scratch for in-place VBO re-uploads, grown on demand so a live preview edit never allocates.
	private var uploadScratch = BufferUtils.createFloatBuffer(0)

	/** Re-specifies a whole array buffer's contents in place, reusing the scratch buffer. */
	private fun subDataArrayBuffer(vbo: Int, values: FloatArray) {
		if (uploadScratch.capacity() < values.size) {
			uploadScratch = BufferUtils.createFloatBuffer(values.size)
		}
		uploadScratch.clear()
		uploadScratch.put(values).flip()
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo)
		GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, uploadScratch)
	}
}

/** This backend is desktop OpenGL 3.3 core; the shared GLSL is emitted for it. */
private val DIALECT = GlslDialect.Core330

/**
 * Interleaves a mesh's planned weld attributes into the 12-byte-per-vertex form the glue VAO reads:
 * int partner global index, int glue index, float weld weight, in the native byte order [BufferUtils]
 * allocates - which is what `glVertexAttribIPointer` reads back.
 *
 * The layout is GL's; `org.umamo.render.puppet.planGlueLayout` decided the values.  A free function, not
 * a device method, since it touches no device state.
 *
 * @param GlueVertexAttributes attributes The mesh's planned per-vertex weld attributes.
 * @return ByteBuffer The interleaved buffer, flipped and ready to upload.
 */
private fun interleaveGlueAttributes(attributes: org.umamo.render.puppet.GlueVertexAttributes): ByteBuffer {
	val vertexCount = attributes.partnerIndex.size
	val buffer = BufferUtils.createByteBuffer(vertexCount * 3 * Int.SIZE_BYTES)
	for (vertexIndex in 0 until vertexCount) {
		buffer.putInt(attributes.partnerIndex[vertexIndex])
		buffer.putInt(attributes.glueIndex[vertexIndex])
		buffer.putFloat(attributes.weldWeight[vertexIndex])
	}
	buffer.flip()
	return buffer
}

/**
 * Links a GL program from vertex + fragment source, throwing with the info log on failure.
 *
 * @param String vertexSource   The vertex-stage GLSL.
 * @param String fragmentSource The fragment-stage GLSL.
 * @param String label          A name for the failure message.
 * @return Int The linked program handle.
 */
internal fun linkGlProgram(vertexSource: String, fragmentSource: String, label: String): Int {
	val program = attachGlShaders(vertexSource, fragmentSource)
	GL20.glLinkProgram(program)
	check(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_FALSE) {
		"$label program link failed: ${GL20.glGetProgramInfoLog(program)}"
	}
	return program
}

/** Compiles and attaches both stages onto a fresh program (not yet linked, so the TF varyings can be set). */
internal fun attachGlShaders(vertexSource: String, fragmentSource: String): Int {
	val vertexShader = compileGlShader(GL20.GL_VERTEX_SHADER, vertexSource)
	val fragmentShader = compileGlShader(GL20.GL_FRAGMENT_SHADER, fragmentSource)
	val program = GL20.glCreateProgram()
	GL20.glAttachShader(program, vertexShader)
	GL20.glAttachShader(program, fragmentShader)
	return program
}

/** Compiles one shader stage, throwing with the info log if compilation fails. */
internal fun compileGlShader(stage: Int, source: String): Int {
	val shader = GL20.glCreateShader(stage)
	GL20.glShaderSource(shader, source)
	GL20.glCompileShader(shader)
	check(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_FALSE) {
		"GL shader compile failed: ${GL20.glGetShaderInfoLog(shader)}"
	}
	return shader
}