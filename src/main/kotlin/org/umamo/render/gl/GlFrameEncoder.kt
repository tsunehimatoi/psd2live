package org.umamo.render.gl

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.umamo.render.device.AxisLineUniforms
import org.umamo.render.device.CompositeUniforms
import org.umamo.render.device.DeformCapturePassEncoder
import org.umamo.render.device.DeformCapturePipeline
import org.umamo.render.device.DeformUniforms
import org.umamo.render.device.DeformedPositionStore
import org.umamo.render.device.DrawTextures
import org.umamo.render.device.FragmentUniforms
import org.umamo.render.device.FrameEncoder
import org.umamo.render.device.GpuMesh
import org.umamo.render.device.GpuTexture
import org.umamo.render.device.GridUniforms
import org.umamo.render.device.LoadAction
import org.umamo.render.device.RenderPassEncoder
import org.umamo.render.device.RenderPassSpec
import org.umamo.render.device.RenderPipeline
import org.umamo.render.device.WorldToNdc
import org.umamo.render.glsl.UNIT_ATLAS
import org.umamo.render.glsl.UNIT_CP
import org.umamo.render.glsl.UNIT_DELTA
import org.umamo.render.glsl.UNIT_DEST
import org.umamo.render.glsl.UNIT_LAYER
import org.umamo.render.glsl.UNIT_MASK
import org.umamo.render.glsl.UNIT_POSITION
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * The GL implementation of one frame's recorded work.
 *
 * GL has no command buffer, so "recording" is really "issuing immediately": each call runs against the
 * current context as it arrives.  The encoder shape still earns its keep - it names the target every pass
 * writes and fixes each pipeline's blend up front, both of which read better than the ambient-state
 * idiom - and it is what a Metal backend, which does have command buffers, would map onto directly.
 *
 * @param Int emptyVao A bound VAO for the attribute-less draws (grid, axis, atlas page); a core profile
 *   requires one even when the shader synthesises positions from gl_VertexID.
 */
internal class GlFrameEncoder(private val emptyVao: Int) : FrameEncoder {
	override fun beginRenderPass(spec: RenderPassSpec): RenderPassEncoder {
		val target = spec.colorTarget as GlRenderTarget
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebuffer)
		GL11.glViewport(0, 0, spec.viewportWidth, spec.viewportHeight)
		val scissor = spec.scissor
		if (scissor != null) {
			// The spec's rect is top-left-origin (the read-back convention); GL scissors bottom-up.
			GL11.glEnable(GL11.GL_SCISSOR_TEST)
			GL11.glScissor(scissor.x, spec.viewportHeight - scissor.y - scissor.height, scissor.width, scissor.height)
		} else {
			GL11.glDisable(GL11.GL_SCISSOR_TEST)
		}
		if (spec.loadAction == LoadAction.Clear) {
			// With a scissor set, the clear is confined to the rect too - that is the point: a
			// bounds-scissored composite layer never pays a full-viewport clear.
			GL11.glClearColor(spec.clearRed, spec.clearGreen, spec.clearBlue, spec.clearAlpha)
			GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
		}
		// Load preserves the target's contents (a bound FBO already holds them); DontCare needs no work on
		// GL - the pass overwrites every pixel, which a tile-based backend would exploit but GL cannot.
		return GlRenderPassEncoder(emptyVao)
	}

	override fun beginDeformCapturePass(pipeline: DeformCapturePipeline, store: DeformedPositionStore): DeformCapturePassEncoder {
		val glPipeline = pipeline as GlDeformCapturePipeline
		GL20.glUseProgram(glPipeline.program)
		GL20.glUniform1i(glPipeline.locations.deltaTex, UNIT_DELTA)
		GL20.glUniform1i(glPipeline.locations.cpTex, UNIT_CP)
		// Discard the rasterizer: the capture writes positions via transform feedback and draws nothing.
		GL11.glEnable(GL30.GL_RASTERIZER_DISCARD)
		return GlDeformCapturePassEncoder(glPipeline, store as GlDeformedPositionStore)
	}

	override fun barrier(store: DeformedPositionStore) {
		// The declared write→read dependency on the position store. The WSL d3d12/Mesa stack does not order
		// transform-feedback writes before a later texture-buffer read, and glMemoryBarrier does not cover
		// feedback writes (they are coherent-pipeline writes), so the full sync is the only reliable fix.
		// Called only on pose-change frames, so a static pose pays nothing.
		GL11.glFinish()
	}

	override fun endFrame() {
		// Nothing to submit on GL: the calls already ran. A command-buffer backend would commit here.
	}
}

