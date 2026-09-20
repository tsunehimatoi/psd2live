package org.umamo.render.puppet

import org.umamo.render.ContentBounds
import org.umamo.render.DecodedImage
import org.umamo.render.GridColors
import org.umamo.render.LayerDrawPlan
import org.umamo.render.LayerRasterBatch
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.WorldAxisColors
import org.umamo.render.device.AxisLineUniforms
import org.umamo.render.device.CompositeUniforms
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
import org.umamo.render.device.MeshSpec
import org.umamo.render.device.PipelineBlend
import org.umamo.render.device.PipelinePurpose
import org.umamo.render.device.RenderDevice
import org.umamo.render.device.RenderPassEncoder
import org.umamo.render.device.RenderPipeline
import org.umamo.render.device.RenderPipelineSpec
import org.umamo.render.device.RenderTarget
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.ScissorRect
import org.umamo.render.device.TextureFilter
import org.umamo.render.device.TextureFormat
import org.umamo.render.device.TextureWrap
import org.umamo.render.device.WorldToNdc
import org.umamo.render.eval.DeformedGeometry
import org.umamo.render.eval.DeformerWorld
import org.umamo.render.eval.MeshBlendState
import org.umamo.render.eval.PartRenderState
import org.umamo.render.eval.PoseDeformInputs
import org.umamo.render.eval.RenderPlanComposite
import org.umamo.render.eval.RenderPlanDrawable
import org.umamo.render.eval.RenderPlanNode
import org.umamo.render.eval.RotationWorld
import org.umamo.render.eval.WarpWorld
import org.umamo.render.eval.applyCpuDeform
import org.umamo.render.eval.preparePose
import org.umamo.render.glsl.MAX_BLEND_CORNERS
import org.umamo.render.glsl.MAX_CORNERS
import org.umamo.render.glsl.MAX_GLUES
import org.umamo.render.glsl.SELECTION_TINT_STRENGTH
import org.umamo.runtime.eval.WeightedCell
import org.umamo.runtime.eval.cellsByLinearIndex
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.visibleDrawableIds
import kotlin.concurrent.Volatile
import kotlin.math.ceil
import kotlin.math.floor

/**
 * How many on-demand underlay images stay uploaded at once.  Small on purpose: a UV editor shows one
 * layer at a time, so this covers a few areas plus the layer they were just looking at, and bounds
 * GPU memory against a document carrying hundreds of layers.
 */
private const val UNDERLAY_TEXTURE_CACHE_SIZE = 4

/**
 * GPU-deforming puppet renderer, over a [RenderDevice].
 *
 * The keyform morph + deformer cascade run in the vertex shader; the CPU only prepares the cheap per-pose
 * data.  Glue (seam-welding vertex pairs across two meshes) is a two-pass GPU step: pass 1
 * transform-feedback-deforms every glue-involved mesh into one shared position store, pass 2 renders the
 * visible glue meshes welding own + partner positions read from that store.  Non-glue meshes render in a
 * single deform pass.  Draws in Cubism render order with per-drawable opacity, blend, and masks.
 *
 * This class holds NO GL - every GPU operation goes through [device], and every decision (glue layout,
 * pose resolution, model diff) is a backend-neutral call into `org.umamo.render.puppet`.  A second
 * backend is therefore a second [RenderDevice], not a second renderer.  It runs on the render thread; the
 * host makes [device]'s context current there.
 */
