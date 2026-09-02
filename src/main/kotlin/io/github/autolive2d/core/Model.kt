package io.github.autolive2d.core

import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
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

data class LayerSemantic(
	val tag: SemanticTag,
	val side: Side = Side.NONE,
	val variant: Int? = null,
	val normalizedName: String,
	val confidence: Float,
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

data class PipelineConfig(
	val atlasSize: Int = 4096,
	val texturePadding: Int = 2,
	val meshSpacing: Int = 64,
	val alphaThreshold: Int = 8,
	val headTurnStrength: Float = 1f,
	val bodyStrength: Float = 1f,
	val meshOnly: Boolean = false,
	val generateDeformers: Boolean = true,
	val exportMotions: Boolean = true,
	val motionIdle: Boolean = true,
	val motionBlink: Boolean = true,
	val motionNod: Boolean = true,
	val motionShake: Boolean = true,
	val generatePhysics: Boolean = true,
	val physicsFrontHair: Boolean = true,
	val physicsBackHair: Boolean = true,
	val physicsEyeJelly: Boolean = true,
	val exportCmo3: Boolean = true,
	val exportMoc3: Boolean = true,
	val exportJson: Boolean = true,
	/** Manual UI corrections, keyed by the stable source/virtual-layer id. */
	val layerOverrides: Map<String, LayerClassificationOverride> = emptyMap(),
	/** Photoshop-style layer-eye overrides; omitted entries retain their PSD visibility. */
	val layerVisibility: Map<String, Boolean> = emptyMap(),
)

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

	fun encodePreviewBundle(): ByteArray = ByteArrayOutputStream().use { output ->
		output.write("QDPREVIEW".encodeToByteArray())
		output.writeLittleEndian(1, Int.SIZE_BYTES)
		val manifest = manifestPath.encodeToByteArray()
		output.writeLittleEndian(manifest.size.toLong(), Int.SIZE_BYTES)
		output.writeLittleEndian(assets.size.toLong(), Int.SIZE_BYTES)
		output.write(manifest)
		for (asset in assets) {
			val path = asset.path.encodeToByteArray()
			output.writeLittleEndian(path.size.toLong(), Int.SIZE_BYTES)
			output.writeLittleEndian(asset.bytes.size.toLong(), Long.SIZE_BYTES)
			output.write(path)
			output.write(asset.bytes)
		}
		output.toByteArray()
	}

	private fun ByteArrayOutputStream.writeLittleEndian(value: Long, byteCount: Int) {
		for (index in 0 until byteCount) write((value ushr (index * 8)).toInt() and 0xff)
	}
}

data class LayerClassificationOverride(
	val tag: SemanticTag,
	val side: Side,
)

data class AtlasPlacement(
	val page: Int,
	val x: Int,
	val y: Int,
	val width: Int,
	val height: Int,
)

data class AtlasPage(val image: BufferedImage, val png: ByteArray)

data class PackedAtlas(
	val pages: List<AtlasPage>,
	val placementByLayerId: Map<String, AtlasPlacement>,
)

data class PipelineAnalysis(
	val source: SourceArt,
	val layers: List<ClassifiedLayer>,
	val anchors: RigAnchors,
	val warnings: List<String>,
	val preview: BufferedImage,
)

/** The exact atlas and rig shown by the workbench before export. */
data class RigPreviewModel(
	val analysis: PipelineAnalysis,
	val atlas: PackedAtlas,
	val rig: BuiltRig,
	val config: PipelineConfig,
	val runtimeBundle: CubismRuntimeBundle,
)

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