/** Records draws into one GL render pass. */
internal class GlRenderPassEncoder(private val emptyVao: Int) : RenderPassEncoder {
	private var pipeline: GlRenderPipeline? = null
	private val current: GlRenderPipeline get() = pipeline ?: error("setPipeline before drawing")

	// Per-program state is re-established only when the program actually switches. A uniform keeps its value
	// on the program object across binds, and the renderer passes the same camera / glue state for a whole
	// pass, so re-sending them per draw (as the pre-device renderer's useProgramFor did NOT) is wasted work:
	// a run of same-blend drawables would otherwise churn glUseProgram + blend + samplers + camera every draw.
	private var cameraApplied = false
	private var glueStateApplied = false

	// Scratch for the corner uniform arrays, reused across draws so the per-draw marshalling never allocates.
	private val cornerCellScratch = BufferUtils.createIntBuffer(org.umamo.render.glsl.MAX_CORNERS)
	private val cornerWeightScratch = BufferUtils.createFloatBuffer(org.umamo.render.glsl.MAX_CORNERS)

	override fun setPipeline(pipeline: RenderPipeline) {
		val glPipeline = pipeline as GlRenderPipeline
		if (glPipeline === this.pipeline) {
			return // already bound: program, blend, and samplers are all still in effect
		}
		this.pipeline = glPipeline
		cameraApplied = false
		glueStateApplied = false
		GL20.glUseProgram(glPipeline.program)
		applyBlend(glPipeline.blend)
		applyCull(glPipeline.cullBackFaces)
		// Sampler → texture unit is constant per program; -1 for a sampler the program lacks is a no-op.
		val locations = glPipeline.locations
		GL20.glUniform1i(locations.atlas, UNIT_ATLAS)
		GL20.glUniform1i(locations.maskTexture, UNIT_MASK)
		GL20.glUniform1i(locations.deltaTex, UNIT_DELTA)
		GL20.glUniform1i(locations.cpTex, UNIT_CP)
		GL20.glUniform1i(locations.positionBuffer, UNIT_POSITION)
		GL20.glUniform1i(locations.layerTexture, UNIT_LAYER)
		GL20.glUniform1i(locations.destTexture, UNIT_DEST)
	}

	override fun setCamera(worldToNdc: WorldToNdc, screenTexWidth: Int, screenTexHeight: Int) {
		if (cameraApplied) {
			return // the current program already holds this pass's camera (constant across the pass)
		}
		cameraApplied = true
		val locations = current.locations
		GL20.glUniform4f(locations.worldToNdc, worldToNdc.scaleX, worldToNdc.scaleY, worldToNdc.offsetX, worldToNdc.offsetY)
		GL20.glUniform2f(locations.screenTexSize, screenTexWidth.toFloat(), screenTexHeight.toFloat())
	}

	override fun drawPuppetMesh(mesh: GpuMesh, deform: DeformUniforms, fragment: FragmentUniforms, textures: DrawTextures) {
		marshalDeformUniforms(current.locations, deform, textures, cornerCellScratch, cornerWeightScratch)
		setFragmentUniforms(current, fragment, textures)
		val glMesh = mesh as GlMesh
		GL30.glBindVertexArray(glMesh.vao)
		GL11.glDrawElements(GL11.GL_TRIANGLES, glMesh.indexCount, GL11.GL_UNSIGNED_INT, 0L)
	}

