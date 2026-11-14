package io.github.psd2live.document

/**
 * A fixed atlas position for one layer's tile: its upright rectangle's top left at texture pixel ([x], [y]) on
 * page [page], turned [rotation] degrees about its centre.
 */
data class TexturePin(
	val page: Int,
	val x: Int,
	val y: Int,
	val rotation: Float = 0f,
) {
	init {
		require(page >= 0 && x >= 0 && y >= 0) { "Texture pin must not be negative" }
		require(rotation.isFinite()) { "Texture pin rotation must be finite" }
	}
}

/**
 * One layer's texture settings, overriding the automatic atlas budget.
 *
 * The atlas holds `raster pixels x density x fit` for each layer, where fit (at most 1) is solved
 * once for all unlocked layers so the atlas stays within its budget.
 *
 * Stored in the document texture overrides and read by the atlas packer (`AtlasLayout`) through
 * `PipelineConfig.textureOverrides`.
 *
 * @property density Texture pixels per raster pixel; null keeps the default of 1.
 * @property lock Whether the layer keeps its density when the atlas has to shrink to fit.
 * @property pin A fixed atlas position; null lets the packer place the tile.
 */
data class TextureOverride(
	val density: Float? = null,
	val lock: Boolean = false,
	val pin: TexturePin? = null,
) {
	init {
		require(density == null || (density.isFinite() && density > 0f)) { "Texture density must be positive" }
	}

	/** Whether this override changes nothing, so it need not be stored. */
	val isDefault: Boolean get() = density == null && !lock && pin == null
}
