package org.umamo.render.device

import org.umamo.format.raster.RasterImage

/**
 * The GPU backend seam: one implementation per graphics API.
 *
 * Everything above it - the puppet renderer, the deform eval, the pose resolution, the model diff - is
 * backend-neutral.  Desktop OpenGL 3.3 core is implemented; Android GLES 3.0 is a near-transliteration of
 * it (the two differ in binding style, not semantics); iPadOS Metal is a later port.
 *
 * Shaped for the GL family first, deliberately.  Where avoiding a GL-ism costs nothing it is avoided -
 * passes name their target, pipelines are immutable, uniforms are structs - because those read better
 * regardless.  Where pre-solving Metal would cost real complexity for a backend that does not exist yet,
 * it is not done; that bill is paid if and when Metal asks for it.
 *
 * A device is created by the host, which owns whatever context or queue the backend needs, and is used
 * from ONE thread only - the render thread.
 *
 * Two kinds of work sit on this interface.  Drawing is RECORDED into a frame: [beginFrame] hands out a
 * [FrameEncoder] whose passes must all be begun and ended within one begin/end.  The remaining
 * operations - [resolve], [beginReadback] / [pollReadback], [readPixels], and every resource create /
 * destroy - are SELF-CONTAINED: each is a complete unit of work on its own, called outside any frame, and
 * a backend that records into command buffers (Metal) is free to open and commit its own buffer internally
 * to service one.  They are not part of a FrameEncoder because they do not compose with a pass - a resolve
 * runs between a render frame and its read-back, and a read-back is polled across many later frames.
 *
 * GPU バックエンドの継ぎ目。GL / GLES / Metal ごとに実装する。描画はフレームに記録し、resolve / 読み戻し /
 * リソース操作はフレーム外の自己完結操作。
 */
public interface RenderDevice {
	// --- Resources. Everything the device hands out, the device frees. ---

	/**
	 * Uploads an image as a sampleable texture.
	 *
	 * @param Int           width  Width in pixels.
	 * @param Int           height Height in pixels.
	 * @param TextureFormat format The pixel format.
	 * @param TextureFilter filter How texels are filtered between sample points.
	 * @param ByteArray?    pixels RGBA8888, top row first; null allocates without uploading.
	 * @param TextureWrap   wrap   What a coordinate outside [0, 1] yields.
	 * @return GpuTexture The texture.
	 */
	fun createTexture(
		width: Int,
		height: Int,
		format: TextureFormat,
		filter: TextureFilter,
		pixels: ByteArray?,
		wrap: TextureWrap = TextureWrap.ClampToEdge,
	): GpuTexture

	/**
	 * Uploads float texels (RG pairs, row-major from the top) as a sampleable texture.
	 *
	 * @param Int           width  Width in texels.
	 * @param Int           height Height in texels.
	 * @param TextureFilter filter How texels are filtered.
	 * @param FloatArray    texels The texels.
	 * @return GpuTexture The texture.
	 */
	fun createFloatTexture(width: Int, height: Int, filter: TextureFilter, texels: FloatArray): GpuTexture

	/**
	 * Re-specifies an existing float texture's contents in place, keeping the same handle.
	 *
	 * The renderer calls this once per POSE CHANGE, never in the draw loop, and a backend must not
	 * re-create the resource here.  Warp control points are pose-dependent but frame-invariant, and
	 * re-specifying that texture 60x/sec churned the d3d12/Mesa driver into progressively corrupting the
	 * points it sampled - the "facial features warp over time" bug, worst on masked warp meshes, which
	 * draw twice.  The frequency is the fix; keep it.
	 *
	 * @param GpuTexture texture The texture to overwrite.
	 * @param Int        width   Width in texels.
	 * @param Int        height  Height in texels.
	 * @param FloatArray texels  The new texels.
	 */
	fun updateFloatTexture(texture: GpuTexture, width: Int, height: Int, texels: FloatArray)

	/**
	 * Uploads a mesh's static residency.
	 *
	 * @param MeshSpec spec The mesh's rest geometry and, for a glue mesh, its weld attributes.
	 * @return GpuMesh The resident mesh.
	 */
	fun createMesh(spec: MeshSpec): GpuMesh

