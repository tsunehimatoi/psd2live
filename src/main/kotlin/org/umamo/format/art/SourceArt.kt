package org.umamo.format.art

import org.umamo.format.FormatCodec
import org.umamo.format.ReadOnlyCodec
import kotlin.jvm.JvmInline

/**
 * Stable identity of a source-art layer, format-agnostic.
 *
 * `@JvmInline value class` wraps a String with zero runtime overhead (the compiler erases it
 * to a bare String where it can) while making the type distinct - you can't accidentally pass a
 * raw layer name where a layer id is expected. This replaces the "stringly-typed" anti-pattern
 * (`Map<String, …>` keyed by ambiguous strings). The meaning of the wrapped value depends on
 * the source: CLIP supplies Celsys's rename-stable internal id; PSD can only offer a name/path.
 *
 * The `kotlin.jvm.JvmInline` import is load-bearing, not noise: `kotlin.jvm.*` is a default import
 * only on JVM targets, so without it this file stops compiling the moment a non-JVM target
 * (Kotlin/Native, for iPadOS) is added.  The annotation is an optional expectation, so non-JVM
 * targets simply ignore it.
 *
 * ソースレイヤーの安定 ID。value class で型安全かつゼロコスト。
 */
@JvmInline
value class LayerId(val raw: String)

/**
 * Position and size of a layer on the source canvas, in pixels, with a top-left origin (the
 * image/PSD convention). The renderer flips Y when mapping to GL's bottom-left origin.
 * [AlphaAnalysis.opaqueBounds] reuses this shape raster-locally, with left/top relative to the
 * raster's own origin instead of the canvas.
 */
data class LayerBounds(
	val left: Int,
	val top: Int,
	val width: Int,
	val height: Int,
)

/**
 * How a layer composites onto what's beneath it - the full PSD/Clip Studio/Krita blend-mode set.
 *
 * Source art (PSD, CLIP, KRA) shares essentially the same blend-mode vocabulary, so the neutral
 * model carries it verbatim rather than collapsing it.  A renderer that only supports a subset
 * (Cubism drawables are Normal/Add/Multiply) maps the rest down at draw time - that is a render
 * concern, not a reason to discard the source's intent here.  Defaults to [Normal] (source-over).
 * CSP-specific names noted: [GlowDodge] and [AddGlow] are CSP's clamped Color-Dodge/Add variants;
 * [Luminosity] is CSP's "Brightness".
 */
enum class LayerBlend {
	Normal,
	Darken,
	Multiply,
	ColorBurn,
	LinearBurn,
	Subtract,
	DarkerColor,
	Lighten,
	Screen,
	ColorDodge,
	GlowDodge,
	Add,
	AddGlow,
	LighterColor,
	Overlay,
	SoftLight,
	HardLight,
	VividLight,
	LinearLight,
	PinLight,
	HardMix,
	Difference,
	Exclusion,
	Hue,
	Saturation,
	Color,
	Luminosity,
	Divide,
}

/**
 * What kind of source layer this is - chiefly, whether it carries ingestible raster pixels.
 *
 * EN: A raster-ingesting consumer cares most about [Raster] (has decodable pixels) versus everything
 *     else (no stored raster).  The non-raster kinds are still surfaced so a consumer can report or
 *     skip them knowingly rather than treating them as empty raster layers: [Text] and [Vector] are
 *     object layers whose content is regenerated from object data on load (no persisted raster);
 *     [Adjustment] is a procedural correction/filter layer; [Fill] is a paper/solid-fill layer.
 * JA: レイヤー種別。取り込み側はラスタの有無を知る必要がある。非ラスタ種別も識別して通知/スキップできる。
 */
enum class SourceLayerKind {
	Raster,
	Text,
	Vector,
	Adjustment,
	Fill,
	Unknown,
}

