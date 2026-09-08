package org.umamo.render.device

import org.umamo.render.GridColors
import org.umamo.render.glsl.MAX_BLEND_CORNERS
import org.umamo.render.glsl.MAX_CORNERS

// The per-draw and per-pass shader inputs, as structs rather than named scalars.
//
// The renderer names no uniform. Each backend hand-writes one marshaller per struct: the GL family caches
// the locations once at pipeline creation and issues glUniform*; a Metal backend would write a mirroring
// MSL struct and setVertexBytes it. Deliberately NOT reflection-driven - kotlin-reflect does not exist on
// Kotlin/Native - and deliberately not a writeTo(sink), which would force this shared code to commit to a
// memory layout when std140 and MSL alignment disagree. commonMain supplies VALUES; layout is the
// backend's. That split is the cleanest line in the design.

/** The camera's world→NDC affine: `ndc = world * (scaleX, scaleY) + (offsetX, offsetY)`. */
public data class WorldToNdc(val scaleX: Float, val scaleY: Float, val offsetX: Float, val offsetY: Float)

/**
 * One drawable's per-pose deform inputs: the active morph corners and the baked parent transform.
 *
 * MUTABLE AND REUSED. The renderer refills one instance per draw rather than allocating ~60 of these a
 * frame. A device implementation MUST therefore marshal every field before returning and MUST NOT retain
 * the instance. Both `glUniform*` and Metal's `setVertexBytes` copy, so both can honor that; a backend
 * that wanted to record and replay a command buffer could not, and would need its own copy.
 *
 * Getting this wrong renders garbage that looks exactly like a driver bug, which is why it is stated here
 * rather than left to be discovered.
 *
 * @property Int        cornerCount  Active corners, 0..MAX_CORNERS.
 * @property IntArray   cornerCell   Each active corner's keyform cell linear index; length MAX_CORNERS.
 * @property FloatArray cornerWeight Each active corner's blend weight; length MAX_CORNERS.
 * @property Int        parentType   0 = direct, 1 = rotation, 2 = warp.
 * @property FloatArray rotation     The rotation affine (c12,c13,c14,c15,ox,oy); read when parentType==1.
 * @property Int        warpColumns  Warp lattice columns;  read when parentType==2.
 * @property Int        warpRows     Warp lattice rows;     read when parentType==2.
 * @property Boolean    warpBilinear Bilinear vs triangle blend; read when parentType==2.
 */
public class DeformUniforms {
	var cornerCount: Int = 0
	val cornerCell: IntArray = IntArray(MAX_CORNERS)
	val cornerWeight: FloatArray = FloatArray(MAX_CORNERS)

	// Blend-shape delta columns (appended after the grid cells in the delta texture); zero-blend
	// drawables keep blendCount == 0 so the shader loop never runs.
	var blendCount: Int = 0
	val blendCell: IntArray = IntArray(MAX_BLEND_CORNERS)
	val blendWeight: FloatArray = FloatArray(MAX_BLEND_CORNERS)
	var parentType: Int = 0
	val rotation: FloatArray = FloatArray(6)
	var warpColumns: Int = 0
	var warpRows: Int = 0
	var warpBilinear: Boolean = false
}

/**
 * One draw's fragment inputs, shared by the puppet and atlas-page pipelines.
 *
 * Mutable and reused on the same contract as [DeformUniforms].
 *
 * @property Boolean useTexture   Sample [DrawTextures.atlas] rather than the flat color below.
 * @property Float   colorRed     Flat color red,   used when [useTexture] is false.
 * @property Float   colorGreen   Flat color green, used when [useTexture] is false.
 * @property Float   colorBlue    Flat color blue,  used when [useTexture] is false.
 * @property Float   colorAlpha   Flat color alpha, used when [useTexture] is false.
 * @property Float   opacity      The pose-blended opacity, multiplied into alpha.
 * @property Boolean useMask      Multiply alpha by the mask coverage.
 * @property Boolean invertMask   Use 1 - coverage instead.
 * @property Float   multiplyRed    Per-drawable multiply-color red   (identity 1), applied before premultiply.
 * @property Float   multiplyGreen  Per-drawable multiply-color green (identity 1).
 * @property Float   multiplyBlue   Per-drawable multiply-color blue  (identity 1).
 * @property Float   screenRed      Per-drawable screen-color red   (identity 0).
 * @property Float   screenGreen    Per-drawable screen-color green (identity 0).
 * @property Float   screenBlue     Per-drawable screen-color blue  (identity 0).
 * @property Float   highlight    How far to tint toward the highlight color (0 = untinted).
 * @property Float   highlightRed   Highlight tint red.
 * @property Float   highlightGreen Highlight tint green.
 * @property Float   highlightBlue  Highlight tint blue.
 * @property FloatArray uvAffine   The 2x3 row-major affine (m00, m01, m02, m10, m11, m12) applied to the
 *   interpolated texture coordinate before sampling.  Identity leaves the stored mapping alone; a
 *   drawable sampling its SOURCE ARTWORK instead of the packed atlas carries the transform between
 *   those two frames here, so the same stored coordinates address either image.  Rides on the fragment
 *   uniforms deliberately: the glue draw path marshals only these, so a vertex-stage home would
 *   silently miss glue meshes.
 */