	/**
	 * Re-uploads a resident mesh's rest positions in place; the topology is unchanged.
	 *
	 * @param GpuMesh    mesh          The resident mesh.
	 * @param FloatArray restPositions The new positions, same length as at upload.
	 */
	fun updateMeshPositions(mesh: GpuMesh, restPositions: FloatArray)

	/**
	 * Re-uploads a resident mesh's UVs in place; the topology is unchanged.
	 *
	 * @param GpuMesh    mesh The resident mesh.
	 * @param FloatArray uvs  The new UVs, same length as at upload.
	 */
	fun updateMeshUvs(mesh: GpuMesh, uvs: FloatArray)

	/**
	 * Allocates a render target.
	 *
	 * @param RenderTargetSpec spec What to allocate.
	 * @return RenderTarget The target.
	 */
	fun createRenderTarget(spec: RenderTargetSpec): RenderTarget

	/**
	 * Allocates the shared pass-1 deformed-position store.
	 *
	 * @param Int vertexCapacity The total glue vertex count across every glue mesh.
	 * @return DeformedPositionStore The store.
	 */
	fun createDeformedPositionStore(vertexCapacity: Int): DeformedPositionStore

	/**
	 * Returns the draw pipeline for [spec], creating it on first ask.  Pipelines are immutable and reused.
	 *
	 * @param RenderPipelineSpec spec Which draw, and how it blends.
	 * @return RenderPipeline The pipeline.
	 */
	fun createRenderPipeline(spec: RenderPipelineSpec): RenderPipeline

	/**
	 * Returns the pipeline that evaluates the deform per vertex and writes positions without drawing.
	 *
	 * @return DeformCapturePipeline The pipeline.
	 */
	fun createDeformCapturePipeline(): DeformCapturePipeline

	/** Frees [texture]. */
	fun destroyTexture(texture: GpuTexture)

	/** Frees [mesh] and every buffer it owns. */
	fun destroyMesh(mesh: GpuMesh)

	/** Frees [target]. */
	fun destroyRenderTarget(target: RenderTarget)

	// --- Frame recording ---

	/**
	 * Begins recording a frame's work.  End it with [FrameEncoder.endFrame].
	 *
	 * @return FrameEncoder The encoder.
	 */
	fun beginFrame(): FrameEncoder

	/**
	 * Copies [source] into [destination], filtered - the supersample resolve (a box downscale when the
	 * source is larger).
	 *
	 * A device primitive rather than a caller-side blit, because "blit" is the GL spelling only:
	 * `glBlitFramebuffer` with GL_LINEAR does this in one call, but Metal's `MTLBlitCommandEncoder`
	 * can neither filter nor scale - a Metal backend implements this as a full-screen textured draw
	 * (or MPSImageBilinearScale).  Exposing the honest verb keeps that difference inside the device.
	 *
	 * The layer-composite path also calls this BETWEEN two passes of one frame (snapshotting the
	 * destination before a composite draw samples it).  On GL that is naturally ordered; a
	 * command-buffer backend must order the copy against the passes recorded before and after it.
	 *
	 * @param RenderTarget source      The surface to read (any size).
	 * @param RenderTarget destination The surface to fill, at its own size.
	 * @param ScissorRect? region      The sub-rectangle to copy (same coordinates in both surfaces),
	 *   or null for the whole surface.  Only valid when [source] and [destination] are the same size -
	 *   the scaled supersample resolve always passes null.  The composite path passes its layer bounds
	 *   here so the destination snapshot copies only the pixels the scissored composite will sample.
	 *   Equivalent to [resolveUsed] at the targets' full allocated sizes.
	 */
	fun resolve(source: RenderTarget, destination: RenderTarget, region: ScissorRect? = null)

