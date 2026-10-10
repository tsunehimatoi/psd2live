package io.github.psd2live.core

import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import java.awt.image.BufferedImage
import java.nio.file.Path

enum class SemanticTag(val canonicalName: String, val group: LayerGroup) {
	BACK_HAIR("back hair", LayerGroup.HEAD),
	FRONT_HAIR("front hair", LayerGroup.HEAD),
	HEADWEAR("headwear", LayerGroup.HEAD),
	FACE("face", LayerGroup.HEAD),
	FACE_DETAIL("facedetail", LayerGroup.HEAD),
	IRIDES("irides", LayerGroup.HEAD),
	EYEBROW("eyebrow", LayerGroup.HEAD),
	EYEWHITE("eyewhite", LayerGroup.HEAD),
	EYELASH("eyelash", LayerGroup.HEAD),
	EYE_CLOSE("eye_close", LayerGroup.HEAD),
	EYEWEAR("eyewear", LayerGroup.HEAD),
	EARS("ears", LayerGroup.HEAD),
	EARWEAR("earwear", LayerGroup.HEAD),
	NOSE("nose", LayerGroup.HEAD),
	MOUTH("mouth", LayerGroup.HEAD),
	MOUTH_OPEN("mouth_open", LayerGroup.HEAD),
	MOUTH_CLOSE("mouth_close", LayerGroup.HEAD),
	TOOTH_T("tooth-t", LayerGroup.HEAD),
	TOOTH_B("tooth-b", LayerGroup.HEAD),
	TONGUE("tongue", LayerGroup.HEAD),
	NECK("neck", LayerGroup.BODY),
	NECKWEAR("neckwear", LayerGroup.BODY),
	TOPWEAR("topwear", LayerGroup.BODY),
	HANDWEAR("handwear", LayerGroup.BODY),
	BOTTOMWEAR("bottomwear", LayerGroup.BODY),
	LEGWEAR("legwear", LayerGroup.BODY),
	FOOTWEAR("footwear", LayerGroup.BODY),
	TAIL("tail", LayerGroup.EXTRA),
	WINGS("wings", LayerGroup.EXTRA),
	OBJECTS("objects", LayerGroup.EXTRA),
	UNKNOWN("unknown", LayerGroup.UNKNOWN),
}

enum class LayerGroup { HEAD, BODY, EXTRA, UNKNOWN }

enum class Side { LEFT, RIGHT, NONE }

enum class LayerType { PRESET, TOGGLE, SWITCH }

data class LayerSemantic(
	val tag: SemanticTag,
	val side: Side = Side.NONE,
	val variant: Int? = null,
	val normalizedName: String,
	val confidence: Float,
	val type: LayerType = LayerType.PRESET,
	val parameter: String = "",
	val switchId: Int = 0,
)

data class ClassifiedLayer(
	val source: SourceLayer,
	val semantic: LayerSemantic,
	val bounds: Bounds,
	val centroidX: Float,
	val centroidY: Float,
	val opaquePixels: Int,
)

data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
	val width: Float get() = right - left
	val height: Float get() = bottom - top
	val centerX: Float get() = (left + right) * 0.5f
	val centerY: Float get() = (top + bottom) * 0.5f

	fun union(other: Bounds): Bounds =
		Bounds(
			minOf(left, other.left),
			minOf(top, other.top),
			maxOf(right, other.right),
			maxOf(bottom, other.bottom),
		)

	fun expanded(fraction: Float): Bounds {
		val dx = width * fraction
		val dy = height * fraction
		return Bounds(left - dx, top - dy, right + dx, bottom + dy)
	}
}

data class RigAnchors(
	val character: Bounds,
	val face: Bounds,
	val body: Bounds,
	val faceCenterX: Float,
	val faceCenterY: Float,
	val chinX: Float,
	val chinY: Float,
	val shoulderY: Float,
	val hipY: Float,
)

enum class MeshFillAlgorithm { GRADED_POISSON, ADAPTIVE_QUADTREE, SIMPLE_TRIANGLES, TRIANGLE_FRACTAL, CONTOUR_PAVING }
enum class MeshEdgeMode { SINGLE, DOUBLE, TRIPLE }

