package org.umamo.render.glsl

// The canvas backdrop's GLSL - the world-aligned grid and the world-origin axis lines.
//
// These were previously hand-copied between the desktop and Android backends, identical but for the
// preamble, with nothing pinning the two together.  They had already begun to drift: the Android copies
// had silently lost three of the explanatory comments.  Only comments so far, which is precisely why
// merging them now was worth doing - the next divergence would have been in the math, and nothing would
// have caught it.
//
// Metal shares none of this; see GlslDialect.

/**
 * The full-screen pass's vertex shader: one oversized triangle covering the viewport.
 *
 * No vertex buffer - the three corners are derived from `gl_VertexID`, so no attributes are needed (and
 * on a core profile, only an empty VAO).
 *
 * 頂点バッファ無しの全画面パス。三角形の頂点は gl_VertexID から生成する。
 *
 * @param GlslDialect dialect The target flavor.
 * @return String The ready-to-compile source.
 */
internal fun gridVertexShader(dialect: GlslDialect): String =
	glslHeader(dialect) +
		"""
		void main() {
			// id 0 -> (-1,-1), 1 -> (-1,3), 2 -> (3,-1): one oversized triangle covering the viewport.
			gl_Position = vec4(float(gl_VertexID / 2) * 4.0 - 1.0, float(gl_VertexID % 2) * 4.0 - 1.0, 0.0, 1.0);
		}
		""".trimIndent()

/**
 * The world-aligned grid backdrop's fragment shader.
 *
 * Each fragment recovers its world position by inverting the same world-to-NDC affine the puppet is
 * projected through, then draws anti-aliased lines wherever that world coord is near a multiple of the
 * major or minor spacing.  Colors and spacings arrive as uniforms so the backdrop follows the editor
 * theme and the per-document grid config.  Opaque, so it both clears and paints in one pass.
 *
 * ワールド整列グリッド。worldToNdc を逆変換して各フラグメントのワールド座標を求め、主線・副線を描く。
 *
 * @param GlslDialect dialect The target flavor.
 * @return String The ready-to-compile source.
 */
internal fun gridFragmentShader(dialect: GlslDialect): String =
	glslHeader(dialect) +
		"""
		out vec4 fragColor;
		uniform vec2 viewportSize;
		uniform vec4 worldToNdc;      // scaleX, scaleY, offsetX, offsetY
		uniform vec2 majorSpacing;    // world units per major cell, per axis
		uniform float subdivisions;   // minor lines per major cell
		uniform float lineWidthPx;    // line half-width in framebuffer pixels
		uniform vec3 backgroundColor;
		uniform vec3 majorColor;
		uniform vec3 minorColor;
		uniform vec2 gridOrigin;   // world point a major line passes through (the world axes)

		// Anti-aliased line coverage for a coordinate on a lattice of the given spacing.  cellDist is the
		// fractional distance to the nearest line in cell units (0 on a line, 0.5 mid-cell); scaling it by
		// spacing / worldPerPixel converts it to framebuffer pixels, so the band stays a constant on-screen
		// width at any zoom - derived from the affine, not GLSL derivatives, so it is driver-independent.
		float lineCoverage(vec2 world, vec2 spacing, vec2 worldPerPixel) {
			vec2 cell = world / spacing;
			vec2 cellDist = abs(fract(cell - 0.5) - 0.5);
			vec2 pixelDist = cellDist * spacing / worldPerPixel;
			float nearestPx = min(pixelDist.x, pixelDist.y);
			return 1.0 - smoothstep(0.0, lineWidthPx, nearestPx);
		}

		void main() {
			vec2 ndc = (gl_FragCoord.xy / viewportSize) * 2.0 - 1.0;
			vec2 world = (ndc - worldToNdc.zw) / worldToNdc.xy;
			// World units per framebuffer pixel, from the affine: an NDC span of 2 covers viewportSize pixels
			// and worldToNdc.xy world units per NDC unit.  abs() drops the Y-axis flip sign.
			vec2 worldPerPixel = abs((2.0 / viewportSize) / worldToNdc.xy);
			// Anchor the lattice on the world origin (the axes), so a major line always crosses the axes
			// rather than falling mid-cell for an arbitrary canvas size.
			vec2 gridWorld = world - gridOrigin;
			vec2 minorSpacing = majorSpacing / max(subdivisions, 1.0);
			// Fade the minor lines out as their on-screen spacing collapses, so a zoomed-out grid does not
			// alias into a solid wash: minorScreenPx is the minor spacing measured in framebuffer pixels.
			vec2 minorScreenPx = minorSpacing / worldPerPixel;
			float minorFade = clamp((min(minorScreenPx.x, minorScreenPx.y) - 4.0) / 8.0, 0.0, 1.0);
			float minor = lineCoverage(gridWorld, minorSpacing, worldPerPixel) * minorFade;
			float major = lineCoverage(gridWorld, majorSpacing, worldPerPixel);
			vec3 color = mix(backgroundColor, minorColor, minor);
			color = mix(color, majorColor, major);
			fragColor = vec4(color, 1.0);
		}
		""".trimIndent()

/**
 * One world-origin axis line's vertex shader.
 *
 * The two endpoints come from `gl_VertexID` (-1 / +1 along the line); the fixed coordinate and the
 * orientation arrive as uniforms, so one program draws both axes.
 *
 * 軸線 1 本を描くパス。端点は gl_VertexID から生成し、固定座標と向きは uniform で受け取る。
 *
 * @param GlslDialect dialect The target flavor.
 * @return String The ready-to-compile source.
 */
internal fun axisVertexShader(dialect: GlslDialect): String =
	glslHeader(dialect) +
		"""
		uniform float linePositionNdc;
		uniform float lineVertical;
		void main() {
			float along = float(gl_VertexID) * 2.0 - 1.0;
			vec2 position = mix(vec2(along, linePositionNdc), vec2(linePositionNdc, along), lineVertical);
			gl_Position = vec4(position, 0.0, 1.0);
		}
		""".trimIndent()

/**
 * The axis line's fragment shader: a flat, opaque line color.
 *
 * @param GlslDialect dialect The target flavor.
 * @return String The ready-to-compile source.
 */
internal fun axisFragmentShader(dialect: GlslDialect): String =
	glslHeader(dialect) +
		"""
		out vec4 fragColor;
		uniform vec3 lineColor;
		void main() {
			fragColor = vec4(lineColor, 1.0);
		}
		""".trimIndent()