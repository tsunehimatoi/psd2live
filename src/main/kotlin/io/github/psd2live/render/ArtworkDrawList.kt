package io.github.psd2live.render

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.RigCanvasSupport
import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DrawableId

/**
 * One textured mesh of the artwork pass, as both painters draw it.
 *
 * @property page        Atlas page the mesh samples.
 * @property opacity     Final opacity, the drawable's own times the pass alpha and any dimming.
 * @property tintColor   ARGB colour washed over the mesh's triangles after it is drawn, 0 for none.
 * @property tintAlpha   Strength of that wash.
 * @property maskIds     Meshes whose alpha clips this one; an empty list draws it unclipped.
 * @property maskPages   The atlas page of each of [maskIds].
 * @property invertMask  The part shows only outside its masks instead of inside them.
 * @property blendMode   Colour blend with what is under the mesh.
 * @property alphaBlend  Alpha blend with what is under the mesh.
 * @property multiply    Multiply colour, applied to the texels' straight colour before [screen].
 * @property screen      Screen colour.
 */
internal class ArtworkDraw(
	val drawableId: DrawableId,
	val page: Int,
	val opacity: Float,
	val tintColor: Int,
	val tintAlpha: Float,
	val maskIds: List<DrawableId>,
	val maskPages: IntArray = IntArray(0),
	val invertMask: Boolean = false,
	val blendMode: BlendMode = BlendMode.Normal,
	val alphaBlend: AlphaBlendMode = AlphaBlendMode.Over,
	val multiply: ColorRgb = ColorRgb.MultiplyIdentity,
	val screen: ColorRgb = ColorRgb.ScreenIdentity,
) {
	/**
	 * Whether premultiplied fixed-function blending draws the mesh exactly: normal over, and the pre-5.3 add and
	 * multiply under any alpha blend. Every other pair composites against a copy of what is under it.
	 */
	val fixedFunction: Boolean get() = (alphaBlend == AlphaBlendMode.Over && blendMode.isLegacy) || blendMode.ignoresAlphaBlend

	val tinted: Boolean get() = multiply != ColorRgb.MultiplyIdentity || screen != ColorRgb.ScreenIdentity
}

/** How strongly a hovered part is washed with its component colour. */
const val HOVER_TINT_STRENGTH = 0.35f

/** What the artwork pass shows, decided once for both the Skia painter and the GPU renderer. */
data class ArtworkOptions(
	val alpha: Float = 1f,
	val visibleLayerIds: Set<String>? = null,
	val drawOrderOverrides: Map<String, Float> = emptyMap(),
	val dimUnselected: Boolean = false,
	val highlightedLayerIds: Set<String>? = null,
	val dimmedAlphaMultiplier: Float = 0.22f,
	val tintLayerIds: Set<String>? = null,
	val tintColor: Int = 0,
	val tintAlpha: Float = HOVER_TINT_STRENGTH,
)

internal object ArtworkDrawList {
	/** The meshes of [model] at [geometry] to draw, back to front. */
	fun build(model: RigPreviewModel, geometry: DeformedGeometry, options: ArtworkOptions): List<ArtworkDraw> {
		val drawables = model.rig.puppet.drawables.filter { it.mesh != null && it.id in geometry.worldPositions }
			.sortedBy { RigCanvasSupport.displayOrder(model, it, geometry, options.drawOrderOverrides) }
		val byId = model.rig.puppet.drawables.associateBy { it.id }
		val visible = options.visibleLayerIds
		val out = ArrayList<ArtworkDraw>(drawables.size)
		for (drawable in drawables) {
			val layerId = model.rig.layerIdByDrawableId[drawable.id.raw]
			// A reference layer's quad has no atlas tile: the canvas draws its pixels on their own.
			if (layerId != null && layerId in model.referenceLayerIds) continue
			if (visible != null && layerId != null && layerId !in visible) continue
			if (visible != null && layerId == null && drawable.id.raw !in visible && !drawable.isVisible) continue
			val highlighted = options.highlightedLayerIds == null ||
				(layerId != null && layerId in options.highlightedLayerIds) || drawable.id.raw in options.highlightedLayerIds
			val dim = if (options.dimUnselected && !highlighted) options.dimmedAlphaMultiplier else 1f
			val opacity = ((geometry.opacity[drawable.id] ?: drawable.opacity) * options.alpha * dim).coerceIn(0f, 1f)
			if (opacity <= 0.001f) continue
			val page = model.rig.pageByDrawableId[drawable.id.raw] ?: drawable.texturePage
			val tinted = options.tintColor != 0 && options.tintLayerIds != null &&
				((layerId != null && layerId in options.tintLayerIds) || drawable.id.raw in options.tintLayerIds)
			val masks = if (drawable.maskedBy.isEmpty()) emptyList()
				else drawable.maskedBy.filter { id ->
					val source = byId[id]
					source != null && source.isVisible && source.mesh != null && id in geometry.worldPositions
				}
			val maskPages = IntArray(masks.size) { model.rig.pageByDrawableId[masks[it].raw] ?: byId.getValue(masks[it]).texturePage }
			out += ArtworkDraw(drawable.id, page, opacity, if (tinted) options.tintColor else 0, options.tintAlpha, masks, maskPages,
				invertMask = masks.isNotEmpty() && drawable.invertMask, blendMode = drawable.blendMode, alphaBlend = drawable.alphaBlendMode,
				multiply = geometry.multiplyColor[drawable.id] ?: ColorRgb.MultiplyIdentity,
				screen = geometry.screenColor[drawable.id] ?: ColorRgb.ScreenIdentity)
		}
		return out
	}
}
