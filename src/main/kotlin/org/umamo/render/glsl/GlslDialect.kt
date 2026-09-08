package org.umamo.render.glsl

/**
 * A GLSL flavor one of the GL-family backends compiles.
 *
 * The sources in this package are shared by the desktop GL 3.3 core backend and the Android GLES 3.0
 * one, which want the same bodies behind different preambles.  A Metal backend shares NOTHING here - MSL
 * is a different language, supplied by that backend - so this enum is deliberately GL-family only rather
 * than pretending to be a backend-neutral shader abstraction.
 *
 * GL 系（デスクトップ GL 3.3 / Android GLES 3.0）のシェーダ方言。Metal は MSL を別途持つ。
 */
internal enum class GlslDialect {
	/** Desktop OpenGL 3.3 core profile. */
	Core330,

	/** OpenGL ES 3.0 - the Android baseline (3.0 is the floor because VAOs and gl_VertexID need it). */
	Es300,
}

/**
 * The `#version` preamble for [dialect], including the precision qualifiers ES requires.
 *
 * ES has no default precision for floats or ints in a fragment shader, so omitting these is a compile
 * error there and a silent no-op on desktop - which is exactly the sort of divergence that made keeping
 * two hand-copied shader sets untenable.
 *
 * @param GlslDialect dialect The target flavor.
 * @return String The preamble, newline-terminated.
 */
internal fun glslHeader(dialect: GlslDialect): String =
	when (dialect) {
		GlslDialect.Core330 -> "#version 330 core\n"
		GlslDialect.Es300 -> "#version 300 es\nprecision highp float;\nprecision highp int;\n"
	}