	override fun drawGlueMesh(
		mesh: GpuMesh,
		store: DeformedPositionStore,
		baseVertexOffset: Int,
		glueIntensities: FloatArray,
		fragment: FragmentUniforms,
		textures: DrawTextures,
	) {
		val locations = current.locations
		// The weld intensities and the position texture buffer are constant across the pass, so bind them
		// once per glue-program bind (matching the old useProgramFor), not per glue draw.
		if (!glueStateApplied) {
			glueStateApplied = true
			GL20.glUniform1fv(locations.glueIntensity, glueIntensities)
			GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT_POSITION)
			GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, (store as GlDeformedPositionStore).textureBuffer)
		}
		GL20.glUniform1i(locations.baseOffset, baseVertexOffset)
		setFragmentUniforms(current, fragment, textures)
		val glMesh = mesh as GlMesh
		GL30.glBindVertexArray(glMesh.vao)
		GL11.glDrawElements(GL11.GL_TRIANGLES, glMesh.indexCount, GL11.GL_UNSIGNED_INT, 0L)
	}

	override fun drawAtlasPage(atlas: GpuTexture, pageWidth: Float, pageHeight: Float, fragment: FragmentUniforms) {
		val locations = current.locations
		GL20.glUniform2f(locations.pageSize, pageWidth, pageHeight)
		setFragmentUniforms(current, fragment, DrawTextures().also { it.atlas = atlas })
		GL30.glBindVertexArray(emptyVao)
		GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4)
	}

	override fun drawComposite(composite: CompositeUniforms, textures: DrawTextures) {
		val locations = current.locations
		GL20.glUniform1i(locations.colorMode, composite.colorMode)
		GL20.glUniform1i(locations.alphaMode, composite.alphaMode)
		GL20.glUniform1f(locations.opacity, composite.opacity)
		GL20.glUniform3f(locations.multiplyColor, composite.multiplyRed, composite.multiplyGreen, composite.multiplyBlue)
		GL20.glUniform3f(locations.screenColor, composite.screenRed, composite.screenGreen, composite.screenBlue)
		GL20.glUniform1i(locations.useMask, if (composite.useMask) 1 else 0)
		GL20.glUniform1i(locations.invertMask, if (composite.invertMask) 1 else 0)
		textures.compositeLayer?.let { bindTexture2D(UNIT_LAYER, it) }
		textures.destinationSnapshot?.let { bindTexture2D(UNIT_DEST, it) }
		if (composite.useMask) {
			textures.maskCoverage?.let { bindTexture2D(UNIT_MASK, it) }
		}
		GL30.glBindVertexArray(emptyVao)
		GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3)
	}

	override fun drawGrid(uniforms: GridUniforms) {
		val locations = current.locations
		val affine = uniforms.worldToNdc
		GL20.glUniform4f(locations.worldToNdc, affine.scaleX, affine.scaleY, affine.offsetX, affine.offsetY)
		GL20.glUniform2f(locations.viewportSize, uniforms.viewportWidth.toFloat(), uniforms.viewportHeight.toFloat())
		GL20.glUniform2f(locations.majorSpacing, uniforms.majorSpacingX, uniforms.majorSpacingY)
		GL20.glUniform2f(locations.gridOrigin, uniforms.originX, uniforms.originY)
		GL20.glUniform1f(locations.subdivisions, uniforms.subdivisions.toFloat())
		GL20.glUniform1f(locations.lineWidthPx, uniforms.lineWidthPx)
		val colors = uniforms.colors
		GL20.glUniform3f(locations.backgroundColor, colors.backgroundRed, colors.backgroundGreen, colors.backgroundBlue)
		GL20.glUniform3f(locations.majorColor, colors.majorRed, colors.majorGreen, colors.majorBlue)
		GL20.glUniform3f(locations.minorColor, colors.minorRed, colors.minorGreen, colors.minorBlue)
		GL30.glBindVertexArray(emptyVao)
		GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3)
	}

	override fun drawAxisLine(uniforms: AxisLineUniforms) {
		val locations = current.locations
		GL20.glUniform1f(locations.linePositionNdc, uniforms.linePositionNdc)
		GL20.glUniform1f(locations.lineVertical, if (uniforms.vertical) 1f else 0f)
		GL20.glUniform3f(locations.lineColor, uniforms.red, uniforms.green, uniforms.blue)
		GL30.glBindVertexArray(emptyVao)
		GL11.glDrawArrays(GL11.GL_LINES, 0, 2)
	}

	override fun end() {
		GL30.glBindVertexArray(0)
		GL20.glUseProgram(0)
		// Scissor is per-pass state established in beginRenderPass; dropping it here keeps the
		// between-pass operations (resolve blits, read-backs) unclipped whatever pass ran last.
		GL11.glDisable(GL11.GL_SCISSOR_TEST)
	}

	/** Sets a draw's fragment uniforms and binds its atlas / mask textures when present. */
	private fun setFragmentUniforms(pipeline: GlRenderPipeline, fragment: FragmentUniforms, textures: DrawTextures) {
		val locations = pipeline.locations
		GL20.glUniform1i(locations.useTexture, if (fragment.useTexture) 1 else 0)
		val atlas = textures.atlas
		if (fragment.useTexture && atlas != null) {
			bindTexture2D(UNIT_ATLAS, atlas)
		} else {
			GL20.glUniform4f(locations.drawColor, fragment.colorRed, fragment.colorGreen, fragment.colorBlue, fragment.colorAlpha)
		}
		GL20.glUniform1f(locations.opacity, fragment.opacity)
		GL20.glUniform3f(locations.multiplyColor, fragment.multiplyRed, fragment.multiplyGreen, fragment.multiplyBlue)
		GL20.glUniform3f(locations.screenColor, fragment.screenRed, fragment.screenGreen, fragment.screenBlue)
		GL20.glUniform1f(locations.highlight, fragment.highlight)
		GL20.glUniform3f(locations.highlightColor, fragment.highlightRed, fragment.highlightGreen, fragment.highlightBlue)
		// Sent every draw, like the rest of these - a program that does not declare them resolves -1, and
		// glUniform* with -1 is a defined no-op, so the grid / composite / axis pipelines ignore it.
		GL20.glUniform3f(locations.uvAffineRow0, fragment.uvAffine[0], fragment.uvAffine[1], fragment.uvAffine[2])
		GL20.glUniform3f(locations.uvAffineRow1, fragment.uvAffine[3], fragment.uvAffine[4], fragment.uvAffine[5])
		GL20.glUniform1i(locations.useMask, if (fragment.useMask) 1 else 0)
		GL20.glUniform1i(locations.invertMask, if (fragment.invertMask) 1 else 0)
		if (fragment.useMask) {
			textures.maskCoverage?.let { bindTexture2D(UNIT_MASK, it) }
		}
	}
}

