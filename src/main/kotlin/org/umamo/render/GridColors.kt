package org.umamo.render

/**
 * The three colors of the viewport grid backdrop, as 0..1 linear RGB triples: the flat canvas fill, the
 * major grid line color, and the minor (subdivision) grid line color.  Kept as plain floats (not a Compose
 * Color) so :render stays free of any UI dependency; the editor maps its themed palette into this at the
 * call site.  [Classic] is a neutral grey grid, used as the default so a caller that does not theme the
 * backdrop still gets a sensible surface.
 *
 * ビューポートのグリッド背景の 3 色（0..1 の RGB）。背景・主線・副線。:render を UI 非依存に保つため素の float。
 *
 * @property Float backgroundRed   Red of the flat canvas fill.
 * @property Float backgroundGreen Green of the flat canvas fill.
 * @property Float backgroundBlue  Blue of the flat canvas fill.
 * @property Float majorRed        Red of the major grid line.
 * @property Float majorGreen      Green of the major grid line.
 * @property Float majorBlue       Blue of the major grid line.
 * @property Float minorRed        Red of the minor (subdivision) grid line.
 * @property Float minorGreen      Green of the minor grid line.
 * @property Float minorBlue       Blue of the minor grid line.
 */
data class GridColors(
	val backgroundRed: Float,
	val backgroundGreen: Float,
	val backgroundBlue: Float,
	val majorRed: Float,
	val majorGreen: Float,
	val majorBlue: Float,
	val minorRed: Float,
	val minorGreen: Float,
	val minorBlue: Float,
) {
	companion object {
		/** A neutral dark-grey grid: #2E2E2E fill, #484848 major lines, #3A3A3A minor lines. */
		val Classic: GridColors =
			GridColors(
				0.204f,
				0.204f,
				0.204f,
				0.317f,
				0.317f,
				0.317f,
				0.270f,
				0.270f,
				0.270f,
			)
	}
}

/**
 * The two world-axis line colors, as 0..1 linear RGB triples - the red X axis and the blue Z axis
 * drawn through the world origin behind the puppet (the project's display convention is Y+ forward,
 * Z+ up, so the vertical world axis is presented as Z).  Plain floats for the same reason as
 * [GridColors]: :render stays free of any UI dependency.
 *
 * ワールド軸線の 2 色（0..1 の RGB）。横が赤い X 軸、縦が青い Z 軸（表示規約は Y+ 前、Z+ 上）。
 *
 * @property Float xRed   Red of the horizontal X axis line.
 * @property Float xGreen Green of the horizontal X axis line.
 * @property Float xBlue  Blue of the horizontal X axis line.
 * @property Float zRed   Red of the vertical Z axis line.
 * @property Float zGreen Green of the vertical Z axis line.
 * @property Float zBlue  Blue of the vertical Z axis line.
 */
data class WorldAxisColors(
	val xRed: Float,
	val xGreen: Float,
	val xBlue: Float,
	val zRed: Float,
	val zGreen: Float,
	val zBlue: Float,
) {
	companion object {
		/** Blender-like axis colors: a muted red X and a muted blue Z. */
		val Classic: WorldAxisColors = WorldAxisColors(0.84f, 0.31f, 0.36f, 0.31f, 0.44f, 0.85f)
	}
}