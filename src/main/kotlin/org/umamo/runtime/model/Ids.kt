package org.umamo.runtime.model

import kotlin.jvm.JvmInline

/**
 * Typed identifiers for model entities.
 *
 * Each is a `@JvmInline value class` - a zero-cost wrapper the compiler erases to the bare
 * underlying value where it can, while keeping the types distinct at compile time. This replaces
 * the "stringly-typed" anti-pattern (`Map<String, …>` where any String fits any slot): you can't
 * pass a [DrawableId] where a [PartId] is wanted. Cheap safety, no allocation.
 *
 * The import is load-bearing, not noise: `kotlin.jvm.*` is a default import only on JVM targets, so
 * without it this file stops compiling the moment a non-JVM target (Kotlin/Native, for iPadOS) is
 * added.  The annotation itself is an optional expectation, so non-JVM targets simply ignore it.
 */
@JvmInline
value class PartId(val raw: String)

@JvmInline
value class DrawableId(val raw: String)

@JvmInline
value class DeformerId(val raw: String)

@JvmInline
value class ParameterId(val raw: String)

@JvmInline
value class ParameterGroupId(val raw: String)

@JvmInline
value class KeyformId(val raw: String)

/**
 * One piece of source art in the document's atlas.
 *
 * Distinct from a source-LAYER identity, which says which artwork file and layer a tile came from:
 * this only names a tile within one document, and the two coincide only for a rig built from layered
 * art in the first place.
 */
@JvmInline
value class AtlasTileId(val raw: String)

/**
 * One artwork file in the document's source list ([PuppetModel.sources]) - what a tile's
 * [SourceLayerRef] names as the file half of its binding.
 */
@JvmInline
value class ArtSourceId(val raw: String)