/** Records the pass-1 deform capture into the shared position store. */
internal class GlDeformCapturePassEncoder(
	private val pipeline: GlDeformCapturePipeline,
	private val store: GlDeformedPositionStore,
) : DeformCapturePassEncoder {
	private val cornerCellScratch = BufferUtils.createIntBuffer(org.umamo.render.glsl.MAX_CORNERS)
	private val cornerWeightScratch = BufferUtils.createFloatBuffer(org.umamo.render.glsl.MAX_CORNERS)

	override fun captureDeformedPositions(
		mesh: GpuMesh,
		deform: DeformUniforms,
		textures: DrawTextures,
		destinationVertexOffset: Int,
		vertexCount: Int,
	) {
		marshalDeformUniforms(pipeline.locations, deform, textures, cornerCellScratch, cornerWeightScratch)
		GL30.glBindVertexArray((mesh as GlMesh).vao)
		GL30.glBindBufferRange(
			GL30.GL_TRANSFORM_FEEDBACK_BUFFER,
			0,
			store.buffer,
			destinationVertexOffset.toLong() * 2 * Float.SIZE_BYTES,
			vertexCount.toLong() * 2 * Float.SIZE_BYTES,
		)
		GL30.glBeginTransformFeedback(GL11.GL_POINTS)
		GL11.glDrawArrays(GL11.GL_POINTS, 0, vertexCount)
		GL30.glEndTransformFeedback()
	}

	override fun end() {
		GL11.glDisable(GL30.GL_RASTERIZER_DISCARD)
	}
}

/**
 * Marshals a mesh's per-pose deform uniforms and binds its delta (and, for a warp parent, control-point)
 * texture.
 *
 * Shared by the pass-2 draw and the pass-1 capture so the two paths cannot diverge: they feed the exact
 * same deform inputs to the exact same DEFORM_GLSL, which is what lets GpuDeformValidationTest's pin on the
 * draw path also stand for the capture path.  Both pass their own reused scratch buffers.
 *
 * @param GlUniformLocations locations         The bound program's uniform locations.
 * @param DeformUniforms     deform            The per-pose deform inputs.
 * @param DrawTextures       textures          The delta and (warp) control-point textures to bind.
 * @param IntBuffer          cornerCellScratch Reused scratch for the corner-cell uniform array.
 * @param FloatBuffer        cornerWeightScratch Reused scratch for the corner-weight uniform array.
 */