/** What the lengths in [MeshSettings] are measured in. */
enum class MeshUnits {
	/** Source pixels, as projects saved before mesh units: detail and cost grow with the document's resolution. */
	PIXELS,
	/**
	 * Pixels of a document [MeshResolution.REFERENCE_SIDE] long: the same settings give the same mesh at any
	 * resolution, and larger documents are meshed from a reduced raster.
	 */
	DOCUMENT,
}

/** What a layer's mesh outline is traced from ([MeshResolution.input]). */
enum class MeshTrace {
	/**
	 * The layer resampled to one pixel per canvas unit, traced at the mesh unit, as projects saved before the
	 * texture trace: a raster denser than its canvas rectangle is averaged down first, so a stroke thinner than a
	 * canvas unit can fade below the alpha threshold and fall outside the mesh.
	 */
	CANVAS,
	/**
	 * The layer's own pixels, their alpha reduced by maximum (so nothing painted is lost) to a working
	 * resolution up to [MeshResolution.MAX_DETAIL] times finer than a mesh unit: the outline follows the drawn
	 * edge while vertex spacing and every tolerance stay in mesh units.
	 */
	TEXTURE,
}

/**
 * [edgeRatio]: first interior spacing over the contour spacing, so the fill never repeats the contour row.
 * [gradation]: spacing growth per pixel of depth, up to the interior density.
 */
@kotlinx.serialization.Serializable
data class PoissonFillParameters(val edgeRatio: Float = 2f, val gradation: Float = 1f, val jitter: Float = 0.45f)

/** [angle]: lattice orientation in degrees. */
@kotlinx.serialization.Serializable
data class LatticeFillParameters(val edgeRatio: Float = 2f, val gradation: Float = 1f, val angle: Float = 0f)

/** [maxRows]: contour-parallel rows before the center switches to a graded lattice. */
@kotlinx.serialization.Serializable
data class PavingFillParameters(val edgeRatio: Float = 2f, val gradation: Float = 1f, val maxRows: Int = 12)

/** UI ranges of the fill parameters; the generator clamps to the same ranges. */
object MeshFillRanges {
	val edgeRatio = 1f..4f
	val gradation = 0.25f..4f
	val jitter = 0f..1f
	val angle = 0f..90f
	val maxRows = 0..24
}

/** Per-algorithm controls; each algorithm reads only its own group, so switching keeps every group. */
@kotlinx.serialization.Serializable
data class MeshFillParameters(
	val poisson: PoissonFillParameters = PoissonFillParameters(),
	val quadtree: LatticeFillParameters = LatticeFillParameters(),
	val fractal: LatticeFillParameters = LatticeFillParameters(gradation = 2f),
	val paving: PavingFillParameters = PavingFillParameters(),
)

data class MeshSettings(
	val outerMargin: Float = 1.0f,
	val edgeMode: MeshEdgeMode = MeshEdgeMode.SINGLE,
	val edgeWidth: Float = 10.0f,
	val maxEdgeDistance: Float = 6.0f,
	val interiorDensity: Float = 40.0f,
	val fillAlgorithm: MeshFillAlgorithm = MeshFillAlgorithm.GRADED_POISSON,
	val suppressBoundaryDiagonals: Boolean = false,
	val fillParameters: MeshFillParameters = MeshFillParameters(),
	/**
	 * Gaps and notches narrower than this many mesh units are wrapped into the outline ([MeshWrap]): fine
	 * protrusions such as lashes or strand tips share one envelope instead of an outline each. 0 traces the
	 * drawn edge as is.
	 */
	val wrap: Float = 0f,
)

