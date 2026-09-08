package org.umamo.render.glsl

/**
 * The shared GLSL for Umamo's GPU deformation - the per-vertex morph + deformer cascade. Both the live
 * renderer (`PuppetRenderer`, which projects the result to `gl_Position`) and the GPU-vs-CPU
 * transform-feedback test (which captures the world position) prepend their own `#version` + in/out
 * declarations and `main`, then call [deformWorld]. Sharing this snippet means the test exercises the
 * exact deform math the renderer ships, not a hand-kept copy.
 *
 * The uniforms it declares are filled per pose from the backend-neutral `PoseDeformInputs`:
 *  - [deltaTex] - RG = a keyform cell's per-vertex delta; texel (col = cell linear index, row = vertex id).
 *  - cornerCount / cornerCell[] / cornerWeight[] - the active multilinear corners + weights.
 *  - parentType / rot[] / cpTex / warp* - the baked parent-deformer transform (0 direct, 1 rotation, 2 warp).
 */
internal const val DEFORM_GLSL =
	"""
	uniform sampler2D deltaTex;
	uniform int cornerCount;
	uniform int cornerCell[$MAX_CORNERS];
	uniform float cornerWeight[$MAX_CORNERS];
	uniform int blendCount;
	uniform int blendCell[$MAX_BLEND_CORNERS];
	uniform float blendWeight[$MAX_BLEND_CORNERS];
	uniform int parentType;
	uniform float rot[6];
	uniform sampler2D cpTex;
	uniform int warpCols;
	uniform int warpRows;
	uniform int warpBilinear;

	vec2 cpAt(int index) {
		int pointsPerRow = warpCols + 1;
		return texelFetch(cpTex, ivec2(index % pointsPerRow, index / pointsPerRow), 0).rg;
	}

	vec2 warpExtrap(vec2 uv) {
		int pointsPerRow = warpCols + 1;
		int lastRowBase = warpRows * pointsPerRow;
		vec2 topLeft = cpAt(0);
		vec2 topRight = cpAt(warpCols);
		vec2 botLeft = cpAt(lastRowBase);
		vec2 botRight = cpAt(warpCols + lastRowBase);
		float gridU = float(warpCols) * uv.x;
		float gridV = float(warpRows) * uv.y;
		vec2 deltaU = ((botRight - topLeft) + (topRight - botLeft)) * 0.5;
		vec2 deltaV = ((botRight - topLeft) - (topRight - botLeft)) * 0.5;
		vec2 origin = (topLeft + topRight + botLeft + botRight) * 0.25 - (botRight - topLeft) * 0.5;
		if (uv.x <= -2.0 || uv.x >= 3.0 || uv.y <= -2.0 || uv.y >= 3.0) {
			return origin + deltaU * uv.x + deltaV * uv.y;
		}
		float blendU = 0.0;
		float blendV = 0.0;
		vec2 corner0 = vec2(0.0);
		vec2 corner1 = vec2(0.0);
		vec2 corner2 = vec2(0.0);
		vec2 corner3 = vec2(0.0);
		if (uv.x <= 0.0) {
			blendU = (uv.x + 2.0) * 0.5;
			corner3 = origin - 2.0 * deltaU;
			if (uv.y <= 0.0) {
				corner2 = topLeft;
				blendV = (uv.y + 2.0) * 0.5;
				corner1 = origin - 2.0 * deltaV;
				corner0 = corner3 - 2.0 * deltaV;
			} else if (uv.y < 1.0) {
				int loIdx = int(gridV);
				float edgeHi;
				int hiIdx;
				if (warpRows == loIdx) {
					loIdx = warpRows - 1;
					hiIdx = warpRows;
					edgeHi = float(warpRows);
				} else {
					hiIdx = loIdx + 1;
					edgeHi = float(loIdx + 1);
				}
				blendV = gridV - float(loIdx);
				float edgeLo = float(loIdx) / float(warpRows);
				corner1 = cpAt(loIdx * pointsPerRow);
				corner2 = cpAt(hiIdx * pointsPerRow);
				corner0 = edgeLo * deltaV + corner3;
				corner3 = (edgeHi / float(warpRows)) * deltaV + corner3;
			} else {
				corner1 = botLeft;
				blendV = (uv.y - 1.0) * 0.5;
				corner0 = corner3 + deltaV;
				corner3 = 3.0 * deltaV + corner3;
				corner2 = 3.0 * deltaV + origin;
			}
		} else if (uv.x < 1.0) {
			if (uv.y <= 0.0) {
				int loIdx = int(gridU);
				vec2 edge;
				float edgeHi;
				if (warpCols == loIdx) {
					loIdx = warpCols - 1;
					edge = topRight;
					edgeHi = float(warpCols);
				} else {
					edge = cpAt(loIdx + 1);
					edgeHi = float(loIdx + 1);
				}
				blendV = (uv.y + 2.0) * 0.5;
				blendU = gridU - float(loIdx);
				float edgeLo = float(loIdx) / float(warpCols);
				corner3 = cpAt(loIdx);
				corner2 = edge;
				corner0 = (edgeLo * deltaU + origin) - 2.0 * deltaV;
				corner1 = ((edgeHi / float(warpCols)) * deltaU + origin) - 2.0 * deltaV;
			} else if (uv.y >= 1.0) {
				int loIdx = int(gridU);
				float edgeHi;
				int hiIdx;
				if (warpCols == loIdx) {
					loIdx = warpCols - 1;
					hiIdx = warpCols;
					edgeHi = float(warpCols);
				} else {
					hiIdx = loIdx + 1;
					edgeHi = float(loIdx + 1);
				}
				blendV = (uv.y - 1.0) * 0.5;
				blendU = gridU - float(loIdx);
				float edgeLo = float(loIdx) / float(warpCols);
				corner0 = cpAt(loIdx + lastRowBase);
				corner1 = cpAt(lastRowBase + hiIdx);
				corner2 = (edgeHi / float(warpCols)) * deltaU + origin + 3.0 * deltaV;
				corner3 = edgeLo * deltaU + origin + 3.0 * deltaV;
			} else {
				return origin + deltaU * uv.x + deltaV * uv.y;
			}
		} else {
			blendU = (uv.x - 1.0) * 0.5;
			corner2 = 3.0 * deltaU + origin;
			if (uv.y <= 0.0) {
				corner3 = topRight;
				blendV = (uv.y + 2.0) * 0.5;
				corner0 = (origin + deltaU) - 2.0 * deltaV;
				corner1 = corner2 - 2.0 * deltaV;
			} else if (uv.y < 1.0) {
				int loIdx = int(gridV);
				float edgeHi;
				int hiIdx;
				if (warpRows == loIdx) {
					loIdx = warpRows - 1;
					hiIdx = warpRows;
					edgeHi = float(warpRows);
				} else {
					hiIdx = loIdx + 1;
					edgeHi = float(loIdx + 1);
				}
				blendV = gridV - float(loIdx);
				corner0 = cpAt(loIdx * pointsPerRow + warpCols);
				corner3 = cpAt(hiIdx * pointsPerRow + warpCols);
				float edgeLo = float(loIdx) / float(warpRows);
				corner1 = edgeLo * deltaV + corner2;
				corner2 = (edgeHi / float(warpRows)) * deltaV + corner2;
			} else {
				corner0 = botRight;
				blendV = (uv.y - 1.0) * 0.5;
				corner1 = corner2 + deltaV;
				corner3 = origin + deltaU + 3.0 * deltaV;
				corner2 = 3.0 * deltaV + corner2;
			}
		}
		if (blendU + blendV <= 1.0) {
			return (corner1 - corner0) * blendU + corner0 + (corner3 - corner0) * blendV;
		}
		return (corner3 - corner2) * (1.0 - blendU) + corner2 + (corner1 - corner2) * (1.0 - blendV);
	}

	// Warp lattice map of normalized (u,v): in-grid bilinear/triangle blend of the 4 surrounding control
	// points, else warpExtrap's full edge-blend extrapolation outside the lattice (ported from the CPU).
	vec2 warpDeform(vec2 uv) {
		int pointsPerRow = warpCols + 1;
		if (uv.x >= 0.0 && uv.y >= 0.0 && uv.x < 1.0 && uv.y < 1.0) {
			float gridU = float(warpCols) * uv.x;
			float gridV = float(warpRows) * uv.y;
			int cellU = int(gridU);
			int cellV = int(gridV);
			float fu = gridU - float(cellU);
			float fv = gridV - float(cellV);
			int cell = cellV * pointsPerRow + cellU;
			vec2 p00 = cpAt(cell);
			vec2 p10 = cpAt(cell + 1);
			vec2 p01 = cpAt(cell + pointsPerRow);
			vec2 p11 = cpAt(cell + pointsPerRow + 1);
			if (warpBilinear == 0) {
				if (fu + fv <= 1.0) {
					return p10 * fu + p00 * (1.0 - fu - fv) + p01 * fv;
				}
				float w = (fu - 1.0) + fv;
				return p01 * (1.0 - fu) + p11 * w + p10 * (1.0 - fv);
			}
			float ifv = 1.0 - fv;
			return p10 * (fu * ifv) + p00 * ((1.0 - fu) * ifv) + p01 * ((1.0 - fu) * fv) + p11 * (fu * fv);
		}
		return warpExtrap(uv);
	}

	// Full per-vertex deform: blend base + Σ wᵢ·Δᵢ (active cells fetched from deltaTex by gl_VertexID),
	// push through the parent transform (rotation affine / warp lattice / none), then negate Y. Glue is
	// not applied here (a cross-mesh post-pass) - matches the CPU deform before its glue weld.
	vec2 deformWorld(vec2 base) {
		vec2 local = base;
		for (int corner = 0; corner < cornerCount && corner < $MAX_CORNERS; corner++) {
			vec2 delta = texelFetch(deltaTex, ivec2(cornerCell[corner], gl_VertexID), 0).rg;
			local += cornerWeight[corner] * delta;
		}
		// Blend shapes: additive delta columns appended after the grid cells in deltaTex, weighted
		// per pose (zero-blend models keep blendCount == 0 and skip the loop entirely).
		for (int blendCorner = 0; blendCorner < blendCount && blendCorner < $MAX_BLEND_CORNERS; blendCorner++) {
			vec2 blendDelta = texelFetch(deltaTex, ivec2(blendCell[blendCorner], gl_VertexID), 0).rg;
			local += blendWeight[blendCorner] * blendDelta;
		}
		vec2 world;
		if (parentType == 1) {
			world = vec2(rot[3] * local.y + rot[0] * local.x + rot[4],
			             local.y * rot[1] + local.x * rot[2] + rot[5]);
		} else if (parentType == 2) {
			world = warpDeform(local);
		} else {
			world = local;
		}
		world.y = -world.y;
		return world;
	}
	"""

