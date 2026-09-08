package org.umamo.interop.moc3.import

import org.umamo.format.moc3.moc.ParameterType
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode

/**
 * Imports the parameter axes, in file order.
 *
 * File order is the addressing every keyform axis and every blend record uses to name its driving
 * parameter, so this list is not reordered.
 *
 * @param Moc3ImportContext context The import's derived state.
 * @return List<Parameter> The runtime parameters, in file order.
 */
internal fun importParameters(context: Moc3ImportContext): List<Parameter> =
	context.mocDocument.parameters.mapIndexed { parameterIndex, source ->
		Parameter(
			// Through the context's table, not from source.id: a file carrying the same id twice resolves
			// the repeat to a synthesized id there, and building it here instead would re-merge the two.
			id = context.parameterIds[parameterIndex],
			// cdi3: DisplayParameter.name is the display label; fall back to the id (ParamAngleX).
			name = context.parameterNameById[source.id] ?: source.id,
			min = source.minimumValue,
			max = source.maximumValue,
			default = source.defaultValue,
			// MOC3 v4+ section 114 Parameter types (null on moc < 4 = all normal).
			kind = if (source.type == ParameterType.BLEND_SHAPE) ParameterKind.BLEND_SHAPE else ParameterKind.NORMAL,
			// MOC3 §5.5 s54: wrap rather than clamp at the limits.
			repeat = source.repeats,
		)
	}

/**
 * Imports the LINKED ("combined") parameter pairs that cdi3 records - the editor's 2D pads.
 *
 * @param Moc3ImportContext context The import's derived state.
 * @return List<ParameterLink> The pairs, empty without cdi3.
 */
internal fun importParameterLinks(context: Moc3ImportContext): List<ParameterLink> {
	val knownParameterIds = context.parameterIds.toSet()
	// cdi3: CombinedParameters is an array of [horizontal, vertical] id pairs - the editor's LINKED
	// parameter pads. Entries that are not a 2-pair or name an unknown parameter are skipped.
	return context.displayInfo?.combinedParameters.orEmpty().mapNotNull { pair ->
		val horizontalId = pair.getOrNull(0)?.let(::ParameterId) ?: return@mapNotNull null
		val verticalId = pair.getOrNull(1)?.let(::ParameterId) ?: return@mapNotNull null
		if (pair.size == 2 && horizontalId in knownParameterIds && verticalId in knownParameterIds) {
			ParameterLink(horizontalId, verticalId)
		} else {
			null
		}
	}
}

/**
 * Builds the parameter-panel group tree from cdi3 display info.
 *
 * cdi3 stores two flat lists (parameters and groups, each naming an owning groupId; "" = root), so
 * within each group the leaf parameters come first (cdi3 order) followed by sub-groups (cdi3 order) -
 * the original interleaving is not recorded in a baked export.  Parameters cdi3 never places (or
 * placed under an unknown group) are appended at the root so every axis stays reachable in the panel.
 *
 * @param Moc3ImportContext context The import's derived state.
 * @return List<ParameterNode> The root children (empty when cdi3 is absent).
 */
internal fun importParameterTree(context: Moc3ImportContext): List<ParameterNode> {
	val displayInfo = context.displayInfo ?: return emptyList()
	val parameterIds = context.parameterIds
	val knownParameterIds = parameterIds.toSet()
	val groupsByParent = displayInfo.parameterGroups.groupBy { group -> group.groupId }
	val groupIds = displayInfo.parameterGroups.mapTo(HashSet()) { group -> group.id }
	val parametersByGroup =
		displayInfo.parameters
			.filter { parameter -> ParameterId(parameter.id) in knownParameterIds }
			.groupBy { parameter -> if (parameter.groupId in groupIds) parameter.groupId else "" }
	val visited = HashSet<String>()

	fun childrenOf(ownerGroupId: String): List<ParameterNode> =
		buildList {
			for (parameter in parametersByGroup[ownerGroupId].orEmpty()) {
				add(ParameterNode.Param(ParameterId(parameter.id)))
			}
			for (group in groupsByParent[ownerGroupId].orEmpty()) {
				if (!visited.add(group.id)) {
					continue
				}
				add(
					ParameterNode.Group(
						id = ParameterGroupId(group.id),
						name = group.name,
						// cdi3 records no fold state; open reads better than a wall of collapsed rows.
						initiallyOpen = true,
						children = childrenOf(group.id),
					),
				)
			}
		}

	val tree = childrenOf("")

	// Safety net: any moc parameter cdi3 never mentions still gets a root leaf, so the panel tree
	// covers every axis (the tree replaces the flat list when non-empty).
	val placedParameterIds =
		buildSet {
			fun walk(nodes: List<ParameterNode>) {
				for (node in nodes) {
					when (node) {
						is ParameterNode.Param -> add(node.id)
						is ParameterNode.Group -> walk(node.children)
					}
				}
			}
			walk(tree)
		}
	val unplaced = parameterIds.filter { parameterId -> parameterId !in placedParameterIds }
	return tree + unplaced.map { parameterId -> ParameterNode.Param(parameterId) }
}