data class PipelineConfig(
	val atlasSize: Int = 4096,
	val textureUpscale: TextureUpscaleConfig = TextureUpscaleConfig(),
	val texturePadding: Int = 2,
	val meshSpacing: Int = 40,
	val meshOuterMargin: Float = 1.0f,
	val meshEdgeMode: MeshEdgeMode = MeshEdgeMode.SINGLE,
	val meshEdgeWidth: Float = 10.0f,
	val meshMaxEdgeDistance: Float = 6.0f,
	val meshInteriorDensity: Float = 40.0f,
	val meshFillAlgorithm: MeshFillAlgorithm = MeshFillAlgorithm.GRADED_POISSON,
	val meshSuppressBoundaryDiagonals: Boolean = false,
	val meshFillParameters: MeshFillParameters = MeshFillParameters(),
	val meshOverrides: Map<String, MeshSettings> = emptyMap(),
	/** The unit of every mesh length above and of [meshOverrides]. */
	val meshUnits: MeshUnits = MeshUnits.DOCUMENT,
	/** What mesh outlines are traced from; projects saved before it trace the canvas view. */
	val meshTrace: MeshTrace = MeshTrace.TEXTURE,
	/** [MeshSettings.wrap] of every layer without an override; projects saved before it wrap nothing. */
	val meshWrap: Float = 0f,
	val alphaThreshold: Int = 8,
	val headTurnStrength: Float = 1f,
	val bodyStrength: Float = 1f,
	/** How far the body parameters move the body at their full values (see [RigTuning]). */
	val rigTuning: RigTuning = RigTuning(),
	val meshOnly: Boolean = false,
	val generateDeformers: Boolean = true,
	val featureDisplacementEnabled: Boolean = false,
	val mouthOutlineEnabled: Boolean = true,
	val mouthShape: String = "smile",
    val mouthCurve: io.github.psd2live.core.MouthCurve = io.github.psd2live.core.MouthCurve.preset("smile"),
    val mouthColor: Int? = null,
    val mouthThickness: Float = 1.5f,
	val exportMotions: Boolean = true,
	/** The basic motions (idle, blink, nod, shake) as a group: off, the model has none of them. */
	val motionBasic: Boolean = true,
	val motionIdle: Boolean = true,
	val motionBlink: Boolean = true,
	val motionNod: Boolean = true,
	val motionShake: Boolean = true,
	/**
	 * The skeleton presets as a group: off, the model has none of them. On, each is exported when the skeleton
	 * can play it and it is not switched off ([MotionPresetSettings.disabled]).
	 */
	val motionSkeleton: Boolean = true,
	val generatePhysics: Boolean = true,
	val physicsFrontHair: Boolean = true,
	val physicsBackHair: Boolean = true,
	val physicsEyeJelly: Boolean = true,
	/** The hair model preset simulates this hair: the legacy sway warp, parameter and pendulum are not built. */
	val hairSimulationFront: Boolean = false,
	val hairSimulationBack: Boolean = false,
	val exportCmo3: Boolean = true,
	val exportMoc3: Boolean = true,
	val exportJson: Boolean = true,
	/**
	 * Cubism SDK / model-target version written into MOC3 and CMO3.
	 * Defaults to Cubism 5.0 to match the generated-rig baseline.
	 */
	val runtimeTarget: org.umamo.runtime.model.RuntimeTarget = org.umamo.runtime.model.RuntimeTarget.Cubism50,
	/**
	 * MOC3 bake options. Defaults follow the official editor's export dialog (hidden / guide
	 * objects dropped) rather than [org.umamo.interop.moc3.Moc3ExportOptions.Default].
	 */
	val exportHiddenParts: Boolean = false,
	val exportHiddenDrawables: Boolean = false,
	val exportGuideImageParts: Boolean = false,
	val exportIncludePhysics: Boolean = true,
	val exportIncludeUserData: Boolean = true,
	val exportIncludeDisplayInfo: Boolean = true,
	/** Bake scale override; null resolves from the model (recorded scale, else canvas width). */
	val exportPixelsPerUnit: Float? = null,
	/** Manual UI corrections, keyed by the stable source/virtual-layer id. */
	val layerOverrides: Map<String, LayerClassificationOverride> = emptyMap(),
	/** Photoshop-style layer-eye overrides; omitted entries retain their PSD visibility. */
	val layerVisibility: Map<String, Boolean> = emptyMap(),
	/** Layers deleted by the user, excluded from atlas packing and rigging. */
	val deletedLayerIds: Set<String> = emptySet(),
	/** Manual parent deformer overrides (layerId or deformerId -> parentDeformerId or null for root). */
	val parentOverrides: Map<String, String?> = emptyMap(),
	/** Manual draw order overrides (layerId or drawableId -> drawOrder within 0..1000). */
	val drawOrderOverrides: Map<String, Float> = emptyMap(),
	/** Durable Agent/editor changes replayed over every generated base rig and retained on export. */
	val rigEdits: RigEditOverlay = RigEditOverlay.Empty,
	/** Optional immutable generation input; textures still come from the current source artwork. */
	val generationSource: org.umamo.format.art.SourceArt? = null,
	val meshSource: org.umamo.format.art.SourceArt? = null,
	/** The stored atlas budget (`atlas` setting); null keeps [atlasSize] and [texturePadding] with the default page count. */
	val atlasBudget: AtlasBudget? = null,
	/** Per-layer texture density, lock and pin by source layer ID ([AtlasLayout]). */
	val textureOverrides: Map<String, io.github.psd2live.project.TextureOverride> = emptyMap(),
	/** The stored atlas layout (`atlasArrangement` setting); null arranges the atlas automatically on every build. */
	val atlasArrangement: io.github.psd2live.project.AtlasArrangement? = null,
) {
	/** The budget the atlas is packed within: [atlasBudget], else the legacy page size and padding with the default page count. */
	fun effectiveAtlasBudget(): AtlasBudget = atlasBudget ?: AtlasBudget(atlasSize.coerceAtLeast(1), AtlasBudget.DEFAULT_MAX_PAGES, texturePadding.coerceAtLeast(0))

	fun moc3ExportOptions(): org.umamo.interop.moc3.Moc3ExportOptions =
		org.umamo.interop.moc3.Moc3ExportOptions(
			exportHiddenParts = exportHiddenParts,
			exportHiddenDrawables = exportHiddenDrawables,
			exportGuideImageParts = exportGuideImageParts,
			includePhysics = exportIncludePhysics,
			includeUserData = exportIncludeUserData,
			includeDisplayInfo = exportIncludeDisplayInfo,
			pixelsPerUnitOverride = exportPixelsPerUnit?.takeIf { it > 0f },
		)
}