/**
 * The transform-feedback deform shader: runs [DEFORM_GLSL]'s `deformWorld` and captures the result as
 * `outWorld` (no projection).
 *
 * Used by the renderer's glue pass 1 to write every glue mesh's deformed world positions into the shared
 * position buffer, and by the GPU-vs-CPU validation test - which is the point: the test pins the exact
 * deform body that ships, not a copy of it.  Its varying `outWorld` must be registered via
 * `glTransformFeedbackVaryings` before linking.
 *
 * Transform feedback is core in GL 3.3 AND GLES 3.0, so both dialects can run this as-is.
 *
 * @param GlslDialect dialect The target flavor.
 * @return String The ready-to-compile source.
 */
internal fun tfDeformVertexShader(dialect: GlslDialect): String =
	glslHeader(dialect) +
		"layout(location = 0) in vec2 inBase;\n" +
		"out vec2 outWorld;\n" +
		DEFORM_GLSL.trimIndent() + "\n" +
		"void main() {\n" +
		"	outWorld = deformWorld(inBase);\n" +
		"	gl_Position = vec4(0.0, 0.0, 0.0, 1.0);\n" +
		"}\n"

/**
 * The do-nothing fragment stage the transform-feedback program links against - it captures vertices and
 * rasterizes nothing, but a program still needs a fragment shader to link.
 *
 * @param GlslDialect dialect The target flavor.
 * @return String The ready-to-compile source.
 */
internal fun tfDiscardFragmentShader(dialect: GlslDialect): String = glslHeader(dialect) + "void main() {}\n"