	/**
	 * [resolve] restricted to each target's USED region - the (width x height) extent the last pass
	 * actually rendered, anchored at the render origin (the GL device's bottom-left rows; a
	 * top-left-origin backend's top-left rows).  This is the primitive a grow-only surface needs:
	 * its targets are allocated at a high-water capacity and only the used region holds pixels, so a
	 * full-allocation copy would read garbage and a full-allocation scale would misplace the content.
	 *
	 * @param RenderTarget source The surface to read.
	 * @param Int sourceUsedWidth The rendered extent of [source] along x, in pixels.
	 * @param Int sourceUsedHeight The rendered extent of [source] along y, in pixels.
	 * @param RenderTarget destination The surface to fill.
	 * @param Int destinationUsedWidth The rendered extent of [destination] along x, in pixels.
	 * @param Int destinationUsedHeight The rendered extent of [destination] along y, in pixels.
	 * @param ScissorRect? region The sub-rectangle to copy (same top-left-origin coordinates in both
	 *   used regions, flipped against the USED height on a bottom-up backend), or null to scale the
	 *   whole source used region onto the destination used region (filtered - the supersample
	 *   resolve).  Only valid when the used sizes are equal, exactly like [resolve]'s same-size rule.
	 */
	fun resolveUsed(
		source: RenderTarget,
		sourceUsedWidth: Int,
		sourceUsedHeight: Int,
		destination: RenderTarget,
		destinationUsedWidth: Int,
		destinationUsedHeight: Int,
		region: ScissorRect? = null,
	)

	// --- Read-back ---

	/**
	 * Begins an ASYNCHRONOUS read-back of [target], returning immediately.
	 *
	 * The synchronous [readPixels] stalls the pipeline until the GPU finishes; this schedules the copy
	 * on the GPU timeline instead, so a per-frame caller (the viewport read-back) never blocks while a
	 * slider is dragged.  Poll the ticket with [pollReadback] on later ticks.
	 *
	 * The GL device implements this as glReadPixels into a pooled pixel-buffer object gated by a fence;
	 * a Metal backend would stash the bytes from a command-buffer completion handler and hand them over
	 * on the poll - polling (rather than a callback) is deliberate, so results always arrive on the
	 * render thread on every backend, whatever thread the backend completes on.
	 *
	 * @param RenderTarget target The target to read.
	 * @return ReadbackTicket The claim ticket to poll.
	 */
	fun beginReadback(target: RenderTarget): ReadbackTicket

	/**
	 * [beginReadback] restricted to the target's USED region (see [resolveUsed] for the anchoring
	 * contract).  The delivered image is usedWidth x usedHeight, top row first as always.
	 *
	 * @param RenderTarget target The target to read.
	 * @param Int usedWidth The rendered extent along x, in pixels.
	 * @param Int usedHeight The rendered extent along y, in pixels.
	 * @return ReadbackTicket The claim ticket to poll.
	 */
	fun beginReadback(target: RenderTarget, usedWidth: Int, usedHeight: Int): ReadbackTicket

	/**
	 * The pixels of an in-flight read-back once ready, or null while the GPU is still copying.
	 *
	 * Non-blocking, and null means STILL IN FLIGHT and nothing else: once the copy is done the ticket is
	 * spent and this returns a non-null image, EVEN if the backend then hit an error retrieving the bytes
	 * (in which case the image is a same-size zero fill - one lost frame, never a null the caller would
	 * mistake for still-in-flight and re-poll into the spent-ticket check).  One-shot: the first non-null
	 * return spends the ticket and recycles its staging; polling a spent ticket is a caller bug.  Same
	 * orientation contract as [readPixels]: RGBA8888, TOP row first, whatever the backend's framebuffer
	 * origin.
	 *
	 * @param ReadbackTicket ticket The claim ticket from [beginReadback].
	 * @return RasterImage? The pixels once ready, or null while still in flight.
	 */
	fun pollReadback(ticket: ReadbackTicket): RasterImage?

	/**
	 * Abandons an in-flight read-back, freeing its staging without waiting for the pixels.  For
	 * teardown; a spent ticket is a no-op.
	 *
	 * @param ReadbackTicket ticket The claim ticket to abandon.
	 */
	fun cancelReadback(ticket: ReadbackTicket)