class PuppetRenderer(
	private val model: PuppetModel,
	// var: the session owns the effective page set now - a repack swaps it mid-session through
	// setAtlasPages, which re-points this at the set the uploaded handles came from.
	private var textures: PuppetTextures,
	private val device: RenderDevice,
) {
	// Draw pipelines by blend x cull, plus the fixed-purpose ones, created lazily and reused every frame.
	// Cull is index [0]=double-sided / [1]=back-face-culled so the per-draw lookup allocates no key.
	private val puppetPipelines = Array(2) { HashMap<BlendMode, RenderPipeline>() }
	private val gluePipelines = Array(2) { HashMap<BlendMode, RenderPipeline>() }
	private var atlasPagePipeline: RenderPipeline? = null
	private var gridPipeline: RenderPipeline? = null
	private var axisPipeline: RenderPipeline? = null
	private var compositePipeline: RenderPipeline? = null
	private var capturePipeline: DeformCapturePipeline? = null

	// Rest-pose content extent, computed lazily and reused for contentBounds()/the fit fallback; re-armed
	// by updateModel/setShownDrawables so the framing follows geometry and visibility edits.
	private var minX = 0f
	private var minY = 0f
	private var spanX = 1f
	private var spanY = 1f
	private var bboxReady = false

	// The view to project through. null until setCamera; render() then fits contentBounds by default.
	private var currentCamera: ViewportCamera? = null

	// The last pose's prepared deform inputs, cached so picking can re-run the CPU deform on demand (at
	// click time, off the render thread) without re-doing it every frame. Immutable once built, so the
	// volatile reference is a safe publish to the UI thread that calls pickGeometry. null before first pose.
	@Volatile
	private var lastPoseInputs: PoseDeformInputs? = null

	// Drawables currently selected, tinted by the highlight uniform when drawn. Set from the UI thread
	// (a wholesale immutable-set swap, so a volatile reference is a safe publish); read on the render thread.
	@Volatile
	private var selectedIds: Set<DrawableId> = emptySet()

	// The color selected drawables are tinted toward (the selection highlight), fed from the editor settings
	// on the UI thread and read on the render thread. Defaults to the classic blue accent until the host
	// pushes the configured color. RGB, each 0..1; the immutable FloatArray swap makes the volatile
	// reference a safe publish.
	@Volatile
	private var highlightColor: FloatArray = floatArrayOf(0.20f, 0.55f, 1.0f)

	// The active (last-selected) drawable, tinted toward activeHighlightColor instead of highlightColor so
	// the primary target of a multi-selection reads apart from the rest. Set from the UI thread; null when
	// nothing is active. Always a member of selectedIds when non-null.
	@Volatile
	private var activeId: DrawableId? = null

	// The color the active drawable is tinted toward, fed from the editor settings alongside highlightColor.
	// Defaults to the edit-mode active green (#7DE400) until the host pushes the configured color.
	@Volatile
	private var activeHighlightColor: FloatArray = floatArrayOf(0.49f, 0.89f, 0.0f)

	// The last pose's resolved draw list (back-to-front; last = front), published for picking. This folds
	// in the parts/group hierarchy, so it is the authoritative front/back order. Render-thread-written,
	// UI-thread-read; the immutable list swap is a safe publish.
	@Volatile
	private var lastDrawnOrder: List<DrawableId> = emptyList()

	// Framebuffer pixels per on-screen pixel. 1 = native; the offscreen service sets >1 when it supersamples,
	// so the grid line width scales to match and reads back at a constant on-screen size.
	private var gridPixelScale: Float = 1f

	// The grid backdrop colors, set from the editor theme via setGrid; defaults to a neutral grey grid.
	private var gridColors: GridColors = GridColors.Classic

	// The per-document grid geometry (major line spacing in world units, and subdivisions per major cell).
	private var gridScale: Float = 100f
	private var gridSubdivisions: Int = 10

	// The coverage target the mask pass renders into and the masked draws sample.  Grow-only: kept at
	// a per-axis high-water capacity and rendered into via an origin-anchored viewport, so a gutter
	// drag's per-frame size changes never destroy + recreate it.  The shader's screen-space lookup
	// divides gl_FragCoord by the ALLOCATED size (screenTexSize), which is what keeps the lookup
	// lined up when the capacity exceeds the viewport.
	private var maskTarget: RenderTarget? = null

	// The layer-composite targets: one layer target per nesting depth (grown lazily to the deepest
	// isolated group actually rendered) plus one shared destination-snapshot target - composites are
	// strictly sequential, so a single snapshot suffices.  All allocated at the SAME capacity as the
	// mask target, because the composite shader samples layer, snapshot, and mask through one
	// screenTexSize divisor - so the three must share allocation dims, and they grow together.
	private val compositeTargets = ArrayList<RenderTarget>()
	private var snapshotTarget: RenderTarget? = null

	// The shared side-target capacity (mask + snapshot + composite pool), each axis a high-water mark.
	private var sideTargetCapacityWidth = 0
	private var sideTargetCapacityHeight = 0

	// The shared pass-1 deformed-position store; null when the model has no glue.
	private var store: DeformedPositionStore? = null
	private var glueIntensities = FloatArray(MAX_GLUES) { 1f }

	// Pass 1 only needs to re-deform the shared store when the pose changed; on a static pose its contents
	// are unchanged. Set by setPose, consumed by render. Gating here also confines the write→read barrier
	// to pose-change frames.
	private var glueBufferDirty = true

	// Reused per-draw uniform + texture scratch, refilled each draw rather than allocated. Render-thread only.
	private val deformScratch = DeformUniforms()
	private val fragmentScratch = FragmentUniforms()
	private val texturesScratch = DrawTextures()
	private val compositeScratch = CompositeUniforms()

	// Which resident drawables carry triangles (id → indexCount > 0). Residency changes only at initGl and
	// updateModel, and setPose is far hotter (every pose tick during a drag), so this is cached and rebuilt
	// only where gpuById itself changes rather than mapped afresh every pose.
	private var renderableById: Map<DrawableId, Boolean> = emptyMap()

	/**
	 * A drawable resident on the GPU.  Static residency (mesh, delta texture, optional warp control-point
	 * texture, atlas) plus the per-pose state setPose stamps onto it.  [isGlueMesh] meshes are deformed in
	 * pass 1 (even index-less anchors, whose positions are weld partners) and rendered via the glue pipeline
	 * in pass 2.
	 */
	private class GpuDrawable(
		val id: DrawableId,
		val mesh: GpuMesh,
		val deltaTexture: GpuTexture,
		val vertexCount: Int,
		val indexCount: Int,
		val cpTexture: GpuTexture?,
		// The atlas page this drawable samples.  A var because a repack swaps the page set mid-session
		// and setAtlasPages re-stamps every resident - the index map moves with the pages, not just the
		// handles.
		var atlasTexture: GpuTexture?,
		// The drawable's SOURCE ARTWORK alternative to the atlas, and the affine carrying its stored
		// coordinates into that image's frame.  Both var: a drawable acquires them when the document
		// switches to source-artwork display, which is a state change, not a re-upload.  Null means this
		// drawable has no recoverable art and keeps rendering from the atlas - the mode is best-effort
		// per drawable, so a document is routinely mixed.
		var layerTexture: GpuTexture? = null,
		var layerUvAffine: FloatArray? = null,
		val color: FloatArray,
		// Static composite state: var because a composite-only edit is a ModelDiff Keep (no buffer work),
		// which re-stamps these on the reused resident in updateModel - or the viewport would keep drawing
		// with the blend/mask/culling captured at upload until an unrelated topology change forced a reupload.
		var blendMode: BlendMode,
		/** The drawable's 5.3 alpha blend; non-Over routes the draw through the composite path. */
		var alphaBlendMode: AlphaBlendMode,
		/** The Cubism Culling toggle: true culls back faces, false (default) is double-sided. */
		var culling: Boolean,
		var maskIds: List<DrawableId>,
		var invertMask: Boolean,
		val isGlueMesh: Boolean,
		val glueBaseOffset: Int,
		/** The static blend-shape column assignment in [deltaTexture] (empty when binding-free). */
		val blendLayout: BlendColumnLayout,
		/**
		 * Rest positions for the exact composite-scissor bounds (see PosedAabb).  A var because an
		 * in-place base-mesh move (updateModel's Keep-with-positions tier) re-uploads new positions to
		 * the GPU mesh and must re-point this at them, or the bounds walk would size the scissor to the
		 * pre-edit geometry and clip the moved vertices.
		 */
		var boundsBase: FloatArray,
		/** Grid cells by linear index for the bounds walk; a keyform edit re-uploads whole (Reupload). */
		val boundsCells: Map<Int, KeyformCell<MeshDeltaForm>>,
	) {
		/**
		 * The texture this drawable actually samples: its source artwork when the document displays from
		 * artwork AND this drawable has recoverable art, else the atlas page it was packed onto.
		 *
		 * The fallback is per drawable on purpose - art the document does not retain, a placement that
		 * will not invert, or a layer that would not decode all leave a drawable on the atlas rather than
		 * removing it from the puppet.
		 *
		 * @return GpuTexture? The texture to bind, or null when the drawable has neither.
		 */
		fun activeTexture(): GpuTexture? = layerTexture ?: atlasTexture

		var corners: List<WeightedCell>? = null
		var parentWorld: DeformerWorld? = null
		var opacity: Float = 1f
		var multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity
		var screenColor: ColorRgb = ColorRgb.ScreenIdentity
		var visible: Boolean = false

		/** The pose's resolved blend-shape state; null for binding-free drawables. */
		var blend: MeshBlendState? = null

		/**
		 * Whether this drawable's blend is a 5.3 extended blend (not fixed-function-expressible), so it
		 * routes through the destination-sampling composite path rather than a plain draw.
		 */
		val isExtendedBlend: Boolean
			get() = !blendMode.isLegacy || alphaBlendMode != AlphaBlendMode.Over
	}

	// The live model used for the per-pose deform eval, the render order, and the reconcile diff. A var so
	// an edit can re-push it via updateModel. @Volatile because the render thread writes it while the UI
	// thread reads it (pickGeometry); a PuppetModel is immutable, so the reference swap is a safe publish.
	@Volatile
	private var currentModel: PuppetModel = model
	private var baseOrder: List<DrawableId> = model.drawables.map { it.id }
	private var currentRenderRoot: RenderGroup = model.renderRoot

	// Effective Parts-panel visibility (own eyeball ∧ every ancestor part's), resolved once per change. Gates
	// only the drawn list; hidden meshes that are mask sources or glue partners still deform. Render-thread only.
	private var shownDrawableIds: Set<DrawableId> = model.visibleDrawableIds()
	private var glueDeformList: List<GpuDrawable> = emptyList()
	private var gpuById: Map<DrawableId, GpuDrawable> = emptyMap()

	// The pose's resolved render plan (back-to-front with composite boundaries) and the per-isolated-part
	// pose-blended composite channels, both set by setPose and walked by render. Render-thread only.
	private var currentPlan: List<RenderPlanNode> = emptyList()
	private var currentCompositeStates: Map<PartId, PartRenderState> = emptyMap()

	// Per-pose composite acceleration state, derived from the plan by setPose (render-thread only):
	// isolated parts whose composite is a pose-identity Normal/Over over an all-Normal/Over subtree
	// (render() draws those inline - no layer, no snapshot, no composite draw; premultiplied Over is
	// associative, so the pixels are identical up to one less 8-bit quantization), and the
	// conservative world bounds of each isolated subtree / extended-blend drawable (render() scissors
	// the layer clear, snapshot copy, and composite draw to them - except Out-alpha composites, whose
	// blend erases the destination wherever the layer is EMPTY, so they keep the full-viewport path).
	private var flattenableComposites: Set<PartId> = emptySet()
	private var compositeWorldBounds: Map<PartId, PosedAabb> = emptyMap()
	private var extendedDrawableWorldBounds: Map<DrawableId, PosedAabb> = emptyMap()

	// The two composite accelerations, each independently switchable so a correctness test can render
	// the same pose with either or both disabled and assert every combination is pixel-preserving on
	// real corpus models.  [compositeFlattenEnabled] draws identity Normal/Over groups inline;
	// [compositeBoundsScissorEnabled] confines a composite's layer work (and the empty-layer skip) to
	// the subtree's bounds.  Both on by default.
	internal var compositeFlattenEnabled = true
	internal var compositeBoundsScissorEnabled = true

	// Glue weld partners by drawable (both directions), rebuilt with residency: a glue mesh's welded
	// vertices stay inside the hull of its own and its partners' unwelded bounds, so the bounds walk
	// unions the partner extents in.
	private var gluePartnersById: Map<DrawableId, List<DrawableId>> = emptyMap()

	// The uploaded atlas pages, index-parallel to PuppetTextures.atlases. Retained so a structural reconcile
	// in updateModel can bind a newly-uploaded drawable to its page.
	private var atlasHandles: List<GpuTexture> = emptyList()

	// Underlay images uploaded on demand (the UV editor's source-layer view), keyed by image identity and
	// insertion-ordered so eviction drops the oldest. Unlike atlasHandles, which upload and free as one
	// whole page set (at init, or wholesale on a setAtlasPages swap), these arrive and are created or
	// destroyed one at a time across the renderer's life - as the source-artwork textures below also are.
	private val underlayTextures = LinkedHashMap<DecodedImage, GpuTexture>()

	// Which artwork every drawable would display from - the mapping, complete for the whole document and
	// carrying no pixels.  Retained so a structural reconcile can re-stamp a rebuilt resident, exactly as
	// atlasHandles is, and so a layer dropped for budget can be promoted again without rebuilding it.
	private var layerPlan: LayerDrawPlan = LayerDrawPlan.EMPTY

	// The uploaded artwork, keyed by LAYER (duplicated art is shared, so this is the count of distinct
	// images, not of drawables).  Only applySourceLayerDisplay adds to or removes from it, because
	// freeing a texture is only safe together with the re-stamp that stops drawables pointing at it.
	private val layerTextures = HashMap<String, GpuTexture>()

	// Layers the document references but whose pixels would not decode.  Held so the ask stops repeating
	// them; cleared with the plan, because a new document may well decode them.
	private val undecodableLayerKeys = HashSet<String>()

	// Whether the puppet is actually displaying from artwork.  False until every mapped layer has landed,
	// which is what keeps the mode from ever showing a half-artwork puppet.
	private var sourceLayerDisplayReady = false

	// Whether render() draws the world-origin axis lines. Off by default so headless render-diff tests stay
	// line-free; the editor's viewport host opts in.
	private var worldAxesVisible = false

	/**
	 * Creates the pipelines, uploads the atlas page(s), lays out the shared glue store, and uploads each
	 * drawable's static data.  Must run with the device's context current.
	 */
	fun initGl() {
		capturePipeline = device.createDeformCapturePipeline()
		atlasPagePipeline = device.createRenderPipeline(RenderPipelineSpec(PipelinePurpose.AtlasPageDraw, PipelineBlend.Normal))
		gridPipeline = device.createRenderPipeline(RenderPipelineSpec(PipelinePurpose.GridBackdrop, PipelineBlend.Opaque))
		axisPipeline = device.createRenderPipeline(RenderPipelineSpec(PipelinePurpose.WorldAxisLine, PipelineBlend.Opaque))
		// Blending disabled: the composite shader computes the whole blend from layer + snapshot.
		compositePipeline = device.createRenderPipeline(RenderPipelineSpec(PipelinePurpose.Composite, PipelineBlend.Opaque))
		atlasHandles =
			textures.atlases.map { device.createTexture(it.width, it.height, TextureFormat.Rgba8, TextureFilter.Linear, it.rgba) }
		val warpDeformerIds = model.deformers.filterIsInstance<Deformer.Warp>().map { it.id }.toSet()

		// Glue addressing is planned in commonMain; the device holds the store and the interleaved attrs.
		val glueLayout = planGlueLayout(model)
		if (glueLayout.globalVertexCount > 0) {
			store = device.createDeformedPositionStore(glueLayout.globalVertexCount)
		}

		val uploaded = ArrayList<GpuDrawable>()
		for (drawable in model.drawables) {
			val gpuDrawable =
				uploadDrawable(
					drawable = drawable,
					glueAttributes = glueLayout.attributesById[drawable.id],
					glueBaseOffset = glueLayout.baseOffsetById[drawable.id] ?: 0,
					warpDeformerIds = warpDeformerIds,
				) ?: continue
			uploaded.add(gpuDrawable)
		}
		gpuById = uploaded.associateBy { it.id }
		glueDeformList = uploaded.filter { it.isGlueMesh }
		renderableById = gpuById.mapValues { (_, resident) -> resident.indexCount > 0 }
		rebuildGluePartners(model)
	}

	/**
	 * Uploads one drawable's static GPU data and returns its resident [GpuDrawable], or null when it draws
	 * nothing (no mesh, no keyforms, empty geometry, or a triangle-less non-glue mesh).  Shared by [initGl]
	 * and the structural reconcile in [updateModel]; must run with the device's context current.
	 *
	 * @param Drawable                drawable        The model drawable.
	 * @param GlueVertexAttributes?  glueAttributes  Its planned weld attributes, or null when not glued.
	 * @param Int                     glueBaseOffset  Its base index in the shared glue store (0 when not glued).
	 * @param Set<DeformerId>         warpDeformerIds The model's warp deformers.
	 * @return GpuDrawable? The uploaded drawable, or null when it draws nothing.
	 */
	private fun uploadDrawable(
		drawable: Drawable,
		glueAttributes: GlueVertexAttributes?,
		glueBaseOffset: Int,
		warpDeformerIds: Set<DeformerId>,
	): GpuDrawable? {
		val mesh = drawable.mesh ?: return null
		// A null grid is an UNKEYED drawable, which uploads a single all-zero delta column and renders at
		// its rest mesh. Refusing to upload it would leave a freshly created (or freshly unbound) drawable
		// invisible - the one state where the rigger most needs to see it.
		val grid = drawable.geometryGrid
		if (mesh.positions.isEmpty()) {
			return null
		}
		val isGlue = glueAttributes != null
		if (!isGlue && mesh.indices.isEmpty()) {
			return null // a non-glue mesh with no triangles draws nothing and is no weld partner
		}
		val cellCount = keyformCellCount(grid)
		val vertexCount = mesh.positions.size / 2
		// Built once and shared: the delta-texel bake and the composite-bounds walk both need it. An
		// unkeyed drawable has no cells, so every lookup misses and contributes a zero offset.
		val cells = if (grid != null) cellsByLinearIndex(grid) else emptyMap()
		// Blend-shape delta columns append after the grid cells; the zero-blend layout is empty and
		// the texture build reduces to the plain grid texels.
		val blendLayout = blendColumnLayout(drawable, cellCount)
		val defaults = currentModel.parameters.associate { it.id to it.default }
		val texels =
			if (blendLayout.blendColumnCount > 0) {
				buildDeltaTexelsWithBlend(grid, drawable, { defaults[it] ?: 0f }, vertexCount, blendLayout, cells)
			} else {
				buildDeltaTexels(grid, vertexCount, cellCount, cells)
			}
		val deltaTexture =
			device.createFloatTexture(cellCount + blendLayout.blendColumnCount, vertexCount, TextureFilter.Nearest, texels)
		val gpuMesh = device.createMesh(MeshSpec(mesh.positions, mesh.uvs, mesh.indices, glueAttributes))
		// A warp-parented drawable needs a control-point texture setPose re-specifies each pose. Created as a
		// 1x1 placeholder here so updateFloatTexture always has a handle to overwrite.
		val cpTexture =
			if (drawable.parentDeformerId in warpDeformerIds) {
				device.createFloatTexture(1, 1, TextureFilter.Nearest, FloatArray(2))
			} else {
				null
			}
		// The atlas mapping is keyed by the SOURCE format's drawable ids, so a session-created copy resolves
		// its page through the drawable it was duplicated from (Drawable.textureSourceId).
		val atlasIndex = textures.atlasIndexByDrawableId[(drawable.textureSourceId ?: drawable.id).raw]
		return GpuDrawable(
			id = drawable.id,
			mesh = gpuMesh,
			deltaTexture = deltaTexture,
			vertexCount = vertexCount,
			indexCount = mesh.indices.size,
			cpTexture = cpTexture,
			atlasTexture = atlasIndex?.let { atlasHandles[it] },
			color = fallbackColorFor(drawable.id.raw),
			blendMode = drawable.blendMode,
			alphaBlendMode = drawable.alphaBlendMode,
			culling = drawable.culling,
			maskIds = drawable.maskedBy,
			invertMask = drawable.invertMask,
			isGlueMesh = isGlue,
			glueBaseOffset = glueBaseOffset,
			blendLayout = blendLayout,
			boundsBase = mesh.positions,
			boundsCells = cells,
		)
	}

	/**
	 * Sets which artwork every drawable would display from.  An empty plan returns the whole puppet to
	 * its atlas pages.
	 *
	 * The plan carries no pixels, so this is cheap.  The pixels follow through
	 * [deliverSourceLayerRasters], and the puppet keeps displaying from its atlas until they ALL have -
	 * the producer streams them in chunks rather than holding the document's artwork decoded at once.
	 *
	 * Render thread only - it frees GPU resources through the display pass below.
	 *
	 * @param LayerDrawPlan plan The mapping to display by, or [LayerDrawPlan.EMPTY] for the atlas.
	 */
	fun setSourceLayerPlan(plan: LayerDrawPlan) {
		if (plan === layerPlan) {
			return
		}
		layerPlan = plan
		// A new plan is a new document or a new drawable set; a layer that would not decode for the old
		// one deserves another chance rather than being written off for the renderer's life.
		undecodableLayerKeys.clear()
		applySourceLayerDisplay()
	}

	/**
	 * Takes delivery of decoded artwork, uploading it for the current plan.
	 *
	 * Render thread only: it creates and destroys GPU resources, so it must run with the context
	 * current.  The engine holds the pending value and calls this from inside the render loop, the way
	 * every other renderer input arrives.
	 *
	 * The batch is consumed, never retained - its images are uploaded and the reference dropped, so the
	 * decoded bytes do not outlive the call.
	 *
	 * @param LayerRasterBatch batch The decoded artwork to take up.
	 */
	fun deliverSourceLayerRasters(batch: LayerRasterBatch) {
		undecodableLayerKeys.addAll(batch.undecodableLayerKeys)
		for ((layerKey, image) in batch.rastersByLayerKey) {
			if (layerKey in layerTextures || layerKey !in layerPlan.layerByteCostByKey) {
				continue
			}
			layerTextures[layerKey] =
				device.createTexture(
					image.width,
					image.height,
					TextureFormat.Rgba8,
					TextureFilter.Linear,
					image.rgba,
					// A drawable's mesh overhangs the art it samples, so its recovered coordinates run past
					// this image - across the corpus by up to 443 layer pixels.  An atlas page absorbs that
					// overhang in its own padding; a layer image has no padding to absorb it, so the wrap
					// mode is what has to supply the transparency (see TextureWrap).
					TextureWrap.ClampToTransparentBorder,
				)
		}
		applySourceLayerDisplay()
	}

	/**
	 * Frees artwork the current plan does not want, re-points every resident, and republishes the ask.
	 *
	 * THE ONLY MUTATOR of [layerTextures]: freeing a texture while a drawable still points at it leaves a
	 * dangling handle that draws garbage, so freeing and the re-stamp are one operation rather than two a
	 * caller must remember to pair.
	 *
	 * Display is ALL OR NOTHING.  Source-artwork display is an inspection mode - the rigger is looking at
	 * the art to judge it - so a puppet showing artwork for some drawables and atlas pages for the rest
	 * is not a partial success, it is a view that cannot be trusted, because nothing on screen
	 * distinguishes the two.  Until every layer the plan maps has either landed or proved undecodable,
	 * the whole puppet stays on its atlas.
	 *
	 * The one permitted mix is artwork that does not EXIST: a drawable the document retains no art for,
	 * or art that will not decode, keeps its atlas page and is counted and reported to the rigger.  That
	 * is honest - the mode cannot show what is not there - and it is bounded and named, where a
	 * memory-driven mix would be silent and arbitrary.
	 */
	private fun applySourceLayerDisplay() {
		val wanted = layerPlan.layerByteCostByKey.keys - undecodableLayerKeys
		// Artwork no longer mapped is freed whether or not the mode is engaged: it is dead either way, and
		// holding it would let a document switch leak the previous one's pages.
		val obsolete = layerTextures.keys.filter { layerKey -> layerKey !in wanted }
		for (layerKey in obsolete) {
			layerTextures.remove(layerKey)?.let { texture -> device.destroyTexture(texture) }
		}
		sourceLayerDisplayReady = wanted.isNotEmpty() && layerTextures.keys.containsAll(wanted)
		for ((drawableId, gpuDrawable) in gpuById) {
			stampSourceLayer(gpuDrawable, drawableId)
		}
	}

	/**
	 * Whether the puppet is displaying from source artwork, for tests and diagnostics.
	 *
	 * @return Triple Whether the mode is engaged, the resident layer count, and how many the plan maps.
	 */
	internal fun sourceLayerDisplayState(): Triple<Boolean, Int, Int> =
		Triple(sourceLayerDisplayReady, layerTextures.size, layerPlan.layerByteCostByKey.size)

	/**
	 * Points one resident at its source artwork, or back at its atlas page when the current set does not
	 * cover it.
	 *
	 * Applied to EVERY resident, not just the drawn ones: a mask source is posed and drawn into the
	 * coverage pass while sitting outside the shown set, and a mask sampling one frame while the art it
	 * clips samples the other cuts the silhouette to garbage.
	 *
	 * Nothing points at artwork until the mode is READY - every mapped layer landed - so the puppet is
	 * never caught half in one frame and half in the other.  After that, the only drawables left on the
	 * atlas are the ones whose artwork does not exist, which are counted and reported.
	 *
	 * @param GpuDrawable gpuDrawable The resident to stamp.
	 * @param DrawableId  drawableId  Its id, keying into the current plan.
	 */
	private fun stampSourceLayer(gpuDrawable: GpuDrawable, drawableId: DrawableId) {
		val draw = if (sourceLayerDisplayReady) layerPlan.drawsByDrawableId[drawableId.raw] else null
		val texture = draw?.let { layerTextures[it.layerKey] }
		gpuDrawable.layerTexture = texture
		gpuDrawable.layerUvAffine = if (texture != null) draw.uvAffine else null
	}

	/**
	 * Swaps the atlas page set: destroys the uploaded pages, uploads [next]'s, re-points [textures], and
	 * re-stamps every resident's page binding.  One operation, because freeing a page is only safe
	 * together with the re-stamp that stops drawables sampling it - the same rule
	 * [applySourceLayerDisplay] states for artwork.  Must run with the device's context current.
	 *
	 * @param PuppetTextures next The new page set.
	 */
	fun setAtlasPages(next: PuppetTextures) {
		if (next === textures) {
			// The set the handles already came from - a re-published wrapper around unchanged pages.
			return
		}
		for (handle in atlasHandles) {
			device.destroyTexture(handle)
		}
		textures = next
		atlasHandles =
			next.atlases.map { device.createTexture(it.width, it.height, TextureFormat.Rgba8, TextureFilter.Linear, it.rgba) }
		applyAtlasBinding()
	}

	/**
	 * Re-points every resident at its atlas page under the current [textures] - the atlas twin of
	 * [applySourceLayerDisplay]'s stamp pass.  The index map is re-read per resident, not just the
	 * handles: a repack can move a drawable to a different page, so the old index is as stale as the
	 * old texture.
	 */
	private fun applyAtlasBinding() {
		val bindingSourceById = currentModel.drawables.associateBy({ it.id }, { it.textureSourceId ?: it.id })
		for ((drawableId, gpuDrawable) in gpuById) {
			// The atlas mapping is keyed by the SOURCE format's drawable ids, exactly as at upload: a
			// session-created copy resolves its page through the drawable it was duplicated from.
			val bindingKey = (bindingSourceById[drawableId] ?: drawableId).raw
			gpuDrawable.atlasTexture = textures.atlasIndexByDrawableId[bindingKey]?.let { atlasHandles.getOrNull(it) }
		}
	}

	/**
	 * Frees one resident drawable's device objects: its mesh and delta / control-point textures.  The atlas
	 * and any source-artwork texture are shared across drawables and stay.  Must run with the device's
	 * context current.
	 *
	 * @param GpuDrawable gpuDrawable The resident drawable to free.
	 */
	private fun deleteDrawable(gpuDrawable: GpuDrawable) {
		device.destroyMesh(gpuDrawable.mesh)
		device.destroyTexture(gpuDrawable.deltaTexture)
		gpuDrawable.cpTexture?.let { device.destroyTexture(it) }
	}

	/**
	 * Frees every device object this renderer owns.  Must run with the context current, and nothing may
	 * render afterwards.
	 *
	 * The artwork and underlay caches are created and destroyed across the renderer's life rather than
	 * uploaded once, so letting them die with the context is no longer enough: an engine that outlives
	 * one renderer would leak everything the previous one had admitted.  The pipelines and the shared
	 * position store are not freed here because the device seam exposes no way to - they remain
	 * context-lifetime objects.
	 */
	fun disposeGl() {
		for (gpuDrawable in gpuById.values) {
			deleteDrawable(gpuDrawable)
		}
		gpuById = emptyMap()
		glueDeformList = emptyList()
		renderableById = emptyMap()
		for (texture in layerTextures.values) {
			device.destroyTexture(texture)
		}
		layerTextures.clear()
		for (texture in underlayTextures.values) {
			device.destroyTexture(texture)
		}
		underlayTextures.clear()
		for (texture in atlasHandles) {
			device.destroyTexture(texture)
		}
		atlasHandles = emptyList()
	}

	fun setPose(parameters: Map<ParameterId, Float>, channelOverrides: Map<KeyableTarget, ChannelValue> = emptyMap()) {
		// currentModel (not the construction-time model) so a deformer reparent / reorder re-evaluates here.
		val inputs = preparePose(currentModel, parameters, channelOverrides)
		lastPoseInputs = inputs // publish for on-demand picking (CPU deform re-run at click time)
		glueBufferDirty = true // the pose moved: pass 1 must re-deform the shared store next render
		// Resolve first, in backend-neutral terms; then apply onto the resident drawables and upload.
		val resolved =
			resolvePose(
				inputs = inputs,
				renderableById = renderableById,
				shownIds = shownDrawableIds,
				baseOrder = baseOrder,
				renderRoot = currentRenderRoot,
				glueIntensities = glueIntensities,
			)
		for (gpuDrawable in gpuById.values) {
			gpuDrawable.visible = false
		}
		for (posedDrawable in resolved.posed.values) {
			val gpuDrawable = gpuById[posedDrawable.drawableId] ?: continue
			gpuDrawable.corners = posedDrawable.corners
			val parentWorld = posedDrawable.parentWorld
			gpuDrawable.parentWorld = parentWorld
			// Warp control points are pose-dependent but frame-INVARIANT: upload them here, once per pose
			// change, NOT every frame in the draw loop. Re-specifying this texture 60×/sec would churn the
			// d3d12/Mesa driver and progressively corrupt the sampled control points, causing visible
			// warp flicker over time - worst on masked warp meshes, which draw twice per frame.
			if (parentWorld is WarpWorld && gpuDrawable.cpTexture != null) {
				device.updateFloatTexture(gpuDrawable.cpTexture, parentWorld.cols + 1, parentWorld.rows + 1, parentWorld.cp)
			}
			gpuDrawable.opacity = posedDrawable.opacity
			gpuDrawable.multiplyColor = posedDrawable.multiplyColor
			gpuDrawable.screenColor = posedDrawable.screenColor
			gpuDrawable.blend = posedDrawable.blend
			gpuDrawable.visible = true
		}
		// resolvePose filled glueIntensities (this renderer's own array) in place - no copy needed.
		currentPlan = resolved.renderPlan
		currentCompositeStates = inputs.partCompositeStates
		prepareCompositeAuxState()
		lastDrawnOrder = resolved.drawOrder // publish the resolved back-to-front order for picking
	}

	/**
	 * Derives the per-pose composite acceleration state from the freshly resolved plan: which
	 * isolated parts flatten into their parent pass this pose (identity Normal/Over composite over
	 * an all-Normal/Over subtree - premultiplied Over is associative, so inlining is pixel-exact),
	 * and the conservative world bounds of each isolated subtree and extended-blend drawable, which
	 * render() turns into layer scissors.  A subtree containing a drawable whose bounds cannot be
	 * computed publishes NO bounds, so render() falls back to the full-viewport path - conservatism
	 * is the contract, an undersized bound would clip pixels.
	 */
	private fun prepareCompositeAuxState() {
		if (!compositeFlattenEnabled && !compositeBoundsScissorEnabled) {
			flattenableComposites = emptySet()
			compositeWorldBounds = emptyMap()
			extendedDrawableWorldBounds = emptyMap()
			return
		}
		val flattenable = HashSet<PartId>()
		val compositeBounds = HashMap<PartId, PosedAabb>()
		val extendedBounds = HashMap<DrawableId, PosedAabb>()
		// Memoized per-drawable UNWELDED world bounds; a glue mesh's welded bounds union its posed
		// partners' in (welds move each side within the hull of both unwelded shapes).
		val ownBoundsById = HashMap<DrawableId, PosedAabb?>()

		fun ownWorldBounds(id: DrawableId): PosedAabb? {
			if (id in ownBoundsById) {
				return ownBoundsById[id]
			}
			val gpuDrawable = gpuById[id]
			val corners = gpuDrawable?.corners
			// visible == posed this frame, so corners are fresh (they stay stale-but-non-null on a
			// drawable that dropped out of the pose); an unposed one has nothing to bound.
			val bounds =
				if (gpuDrawable == null || corners == null || !gpuDrawable.visible) {
					null
				} else {
					deformedWorldBounds(gpuDrawable.boundsBase, gpuDrawable.boundsCells, corners, gpuDrawable.parentWorld, gpuDrawable.blend)
				}
			ownBoundsById[id] = bounds
			return bounds
		}

		fun weldedWorldBounds(id: DrawableId): PosedAabb? {
			var bounds = ownWorldBounds(id) ?: return null
			for (partnerId in gluePartnersById[id].orEmpty()) {
				if (gpuById[partnerId]?.visible != true) {
					continue // an unposed partner welds at zero intensity and moves nothing
				}
				val partnerBounds = ownWorldBounds(partnerId) ?: return null
				bounds = unionBounds(bounds, partnerBounds)
			}
			return bounds
		}

		// Walks one composite subtree, returning its bounds (null = at least one drawn child had no
		// computable bounds) and whether its CONTENT is flatten-transparent: every drawn drawable
		// plain Normal/Over, and every nested composite itself blending Normal/Over (its internals
		// only shape its own layer; Over's associativity makes its placement dest-agnostic).
		fun walkComposite(node: RenderPlanComposite): Pair<PosedAabb?, Boolean> {
			var bounds: PosedAabb? = null
			var boundsComplete = true
			var contentFlattenable = true
			for (child in node.children) {
				when (child) {
					is RenderPlanDrawable -> {
						val gpuDrawable = gpuById[child.id] ?: continue
						val extended = gpuDrawable.isExtendedBlend
						if (gpuDrawable.blendMode != BlendMode.Normal || gpuDrawable.alphaBlendMode != AlphaBlendMode.Over) {
							contentFlattenable = false
						}
						val childBounds = weldedWorldBounds(child.id)
						if (childBounds == null) {
							boundsComplete = false
						} else {
							if (extended) {
								extendedBounds[child.id] = childBounds
							}
							bounds = bounds?.let { unionBounds(it, childBounds) } ?: childBounds
						}
					}

					is RenderPlanComposite -> {
						val (childBounds, childContent) = walkComposite(child)
						if (child.composite.blendMode != BlendMode.Normal || child.composite.alphaBlendMode != AlphaBlendMode.Over) {
							contentFlattenable = false
						}
						if (childBounds == null) {
							boundsComplete = false
						} else {
							bounds = bounds?.let { unionBounds(it, childBounds) } ?: childBounds
						}
					}
				}
			}
			val subtreeBounds = if (boundsComplete) bounds else null
			subtreeBounds?.let { compositeBounds[node.partId] = it }
			if (compositeFlattenEnabled && contentFlattenable && compositeStateIsIdentity(node)) {
				flattenable.add(node.partId)
			}
			return subtreeBounds to contentFlattenable
		}

		for (node in currentPlan) {
			when (node) {
				is RenderPlanDrawable -> {
					val gpuDrawable = gpuById[node.id] ?: continue
					if (gpuDrawable.isExtendedBlend) {
						weldedWorldBounds(node.id)?.let { extendedBounds[node.id] = it }
					}
				}

				is RenderPlanComposite -> walkComposite(node)
			}
		}
		flattenableComposites = flattenable
		compositeWorldBounds = if (compositeBoundsScissorEnabled) compositeBounds else emptyMap()
		extendedDrawableWorldBounds = if (compositeBoundsScissorEnabled) extendedBounds else emptyMap()
	}

	/**
	 * Whether an isolated part's composite is a pose-identity source-over: Normal/Over, unmasked,
	 * and its pose-blended channels exactly at identity.  Exact equality on purpose - a channel a
	 * hair off identity takes the real composite path rather than a visibly-approximate flatten.
	 */
	private fun compositeStateIsIdentity(node: RenderPlanComposite): Boolean {
		val composite = node.composite
		if (composite.blendMode != BlendMode.Normal || composite.alphaBlendMode != AlphaBlendMode.Over) {
			return false
		}
		if (composite.maskedBy.isNotEmpty()) {
			return false
		}
		val state = currentCompositeStates[node.partId]
		val opacity = state?.opacity ?: composite.opacity
		val multiply = state?.multiplyColor ?: composite.multiplyColor
		val screen = state?.screenColor ?: composite.screenColor
		return opacity == 1f && multiply == ColorRgb.MultiplyIdentity && screen == ColorRgb.ScreenIdentity
	}

	fun setCamera(camera: ViewportCamera) {
		currentCamera = camera
	}

	/**
	 * Sets how many framebuffer pixels map to one on-screen pixel, so the grid line width stays a constant
	 * on-screen size when the offscreen service renders supersampled and downscales.  1 = native, 2 = 2×.
	 *
	 * @param Float scale Framebuffer pixels per on-screen pixel.
	 */
	fun setRenderScale(scale: Float) {
		gridPixelScale = scale
	}

	/**
	 * Sets the grid backdrop's colors and geometry, so the viewport can follow the editor theme and the
	 * per-document grid config.  The next [render] picks them up.
	 *
	 * @param GridColors colors       The background / major / minor grid colors.
	 * @param Float      scale        The major grid line spacing in world units.
	 * @param Int        subdivisions The minor lines per major cell.
	 */
	fun setGrid(colors: GridColors, scale: Float, subdivisions: Int) {
		gridColors = colors
		gridScale = scale
		gridSubdivisions = subdivisions
	}

	/**
	 * Sets which drawables are highlighted (object-mode selection).  The next [render] tints them.
	 *
	 * @param Set<DrawableId> ids The selected drawable ids.
	 */
	fun setSelection(ids: Set<DrawableId>) {
		selectedIds = ids
	}

	/**
	 * Sets which drawable is active (the last-selected object of a multi-selection), tinted toward
	 * [activeHighlightColor] rather than [highlightColor]. Null clears the distinction.
	 *
	 * @param DrawableId? id The active drawable id, or null when none is active.
	 */
	fun setActiveSelection(id: DrawableId?) {
		activeId = id
	}

	/**
	 * Updates the set of drawables actually drawn (the resolved visibility cascade), so a visibility edit
	 * takes effect on the next [render].
	 *
	 * @param Set ids The drawable ids to draw.
	 */
	fun setShownDrawables(ids: Set<DrawableId>) {
		if (ids != shownDrawableIds) {
			// The bbox skips hidden drawables, so a visibility change invalidates the cached framing.
			bboxReady = false
		}
		shownDrawableIds = ids
	}

	/**
	 * Shows or hides the world-origin axis lines (the red X / blue Z cross at the model's world origin).
	 *
	 * @param Boolean visible True to draw the axes each frame.
	 */
	fun setWorldAxesVisible(visible: Boolean) {
		worldAxesVisible = visible
	}

	/**
	 * Reconciles the renderer with the current model after an edit, via a backend-neutral [diffModel] whose
	 * four tiers are applied here as device calls: a reorder / reparent needs no buffer work; a base-mesh
	 * move re-uploads positions; a UV edit re-uploads UVs; a structural change frees and re-uploads whole.
	 *
	 * Structural limits: a session-created drawable never joins the load-time glue layout (glues reference
	 * source ids, so a fresh id welds nothing), and a REMESHED glue mesh degrades to an unwelded draw (its
	 * store region and weld attrs index the old vertex order and are not remapped here).
	 *
	 * The diff compares against [currentModel] and therefore runs BEFORE the reassignment, keeping the
	 * invariant "GPU buffer contents === currentModel's arrays".
	 *
	 * @param PuppetModel newModel The current model.
	 */
	fun updateModel(newModel: PuppetModel) {
		val warpDeformerIds = newModel.deformers.filterIsInstance<Deformer.Warp>().map { it.id }.toSet()
		val diff = diffModel(currentModel, newModel, gpuById.mapValues { (_, resident) -> resident.vertexCount })
		val reconciled = LinkedHashMap<DrawableId, GpuDrawable>()
		for (action in diff.actions) {
			when (action) {
				is DrawableAction.Upload ->
					uploadDrawable(action.drawable, glueAttributes = null, glueBaseOffset = 0, warpDeformerIds = warpDeformerIds)
						?.let { reconciled[action.drawableId] = it }

				is DrawableAction.Reupload -> {
					gpuById[action.drawableId]?.let { deleteDrawable(it) }
					uploadDrawable(action.drawable, glueAttributes = null, glueBaseOffset = 0, warpDeformerIds = warpDeformerIds)
						?.let { reconciled[action.drawableId] = it }
				}

				is DrawableAction.Keep -> {
					val existing = gpuById[action.drawableId] ?: continue
					reconciled[action.drawableId] = existing
					// Re-stamp the static composite state from the edited drawable: a blend/alpha/culling/
					// mask/invert edit does no buffer work, so it lands here, and these were otherwise
					// frozen at upload (isExtendedBlend is a getter, so draw routing updates for free).
					existing.blendMode = action.drawable.blendMode
					existing.alphaBlendMode = action.drawable.alphaBlendMode
					existing.culling = action.drawable.culling
					existing.maskIds = action.drawable.maskedBy
					existing.invertMask = action.drawable.invertMask
					action.positions?.let {
						device.updateMeshPositions(existing.mesh, it)
						// Re-point the bounds walk at the new rest positions too, or the composite scissor
						// would keep sizing to the pre-edit geometry and clip the moved vertices.
						existing.boundsBase = it
					}
					action.uvs?.let { device.updateMeshUvs(existing.mesh, it) }
				}
			}
		}
		// Free residents the edit dropped. A Reupload already freed its own and is never in `removed`, so
		// nothing is freed twice.
		for (drawableId in diff.removed) {
			gpuById[drawableId]?.let { deleteDrawable(it) }
		}
		gpuById = reconciled
		glueDeformList = reconciled.values.filter { it.isGlueMesh }
		renderableById = reconciled.mapValues { (_, resident) -> resident.indexCount > 0 }
		currentModel = newModel
		currentRenderRoot = newModel.renderRoot
		baseOrder = newModel.drawables.map { it.id }
		rebuildGluePartners(newModel)
		// A rebuilt resident comes back on its atlas page, so re-point the whole set at the artwork the
		// document displays from - otherwise an edit silently drops that drawable back to the atlas.
		applySourceLayerDisplay()
		bboxReady = false
	}

	/** Rebuilds the two-way glue partner map the composite bounds walk unions across. */
	private fun rebuildGluePartners(fromModel: PuppetModel) {
		if (fromModel.glues.isEmpty()) {
			gluePartnersById = emptyMap()
			return
		}
		val partners = HashMap<DrawableId, MutableList<DrawableId>>()
		for ((glueIndex, glue) in fromModel.glues.withIndex()) {
			// Glues past MAX_GLUES render unwelded (resolvePose / planGlueLayout skip them by the same
			// index), so their partners move nothing - don't union those extents into the bounds.
			if (glueIndex >= MAX_GLUES) {
				break
			}
			partners.getOrPut(glue.meshA) { ArrayList() }.add(glue.meshB)
			partners.getOrPut(glue.meshB) { ArrayList() }.add(glue.meshA)
		}
		gluePartnersById = partners
	}

	/**
	 * Sets the color selected drawables are tinted toward (the selection highlight).
	 *
	 * @param Float red   The tint red,   0..1.
	 * @param Float green The tint green, 0..1.
	 * @param Float blue  The tint blue,  0..1.
	 */
	fun setSelectionHighlightColor(red: Float, green: Float, blue: Float) {
		highlightColor = floatArrayOf(red, green, blue)
	}

	/**
	 * Sets the color the active drawable is tinted toward (the active-selection highlight).
	 *
	 * @param Float red   The tint red,   0..1.
	 * @param Float green The tint green, 0..1.
	 * @param Float blue  The tint blue,  0..1.
	 */
	fun setActiveSelectionHighlightColor(red: Float, green: Float, blue: Float) {
		activeHighlightColor = floatArrayOf(red, green, blue)
	}

	/**
	 * Evaluates the current pose's deformed world geometry on the CPU for hit-testing, or null before the
	 * first pose.  Pure CPU with no device calls, so it is safe from the UI thread; it reuses the immutable
	 * per-pose inputs cached by the last [setPose].
	 *
	 * @return DeformedGeometry The current deformed geometry, or null before the first pose.
	 */
	fun pickGeometry(): DeformedGeometry? {
		val inputs = lastPoseInputs ?: return null
		return applyCpuDeform(currentModel, inputs)
	}

	/**
	 * The last frame's resolved draw order (back-to-front; last = front), or empty before the first pose -
	 * the hierarchy-correct front/back ranking picking uses to choose among overlapping meshes.
	 *
	 * @return List<DrawableId> The drawn drawables, back-to-front.
	 */
	fun drawnOrder(): List<DrawableId> = lastDrawnOrder

	fun contentBounds(): ContentBounds {
		ensureContentBounds()
		return ContentBounds(minX, minY, spanX, spanY)
	}

	/**
	 * Draws the current pose into [target].
	 *
	 * The target is explicit rather than the bound framebuffer: a pass that discovered its own target could
	 * not exist on a backend with no bound-framebuffer concept, and even on GL, discovering it hid a real
	 * coupling with whoever bound it first.
	 *
	 * @param RenderTarget target         The surface to draw into.
	 * @param Int          viewportWidth  The target width in pixels.
	 * @param Int          viewportHeight The target height in pixels.
	 */
	fun render(target: RenderTarget, viewportWidth: Int, viewportHeight: Int) {
		ensureSideTargets(viewportWidth, viewportHeight)
		val frame = device.beginFrame()

		// Pass 1: capture every glue mesh's deformed positions into the shared store. Only when the pose
		// changed - a static pose leaves the store (and pass 2's reads of it) unchanged.
		val activeStore = store
		if (activeStore != null && glueBufferDirty) {
			val capture = frame.beginDeformCapturePass(capturePipeline!!, activeStore)
			for (gpuDrawable in glueDeformList) {
				if (gpuDrawable.corners == null) {
					continue
				}
				fillDeform(deformScratch, gpuDrawable)
				texturesScratch.atlas = null
				texturesScratch.maskCoverage = null
				texturesScratch.deltaTexture = gpuDrawable.deltaTexture
				texturesScratch.warpControlPoints = gpuDrawable.cpTexture
				capture.captureDeformedPositions(
					gpuDrawable.mesh,
					deformScratch,
					texturesScratch,
					gpuDrawable.glueBaseOffset,
					gpuDrawable.vertexCount,
				)
			}
			capture.end()
			frame.barrier(activeStore)
			glueBufferDirty = false
		}

		val camera = effectiveCamera(viewportWidth, viewportHeight)
		val transform = camera.worldToNdc(viewportWidth, viewportHeight)
		val affine = WorldToNdc(transform[0], transform[1], transform[2], transform[3])

		// Main pass. The grid is an opaque full-screen fill, so it both clears and paints - DontCare load.
		var pass = frame.beginRenderPass(passSpec(target, LoadAction.DontCare, viewportWidth, viewportHeight))
		drawBackdrop(pass, affine, viewportWidth, viewportHeight)
		pass = renderPlanNodes(frame, currentPlan, target, 0, affine, viewportWidth, viewportHeight, pass, scissor = null)
		pass.end()
		frame.endFrame()
	}

	/**
	 * Walks a render-plan span into [target]: plain drawables draw directly (with the mask-coverage
	 * pass fragmentation), a drawable whose blend is not fixed-function-expressible composites as an
	 * implicit singleton composite, and a [RenderPlanComposite] node renders its subtree into a
	 * pooled layer target and composites it back as one layer.  Passes never nest - the frame stays
	 * a flat pass sequence, and the CURRENT pass travels through as the return value.
	 *
	 * Two per-pose accelerations short-circuit the composite machinery (both pixel-preserving; see
	 * [prepareCompositeAuxState]): a pose-identity Normal/Over composite over an all-Normal/Over
	 * subtree draws inline into the current pass, and a non-Out composite whose layer would land
	 * empty on screen (bounds off-viewport, or pose-blended opacity 0) is skipped outright.  The
	 * remaining composites confine their layer work to the subtree's bounds via [scissor] rects.
	 *
	 * A plan with no composite nodes and no extended-blend drawables takes exactly the old flat
	 * path: zero extra passes, zero resolves - the regression guard.
	 *
	 * @param FrameEncoder         frame          The frame being recorded.
	 * @param List<RenderPlanNode> nodes          The span to draw, back-to-front.
	 * @param RenderTarget         target         The surface this span draws into.
	 * @param Int                  depth          The composite nesting depth (0 = the real target).
	 * @param WorldToNdc           affine         The camera affine.
	 * @param Int                  viewportWidth  The viewport width in pixels.
	 * @param Int                  viewportHeight The viewport height in pixels.
	 * @param RenderPassEncoder    startPass      The open pass on [target].
	 * @param ScissorRect?         scissor [target]'s own pass scissor (a composite layer's
	 *   bounds rect when this span IS an isolated subtree), re-applied whenever the span resumes a
	 *   pass on [target]; null at the top level.
	 * @return RenderPassEncoder The open pass on [target] after the span (may differ from [startPass]).
	 */
	private fun renderPlanNodes(
		frame: FrameEncoder,
		nodes: List<RenderPlanNode>,
		target: RenderTarget,
		depth: Int,
		affine: WorldToNdc,
		viewportWidth: Int,
		viewportHeight: Int,
		startPass: RenderPassEncoder,
		scissor: ScissorRect?,
	): RenderPassEncoder {
		var pass = startPass
		for (node in nodes) {
			when (node) {
				is RenderPlanDrawable -> {
					val gpuDrawable = gpuById[node.id] ?: continue
					if (gpuDrawable.isExtendedBlend) {
						// A 5.3 extended blend: not fixed-function-expressible, so draw the drawable
						// alone into a layer target and composite it in-shader.
						// An empty (fully faded) non-Out layer composites to the unchanged destination, so
						// skip it outright - independent of the bounds-scissor toggle (Out erases the
						// destination where the layer is empty, so it must keep the real composite path).
						if (gpuDrawable.alphaBlendMode != AlphaBlendMode.Out && gpuDrawable.opacity == 0f) {
							continue
						}
						var layerRect: ScissorRect? = null
						if (compositeBoundsScissorEnabled && gpuDrawable.alphaBlendMode != AlphaBlendMode.Out) {
							val bounds = extendedDrawableWorldBounds[gpuDrawable.id]
							if (bounds != null) {
								layerRect = scissorRectOf(bounds, affine, viewportWidth, viewportHeight) ?: continue
							}
						}
						pass.end()
						pass = compositeDrawable(frame, gpuDrawable, target, depth, affine, viewportWidth, viewportHeight, layerRect, scissor)
						continue
					}
					var maskCoverage: GpuTexture? = null
					if (gpuDrawable.maskIds.isNotEmpty()) {
						// Mask by pass fragmentation: end the pass, render coverage into the mask target,
						// resume preserving what is drawn so far. Correct on every backend and non-nesting.
						pass.end()
						renderMaskCoverage(frame, gpuDrawable.maskIds, affine, viewportWidth, viewportHeight)
						pass = frame.beginRenderPass(passSpec(target, LoadAction.Load, viewportWidth, viewportHeight, scissor = scissor))
						maskCoverage = maskTarget?.sampledTexture
					}
					val isActive = activeId != null && gpuDrawable.id == activeId
					val highlight = if (isActive || gpuDrawable.id in selectedIds) SELECTION_TINT_STRENGTH else 0f
					drawDrawable(
						pass,
						gpuDrawable,
						affine,
						gpuDrawable.blendMode,
						gpuDrawable.opacity,
						highlight,
						isActive,
						maskCoverage != null,
						maskCoverage,
					)
				}

				is RenderPlanComposite -> {
					if (node.partId in flattenableComposites) {
						// Identity source-over of an all-Normal/Over subtree: Over is associative,
						// so the subtree draws inline - no layer, no snapshot, no composite draw.
						pass = renderPlanNodes(frame, node.children, target, depth, affine, viewportWidth, viewportHeight, pass, scissor)
						continue
					}
					// A fully faded non-Out layer composites to the unchanged destination, so skip it
					// outright - independent of the bounds-scissor toggle (see the extended-blend branch).
					val poseOpacity = currentCompositeStates[node.partId]?.opacity ?: node.composite.opacity
					if (node.composite.alphaBlendMode != AlphaBlendMode.Out && poseOpacity == 0f) {
						continue
					}
					var layerRect: ScissorRect? = null
					if (compositeBoundsScissorEnabled && node.composite.alphaBlendMode != AlphaBlendMode.Out) {
						val bounds = compositeWorldBounds[node.partId]
						if (bounds != null) {
							layerRect = scissorRectOf(bounds, affine, viewportWidth, viewportHeight) ?: continue
						}
					}
					pass.end()
					pass = compositeGroup(frame, node, target, depth, affine, viewportWidth, viewportHeight, layerRect, scissor)
				}
			}
		}
		return pass
	}

	/**
	 * Maps world bounds to a padded, viewport-clamped scissor rectangle (top-left-origin pixels),
	 * or null when the bounds land entirely off the viewport.
	 *
	 * @param PosedAabb  bounds         The world-space bounds.
	 * @param WorldToNdc affine         The camera affine.
	 * @param Int        viewportWidth  The viewport width in pixels.
	 * @param Int        viewportHeight The viewport height in pixels.
	 * @return ScissorRect? The scissor rect, or null when empty.
	 */
	private fun scissorRectOf(bounds: PosedAabb, affine: WorldToNdc, viewportWidth: Int, viewportHeight: Int): ScissorRect? {
		val ndcXa = affine.scaleX * bounds.minX + affine.offsetX
		val ndcXb = affine.scaleX * bounds.maxX + affine.offsetX
		val ndcYa = affine.scaleY * bounds.minY + affine.offsetY
		val ndcYb = affine.scaleY * bounds.maxY + affine.offsetY
		val pixelLeft = (minOf(ndcXa, ndcXb) * 0.5f + 0.5f) * viewportWidth
		val pixelRight = (maxOf(ndcXa, ndcXb) * 0.5f + 0.5f) * viewportWidth
		// NDC +Y is up; top-left-origin rows count down from the top.
		val pixelTop = (0.5f - maxOf(ndcYa, ndcYb) * 0.5f) * viewportHeight
		val pixelBottom = (0.5f - minOf(ndcYa, ndcYb) * 0.5f) * viewportHeight
		// The fringe guard is a constant ON-SCREEN size, so it scales with the supersample factor
		// (gridPixelScale = framebuffer pixels per on-screen pixel), or a 1x-sufficient pad under-covers
		// the fringe at 2x+ and clips an edge texel.
		val pad = SCISSOR_PAD_PX * gridPixelScale
		val viewportWidthF = viewportWidth.toFloat()
		val viewportHeightF = viewportHeight.toFloat()
		// Pad and clamp in FLOAT space before the Int conversion.  A warp-extrapolated vertex can push a
		// pixel edge far past Int range; Float.toInt() would then saturate and the -/+ pad would wrap via
		// unchecked Int overflow to the WRONG end of the clamp, vanishing the layer instead of covering
		// the viewport.  Float coerceIn clamps ±Infinity correctly; a NaN edge never reaches here
		// (deformedWorldBounds publishes no bound for a non-finite vertex, so render() keeps the full
		// viewport path).
		val left = (floor(pixelLeft) - pad).coerceIn(0f, viewportWidthF).toInt()
		val right = (ceil(pixelRight) + pad).coerceIn(0f, viewportWidthF).toInt()
		val top = (floor(pixelTop) - pad).coerceIn(0f, viewportHeightF).toInt()
		val bottom = (ceil(pixelBottom) + pad).coerceIn(0f, viewportHeightF).toInt()
		if (right <= left || bottom <= top) {
			return null
		}
		return ScissorRect(left, top, right - left, bottom - top)
	}

	/**
	 * Renders an isolated part's subtree into the depth's pooled layer target, then composites it
	 * back into [target] as one layer with the part's blend modes, pose-blended channels, and
	 * optional clip mask.  Returns the fresh open pass on [target].
	 *
	 * @param FrameEncoder         frame          The frame being recorded.
	 * @param RenderPlanComposite  node           The isolated part's plan node (subtree + composite settings).
	 * @param RenderTarget         target         The surface the layer composites back into.
	 * @param Int                  depth          The composite nesting depth (its layer target's pool slot).
	 * @param WorldToNdc           affine         The camera affine.
	 * @param Int                  viewportWidth  The viewport width in pixels.
	 * @param Int                  viewportHeight The viewport height in pixels.
	 * @param ScissorRect? layerRect      The subtree's bounds - confines the layer clear + subtree
	 *   draw, null for a full-viewport layer (Out alpha or uncomputable bounds).
	 * @param ScissorRect? parentScissor  [target]'s own scissor (the enclosing layer's rect), null at
	 *   the top level; the composite-back is confined to it intersected with [layerRect].
	 * @return RenderPassEncoder The fresh open pass on [target] after the composite.
	 */
	private fun compositeGroup(
		frame: FrameEncoder,
		node: RenderPlanComposite,
		target: RenderTarget,
		depth: Int,
		affine: WorldToNdc,
		viewportWidth: Int,
		viewportHeight: Int,
		layerRect: ScissorRect?,
		parentScissor: ScissorRect?,
	): RenderPassEncoder {
		val layerTarget = acquireCompositeTarget(depth)
		var subtreePass =
			frame.beginRenderPass(passSpec(layerTarget, LoadAction.Clear, viewportWidth, viewportHeight, clearAlpha = 0f, scissor = layerRect))
		subtreePass = renderPlanNodes(frame, node.children, layerTarget, depth + 1, affine, viewportWidth, viewportHeight, subtreePass, layerRect)
		subtreePass.end()
		val masked = node.composite.maskedBy.isNotEmpty()
		if (masked) {
			renderMaskCoverage(frame, node.composite.maskedBy, affine, viewportWidth, viewportHeight)
		}
		// The pose-blended channels; a part missing from the map (never the case for an isolated
		// group, but harmless) falls back to its static channels.
		val state = currentCompositeStates[node.partId]
		compositeScratch.colorMode = packedColorModeOf(node.composite.blendMode)
		compositeScratch.alphaMode = packedAlphaModeOf(node.composite.alphaBlendMode)
		compositeScratch.opacity = state?.opacity ?: node.composite.opacity
		val multiply = state?.multiplyColor ?: node.composite.multiplyColor
		val screen = state?.screenColor ?: node.composite.screenColor
		compositeScratch.multiplyRed = multiply.red
		compositeScratch.multiplyGreen = multiply.green
		compositeScratch.multiplyBlue = multiply.blue
		compositeScratch.screenRed = screen.red
		compositeScratch.screenGreen = screen.green
		compositeScratch.screenBlue = screen.blue
		compositeScratch.useMask = masked
		compositeScratch.invertMask = masked && node.composite.invertMask
		return encodeComposite(frame, layerTarget, target, affine, viewportWidth, viewportHeight, intersectScissor(layerRect, parentScissor), parentScissor)
	}

	/**
	 * Draws one extended-blend drawable as an implicit singleton composite: the drawable alone (with
	 * its own opacity and clip mask) into the depth's layer target, composited back with its color
	 * and alpha modes at identity channels.  Returns the fresh open pass on [target].
	 *
	 * @param FrameEncoder      frame          The frame being recorded.
	 * @param GpuDrawable       gpuDrawable    The extended-blend drawable to composite.
	 * @param RenderTarget      target         The surface the layer composites back into.
	 * @param Int               depth          The composite nesting depth (its layer target's pool slot).
	 * @param WorldToNdc        affine         The camera affine.
	 * @param Int               viewportWidth  The viewport width in pixels.
	 * @param Int               viewportHeight The viewport height in pixels.
	 * @param ScissorRect? layerRect     The drawable's bounds - confines the layer clear + draw, null
	 *   for a full-viewport layer (Out alpha or uncomputable bounds).
	 * @param ScissorRect? parentScissor [target]'s own scissor, null at the top level.
	 * @return RenderPassEncoder The fresh open pass on [target] after the composite.
	 */
	private fun compositeDrawable(
		frame: FrameEncoder,
		gpuDrawable: GpuDrawable,
		target: RenderTarget,
		depth: Int,
		affine: WorldToNdc,
		viewportWidth: Int,
		viewportHeight: Int,
		layerRect: ScissorRect?,
		parentScissor: ScissorRect?,
	): RenderPassEncoder {
		var maskCoverage: GpuTexture? = null
		if (gpuDrawable.maskIds.isNotEmpty()) {
			renderMaskCoverage(frame, gpuDrawable.maskIds, affine, viewportWidth, viewportHeight)
			maskCoverage = maskTarget?.sampledTexture
		}
		val layerTarget = acquireCompositeTarget(depth)
		val layerPass =
			frame.beginRenderPass(passSpec(layerTarget, LoadAction.Clear, viewportWidth, viewportHeight, clearAlpha = 0f, scissor = layerRect))
		val isActive = activeId != null && gpuDrawable.id == activeId
		val highlight = if (isActive || gpuDrawable.id in selectedIds) SELECTION_TINT_STRENGTH else 0f
		// Normal blend onto the cleared transparent layer just writes the premultiplied pixels; the
		// drawable's real blend happens in the composite below.
		drawDrawable(
			layerPass,
			gpuDrawable,
			affine,
			BlendMode.Normal,
			gpuDrawable.opacity,
			highlight,
			isActive,
			maskCoverage != null,
			maskCoverage,
		)
		layerPass.end()
		compositeScratch.colorMode = packedColorModeOf(gpuDrawable.blendMode)
		compositeScratch.alphaMode = packedAlphaModeOf(gpuDrawable.alphaBlendMode)
		compositeScratch.opacity = 1f
		compositeScratch.multiplyRed = 1f
		compositeScratch.multiplyGreen = 1f
		compositeScratch.multiplyBlue = 1f
		compositeScratch.screenRed = 0f
		compositeScratch.screenGreen = 0f
		compositeScratch.screenBlue = 0f
		compositeScratch.useMask = false
		compositeScratch.invertMask = false
		return encodeComposite(frame, layerTarget, target, affine, viewportWidth, viewportHeight, intersectScissor(layerRect, parentScissor), parentScissor)
	}

	/**
	 * Snapshots [target], begins a fresh Load pass on it, and issues the composite draw from
	 * [layerTarget] against the snapshot using the already-filled [compositeScratch].
	 *
	 * The composite reads and writes the same fragments, so [compositeScissor] bounds BOTH the
	 * destination snapshot copy and the composite draw - the whole target when null.
	 *
	 * The composite draw runs in its OWN pass under [compositeScissor], which is then ended; the
	 * returned continuation pass is scissored to [continuationScissor] (the enclosing span's own
	 * scissor, null at the top level) so the drawables the caller draws AFTER this composite are NOT
	 * clipped to the composite's bounds - reusing the composite's own scissor for the continuation
	 * pass would incorrectly shrink everything drawn behind it in the same span.
	 *
	 * @param FrameEncoder      frame          The frame being recorded.
	 * @param RenderTarget      layerTarget    The pooled layer holding the subtree/drawable to composite.
	 * @param RenderTarget      target         The surface the layer composites back into.
	 * @param WorldToNdc        affine         The camera affine.
	 * @param Int               viewportWidth  The viewport width in pixels.
	 * @param Int               viewportHeight The viewport height in pixels.
	 * @param ScissorRect? compositeScissor   The rect the snapshot + composite are confined to, or null.
	 * @param ScissorRect? continuationScissor The scissor the returned pass carries, or null.
	 * @return RenderPassEncoder A fresh open pass on [target] under [continuationScissor].
	 */
	private fun encodeComposite(
		frame: FrameEncoder,
		layerTarget: RenderTarget,
		target: RenderTarget,
		affine: WorldToNdc,
		viewportWidth: Int,
		viewportHeight: Int,
		compositeScissor: ScissorRect?,
		continuationScissor: ScissorRect?,
	): RenderPassEncoder {
		// Snapshot the destination so the composite shader can sample it (a pass cannot sample its
		// own target); the copy is ordered between the passes around it, and confined to the same
		// rect the composite draw reads and writes.  Used-region resolve: [target] can be the main
		// draw target (the surface's capacity) or a pooled layer (the side-target capacity), and the
		// snapshot has its own capacity - equal USED extents anchored at the origin is the invariant,
		// and the scissor rect flips against the used height.
		val snapshot = snapshotTarget ?: error("composite targets not allocated")
		device.resolveUsed(target, viewportWidth, viewportHeight, snapshot, viewportWidth, viewportHeight, compositeScissor)
		val compositePass = frame.beginRenderPass(passSpec(target, LoadAction.Load, viewportWidth, viewportHeight, scissor = compositeScissor))
		compositePass.setPipeline(compositePipeline!!)
		compositePass.setCamera(affine, sideTargetCapacityWidth, sideTargetCapacityHeight)
		texturesScratch.atlas = null
		texturesScratch.deltaTexture = null
		texturesScratch.warpControlPoints = null
		texturesScratch.maskCoverage = if (compositeScratch.useMask) maskTarget?.sampledTexture else null
		texturesScratch.compositeLayer = layerTarget.sampledTexture
		texturesScratch.destinationSnapshot = snapshot.sampledTexture
		compositePass.drawComposite(compositeScratch, texturesScratch)
		texturesScratch.compositeLayer = null
		texturesScratch.destinationSnapshot = null
		compositePass.end()
		// A fresh pass under the ENCLOSING span's scissor - the composite's own scissor must not leak
		// onto whatever the caller draws next into this target.
		return frame.beginRenderPass(passSpec(target, LoadAction.Load, viewportWidth, viewportHeight, scissor = continuationScissor))
	}

	/**
	 * Renders one atlas page as a flat, upright underlay for a UV-editor area (instead of the posed
	 * puppet): the themed grid backdrop, then the whole page as a single textured quad.  A null or
	 * out-of-range [pageIndex] paints the grid only.
	 *
	 * The page samples the SAME uploaded atlas texture a drawable binds while the puppet displays from the
	 * atlas, through the same premultiplied fragment shader, so the underlay matches the puppet's texel
	 * rendering exactly.
	 *
	 * @param RenderTarget target         The surface to draw into.
	 * @param Int          pageIndex      The atlas page to draw, or null for none.
	 * @param Int          viewportWidth  The target width in pixels.
	 * @param Int          viewportHeight The target height in pixels.
	 */
	fun renderAtlasPage(target: RenderTarget, pageIndex: Int?, viewportWidth: Int, viewportHeight: Int) {
		val page = pageIndex?.let { textures.atlases.getOrNull(it) }
		renderUnderlay(target, page, pageIndex?.let { atlasHandles.getOrNull(it) }, viewportWidth, viewportHeight)
	}

	/**
	 * Draws an arbitrary image as the flat underlay - the UV editor's source-layer view, the pre-atlas
	 * counterpart of [renderAtlasPage].
	 *
	 * Unlike the atlas pages, which upload as a whole set (at init, or wholesale on a [setAtlasPages]
	 * swap) rather than one image at a time, a layer image arrives whenever the editor is pointed at
	 * one, so its texture is created on first sight and cached (see [underlayTextureFor]).  A null
	 * image paints the grid only.
	 *
	 * @param RenderTarget target         The surface to draw into.
	 * @param DecodedImage image          The image to draw, or null for none.
	 * @param Int          viewportWidth  The target width in pixels.
	 * @param Int          viewportHeight The target height in pixels.
	 */
	fun renderUnderlayImage(target: RenderTarget, image: DecodedImage?, viewportWidth: Int, viewportHeight: Int) {
		renderUnderlay(target, image, image?.let { underlayTextureFor(it) }, viewportWidth, viewportHeight)
	}

	/**
	 * The flat underlay draw both UV scenes share: the themed grid backdrop, then the image as a single
	 * textured quad at the world origin.  A null image or handle paints the grid alone.
	 *
	 * The quad samples through the same premultiplied fragment shader the puppet uses, so an underlay
	 * matches the puppet's texel rendering exactly.
	 *
	 * @param RenderTarget target         The surface to draw into.
	 * @param DecodedImage image          The image whose extent the quad and grid tile take, or null.
	 * @param GpuTexture   handle         The uploaded texture for [image], or null.
	 * @param Int          viewportWidth  The target width in pixels.
	 * @param Int          viewportHeight The target height in pixels.
	 */
	private fun renderUnderlay(
		target: RenderTarget,
		image: DecodedImage?,
		handle: GpuTexture?,
		viewportWidth: Int,
		viewportHeight: Int,
	) {
		val camera = effectiveCamera(viewportWidth, viewportHeight)
		val transform = camera.worldToNdc(viewportWidth, viewportHeight)
		val affine = WorldToNdc(transform[0], transform[1], transform[2], transform[3])
		// The UV grid's major lines fall on the unit image tile (UV integers), so the major spacing is the
		// image's pixel extent; minor lines subdivide the tile. With no image, fall back to the square grid.
		val majorSpacingX = image?.width?.toFloat() ?: gridScale
		val majorSpacingY = image?.height?.toFloat() ?: gridScale

		val frame = device.beginFrame()
		val pass = frame.beginRenderPass(passSpec(target, LoadAction.DontCare, viewportWidth, viewportHeight))
		pass.setPipeline(gridPipeline!!)
		// The UV grid's unit tile starts at the image origin (UV 0,0 = image-pixel 0,0), so anchor at (0, 0).
		pass.drawGrid(
			GridUniforms(affine, viewportWidth, viewportHeight, 0f, 0f, majorSpacingX, majorSpacingY, gridSubdivisions, gridPixelScale, gridColors),
		)
		if (image != null && handle != null) {
			pass.setPipeline(atlasPagePipeline!!)
			pass.setCamera(affine, viewportWidth, viewportHeight)
			fragmentScratch.reset()
			fragmentScratch.useTexture = true
			pass.drawAtlasPage(handle, image.width.toFloat(), image.height.toFloat(), fragmentScratch)
		}
		pass.end()
		frame.endFrame()
	}

	/**
	 * The uploaded texture for an underlay image, uploading it on first sight.
	 *
	 * Keyed on image IDENTITY, not content: the store hands out the same decoded instance for a given
	 * layer, so identity is both correct and free.  The cache is deliberately tiny - a UV editor shows
	 * one layer at a time and only a handful of areas can exist - and evicts in insertion order, which
	 * costs a re-upload in the pathological case and bounds GPU memory in every other.  Rendering the
	 * whole document's layers at once is a different problem with a different answer (paging or
	 * rebatching), and it belongs to the viewport's layer display mode, not here.
	 *
	 * Render thread only: it creates GPU resources, so it must run with the context current.
	 *
	 * @param DecodedImage image The image to upload.
	 * @return GpuTexture The texture handle.
	 */
	private fun underlayTextureFor(image: DecodedImage): GpuTexture {
		underlayTextures[image]?.let { existing ->
			return existing
		}
		while (underlayTextures.size >= UNDERLAY_TEXTURE_CACHE_SIZE) {
			val evicted = underlayTextures.keys.first()
			underlayTextures.remove(evicted)?.let { handle -> device.destroyTexture(handle) }
		}
		val created = device.createTexture(image.width, image.height, TextureFormat.Rgba8, TextureFilter.Linear, image.rgba)
		underlayTextures[image] = created
		return created
	}

	/** Draws the grid backdrop and, when enabled, the world-origin axis lines behind the puppet. */
	private fun drawBackdrop(pass: RenderPassEncoder, affine: WorldToNdc, viewportWidth: Int, viewportHeight: Int) {
		pass.setPipeline(gridPipeline!!)
		pass.drawGrid(
			GridUniforms(
				affine,
				viewportWidth,
				viewportHeight,
				currentModel.worldOriginX,
				currentModel.worldOriginY,
				gridScale,
				gridScale,
				gridSubdivisions,
				gridPixelScale,
				gridColors,
			),
		)
		if (worldAxesVisible) {
			// The axes sit between the backdrop and the drawables, reading as part of the canvas.
			val originNdcX = affine.scaleX * currentModel.worldOriginX + affine.offsetX
			val originNdcY = affine.scaleY * currentModel.worldOriginY + affine.offsetY
			val axisColors = WorldAxisColors.Classic
			pass.setPipeline(axisPipeline!!)
			pass.drawAxisLine(AxisLineUniforms(originNdcY, vertical = false, axisColors.xRed, axisColors.xGreen, axisColors.xBlue))
			pass.drawAxisLine(AxisLineUniforms(originNdcX, vertical = true, axisColors.zRed, axisColors.zGreen, axisColors.zBlue))
		}
	}

	/**
	 * Renders the mask sources' coverage into the shared mask target, as its own cleared pass at the
	 * render's USED viewport (the same origin-anchored projection as the main pass, so fragment (x, y)
	 * there reads coverage texel (x, y) here).  The Clear load fills the whole capacity texture
	 * (a clear ignores the viewport) - harmless, and cheap next to the realloc it replaces.
	 *
	 * @param FrameEncoder frame The frame being recorded.
	 * @param List<DrawableId> maskIds The mask source drawables.
	 * @param WorldToNdc affine The camera affine.
	 * @param Int viewportWidth  The render width in pixels.
	 * @param Int viewportHeight The render height in pixels.
	 */
	private fun renderMaskCoverage(
		frame: org.umamo.render.device.FrameEncoder,
		maskIds: List<DrawableId>,
		affine: WorldToNdc,
		viewportWidth: Int,
		viewportHeight: Int,
	) {
		val target = maskTarget ?: return
		val coverage = frame.beginRenderPass(passSpec(target, LoadAction.Clear, viewportWidth, viewportHeight, clearAlpha = 0f))
		for (maskId in maskIds) {
			val mask = gpuById[maskId] ?: continue
			if (!mask.visible || mask.indexCount == 0) {
				continue
			}
			// Coverage is the mask's shape at full intensity, unmasked, and ALWAYS Normal blend regardless
			// of the mask drawable's own blend mode: an Additive or Multiply source would leave the cleared
			// coverage target's alpha at 0, so the masked drawable would sample zero coverage and vanish.
			drawDrawable(
				coverage,
				mask,
				affine,
				blendMode = BlendMode.Normal,
				opacity = 1f,
				highlight = 0f,
				isActive = false,
				masked = false,
				maskCoverage = null,
			)
		}
		coverage.end()
	}

	/**
	 * Binds a drawable's pipeline + per-pose state and issues its draw into [pass].  The camera's
	 * screen-space divisor is always the side-target capacity: a masked draw samples the mask
	 * coverage texture at that allocation, and for an unmasked (or coverage-pass) draw the divisor
	 * is simply unused.
	 *
	 * @param BlendMode blendMode The blend to draw with - the drawable's own for the main pass, forced
	 *   [BlendMode.Normal] for a mask-coverage draw (see [renderMaskCoverage]).
	 */
	private fun drawDrawable(
		pass: RenderPassEncoder,
		gpuDrawable: GpuDrawable,
		affine: WorldToNdc,
		blendMode: BlendMode,
		opacity: Float,
		highlight: Float,
		isActive: Boolean,
		masked: Boolean,
		maskCoverage: GpuTexture?,
	) {
		pass.setPipeline(pipelineFor(gpuDrawable.isGlueMesh, blendMode, gpuDrawable.culling))
		pass.setCamera(affine, sideTargetCapacityWidth, sideTargetCapacityHeight)
		fillFragment(fragmentScratch, gpuDrawable, opacity, highlight, isActive, masked)
		// Reused per draw rather than allocating a bundle per drawable per frame, matching the deform /
		// fragment scratch. A glue draw does not deform, so it needs no delta / control-point textures.
		texturesScratch.atlas = gpuDrawable.activeTexture()
		texturesScratch.maskCoverage = maskCoverage
		if (gpuDrawable.isGlueMesh) {
			texturesScratch.deltaTexture = null
			texturesScratch.warpControlPoints = null
			pass.drawGlueMesh(gpuDrawable.mesh, store!!, gpuDrawable.glueBaseOffset, glueIntensities, fragmentScratch, texturesScratch)
		} else {
			texturesScratch.deltaTexture = gpuDrawable.deltaTexture
			texturesScratch.warpControlPoints = gpuDrawable.cpTexture
			fillDeform(deformScratch, gpuDrawable)
			pass.drawPuppetMesh(gpuDrawable.mesh, deformScratch, fragmentScratch, texturesScratch)
		}
	}

	/** The cached draw pipeline for a glue vs non-glue mesh at a blend mode and cull state. */
	private fun pipelineFor(isGlueMesh: Boolean, blendMode: BlendMode, cullBackFaces: Boolean): RenderPipeline {
		val cache = (if (isGlueMesh) gluePipelines else puppetPipelines)[if (cullBackFaces) 1 else 0]
		return cache.getOrPut(blendMode) {
			val purpose = if (isGlueMesh) PipelinePurpose.PuppetGlueDraw else PipelinePurpose.PuppetDeformDraw
			device.createRenderPipeline(RenderPipelineSpec(purpose, blendOf(blendMode), cullBackFaces))
		}
	}

	/** Fills a drawable's per-pose deform uniforms (active corners + baked parent transform). */
	private fun fillDeform(deform: DeformUniforms, gpuDrawable: GpuDrawable) {
		val corners = gpuDrawable.corners ?: return
		val cornerCount = minOf(MAX_CORNERS, corners.size)
		deform.cornerCount = cornerCount
		for (cornerIndex in 0 until cornerCount) {
			deform.cornerCell[cornerIndex] = corners[cornerIndex].linearIndex
			deform.cornerWeight[cornerIndex] = corners[cornerIndex].weight
		}
		// Blend-shape columns: map the pose's active contributions through the static layout.
		// Surplus beyond MAX_BLEND_CORNERS is dropped deterministically (corpus max is 18).
		var blendCount = 0
		val blend = gpuDrawable.blend
		if (blend != null) {
			for (contribution in blend.contributions) {
				if (blendCount >= MAX_BLEND_CORNERS) {
					break
				}
				val column = gpuDrawable.blendLayout.columnOf(contribution.bindingIndex, contribution.keyIndex) ?: continue
				deform.blendCell[blendCount] = column
				deform.blendWeight[blendCount] = contribution.weight
				blendCount++
			}
		}
		deform.blendCount = blendCount
		when (val parentWorld = gpuDrawable.parentWorld) {
			is RotationWorld -> {
				deform.parentType = 1
				val xform = parentWorld.xform
				deform.rotation[0] = xform.c12
				deform.rotation[1] = xform.c13
				deform.rotation[2] = xform.c14
				deform.rotation[3] = xform.c15
				deform.rotation[4] = xform.ox
				deform.rotation[5] = xform.oy
			}

			is WarpWorld -> {
				deform.parentType = 2
				deform.warpColumns = parentWorld.cols
				deform.warpRows = parentWorld.rows
				deform.warpBilinear = parentWorld.bilinear
			}

			else -> deform.parentType = 0
		}
	}

	/** Fills a drawable's fragment uniforms (texture-or-color, opacity, mask flags, highlight). */
	private fun fillFragment(
		fragment: FragmentUniforms,
		gpuDrawable: GpuDrawable,
		opacity: Float,
		highlight: Float,
		isActive: Boolean,
		masked: Boolean,
	) {
		fragment.reset()
		// The CHOSEN handle, not the atlas one: a drawable rendering from its source artwork has no atlas
		// binding in play, and keying off the wrong field would draw it as a flat color.
		val activeTexture = gpuDrawable.activeTexture()
		if (activeTexture != null) {
			fragment.useTexture = true
			// Identity unless this drawable is sampling its artwork, in which case the affine is what
			// makes its stored (atlas-frame) coordinates address that image instead.
			if (activeTexture === gpuDrawable.layerTexture) {
				gpuDrawable.layerUvAffine?.copyInto(fragment.uvAffine)
			}
		} else {
			fragment.colorRed = gpuDrawable.color[0]
			fragment.colorGreen = gpuDrawable.color[1]
			fragment.colorBlue = gpuDrawable.color[2]
			fragment.colorAlpha = gpuDrawable.color[3]
		}
		fragment.opacity = opacity
		fragment.useMask = masked
		fragment.invertMask = masked && gpuDrawable.invertMask
		fragment.multiplyRed = gpuDrawable.multiplyColor.red
		fragment.multiplyGreen = gpuDrawable.multiplyColor.green
		fragment.multiplyBlue = gpuDrawable.multiplyColor.blue
		fragment.screenRed = gpuDrawable.screenColor.red
		fragment.screenGreen = gpuDrawable.screenColor.green
		fragment.screenBlue = gpuDrawable.screenColor.blue
		fragment.highlight = highlight
		val tint = if (isActive) activeHighlightColor else highlightColor
		fragment.highlightRed = tint[0]
		fragment.highlightGreen = tint[1]
		fragment.highlightBlue = tint[2]
	}

	/**
	 * The camera to project through this frame: the one set by [setCamera], or a fit of the rest-pose
	 * content into the viewport before any is set.
	 */
	private fun effectiveCamera(viewportWidth: Int, viewportHeight: Int): ViewportCamera =
		currentCamera ?: ViewportCamera.fit(contentBounds(), viewportWidth, viewportHeight)

	/**
	 * Computes the rest-pose content bounds lazily, from a CPU eval at default parameters (shown drawables
	 * only).  Re-armed by [updateModel] / [setShownDrawables] so a base-mesh edit re-frames view.fit.
	 */
	private fun ensureContentBounds() {
		if (bboxReady) {
			return
		}
		val bounds = contentBoundsOf(applyCpuDeform(currentModel, preparePose(currentModel, emptyMap())), shownDrawableIds)
		minX = bounds.minX
		minY = bounds.minY
		spanX = bounds.width
		spanY = bounds.height
		bboxReady = true
	}

	/**
	 * Grows the shared side-target capacity (mask + snapshot + composite pool) to hold a
	 * [viewportWidth] x [viewportHeight] render, per-axis high-water: a request inside the current
	 * capacity allocates nothing (the per-frame path during a gutter drag), growth destroys the mask,
	 * snapshot, and pool together and recreates mask + snapshot at the new capacity (the pool refills
	 * lazily in [acquireCompositeTarget]).  The three kinds share one capacity because the composite
	 * shader samples all of them through a single screenTexSize divisor.
	 *
	 * @param Int viewportWidth  The render width in pixels.
	 * @param Int viewportHeight The render height in pixels.
	 */
	private fun ensureSideTargets(viewportWidth: Int, viewportHeight: Int) {
		if (viewportWidth > sideTargetCapacityWidth || viewportHeight > sideTargetCapacityHeight) {
			maskTarget?.let { device.destroyRenderTarget(it) }
			compositeTargets.forEach { device.destroyRenderTarget(it) }
			compositeTargets.clear()
			snapshotTarget?.let { device.destroyRenderTarget(it) }
			snapshotTarget = null
			sideTargetCapacityWidth = maxOf(viewportWidth, sideTargetCapacityWidth)
			sideTargetCapacityHeight = maxOf(viewportHeight, sideTargetCapacityHeight)
			maskTarget =
				device.createRenderTarget(
					RenderTargetSpec(sideTargetCapacityWidth, sideTargetCapacityHeight, TextureFormat.Rgba8, sampled = true),
				)
		}
		if (snapshotTarget == null) {
			snapshotTarget =
				device.createRenderTarget(
					RenderTargetSpec(sideTargetCapacityWidth, sideTargetCapacityHeight, TextureFormat.Rgba8, sampled = true),
				)
		}
	}

	/**
	 * The pooled layer target for one composite nesting depth, allocated on first use at the shared
	 * side-target capacity.  Slots below [depth] are ancestors mid-composite; the slot itself is
	 * always free when asked for, because composites at one level are strictly sequential.
	 */
	private fun acquireCompositeTarget(depth: Int): RenderTarget {
		while (compositeTargets.size <= depth) {
			compositeTargets.add(
				device.createRenderTarget(
					RenderTargetSpec(sideTargetCapacityWidth, sideTargetCapacityHeight, TextureFormat.Rgba8, sampled = true),
				),
			)
		}
		return compositeTargets[depth]
	}

	/** A render-pass spec for [target] at [load], with an optional clear and pass scissor. */
	private fun passSpec(
		target: RenderTarget,
		load: LoadAction,
		viewportWidth: Int,
		viewportHeight: Int,
		clearAlpha: Float = 0f,
		scissor: ScissorRect? = null,
	) = org.umamo.render.device.RenderPassSpec(
		colorTarget = target,
		loadAction = load,
		viewportWidth = viewportWidth,
		viewportHeight = viewportHeight,
		clearAlpha = clearAlpha,
		scissor = scissor,
	)

	/**
	 * Intersects two scissor rects, treating null as "the whole viewport".  A composite layer's own
	 * bounds ([first]) are always contained in its parent layer's ([second]) by construction, so a
	 * non-empty intersection is expected; an empty intersection (only reachable if that containment ever
	 * failed) falls back to [first], the layer's own rect, rather than null - so it can never silently
	 * widen to the whole viewport.
	 *
	 * @param ScissorRect? first  The inner rect (the composite layer's own bounds), or null.
	 * @param ScissorRect? second The outer rect (the enclosing span's scissor), or null.
	 * @return ScissorRect? The intersection, or [first] on an empty intersection, or null when both are null.
	 */
	private fun intersectScissor(first: ScissorRect?, second: ScissorRect?): ScissorRect? {
		if (first == null) {
			return second
		}
		if (second == null) {
			return first
		}
		val left = maxOf(first.x, second.x)
		val top = maxOf(first.y, second.y)
		val right = minOf(first.x + first.width, second.x + second.width)
		val bottom = minOf(first.y + first.height, second.y + second.height)
		if (right <= left || bottom <= top) {
			return first
		}
		return ScissorRect(left, top, right - left, bottom - top)
	}

	private fun blendOf(mode: BlendMode): PipelineBlend =
		when (mode) {
			BlendMode.Normal -> PipelineBlend.Normal
			BlendMode.AdditivePremultiplied -> PipelineBlend.Additive
			BlendMode.MultiplyPremultiplied -> PipelineBlend.Multiply
			// Scene draws never reach here with a 5.3 mode (renderPlanNodes routes extended blends
			// through the destination-sampling composite pass); these branches are a safe fallback
			// approximating with the legacy fixed-function analog where one exists - the same
			// approximation the MOC3 constant-flags 2-bit field encodes for old runtimes.
			BlendMode.Additive, BlendMode.AdditiveGlow -> PipelineBlend.Additive
			BlendMode.Multiply -> PipelineBlend.Multiply
			BlendMode.Darken,
			BlendMode.ColorBurn,
			BlendMode.LinearBurn,
			BlendMode.Lighten,
			BlendMode.Screen,
			BlendMode.ColorDodge,
			BlendMode.Overlay,
			BlendMode.SoftLight,
			BlendMode.HardLight,
			BlendMode.LinearLight,
			BlendMode.Hue,
			BlendMode.Color,
			-> PipelineBlend.Normal
		}
}