/** One file from the MOC3 family consumed by the official Cubism runtime preview. */
data class CubismRuntimeAsset(val path: String, val bytes: ByteArray)

/**
 * The exact runtime family prepared for export.  [encodePreviewBundle] only adds a small transport
 * envelope; every embedded byte is the same byte later written to the output directory.
 */
data class CubismRuntimeBundle(
	val manifestPath: String,
	val assets: List<CubismRuntimeAsset>,
) {
	init {
		require(manifestPath.isNotBlank()) { "Cubism preview manifest path is blank" }
		require(assets.any { it.path == manifestPath }) { "Cubism preview manifest is missing: $manifestPath" }
	}

}

data class LayerClassificationOverride(
	val type: LayerType = LayerType.PRESET,
	val tag: SemanticTag = SemanticTag.UNKNOWN,
	val side: Side = Side.NONE,
	val parameter: String = "",
	val switchId: Int = 0,
) {
	constructor(tag: SemanticTag, side: Side) : this(
		type = LayerType.PRESET,
		tag = tag,
		side = side,
		parameter = "",
		switchId = 0,
	)
}

/**
 * Where one layer's raster sits on an atlas page. [x], [y], [width] and [height] are texture pixels
 * on page [page]; [scaleX] and [scaleY] are texture pixels per raster pixel of the layer, so an upright
 * tile maps raster point `(rx, ry)` to page pixel `(x + rx * scaleX, y + ry * scaleY)`. A tile turned by
 * [rotation] turns that rectangle about its centre ([toPage]); [x]..[x] + [width] is then the upright
 * rectangle, not the cells it covers.
 *
 * Placements live only in memory: the atlas is repacked from the source art on every rebuild, and
 * exports convert them to the engine's own placement.
 */