	/**
	 * Reads [target] back to the host, synchronously.
	 *
	 * Always RGBA8888 with the TOP row first, whatever the backend's framebuffer origin: the GL device
	 * flips its bottom-up read, and a top-left-origin backend would not.  Fixing the convention here is
	 * what stops every caller having to ask which way up a backend is.
	 *
	 * Synchronous, so it stalls the pipeline.  Fine for verification and thumbnails; the per-frame path
	 * uses an asynchronous read-back instead.
	 *
	 * @param RenderTarget target The target to read.
	 * @return RasterImage The pixels, top row first.
	 */
	fun readPixels(target: RenderTarget): RasterImage

	/**
	 * [readPixels] restricted to the target's USED region (see [resolveUsed] for the anchoring
	 * contract).  The returned image is usedWidth x usedHeight, top row first.
	 *
	 * @param RenderTarget target The target to read.
	 * @param Int usedWidth The rendered extent along x, in pixels.
	 * @param Int usedHeight The rendered extent along y, in pixels.
	 * @return RasterImage The pixels, top row first.
	 */
	fun readPixels(target: RenderTarget, usedWidth: Int, usedHeight: Int): RasterImage

	/**
	 * The backend's device / driver / version as one line - a startup diagnostic confirming which backend
	 * actually drives the viewport (a hardware driver vs a software rasterizer, say).
	 *
	 * @return String The description.
	 */
	fun describeBackend(): String
}

/**
 * An in-flight asynchronous read-back - an opaque claim ticket for [RenderDevice.pollReadback].
 *
 * One-shot: a ticket yields its pixels exactly once, after which it is spent and the backend has
 * recycled its staging.
 */
public interface ReadbackTicket

/**
 * One frame's recorded work.  Passes run in the order they are begun.
 *
 * A pass that reads what an earlier pass wrote must be separated from it by [barrier].
 */
public interface FrameEncoder {
	/**
	 * Begins a render pass.  End it before beginning another - passes do not nest.
	 *
	 * @param RenderPassSpec spec The target, what to do with its existing contents, and the viewport.
	 * @return RenderPassEncoder The pass encoder.
	 */
	fun beginRenderPass(spec: RenderPassSpec): RenderPassEncoder

	/**
	 * Begins the deform-capture pass (glue pass 1).
	 *
	 * @param DeformCapturePipeline pipeline The capture pipeline.
	 * @param DeformedPositionStore store    Where the deformed positions land.
	 * @return DeformCapturePassEncoder The pass encoder.
	 */
	fun beginDeformCapturePass(pipeline: DeformCapturePipeline, store: DeformedPositionStore): DeformCapturePassEncoder

	/**
	 * Declares that work after this point reads [store] as written by work before it, so the backend
	 * orders the write against the read.
	 *
	 * A declared DEPENDENCY, not an instruction to stall - which is what lets each backend meet it its own
	 * way.  The GL device issues `glFinish`: the WSL d3d12/Mesa stack does not reliably order transform
	 * feedback's writes before a later texture read, so pass 2 sampled a half-written store and produced
	 * garbage welds while a parameter moved.  `glMemoryBarrier` cannot help - feedback writes are
	 * coherent-pipeline writes, outside its scope - so the full sync is the honest fix.  A Metal backend
	 * would implement this as a genuine no-op, since it tracks the dependency itself within a command
	 * buffer; that is correct, not a stub.
	 *
	 * The renderer calls it only on pose-change frames, so a static pose pays nothing.
	 *
	 * @param DeformedPositionStore store The store written before and read after.
	 */
	fun barrier(store: DeformedPositionStore)

	/** Submits the frame. */
	fun endFrame()
}

/**
 * What a render pass does with its target's existing contents on entry.
 *
 * GL needs none of this and ignores it; it is stated because a tile-based GPU cannot infer it, and
 * because it is genuinely free information the renderer already has.  The grid is an opaque full-screen
 * fill that both clears and paints, so its pass is [DontCare] - and on a tile-based GPU that means the
 * tile is never fetched at all.
 */
