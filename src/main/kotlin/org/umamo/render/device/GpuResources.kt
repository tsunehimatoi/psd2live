package org.umamo.render.device

import org.umamo.render.puppet.GlueVertexAttributes

// The device's opaque handles and the specs used to create them.
//
// Handles are interfaces with no members on purpose: a caller can hold one and hand it back, and can do
// nothing else with it. The concrete type is backend state - a GL name, an MTLBuffer - and keeping it
// unreachable is what stops backend concepts leaking into the shared renderer.

/** A texture the device owns, sampleable from a shader. */
public interface GpuTexture

/**
 * One mesh's vertex + index residency: rest positions, UVs, indices, and - for a glue mesh - the
 * per-vertex weld attributes.  Whatever the backend needs to issue one indexed draw of it.
 */
public interface GpuMesh

/** A compiled, immutable draw pipeline: shader stages, blend, and attribute layout, fixed at creation. */
public interface RenderPipeline

/** A compiled pipeline that evaluates the deform per vertex and writes positions, drawing nothing. */
public interface DeformCapturePipeline

/**
 * A surface a pass renders into.
 *
 * Never implicitly current: every pass names the target it writes.  The renderer used to discover its own
 * target by reading the bound framebuffer out of ambient GL state, which worked only because the caller
 * happened to have bound it first - a coupling that was invisible at both ends.
 */
public interface RenderTarget {
	/** This target's contents as a sampleable texture, or null when created with `sampled = false`. */
	val sampledTexture: GpuTexture?
}

/**
 * The shared store of pass-1 deformed world positions, addressed by GLOBAL vertex index across every
 * glue-involved mesh (see `org.umamo.render.puppet.planGlueLayout`).
 *
 * How the GPU actually holds it is the backend's business - a texture buffer on desktop GL today, and a
 * 2D texture once the GLES 3.0 path lands, since texture buffers are 3.2 there.  The renderer only ever
 * says "the store", which is why that change does not reach it.
 */
public interface DeformedPositionStore

/** A texture/target pixel format.  Only the two the renderer actually uses. */
public enum class TextureFormat {
	/** 8-bit RGBA: atlases, mask coverage, and any target read back to the host. */
	Rgba8,

	/** 32-bit float RG: per-vertex morph deltas, warp control points, deformed positions. */
	Rg32F,
}

/** How a texture's texels are filtered between sample points. */
public enum class TextureFilter {
	/** No interpolation.  Correct for data textures, where a blend between texels is meaningless. */
	Nearest,

	/** Bilinear.  Correct for art. */
	Linear,
}

/** What a texture yields for a coordinate outside [0, 1]. */
public enum class TextureWrap {
	/** Repeats the nearest edge texel outward.  Correct for an atlas page, whose art never reaches its border. */
	ClampToEdge,

	/**
	 * Yields transparent black outside the image.  Correct for art sampled in its OWN frame, which a mesh
	 * routinely overhangs: an art mesh rings outside the opaque region and authored meshes extend further
	 * still for deformation coverage, so a drawable's coordinates run past its layer image by tens to
	 * hundreds of pixels.  Clamping to the edge would repeat the border row or column across that overhang
	 * as a streak; transparent black reproduces what the packed atlas showed there, which is its padding.
	 *
	 * @note GL_CLAMP_TO_BORDER with a zero border color on the GL family (core in GL 3.3 and GLES 3.2),
	 *   MTLSamplerAddressMode.clampToZero on Metal.
	 */
	ClampToTransparentBorder,
}

/**
 * Which draw a pipeline performs.
 *
 * The device is asked for a pipeline by PURPOSE, never by shader source, so each backend owns its own
 * shader library: the GL family reads `org.umamo.render.glsl`, and a Metal backend would supply MSL from
 * a precompiled library.  A `source(purpose, stage): String` seam was considered and rejected for
 * assuming source text exists at runtime, which is false for a `.metallib`.
 *
 * Closed on purpose: adding a pipeline forces every backend to answer for it rather than silently
 * lacking one.
 */
public enum class PipelinePurpose {
	/** A non-glue art mesh: deform in the vertex stage, project, sample the atlas. */
	PuppetDeformDraw,

	/** A glue art mesh: read pass-1 own/partner positions, weld, project, sample the atlas. */
	PuppetGlueDraw,

	/** The UV editor's flat atlas-page underlay quad (attribute-less; corners from the vertex index). */
	AtlasPageDraw,

	/** The world-aligned grid backdrop (attribute-less full-screen triangle). */
	GridBackdrop,

	/** One world-origin axis line (attribute-less; two endpoints from the vertex index). */
	WorldAxisLine,

	/**
	 * The layer composite: an attribute-less full-screen triangle whose fragment blends a
	 * rendered layer against a destination snapshot in-shader (the 18 color x 5 alpha modes are not
	 * fixed-function-expressible), writing with blending DISABLED.  Pair with [PipelineBlend.Opaque].
	 */
	Composite,
}

/**
 * How a pipeline's fragments combine with the target.
 *
 * Semantic rather than factor-level: only these four combinations exist, they are model concepts, and a
 * 15-factor x 4-slot surface would be API nobody uses that every backend would still have to map.  An
 * exhaustive `when` over four values also means adding a mode cannot be forgotten in a backend.
 *
 * [Opaque] is not one of `org.umamo.runtime.model.BlendMode`'s three - it is the backdrop's
 * blending-disabled write, which is why this is its own enum rather than a reuse of that one.
 */
public enum class PipelineBlend {
	/** Blending off: the fragment replaces the target.  The grid and axis backdrop. */
	Opaque,

	/** Premultiplied source-over.  The default for art. */
	Normal,

	/** Additive.  Glows and highlights. */
	Additive,

	/** Multiply.  Shadows and shading. */
	Multiply,
}

/**
 * The immutable description of a draw pipeline.  Created once at init and reused every frame.
 *
 * @property PipelinePurpose purpose       Which draw this pipeline performs.
 * @property PipelineBlend   blend         How its fragments combine with the target.
 * @property Boolean         cullBackFaces Whether back faces are culled (the Cubism drawable
 *   Culling toggle); false = double-sided, the default.  Part of the spec (not a draw parameter)
 *   so the state is bound with the pipeline, like blend.
 */
public data class RenderPipelineSpec(
	val purpose: PipelinePurpose,
	val blend: PipelineBlend,
	val cullBackFaces: Boolean = false,
)

/**
 * A render target to allocate.
 *
 * @property Int           width   Width in pixels.
 * @property Int           height  Height in pixels.
 * @property TextureFormat format  The pixel format.
 * @property Boolean       sampled Whether a later pass samples this target as a texture.  False lets a
 *   backend pick a write-only surface - the GL device allocates a renderbuffer rather than a texture,
 *   which cannot be read back directly but is otherwise cheaper.
 */
public data class RenderTargetSpec(
	val width: Int,
	val height: Int,
	val format: TextureFormat,
	val sampled: Boolean,
)

/**
 * A mesh's static residency to upload.
 *
 * @property FloatArray            restPositions  Rest positions, interleaved x,y.
 * @property FloatArray            uvs            Atlas UVs, interleaved u,v.
 * @property IntArray              indices        Triangle indices.  EMPTY IS LEGAL: a zero-triangle glue
 *   anchor draws nothing and exists only so its deformed positions can be a weld partner.
 * @property GlueVertexAttributes? glueAttributes Per-vertex weld attributes, or null for a non-glue mesh.
 */
public class MeshSpec(
	val restPositions: FloatArray,
	val uvs: FloatArray,
	val indices: IntArray,
	val glueAttributes: GlueVertexAttributes?,
)