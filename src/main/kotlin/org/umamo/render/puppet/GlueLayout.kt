package org.umamo.render.puppet

import org.umamo.render.glsl.MAX_GLUES
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

/**
 * One glue mesh's per-vertex weld attributes, parallel arrays indexed by the mesh's own vertex index.
 *
 * A vertex that is not glued points at ITSELF with weight 0 - a weld that is arithmetically a no-op
 * (`own + (own - own) * 0`), which is why the shader needs no "is this vertex glued" branch beyond the
 * cheap [glueIndex] test.
 *
 * Three arrays rather than one packed byte blob deliberately. The packed form is a GL vertex-attribute
 * layout (12 bytes/vertex, native byte order), and encoding it here would put a backend's memory layout
 * into shared code AND force this module to invent an endianness convention that the GL side would then
 * have to honor silently. What each vertex welds to is shared; how the bytes sit is the backend's.
 *
 * @property IntArray   partnerIndex Per vertex: the partner's GLOBAL index in the shared position store,
 *   or the vertex's own global index when it is not glued.
 * @property IntArray   glueIndex    Per vertex: which glue supplies the per-pose intensity, or -1 when
 *   not glued.
 * @property FloatArray weldWeight   Per vertex: how far to move toward the partner (0 when not glued).
 */
public class GlueVertexAttributes(
	val partnerIndex: IntArray,
	val glueIndex: IntArray,
	val weldWeight: FloatArray,
)

/**
 * The addressing plan for a model's glue: who participates, where each mesh sits in the shared deformed
 * position store, and what every vertex welds to.
 *
 * @property Set   glueMeshIds       Every mesh in any glue pair, INCLUDING zero-triangle anchors, which
 *   draw nothing but whose deformed positions are weld partners.
 * @property Map   baseOffsetById    Each glue mesh's first vertex index in the shared store.
 * @property Int   globalVertexCount The store's total vertex capacity.
 * @property Map   attributesById    Each glue mesh's per-vertex weld attributes.
 */
internal class GlueLayout(
	val glueMeshIds: Set<DrawableId>,
	val baseOffsetById: Map<DrawableId, Int>,
	val globalVertexCount: Int,
	val attributesById: Map<DrawableId, GlueVertexAttributes>,
)

/**
 * Plans [model]'s glue addressing: the participating meshes, their base offsets in the shared deformed
 * position store, and every vertex's weld attributes.
 *
 * Backend-neutral - it decides WHAT welds to what, in terms of vertex indices, and nothing about how a
 * GPU stores it.
 *
 * The offsets are assigned by walking [PuppetModel.drawables] in order and giving each glue mesh the next
 * region, so that iteration order DEFINES the addressing.  Both the GPU write (pass 1 deforms each mesh
 * into its own region) and the GPU read (pass 2 fetches a partner by global index) depend on producer and
 * consumer agreeing on it exactly.
 *
 * @param PuppetModel model The rig.
 * @return GlueLayout The addressing plan; empty throughout when the model has no glue.
 * @note A glue pair whose vertex index is outside its mesh throws here, whereas the CPU weld
 *   (`applyGluesResolved`) silently skips it.  That divergence predates this extraction and is preserved
 *   rather than quietly papered over - a malformed pair should be dealt with deliberately, not by two
 *   different behaviours in two backends.
 */
internal fun planGlueLayout(model: PuppetModel): GlueLayout {
	val glueMeshIds = HashSet<DrawableId>()
	for (glue in model.glues) {
		glueMeshIds.add(glue.meshA)
		glueMeshIds.add(glue.meshB)
	}
	val vertexCountById = HashMap<DrawableId, Int>(glueMeshIds.size)
	val baseOffsetById = HashMap<DrawableId, Int>(glueMeshIds.size)
	var globalVertexCount = 0
	// model.drawables order defines the offsets - see the docblock.
	for (drawable in model.drawables) {
		if (drawable.id !in glueMeshIds) {
			continue
		}
		val vertexCount = (drawable.mesh?.positions?.size ?: 0) / 2
		vertexCountById[drawable.id] = vertexCount
		baseOffsetById[drawable.id] = globalVertexCount
		globalVertexCount += vertexCount
	}

	// Every vertex starts as an un-glued no-op weld: partner = self, no glue, zero weight.
	val attributesById = HashMap<DrawableId, GlueVertexAttributes>(glueMeshIds.size)
	for (id in glueMeshIds) {
		val base = baseOffsetById[id] ?: continue // a glue naming a drawable the model does not carry
		val vertexCount = vertexCountById[id] ?: continue
		attributesById[id] =
			GlueVertexAttributes(
				partnerIndex = IntArray(vertexCount) { vertexIndex -> base + vertexIndex },
				glueIndex = IntArray(vertexCount) { -1 },
				weldWeight = FloatArray(vertexCount),
			)
	}

	// Then each pair points both members at each other, by global index.
	for ((glueIndex, glue) in model.glues.withIndex()) {
		// glue #65+ has no slot in the shader's fixed glueIntensity[MAX_GLUES] uniform array, and a
		// vertex tagged with it would index that array out of bounds. Leave those vertices at their -1
		// (unwelded) default rather than tag them - this is what makes resolvePose's "renders unwelded"
		// promise actually true.
		if (glueIndex >= MAX_GLUES) {
			continue
		}
		val baseA = baseOffsetById[glue.meshA] ?: continue
		val baseB = baseOffsetById[glue.meshB] ?: continue
		val attributesA = attributesById[glue.meshA] ?: continue
		val attributesB = attributesById[glue.meshB] ?: continue
		for (pair in glue.pairs) {
			writeGlueVertex(attributesA, pair.indexA, baseB + pair.indexB, glueIndex, pair.weightA)
			writeGlueVertex(attributesB, pair.indexB, baseA + pair.indexA, glueIndex, pair.weightB)
		}
	}
	return GlueLayout(glueMeshIds, baseOffsetById, globalVertexCount, attributesById)
}

/**
 * Points one vertex at its weld partner.
 *
 * @param GlueVertexAttributes attributes        The owning mesh's attributes.
 * @param Int                  vertexIndex       The vertex, in its own mesh's indexing.
 * @param Int                  partnerGlobalIndex The partner's index in the shared store.
 * @param Int                  glueIndex         Which glue supplies the per-pose intensity.
 * @param Float                weight            How far to move toward the partner.
 */
private fun writeGlueVertex(
	attributes: GlueVertexAttributes,
	vertexIndex: Int,
	partnerGlobalIndex: Int,
	glueIndex: Int,
	weight: Float,
) {
	attributes.partnerIndex[vertexIndex] = partnerGlobalIndex
	attributes.glueIndex[vertexIndex] = glueIndex
	attributes.weldWeight[vertexIndex] = weight
}