data class AtlasPlacement(
	val page: Int,
	val x: Int,
	val y: Int,
	val width: Int,
	val height: Int,
	/** Texture pixels per layer raster pixel, horizontally. */
	val scaleX: Float = 1f,
	/** Texture pixels per layer raster pixel, vertically. */
	val scaleY: Float = 1f,
	/** Degrees the tile turns about its centre, counter-clockwise as Umamo's placements count them (y down). */
	val rotation: Float = 0f,
) {
	/** Page pixel of raster point ([rx], [ry]). */
	fun toPage(rx: Float, ry: Float): FloatArray = TileTurn.toPage(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), rotation,
		rx * scaleX, ry * scaleY)

	/** Raster point of page pixel ([px], [py]). */
	fun toRaster(px: Float, py: Float): FloatArray = TileTurn.toTile(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), rotation, px, py)
		.also { it[0] /= scaleX; it[1] /= scaleY }
}

/**
 * The turn of a tile about its centre, shared by placements, the atlas view and the page compositor: a tile
 * whose upright rectangle is ([x], [y], [width], [height]) shows tile point (tx, ty) - texture pixels from its
 * upright top left - at page pixel `centre + R(rotation) * ((tx, ty) - size / 2)`, with R as Umamo turns
 * (`x' = cos x - sin y`, `y' = sin x + cos y`, y down).
 */
object TileTurn {
	fun toPage(x: Float, y: Float, width: Float, height: Float, rotation: Float, tx: Float, ty: Float): FloatArray {
		val cx = x + width / 2f; val cy = y + height / 2f
		if (rotation == 0f) return floatArrayOf(x + tx, y + ty)
		val r = Math.toRadians(rotation.toDouble()); val c = kotlin.math.cos(r).toFloat(); val s = kotlin.math.sin(r).toFloat()
		val dx = tx - width / 2f; val dy = ty - height / 2f
		return floatArrayOf(cx + c * dx - s * dy, cy + s * dx + c * dy)
	}

	fun toTile(x: Float, y: Float, width: Float, height: Float, rotation: Float, px: Float, py: Float): FloatArray {
		if (rotation == 0f) return floatArrayOf(px - x, py - y)
		val cx = x + width / 2f; val cy = y + height / 2f
		val r = Math.toRadians(rotation.toDouble()); val c = kotlin.math.cos(r).toFloat(); val s = kotlin.math.sin(r).toFloat()
		val dx = px - cx; val dy = py - cy
		return floatArrayOf(c * dx + s * dy + width / 2f, -s * dx + c * dy + height / 2f)
	}

	/** The four page corners of the turned rectangle, top left, top right, bottom right, bottom left (x, y each). */
	fun corners(x: Float, y: Float, width: Float, height: Float, rotation: Float): FloatArray {
		val out = FloatArray(8)
		for ((k, corner) in listOf(0f to 0f, width to 0f, width to height, 0f to height).withIndex()) {
			val p = toPage(x, y, width, height, rotation, corner.first, corner.second)
			out[k * 2] = p[0]; out[k * 2 + 1] = p[1]
		}
		return out
	}

	/** The page box (left, top, right, bottom) the turned rectangle covers. */
	fun bounds(x: Float, y: Float, width: Float, height: Float, rotation: Float): FloatArray {
		if (rotation == 0f) return floatArrayOf(x, y, x + width, y + height)
		val c = corners(x, y, width, height, rotation)
		return floatArrayOf(minOf(c[0], c[2], c[4], c[6]), minOf(c[1], c[3], c[5], c[7]), maxOf(c[0], c[2], c[4], c[6]), maxOf(c[1], c[3], c[5], c[7]))
	}
}

/**
 * One atlas page: its pixels and two lazily made PNG encodings of them. [png] is the canonical encoding
 * every export writes; [previewPng] encodes the same pixels in independently compressed row strips, so a
 * page that differs from [base] only in some rows re-encodes just those ([AtlasPagePng]). The image must
 * not be modified once the page exists. Equality is identity, as the encodings are memoized on it.
 */
