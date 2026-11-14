package io.github.psd2live.document

import java.util.BitSet

/**
 * Where a layer's meshes lie on its texture raster: square cells of [cell] raster pixels, [columns] x [rows]
 * of them covering the raster, row by row in [bits]. A tile with a footprint owns only those cells of its
 * rectangle on the page, so tiles whose rectangles overlap can share a page as long as their meshes do not.
 */
class TextureFootprint(val cell: Int, val columns: Int, val rows: Int, val bits: BitSet) {
	init {
		require(cell > 0 && columns > 0 && rows > 0) { "Texture footprint must have cells" }
		require(bits.length() <= columns * rows) { "Texture footprint bits exceed its cells" }
	}

	operator fun get(column: Int, row: Int): Boolean = bits[row * columns + column]

	/** How many cells the meshes cover. */
	val area: Int get() = bits.cardinality()

	override fun equals(other: Any?): Boolean = other is TextureFootprint && cell == other.cell && columns == other.columns &&
		rows == other.rows && bits == other.bits
	override fun hashCode(): Int = ((cell * 31 + columns) * 31 + rows) * 31 + bits.hashCode()
}

/**
 * One tile's stored spot: its upright rectangle's top left, texture pixel ([x], [y]) on [page], the cells of
 * it its meshes use, and the degrees it turns about its centre (around its centre).
 */
data class ArrangedTile(val page: Int, val x: Int, val y: Int, val footprint: TextureFootprint? = null, val rotation: Float = 0f) {
	init {
		require(page >= 0 && x >= 0 && y >= 0) { "Arranged tile must not be negative" }
		require(rotation.isFinite()) { "Arranged tile rotation must be finite" }
	}
}

/** [degrees] in -180 (excluded) .. 180, the one form a turn is stored and compared in. */
fun normalizedRotation(degrees: Float): Float {
	if (!degrees.isFinite()) return 0f
	var d = degrees % 360f
	if (d <= -180f) d += 360f
	if (d > 180f) d -= 360f
	return if (kotlin.math.abs(d) < 1e-4f) 0f else d
}

/**
 * A stored atlas layout. Without one the atlas is packed afresh on every build (automatic arrangement); with
 * one every listed tile keeps its spot and the common fit stays [fitStep] / [FIT_STEPS], so a paint, a density
 * change or a new layer never moves the other tiles. Tiles it does not list - new layers, or ones that no
 * longer fit where they were - are placed into free space. It changes only when the user
 * arranges the atlas, moves a tile or turns the automatic arrangement back on.
 */
data class AtlasArrangement(val fitStep: Int, val tiles: Map<String, ArrangedTile>) {
	init { require(fitStep in 1..FIT_STEPS) { "Atlas arrangement fit must lie in 1..$FIT_STEPS" } }

	val fit: Double get() = fitStep.toDouble() / FIT_STEPS

	companion object {
		const val FIT_STEPS = 4096
	}
}
