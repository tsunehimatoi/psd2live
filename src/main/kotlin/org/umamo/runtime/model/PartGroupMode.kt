package org.umamo.runtime.model

/**
 * How a part groups its subtree for rendering.  Three levels of one grouping mode - Cubism models
 * these as two entangled checkboxes ("Group by Draw Order" and "offscreen drawing", the latter
 * forcing the former on); Umamo folds them into a single sealed type so the illegal combination is
 * unrepresentable and the Cubism naming stops at the CMO3/MOC3 import boundary.
 */
sealed interface PartGroupMode {
	/**
	 * Transparent to draw order - the subtree's drawables stack individually with the enclosing
	 * group's.  (Cubism: both checkboxes off.)
	 */
	data object PassThrough : PartGroupMode

	/**
	 * The whole subtree takes one slot in the parent's stacking and orders internally among
	 * itself.  (Cubism "Group by Draw Order"; CMO3 CPartSource.enableDrawOrderGroup.)
	 */
	data object Grouped : PartGroupMode

	/**
	 * Grouped, and additionally rendered to its own layer and composited back into the scene as one
	 * unit per the part's [Part.composite] settings.  Payload-free: the compositing settings live on the
	 * part (latent), not on the mode, so they survive the part leaving and re-entering Isolated.  (Cubism
	 * 5.3 "offscreen drawing"; CMO3 CPartSource.useOffscreen; MOC3 §5.6 sections 152-163.)
	 */
	data object Isolated : PartGroupMode
}

/**
 * A part's latent compositing settings: how the subtree's rendered layer blends back into the scene
 * when the part's group mode is [PartGroupMode.Isolated].  Stored on [Part.composite] independent of the
 * mode, so the settings survive the part leaving and re-entering Isolated (and the UMA native format can
 * track them).  The keyformed composite channels (opacity, multiply/screen colors) ride the part's
 * [Part.channelGrids]; the static fallbacks here apply when that grid is absent, mirroring the
 * [Part.drawOrder] + grid pattern.  (Cubism 5.3 "offscreen drawing" - CMO3
 * `CPartSource.useOffscreen` and friends; MOC3 §5.6 sections 152-163.)
 */
data class PartComposite(
	/** The composite's color blend mode. (CMO3 colorComposition; MOC3 s157 colorMode.) */
	val blendMode: BlendMode = BlendMode.Normal,
	/** The composite's alpha blend mode. (CMO3 alphaComposition; MOC3 s157 alphaMode.) */
	val alphaBlendMode: AlphaBlendMode = AlphaBlendMode.Over,
	/**
	 * Drawables whose alpha clips the whole composite (Cubism clipping masks at the part level).
	 * Always drawables - a part chosen as Clipping ID in the editor is expanded to its constituent
	 * drawables at authoring time. (CMO3 clipGuidList; MOC3 offscreen mask prefix.)
	 */
	val maskedBy: List<DrawableId> = emptyList(),
	/**
	 * Parts whose descendant drawables also clip the composite - an Umamo extension over the Cubism data
	 * model, which stores only drawable ids.  Keeping the part reference (rather than expanding it at
	 * authoring time, as Cubism does) means the mask follows the part as its children change.  The render
	 * tree sees the expansion, never these ids: [PuppetModel.deriveRenderRoot] resolves each part to its
	 * descendant drawables and merges them into [maskedBy] for the [RenderGroup] it builds, so the stored
	 * value keeps the grouping for the UI while the renderer stays drawable-only.  A CMO3 export flattens
	 * this away (that format has nowhere to put it); UMA will carry it.
	 */
	val maskedByParts: List<PartId> = emptyList(),
	/** When true, the clip is inverted - the composite shows outside the [maskedBy] coverage. (CMO3 invertClippingMask; MOC3 offscreen flags bit 3.) */
	val invertMask: Boolean = false,
	/** Static composite opacity (0..1) when the part has no keyform grid. (CMO3 CPartForm.opacity; MOC3 s161.) */
	val opacity: Float = 1f,
	/** Static multiply color when the part has no keyform grid. (CMO3 CPartForm.multiplyColor.) */
	val multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
	/** Static screen color when the part has no keyform grid. (CMO3 CPartForm.screenColor.) */
	val screenColor: ColorRgb = ColorRgb.ScreenIdentity,
)