class AtlasPage private constructor(
	val image: BufferedImage,
	private val encoded: ByteArray?,
	@Volatile private var base: AtlasPage?,
	@Volatile private var dirtyRows: java.util.BitSet?,
	/** What [AtlasLayout] composed this page from, or null for a page made otherwise. */
	internal val recipe: AtlasLayout.Recipe? = null,
) {
	/** A page whose canonical encoding is already known, as an imported model's pages are. */
	constructor(image: BufferedImage, png: ByteArray) : this(image, png, null, null)

	/** A page encoded on first use. */
	constructor(image: BufferedImage) : this(image, null, null, null)

	/** The canonical PNG (ImageIO), byte for byte what exports have always written. */
	val png: ByteArray by lazy { encoded ?: AtlasPagePng.canonical(image) }

	private val stripEncoding = lazy {
		val reused = base?.takeIf { it.image.width == image.width && it.image.height == image.height }?.strips
		AtlasPagePng.strips(image, reused, dirtyRows).also {
			// Strips are all a derived page needs from its base: drop it, or every page would keep its history alive.
			base = null; dirtyRows = null
		}
	}

	internal val strips: AtlasPagePng.Strips by stripEncoding

	/** The same pixels as a quickly made, strip-wise PNG for the editor's runtime bundle. */
	val previewPng: ByteArray by lazy { encoded ?: AtlasPagePng.assemble(image.width, image.height, strips) }

	/** This page with [previewPng] as its PNG, for the preview bundle. One instance per page, so bytes keep their identity. */
	internal val forPreview: AtlasPage by lazy { if (encoded != null) this else AtlasPage(image, previewPng, null, null) }

	companion object {
		/**
		 * A page composed from [recipe] whose pixels equal [base]'s outside [dirtyRows]: its preview encoding
		 * reuses [base]'s strips there, if [base] has encoded them (a base that never needed them is not kept).
		 * [dirtyRows] must cover every row whose pixels differ.
		 */
		internal fun composed(image: BufferedImage, recipe: AtlasLayout.Recipe, base: AtlasPage?, dirtyRows: java.util.BitSet?): AtlasPage {
			val reusable = base?.takeIf { it.stripEncoding.isInitialized() && dirtyRows != null }
			return AtlasPage(image, null, reusable, dirtyRows.takeIf { reusable != null }, recipe)
		}
	}
}

data class PackedAtlas(
	val pages: List<AtlasPage>,
	val placementByLayerId: Map<String, AtlasPlacement>,
	/** The common scale of every unlocked tile ([AtlasLayout]): 1 unless the budget forced the textures smaller. */
	val fit: Float = 1f,
	/** Why the layout departs from the request: a fit below 1, locks or pins that did not fit, pages beyond the budget. Logged only. */
	val notices: List<String> = emptyList(),
	/** The mesh footprints the stored arrangement gave tiles; a tile without one owns its whole rectangle. */
	val footprints: Map<String, io.github.psd2live.project.TextureFootprint> = emptyMap(),
	/** Whether the layout is a stored arrangement rather than the automatic one. */
	val arranged: Boolean = false,
) {
	/** These pages with their preview encodings, for the editor's runtime bundle. */
	internal fun forPreview(): PackedAtlas = copy(pages = pages.map { it.forPreview })
}

data class PipelineAnalysis(
	val source: SourceArt,
	val layers: List<ClassifiedLayer>,
	val anchors: RigAnchors,
	val warnings: List<String>,
	val preview: BufferedImage,
    val calibration: PipelineAnalysis? = null,
)