public enum class LoadAction {
	/** Discard and fill with the clear color. */
	Clear,

	/** Preserve: the pass reads what was already there. */
	Load,

	/** The pass overwrites every pixel, so the previous contents need not be fetched. */
	DontCare,
}

/** Whether a pass's results are kept.  [DontCare] lets a tile-based GPU skip the write-back. */
public enum class StoreAction {
	/** Keep the results. */
	Store,

	/** Discard them. */
	DontCare,
}

/**
 * An axis-aligned pixel rectangle restricting where a pass (or a region resolve) writes.
 *
 * Coordinates follow the read-back convention: (0, 0) is the TOP-LEFT pixel, whatever the backend's
 * framebuffer origin (the GL device converts to its bottom-left origin internally).  Fixing the
 * convention here is what stops every caller having to ask which way up a backend is.
 *
 * @property Int x      Left edge in pixels.
 * @property Int y      Top edge in pixels.
 * @property Int width  Width in pixels (> 0).
 * @property Int height Height in pixels (> 0).
 */
public data class ScissorRect(
	val x: Int,
	val y: Int,
	val width: Int,
	val height: Int,
)

/**
 * One render pass.
 *
 * Every field is explicit because a pass that discovered its own target could not exist on a backend with
 * no bound-framebuffer concept - and because, even on GL, discovering it from ambient state hid a real
 * coupling between the renderer and whoever bound the framebuffer first.
 *
 * @property RenderTarget colorTarget    The surface this pass writes.
 * @property LoadAction   loadAction     What to do with its existing contents on entry.
 * @property StoreAction  storeAction    Whether to keep the results.
 * @property Float        clearRed       Clear red;   read only when [loadAction] is [LoadAction.Clear].
 * @property Float        clearGreen     Clear green; read only when [loadAction] is [LoadAction.Clear].
 * @property Float        clearBlue      Clear blue;  read only when [loadAction] is [LoadAction.Clear].
 * @property Float        clearAlpha     Clear alpha; read only when [loadAction] is [LoadAction.Clear].
 * @property Int          viewportWidth  The viewport width in pixels.
 * @property Int          viewportHeight The viewport height in pixels.
 * @property ScissorRect? scissor        Restricts every write of the pass - the clear included - to the
 *   rectangle, for the whole pass (per-pass fixed state, like the target).  Null writes everywhere.
 *   The composite path uses this to confine a layer's clear + composite to the subtree's bounds.
 */
public data class RenderPassSpec(
	val colorTarget: RenderTarget,
	val loadAction: LoadAction,
	val viewportWidth: Int,
	val viewportHeight: Int,
	val storeAction: StoreAction = StoreAction.Store,
	val clearRed: Float = 0f,
	val clearGreen: Float = 0f,
	val clearBlue: Float = 0f,
	val clearAlpha: Float = 0f,
	val scissor: ScissorRect? = null,
)

/** Records draws into one render pass. */
public interface RenderPassEncoder {
	/**
	 * Binds the pipeline subsequent draws use.  Blend and shader stages are fixed by it.
	 *
	 * @param RenderPipeline pipeline The pipeline.
	 */
	fun setPipeline(pipeline: RenderPipeline)

	/**
	 * Sets the pass-wide world→NDC affine and the screen-space texture divisor.
	 *
	 * The divisor is the ALLOCATED size of the screen-space side textures (mask coverage, composite
	 * layer, destination snapshot) a draw may sample by `gl_FragCoord / screenTexSize` - never the
	 * pass viewport, which lives in [RenderPassSpec].  The two agree only when the side targets are
	 * exactly viewport-sized; with grow-only side targets the divisor is the high-water capacity.
	 *
	 * @param WorldToNdc worldToNdc      The camera affine.
	 * @param Int        screenTexWidth  The sampled side textures' allocated width, in pixels.
	 * @param Int        screenTexHeight The sampled side textures' allocated height, in pixels.
	 */
	fun setCamera(worldToNdc: WorldToNdc, screenTexWidth: Int, screenTexHeight: Int)