/**
 * Which of the four output channels a layer writes when composited; a disabled channel passes
 * through from the content below instead of being written by this layer. Defaults to all enabled,
 * since most sources do not expose per-channel masking. Krita's channelflags populates this; a
 * disabled alpha is the same underlying signal as inherit-alpha clipping (see SourceLayer.clipped).
 *
 * レイヤーが合成時に書き込むチャンネル。無効なチャンネルは下のレイヤーを通す。既定は全て有効。
 */
data class ChannelMask(
	val red: Boolean,
	val green: Boolean,
	val blue: Boolean,
	val alpha: Boolean,
) {
	companion object {
		/** All channels enabled - the default when a source exposes no per-channel masking. */
		val ALL: ChannelMask = ChannelMask(red = true, green = true, blue = true, alpha = true)
	}
}

/**
 * Decoded pixels for one layer: RGBA8888, straight (non-premultiplied) alpha, row-major from
 * the top, sized to the layer's cropped [bounds][SourceLayer.bounds] (not the full canvas).
 *
 * A plain class, not a `data class`: it wraps a large [rgba] buffer for which generated structural
 * equals/hashCode would be a footgun (a deep array compare on every call). Identity equality is the
 * right default here.
 *
 * レイヤーの復号済みピクセル（RGBA8888・ストレートアルファ・上から行優先）。
 */
class LayerRaster(
	val width: Int,
	val height: Int,
	val rgba: ByteArray,
)

/**
 * One layer of ingested source art, neutral across PSD/CLIP/KRA. [id] is the re-import join key;
 * [groupPath] is the slash-joined group hierarchy used as the PSD fallback key when [id] is weak.
 */
interface SourceLayer {
	val id: LayerId
	val name: String
	val groupPath: String

	/**
	 * What kind of layer this is. Defaults to [SourceLayerKind.Raster] - the common case and the only
	 * kind the PSD/KRA readers emit - so only readers that surface non-raster layers (CLIP) override
	 * it. A raster-only consumer ingests [SourceLayerKind.Raster] and skips the rest.
	 */
	val kind: SourceLayerKind get() = SourceLayerKind.Raster

	/**
	 * Whether this layer is shown (the eye toggle). Defaults to true. A hidden layer is still read and
	 * surfaced so the consumer can decide whether to ingest or skip it; a layer can also be effectively
	 * hidden by an ancestor folder being hidden ([SourceGroup.visible]).
	 */
	val visible: Boolean get() = true

	/** Draw order within the source document (top-most first), used to disambiguate fallbacks. */
	val order: Int

	/** Where this layer sits on the source canvas. */
	val bounds: LayerBounds

	/** Layer opacity, 0.0..1.0 (PSD stores 0..255). */
	val opacity: Float

	/**
	 * True if this is a clipping-mask layer: its output is clipped to the alpha of the first
	 * non-clipped ("base") layer below it in the same group (Photoshop "clip to layer below").
	 */
	val clipped: Boolean

	/** Compositing mode against the layers beneath. */
	val blend: LayerBlend

	/**
	 * Which output channels this layer contributes; a disabled channel passes through from below.
	 * Defaults to all enabled, as most sources do not expose per-channel masking.
	 */
	val channelMask: ChannelMask get() = ChannelMask.ALL

	/** Decoded pixels, cropped to [bounds]. */
	val raster: LayerRaster
}

/**
 * A folder/group in the source-art hierarchy, identified by its slash-joined [path] (the same path
 * the enclosed [SourceLayer]s carry in [SourceLayer.groupPath]).
 *
 * The flat model keys layers to their enclosing folders by path string rather than nesting; this
 * carries the per-folder attributes that a flattened leaf list would otherwise lose - chiefly the
 * folder [blend] and [opacity], which composite the whole group and are not equivalent to setting
 * each child layer's blend. [passThrough] marks a folder that does not isolate (CSP "Through"): its
 * children blend straight onto what is below the folder, so [blend] is moot.
 *
 * フォルダ属性（パスで識別）。フォルダのブレンド/不透明度は平坦化で失われるためここで保持する。
 */
interface SourceGroup {
	/** Slash-joined path identifying this folder, matching enclosed layers' [SourceLayer.groupPath]. */
	val path: String
	val name: String