public class FragmentUniforms {
	var useTexture: Boolean = false
	var colorRed: Float = 0f
	var colorGreen: Float = 0f
	var colorBlue: Float = 0f
	var colorAlpha: Float = 0f
	var opacity: Float = 1f
	var useMask: Boolean = false
	var invertMask: Boolean = false
	var multiplyRed: Float = 1f
	var multiplyGreen: Float = 1f
	var multiplyBlue: Float = 1f
	var screenRed: Float = 0f
	var screenGreen: Float = 0f
	var screenBlue: Float = 0f
	var highlight: Float = 0f
	var highlightRed: Float = 0f
	var highlightGreen: Float = 0f
	var highlightBlue: Float = 0f

	// Six floats reused in place, like every other value here - see the class contract.  Identity, NOT
	// zeroes: a zeroed affine collapses every coordinate onto one texel.
	val uvAffine: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
}

/**
 * The textures one draw samples.  Null means the draw does not use that slot.
 *
 * Mutable and reused per draw on the same contract as [DeformUniforms] - the renderer refills one instance
 * rather than allocating a bundle per drawable per frame; the device must read the fields before returning
 * and must not retain the instance (binding a texture reads its handle synchronously, so both backends can).
 *
 * @property GpuTexture? atlas             The art texture the draw samples - the packed atlas page, or the
 *   drawable's source artwork when the puppet displays from that - or null to draw the flat color.
 * @property GpuTexture? maskCoverage      The clip mask's coverage, or null when unmasked.
 * @property GpuTexture? deltaTexture      The mesh's morph delta table (deform draws only).
 * @property GpuTexture? warpControlPoints The parent warp's baked control points, or null when the parent
 *   is not a warp.
 */
public class DrawTextures {
	public var atlas: GpuTexture? = null
	public var maskCoverage: GpuTexture? = null
	public var deltaTexture: GpuTexture? = null
	public var warpControlPoints: GpuTexture? = null

	/** The rendered layer a [composite draw][CompositeUniforms] blends in. */
	public var compositeLayer: GpuTexture? = null

	/** The destination snapshot the composite blends against (a copy of the target so far). */
	public var destinationSnapshot: GpuTexture? = null
}

/**
 * One composite draw's inputs: which color/alpha blend to compute in-shader, the composite's
 * pose-blended channels, and its optional clip-mask sampling.
 *
 * Mutable and reused on the same contract as [DeformUniforms].  The mode ints are the MOC3 packed
 * encodings' halves (colorMode 0-17, alphaMode 0-4 - see BlendModeMapping); the composite shader
 * switches on them, and the pure reference `compositeReference` documents the exact math.
 *
 * @property Int     colorMode     The color blend mode int (0-17).
 * @property Int     alphaMode     The alpha blend mode int (0-4).
 * @property Float   opacity       The composite opacity (0..1), applied to the layer before blending.
 * @property Float   multiplyRed   Multiply color red (identity 1).
 * @property Float   multiplyGreen Multiply color green (identity 1).
 * @property Float   multiplyBlue  Multiply color blue (identity 1).
 * @property Float   screenRed     Screen color red (identity 0).
 * @property Float   screenGreen   Screen color green (identity 0).
 * @property Float   screenBlue    Screen color blue (identity 0).
 * @property Boolean useMask       Multiply the layer's alpha by the mask coverage.
 * @property Boolean invertMask    Use 1 - coverage instead.
 */
public class CompositeUniforms {
	public var colorMode: Int = 0
	public var alphaMode: Int = 0
	public var opacity: Float = 1f
	public var multiplyRed: Float = 1f
	public var multiplyGreen: Float = 1f
	public var multiplyBlue: Float = 1f
	public var screenRed: Float = 0f
	public var screenGreen: Float = 0f
	public var screenBlue: Float = 0f
	public var useMask: Boolean = false
	public var invertMask: Boolean = false
}

/**
 * The grid backdrop's inputs for one pass.
 *
 * @property WorldToNdc worldToNdc     The affine the fragment inverts to recover its world position.
 * @property Int        viewportWidth  The framebuffer width, for the fragment's screen-to-world math.
 * @property Int        viewportHeight The framebuffer height.
 * @property Float      originX        World x the lattice is anchored on (a major line crosses it).
 * @property Float      originY        World y the lattice is anchored on.
 * @property Float      majorSpacingX  Major line spacing along X, in world units.
 * @property Float      majorSpacingY  Major line spacing along Y, in world units.
 * @property Int        subdivisions   Minor lines per major cell.
 * @property Float      lineWidthPx    Line half-width in framebuffer pixels (accounts for supersampling).
 * @property GridColors colors         The background / major / minor colors.
 */
public data class GridUniforms(
	val worldToNdc: WorldToNdc,
	val viewportWidth: Int,
	val viewportHeight: Int,
	val originX: Float,
	val originY: Float,
	val majorSpacingX: Float,
	val majorSpacingY: Float,
	val subdivisions: Int,
	val lineWidthPx: Float,
	val colors: GridColors,
)

/**
 * One axis line's inputs.
 *
 * @property Float   linePositionNdc The line's fixed coordinate, in NDC.
 * @property Boolean vertical        True for the vertical axis, false for the horizontal one.
 * @property Float   red             Line red.
 * @property Float   green           Line green.
 * @property Float   blue            Line blue.
 */
public data class AxisLineUniforms(
	val linePositionNdc: Float,
	val vertical: Boolean,
	val red: Float,
	val green: Float,
	val blue: Float,
)