	/**
	 * Draws one deforming art mesh.
	 *
	 * @param GpuMesh          mesh      The mesh.
	 * @param DeformUniforms   deform    Its per-pose deform inputs (marshal, do not retain).
	 * @param FragmentUniforms fragment  Its appearance (marshal, do not retain).
	 * @param DrawTextures     textures  The textures it samples.
	 */
	fun drawPuppetMesh(mesh: GpuMesh, deform: DeformUniforms, fragment: FragmentUniforms, textures: DrawTextures)

	/**
	 * Draws one glue art mesh, welding against pass-1's positions.
	 *
	 * @param GpuMesh                mesh             The mesh.
	 * @param DeformedPositionStore  store            The shared pass-1 positions.
	 * @param Int                    baseVertexOffset This mesh's first vertex index in the store.
	 * @param FloatArray             glueIntensities  Per-glue weld intensity, by glue index.
	 * @param FragmentUniforms       fragment         Its appearance (marshal, do not retain).
	 * @param DrawTextures           textures         The textures it samples.
	 */
	fun drawGlueMesh(
		mesh: GpuMesh,
		store: DeformedPositionStore,
		baseVertexOffset: Int,
		glueIntensities: FloatArray,
		fragment: FragmentUniforms,
		textures: DrawTextures,
	)

	/**
	 * Draws the atlas-page underlay quad.
	 *
	 * @param GpuTexture       atlas      The page.
	 * @param Float            pageWidth  The page width in texels.
	 * @param Float            pageHeight The page height in texels.
	 * @param FragmentUniforms fragment   Its appearance.
	 */
	fun drawAtlasPage(atlas: GpuTexture, pageWidth: Float, pageHeight: Float, fragment: FragmentUniforms)

	/**
	 * Fills the target with the grid backdrop.
	 *
	 * @param GridUniforms uniforms The grid's inputs.
	 */
	fun drawGrid(uniforms: GridUniforms)

	/**
	 * Composites a rendered layer over the destination, blending in-shader (full-screen triangle;
	 * the pipeline must be [PipelinePurpose.Composite], whose blending is disabled - the shader
	 * computes the whole blend from the layer and the destination snapshot).
	 *
	 * @param CompositeUniforms composite The blend modes, channels, and mask flags (marshal, do not retain).
	 * @param DrawTextures      textures  [DrawTextures.compositeLayer] + [DrawTextures.destinationSnapshot],
	 *   plus [DrawTextures.maskCoverage] when the composite is clipped.
	 */
	fun drawComposite(composite: CompositeUniforms, textures: DrawTextures)

	/**
	 * Draws one world-origin axis line.
	 *
	 * @param AxisLineUniforms uniforms The line's inputs.
	 */
	fun drawAxisLine(uniforms: AxisLineUniforms)

	/** Ends the pass. */
	fun end()
}

/** Records the pass-1 deform capture. */
public interface DeformCapturePassEncoder {
	/**
	 * Evaluates the deform for every vertex of [mesh] and writes each result into the store at
	 * [destinationVertexOffset] plus the vertex's own index.  Draws nothing.
	 *
	 * The name describes the EFFECT, not the mechanism, which is what lets the mechanism change without
	 * touching the renderer: the GL family runs it as transform feedback today and may become a
	 * point-scatter render once the store is a 2D texture (a texture cannot be a feedback target); Metal
	 * has no transform feedback at all and would use a compute kernel.  None of that reaches here.
	 *
	 * @param GpuMesh         mesh                   The mesh to deform.
	 * @param DeformUniforms  deform                 Its per-pose deform inputs (marshal, do not retain).
	 * @param DrawTextures    textures               Its delta and warp control-point textures.
	 * @param Int             destinationVertexOffset This mesh's base index in the store.
	 * @param Int             vertexCount            How many vertices to capture.
	 */
	fun captureDeformedPositions(
		mesh: GpuMesh,
		deform: DeformUniforms,
		textures: DrawTextures,
		destinationVertexOffset: Int,
		vertexCount: Int,
	)

	/** Ends the pass. */
	fun end()
}