/** The exact atlas and rig shown by the workbench before export. */
data class RigPreviewModel(
	val analysis: PipelineAnalysis,
	val atlas: PackedAtlas,
	val rig: BuiltRig,
	val config: PipelineConfig,
	val runtimeBundle: CubismRuntimeBundle,
	/** Where [baseRig] and [authored] come from: one is known, the other derived on first use. */
	val sources: PreviewRigSources = PreviewRigSources.of(rig),
	/**
	 * The atlas [baseRig] is bound to when it is not [atlas]: the generation packs deleted layers (after a
	 * deferred deletion) and superseded ones (the originals of a split) too, while [atlas] packs only the layers
	 * the model shows ([RigLayerDeletion.compact]).
	 */
	val generationAtlas: PackedAtlas? = null,
) {
	/**
	 * The generated rig the document's edits replay onto. A model built from a stored authored rig generates it only
	 * when something asks for it.
	 */
	val baseRig: BuiltRig get() = sources.base

	/** The authored state of [config]'s edits ([AuthoredRig]): stored, or replayed from [baseRig]. */
	internal val authored: AuthoredRig get() = sources.authored(config.rigEdits)

	/**
	 * The authored rig of [overlay] - [config]'s edits with entries appended or rewritten, or its swings and simulations
	 * changed - bound to [authored]'s atlas, without generating the base: [authored] with the added entries
	 * ([RigEditOverlay.appendedTo]), else the journal's checkpoint and the entries after it
	 * ([RigEditOverlay.authoredFromCheckpoint]). Null when neither gives it: a document without a checkpoint, or one whose
	 * entries after it need the base.
	 */
	internal fun authoredWithoutBase(overlay: RigEditOverlay): AuthoredRig? {
		val authored = authored
		overlay.appendedTo(config.rigEdits, authored)?.let { return it }
		val (stored, _) = overlay.authoredFromCheckpoint() ?: return null
		return stored.reboundTo(authored.rig.puppet.atlas, authored.rig.puppet.sources)
	}

	/** [authoredWithoutBase]'s puppet, else [overlay] replayed on [baseRig]. */
	internal fun authoredPuppet(overlay: RigEditOverlay): org.umamo.runtime.model.PuppetModel =
		authoredWithoutBase(overlay)?.rig?.puppet ?: overlay.replayAuthored(baseRig.puppet, primitiveSkinsOf(overlay)).model

	private fun primitiveSkinsOf(overlay: RigEditOverlay): PrimitiveSkins =
		if (overlay.authoringJournal.none(ArtPrimitiveJournal::isRecord)) PrimitiveSkins.None else baseRig.primitiveSkins

	/**
	 * The split parts [baseRig] holds for the journal's `art_primitive` records ([BuiltRig.primitiveSkins]); none, without
	 * generating the base, when the journal has no such record.
	 */
	internal val primitiveSkins: PrimitiveSkins
		get() = if (config.rigEdits.authoringJournal.none(ArtPrimitiveJournal::isRecord)) PrimitiveSkins.None else baseRig.primitiveSkins

	/** True only when this exact preview bundle contains an active Cubism physics sidecar. */
	val hasRuntimePhysics: Boolean
		get() = runtimeBundle.assets.any { it.path.endsWith(".physics3.json", ignoreCase = true) }
}

data class ExportedFile(val path: Path, val bytes: Long)

data class PipelineResult(
	val analysis: PipelineAnalysis,
	val exportedFiles: List<ExportedFile>,
	val warnings: List<String>,
	val previewModel: RigPreviewModel,
)

fun interface ProgressListener {
	fun update(stage: String, fraction: Double)
}

/**
 * The base and authored rigs of one preview. A rig built by generation knows its base and replays the authored state
 * from it (the replay checkpoints make that a lookup); a rig built from a stored authored state knows that state for
 * [storedOverlay] and generates its base only on demand.
 */
class PreviewRigSources private constructor(
	private val baseSource: Lazy<BuiltRig>,
	private val storedOverlay: RigEditOverlay?,
	private val stored: AuthoredRig?,
	/** What binding the base reads of its atlas ([PSD2LivePipeline.materializedPreview]); null when not known. */
	val bindingKey: String?,
) {
	val base: BuiltRig by baseSource

	/** Whether [base] is already at hand, so asking for it costs nothing. */
	val baseKnown: Boolean get() = baseSource.isInitialized()

	@Volatile private var replayed: Pair<RigEditOverlay, AuthoredRig>? = null

	internal fun authored(overlay: RigEditOverlay): AuthoredRig {
		if (stored != null && (overlay === storedOverlay || overlay == storedOverlay)) return stored
		replayed?.let { (key, value) -> if (key === overlay || key == overlay) return value }
		return base.authoredRig(overlay).also { replayed = overlay to it }
	}

	companion object {
		fun of(base: BuiltRig, bindingKey: String? = null) = PreviewRigSources(lazyOf(base), null, null, bindingKey)

		/** [authored] is [overlay]'s authored state; [base] generates the rig it came from when asked. */
		internal fun materialized(overlay: RigEditOverlay, authored: AuthoredRig, bindingKey: String?, base: () -> BuiltRig) =
			PreviewRigSources(lazy(base), overlay, authored, bindingKey)
	}
}
