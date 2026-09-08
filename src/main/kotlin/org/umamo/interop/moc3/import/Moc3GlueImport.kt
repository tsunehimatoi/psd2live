package org.umamo.interop.moc3.import

import org.umamo.runtime.keyform.asChannelTrack
import org.umamo.runtime.keyform.channelGridsOf
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GlueForm
import org.umamo.runtime.model.GluePair

/**
 * Imports every glue whose two meshes both resolve.
 *
 * @param Moc3ImportContext context The import's derived state.
 * @return List<Glue> The runtime glues, in file order.
 */
internal fun importGlues(context: Moc3ImportContext): List<Glue> {
	val mocDocument = context.mocDocument
	return mocDocument.glues.mapNotNull { source ->
		val meshA = context.drawableIdsByFileIndex.getOrNull(source.meshAIndex) ?: return@mapNotNull null
		val meshB = context.drawableIdsByFileIndex.getOrNull(source.meshBIndex) ?: return@mapNotNull null
		// MOC3 §5.6 glue: vertex indices are already mesh-local (no UID indirection, unlike CMO3).
		// Pairs whose indices fall outside either mesh are dropped, mirroring Cmo3Import's
		// UID-resolution behavior - the glue layout planner indexes vertex arrays directly, so an
		// unvalidated index from a malformed moc would throw on the render thread after a
		// nominally successful import.
		val vertexCountA = mocDocument.artMeshes.getOrNull(source.meshAIndex)?.vertexCount ?: 0
		val vertexCountB = mocDocument.artMeshes.getOrNull(source.meshBIndex)?.vertexCount ?: 0
		val pairs =
			source.pairs.mapNotNull { pair ->
				if (pair.vertexA in 0 until vertexCountA && pair.vertexB in 0 until vertexCountB) {
					GluePair(pair.vertexA, pair.vertexB, pair.weightA, pair.weightB)
				} else {
					null
				}
			}
		val intensityTrack =
			gridOf(context, context.bindingOf(source.keyformBindingIndex)) { gridIndex ->
				GlueForm(
					source.intensityKeyforms.getOrElse(gridIndex) {
						source.intensityKeyforms.lastOrNull() ?: 1f
					},
				)
			}?.asChannelTrack { form -> ChannelValue.Scalar(form.intensity) }
		// A glue with no keyed intensity welds fully, which is the runtime's long-standing fallback.
		Glue(
			meshA,
			meshB,
			pairs,
			channelGridsOf(FormChannel.GLUE_INTENSITY to intensityTrack),
			intensity = 1f,
			// MOC3 §5.6 s90: the authored name, so a round trip does not synthesize a new one.
			id = source.id.takeIf { it.isNotEmpty() },
		)
	}
}