package org.umamo.render.gl

import org.lwjgl.opengl.GL20
import org.umamo.render.device.DeformCapturePipeline
import org.umamo.render.device.DeformedPositionStore
import org.umamo.render.device.GpuMesh
import org.umamo.render.device.GpuTexture
import org.umamo.render.device.PipelineBlend
import org.umamo.render.device.RenderPipeline
import org.umamo.render.device.RenderTarget

// The GL device's concrete handles. Each wraps the GL names its interface hides; nothing outside this
// package can reach them, which is what keeps GL out of the shared renderer.

/** A GL texture name. */
internal class GlTexture(val handle: Int) : GpuTexture

/**
 * A mesh's GL residency: the VAO plus every buffer it references.
 *
 * The buffers are kept individually, not just the VAO, because they outlive their bindings: an edit
 * re-uploads [positionVbo] / [uvVbo] in place, and freeing the mesh must free all of them.  0 where the
 * mesh has none - an index-less glue anchor has no [indexEbo], a non-glue mesh no [glueVbo].
 */
internal class GlMesh(
	val vao: Int,
	val positionVbo: Int,
	val uvVbo: Int,
	val glueVbo: Int,
	val indexEbo: Int,
	val vertexCount: Int,
	val indexCount: Int,
) : GpuMesh

/**
 * A render target: its framebuffer plus whichever attachment backs it.
 *
 * Exactly one of [colorTexture] / [colorRenderbuffer] is non-zero, chosen by `RenderTargetSpec.sampled`.
 * A renderbuffer is the cheaper write-only surface but cannot be sampled OR read back directly, which is
 * precisely why the flag exists rather than always allocating a texture.
 */
internal class GlRenderTarget(
	val framebuffer: Int,
	val colorTexture: Int,
	val colorRenderbuffer: Int,
	val width: Int,
	val height: Int,
) : RenderTarget {
	override val sampledTexture: GpuTexture? = if (colorTexture != 0) GlTexture(colorTexture) else null
}

/**
 * The shared pass-1 position store: a buffer plus the texture-buffer view pass 2 samples it through.
 *
 * A texture buffer object today, which is desktop-GL only (GLES has them at 3.2, and the Android baseline
 * is 3.0).  The GLES port repacks this as a 2D texture; nothing above the device sees the difference.
 */
internal class GlDeformedPositionStore(
	val buffer: Int,
	val textureBuffer: Int,
	val vertexCapacity: Int,
) : DeformedPositionStore

/**
 * Every uniform any of this backend's programs declares, resolved once at pipeline creation.
 *
 * One flat set rather than a per-purpose struct, because GL makes it free: `glGetUniformLocation` returns
 * -1 for a uniform a program does not declare (or that its compiler optimised away), and `glUniform*`
 * with -1 is a defined no-op.  So the grid pipeline simply has -1 for every puppet uniform and setting
 * them costs a branch in the driver.
 *
 * Resolving them ONCE here is the point: the renderer previously looked a location up by name on every
 * draw - 36 call sites' worth of string lookups per frame.
 */
internal class GlUniformLocations(program: Int) {
	// Per-pass.  viewportSize is the grid program's genuine viewport extent; screenTexSize is the
	// puppet/composite programs' screen-space texture divisor (the side targets' allocated size,
	// which the grow-only capacity can hold above the viewport size).
	val worldToNdc = GL20.glGetUniformLocation(program, "worldToNdc")
	val viewportSize = GL20.glGetUniformLocation(program, "viewportSize")
	val screenTexSize = GL20.glGetUniformLocation(program, "screenTexSize")

	// Samplers
	val atlas = GL20.glGetUniformLocation(program, "atlas")
	val maskTexture = GL20.glGetUniformLocation(program, "maskTexture")
	val deltaTex = GL20.glGetUniformLocation(program, "deltaTex")
	val cpTex = GL20.glGetUniformLocation(program, "cpTex")
	val positionBuffer = GL20.glGetUniformLocation(program, "positionBuffer")

	// Deform
	val cornerCount = GL20.glGetUniformLocation(program, "cornerCount")
	val cornerCell = GL20.glGetUniformLocation(program, "cornerCell")
	val cornerWeight = GL20.glGetUniformLocation(program, "cornerWeight")
	val blendCount = GL20.glGetUniformLocation(program, "blendCount")
	val blendCell = GL20.glGetUniformLocation(program, "blendCell")
	val blendWeight = GL20.glGetUniformLocation(program, "blendWeight")
	val parentType = GL20.glGetUniformLocation(program, "parentType")
	val rot = GL20.glGetUniformLocation(program, "rot")
	val warpCols = GL20.glGetUniformLocation(program, "warpCols")
	val warpRows = GL20.glGetUniformLocation(program, "warpRows")
	val warpBilinear = GL20.glGetUniformLocation(program, "warpBilinear")

	// Glue
	val baseOffset = GL20.glGetUniformLocation(program, "baseOffset")
	val glueIntensity = GL20.glGetUniformLocation(program, "glueIntensity")

	// Fragment
	val useTexture = GL20.glGetUniformLocation(program, "useTexture")
	val drawColor = GL20.glGetUniformLocation(program, "drawColor")
	val opacity = GL20.glGetUniformLocation(program, "opacity")
	val useMask = GL20.glGetUniformLocation(program, "useMask")
	val invertMask = GL20.glGetUniformLocation(program, "invertMask")
	val highlight = GL20.glGetUniformLocation(program, "highlight")
	val highlightColor = GL20.glGetUniformLocation(program, "highlightColor")
	val uvAffineRow0 = GL20.glGetUniformLocation(program, "uvAffineRow0")
	val uvAffineRow1 = GL20.glGetUniformLocation(program, "uvAffineRow1")

	// Atlas page
	val pageSize = GL20.glGetUniformLocation(program, "pageSize")

	// Layer composite
	val layerTexture = GL20.glGetUniformLocation(program, "layerTexture")
	val destTexture = GL20.glGetUniformLocation(program, "destTexture")
	val colorMode = GL20.glGetUniformLocation(program, "colorMode")
	val alphaMode = GL20.glGetUniformLocation(program, "alphaMode")
	val multiplyColor = GL20.glGetUniformLocation(program, "multiplyColor")
	val screenColor = GL20.glGetUniformLocation(program, "screenColor")

	// Grid backdrop
	val majorSpacing = GL20.glGetUniformLocation(program, "majorSpacing")
	val subdivisions = GL20.glGetUniformLocation(program, "subdivisions")
	val lineWidthPx = GL20.glGetUniformLocation(program, "lineWidthPx")
	val backgroundColor = GL20.glGetUniformLocation(program, "backgroundColor")
	val majorColor = GL20.glGetUniformLocation(program, "majorColor")
	val minorColor = GL20.glGetUniformLocation(program, "minorColor")
	val gridOrigin = GL20.glGetUniformLocation(program, "gridOrigin")

	// Axis line
	val linePositionNdc = GL20.glGetUniformLocation(program, "linePositionNdc")
	val lineVertical = GL20.glGetUniformLocation(program, "lineVertical")
	val lineColor = GL20.glGetUniformLocation(program, "lineColor")
}

/** A linked draw program with its blend, cull state, and resolved uniform locations. */
internal class GlRenderPipeline(
	val program: Int,
	val blend: PipelineBlend,
	val cullBackFaces: Boolean,
	val locations: GlUniformLocations,
) : RenderPipeline

/** The transform-feedback program that captures deformed positions without rasterizing. */
internal class GlDeformCapturePipeline(
	val program: Int,
	val locations: GlUniformLocations,
) : DeformCapturePipeline