private fun marshalDeformUniforms(
	locations: GlUniformLocations,
	deform: DeformUniforms,
	textures: DrawTextures,
	cornerCellScratch: IntBuffer,
	cornerWeightScratch: FloatBuffer,
) {
	val cornerCount = minOf(org.umamo.render.glsl.MAX_CORNERS, deform.cornerCount)
	cornerCellScratch.clear()
	cornerWeightScratch.clear()
	for (cornerIndex in 0 until cornerCount) {
		cornerCellScratch.put(deform.cornerCell[cornerIndex])
		cornerWeightScratch.put(deform.cornerWeight[cornerIndex])
	}
	cornerCellScratch.flip()
	cornerWeightScratch.flip()
	GL20.glUniform1i(locations.cornerCount, cornerCount)
	GL20.glUniform1iv(locations.cornerCell, cornerCellScratch)
	GL20.glUniform1fv(locations.cornerWeight, cornerWeightScratch)
	// Blend-shape columns: skip the array uploads entirely for the zero-blend common case.
	val blendCount = minOf(org.umamo.render.glsl.MAX_BLEND_CORNERS, deform.blendCount)
	GL20.glUniform1i(locations.blendCount, blendCount)
	if (blendCount > 0) {
		GL20.glUniform1iv(locations.blendCell, deform.blendCell)
		GL20.glUniform1fv(locations.blendWeight, deform.blendWeight)
	}
	GL20.glUniform1i(locations.parentType, deform.parentType)
	if (deform.parentType == 1) {
		GL20.glUniform1fv(locations.rot, deform.rotation)
	} else if (deform.parentType == 2) {
		GL20.glUniform1i(locations.warpCols, deform.warpColumns)
		GL20.glUniform1i(locations.warpRows, deform.warpRows)
		GL20.glUniform1i(locations.warpBilinear, if (deform.warpBilinear) 1 else 0)
		textures.warpControlPoints?.let { bindTexture2D(UNIT_CP, it) }
	}
	textures.deltaTexture?.let { bindTexture2D(UNIT_DELTA, it) }
}

/** Binds [texture] to the given texture unit as a 2D texture. */
private fun bindTexture2D(unit: Int, texture: GpuTexture) {
	GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
	GL11.glBindTexture(GL11.GL_TEXTURE_2D, (texture as GlTexture).handle)
}

/**
 * Sets the face-culling state for a pipeline.  Applied on every pipeline bind (like blend) because
 * GL cull state is global - a culled drawable's pipeline must not leak culling into the next draw.
 * The front face is CW: corpus rest meshes bake CLOCKWISE in the renderer's Y-negated world space
 * (probed 50933:0 on EricaTamamo), so a culled drawable stays visible at rest and disappears only
 * when a deformation flips it inside-out.  This winding convention is inferred from corpus mesh
 * data, not yet cross-checked against the official editor's own culling render.
 *
 * @param Boolean cullBackFaces True to cull back faces; false leaves the mesh double-sided.
 */
private fun applyCull(cullBackFaces: Boolean) {
	if (cullBackFaces) {
		GL11.glEnable(GL11.GL_CULL_FACE)
		GL11.glFrontFace(GL11.GL_CW)
		GL11.glCullFace(GL11.GL_BACK)
	} else {
		GL11.glDisable(GL11.GL_CULL_FACE)
	}
}

/** Sets the fixed-function blend state for a pipeline's blend mode. */
private fun applyBlend(blend: org.umamo.render.device.PipelineBlend) {
	when (blend) {
		org.umamo.render.device.PipelineBlend.Opaque -> GL11.glDisable(GL11.GL_BLEND)
		org.umamo.render.device.PipelineBlend.Normal -> {
			GL11.glEnable(GL11.GL_BLEND)
			GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
		}
		org.umamo.render.device.PipelineBlend.Additive -> {
			GL11.glEnable(GL11.GL_BLEND)
			GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE)
		}
		org.umamo.render.device.PipelineBlend.Multiply -> {
			GL11.glEnable(GL11.GL_BLEND)
			GL14.glBlendFuncSeparate(GL11.GL_DST_COLOR, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE)
		}
	}
}