// Padding (in ON-SCREEN pixels, scaled to framebuffer pixels by gridPixelScale in scissorRectOf) added
// around a composite layer's conservative bounds before it becomes a scissor rect - a small guard so
// bilinear atlas sampling at the mesh edge and float rounding in the world->pixel map can never clip a
// fringe pixel the un-scissored path would keep.
private const val SCISSOR_PAD_PX = 2

/** Resets every field to its "nothing set" default, for reuse across draws. */
private fun FragmentUniforms.reset() {
	useTexture = false
	colorRed = 0f
	colorGreen = 0f
	colorBlue = 0f
	colorAlpha = 0f
	opacity = 1f
	useMask = false
	invertMask = false
	multiplyRed = 1f
	multiplyGreen = 1f
	multiplyBlue = 1f
	screenRed = 0f
	screenGreen = 0f
	screenBlue = 0f
	highlight = 0f
	highlightRed = 0f
	highlightGreen = 0f
	highlightBlue = 0f
	// IDENTITY, not zero.  Every other field here resets to a harmless zero; this one cannot - a zeroed
	// affine maps every texture coordinate onto texel (0, 0), so the whole draw samples one pixel.
	setIdentityUvAffine(uvAffine)
}

/**
 * Writes the identity 2x3 affine into a uv-affine array in place.
 *
 * @param FloatArray affine The six-float affine to overwrite.
 */
private fun setIdentityUvAffine(affine: FloatArray) {
	affine[0] = 1f
	affine[1] = 0f
	affine[2] = 0f
	affine[3] = 0f
	affine[4] = 1f
	affine[5] = 0f
}