	/** Whether the folder is shown; a hidden folder hides its whole subtree. */
	val visible: Boolean

	/** Folder opacity, 0.0..1.0. */
	val opacity: Float

	/** True if the folder itself is a clipping layer (clips to the layer below it). */
	val clipped: Boolean

	/** How the folder composites onto what is beneath it (ignored when [passThrough] is true). */
	val blend: LayerBlend

	/**
	 * True for a non-isolating "pass-through" folder (Clip Studio's default folder mode): the group's
	 * children composite directly against the layers below the folder rather than within an isolated
	 * group buffer. When true, [blend] does not apply.
	 */
	val passThrough: Boolean
}

/**
 * A parsed source-art document: an ordered set of [SourceLayer]s, the [SourceGroup]s describing the
 * folders that enclose them (keyed by path), plus the document pixel bounds.
 *
 * [groups] defaults to empty so readers that flatten without folder metadata (PSD, KRA) need no
 * change; CLIP overrides it.
 */
interface SourceArt {
	val layers: List<SourceLayer>
	val groups: List<SourceGroup> get() = emptyList()
	val widthPx: Int
	val heightPx: Int
}

/**
 * Effective on-screen visibility of [layer]: its own eye toggle folded together with every ancestor
 * folder's. A layer is shown only when it is itself visible and none of the folders enclosing it is
 * hidden; the enclosing folders are found by walking [SourceLayer.groupPath] upward (a layer in
 * "A/B/C" is hidden if any of "A", "A/B", or "A/B/C" is hidden). With no [groups] - the flat PSD/KRA
 * case, or a document with no folders - this reduces to [SourceLayer.visible].
 *
 * This is the pre-flattened answer for consumers that have no folder hierarchy of their own - the
 * source-art preview compositor, which just draws a flat layer list. It is deliberately NOT what the
 * eventual SourceArt to puppet-model ingest should use: that path keeps the two flags separate
 * (each layer's own [SourceLayer.visible] seeds its drawable's eye toggle, each folder's
 * [SourceGroup.visible] seeds the part the folder becomes) and lets the runtime's part-chain
 * visibility cascade hide a still-"visible" layer whose ancestor part is hidden. Flattening here
 * would discard the per-layer intent the artist may want back when they re-show a folder. Use this
 * for "should I draw this now", not for "what is this layer's stored visibility".
 *
 * @param SourceLayer layer The layer to test; its [SourceLayer.groupPath] locates its folders.
 * @return Boolean Whether the layer should be drawn by a flat (folderless) consumer.
 */
fun SourceArt.isEffectivelyVisible(layer: SourceLayer): Boolean {
	if (!layer.visible) {
		return false
	}
	if (groups.isEmpty() || layer.groupPath.isEmpty()) {
		return true
	}
	val groupByPath = groups.associateBy { group -> group.path }
	var ancestorPath = layer.groupPath
	while (ancestorPath.isNotEmpty()) {
		val ancestor = groupByPath[ancestorPath]
		if (ancestor != null && !ancestor.visible) {
			return false
		}
		val lastSlash = ancestorPath.lastIndexOf('/')
		ancestorPath = if (lastSlash < 0) "" else ancestorPath.substring(0, lastSlash)
	}
	return true
}

/**
 * A [FormatCodec] whose in-memory model is the neutral [SourceArt] - one implementation per art
 * format (PSD, CLIP, KRA). Folding the art readers into [FormatCodec] lets the format registry
 * detect and resolve them exactly like the binary model codecs (CMO3/MOC3), rather than through a
 * separate reader facade. These formats are ingestion-only, so [ReadOnlyCodec] supplies the
 * refusing write - and every art FileKind has writable = false, which advertises that to callers
 * before they try.
 *
 * ソースアートを読む FormatCodec。形式ごとに実装。読み取り専用なので write は常に拒否する。
 */
interface ArtReader : ReadOnlyCodec<SourceArt>