package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.ACDeformerSource
import org.umamo.format.cmo3.model.gen.CAffecterSourceSet
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDeformerSourceSet
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CGlueSource
import org.umamo.format.cmo3.model.gen.CParameterGroup
import org.umamo.format.cmo3.model.gen.CParameterGroupSet
import org.umamo.format.cmo3.model.gen.CParameterSource
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.gen.CPartSource
import org.umamo.format.cmo3.model.gen.CPartSourceSet

/**
 * Lookup index over a CMO3 model graph for the export reconcile: every source category listed and
 * keyed by its runtime id (the Id.idstr the import carries verbatim) and by GUID uuid.  Mirrors
 * Cmo3Import's pass 1, in the opposite direction: where import resolves graph references to runtime
 * ids, the lowering resolves runtime ids back to the graph objects it must mutate.
 */
internal class Cmo3GraphIndex(val modelSource: CModelSource) {
	val parameterSources: List<CParameterSource> =
		Cmo3Import.elementsOf((modelSource.parameterSourceSet as? CParameterSourceSet)?._sources)
			.filterIsInstance<CParameterSource>()

	private val allPartSources: List<CPartSource> =
		Cmo3Import.elementsOf((modelSource.partSourceSet as? CPartSourceSet)?._sources)
			.filterIsInstance<CPartSource>()

	/** The synthetic __RootPart__ tree anchor (excluded from the runtime parts list). */
	val rootPartSource: CPartSource? = modelSource.rootPart as? CPartSource

	/** The user-facing parts, excluding the synthetic root - the ones runtime PartIds resolve to. */
	val userPartSources: List<CPartSource> =
		allPartSources.filter { part -> Cmo3Import.uuidOf(part.guid) != Cmo3Import.uuidOf(rootPartSource?.guid) }

	val deformerSources: List<ACDeformerSource> =
		Cmo3Import.elementsOf((modelSource.deformerSourceSet as? CDeformerSourceSet)?._sources)
			.filterIsInstance<ACDeformerSource>()

	val drawableSources: List<CArtMeshSource> =
		Cmo3Import.elementsOf((modelSource.drawableSourceSet as? CDrawableSourceSet)?._sources)
			.filterIsInstance<CArtMeshSource>()

	val glueSources: List<CGlueSource> =
		Cmo3Import.elementsOf((modelSource.affecterSourceSet as? CAffecterSourceSet)?._sources)
			.filterIsInstance<CGlueSource>()

	val groupSources: List<CParameterGroup> =
		Cmo3Import.elementsOf((modelSource.parameterGroupSet as? CParameterGroupSet)?._groups)
			.filterIsInstance<CParameterGroup>()

	/** The hidden top parameter group whose _childGuids is the panel's authoritative root order. */
	val rootParameterGroup: CParameterGroup? = modelSource.rootParameterGroup as? CParameterGroup

	val parameterByIdStr: Map<String, CParameterSource> = byIdStr(parameterSources) { it.id }
	val partByIdStr: Map<String, CPartSource> = byIdStr(userPartSources) { it.id }
	val deformerByIdStr: Map<String, ACDeformerSource> = byIdStr(deformerSources) { it.id }
	val drawableByIdStr: Map<String, CArtMeshSource> = byIdStr(drawableSources) { it.id }
	val groupByIdStr: Map<String, CParameterGroup> = byIdStr(groupSources) { it.id }

	/** User-part uuids - with [drawableUuids], the org-child classification for _childGuids walks. */
	val userPartUuids: Set<String> = userPartSources.mapNotNull { Cmo3Import.uuidOf(it.guid) }.toSet()
	val drawableUuids: Set<String> = drawableSources.mapNotNull { Cmo3Import.uuidOf(it.guid) }.toSet()

	/** Drawable guid uuid to its runtime id string - the glue targets' resolution key. */
	val drawableIdStrByUuid: Map<String, String> =
		buildMap {
			for (source in drawableSources) {
				val uuid = Cmo3Import.uuidOf(source.guid) ?: continue
				val idStr = Cmo3Import.idStrOf(source.id) ?: continue
				put(uuid, idStr)
			}
		}

	private fun <TSource> byIdStr(sources: List<TSource>, idOf: (TSource) -> Any?): Map<String, TSource> =
		buildMap {
			for (source in sources) {
				Cmo3Import.idStrOf(idOf(source))?.let { idStr -> put(idStr, source) }
			}
		}
}