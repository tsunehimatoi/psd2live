package io.github.psd2live.core

/**
 * Every physics group a model carries, as export, preview, the panel and MCP see it: generated groups
 * (see [PhysicsGenerator]) with the user's replacements, then the user's own, in evaluation order.
 */
object PhysicsCatalog {
	/**
	 * The groups in evaluation order, each with what it exports and why it would not. A user group claims
	 * its outputs first; a generated group on a claimed output steps aside.
	 */
	fun groups(
		present: PhysicsGenerator.Presets,
		enabledPresets: PhysicsGenerator.Presets,
		overlay: RigEditOverlay,
		available: Set<String>,
		model: org.umamo.runtime.model.PuppetModel? = null,
	): List<PhysicsGroup> {
		val inactiveInteriorGroups = overlay.interiorEffects.filter { effect ->
			!effect.enabled || (model != null && effect.issue(model) != null) || effect.parameterIds.any { it !in available }
		}.flatMapTo(HashSet()) { effect -> effect.groups.map { it.id } }
		// Generators name standard parameters by their standard IDs; an ID conversion gave them others.
		val renames = overlay.idRenames.parameters
		val standard = if (renames.isEmpty()) available else {
			val original = renames.entries.associate { it.value to it.key }
			available.mapTo(HashSet()) { original[it] ?: it }
		}
		val generated = (PhysicsGenerator.presetRules(present, standard).map { it to PhysicsOrigin.PRESET } +
			PhysicsGenerator.moduleRules(standard).map { it to PhysicsOrigin.PRESET } +
			PhysicsGenerator.skeletonRules(overlay.skeleton, standard).map { it to PhysicsOrigin.SKELETON } +
			PhysicsGenerator.swingRules(overlay.swingEdits, standard).map { it to PhysicsOrigin.SWING } +
			io.github.psd2live.core.sim.SimGenerator.physicsRules(overlay.simEdits, standard).map { it to PhysicsOrigin.SIMULATION } +
            overlay.interiorEffects.filter { it.enabled }.flatMap { it.groups }.filterNot { it.id in inactiveInteriorGroups }.map { it to PhysicsOrigin.INTERIOR })
			.map { (rule, origin) -> RigIdConversion.forward(rule, renames) to origin }
		val generatedIds = generated.mapTo(HashSet()) { it.first.id }
		val authored = overlay.physicsEdits.associateBy { it.id }
		fun enabled(id: String, origin: PhysicsOrigin) =
			if (origin == PhysicsOrigin.PRESET && id in PhysicsGenerator.presetIds) enabledPresets[id] else id !in overlay.disabledPhysicsIds
		val rows = ordered(generated.map { (rule, origin) ->
			val setting = authored[rule.id] ?: rule
			PhysicsGroup(setting, origin, rule, enabled(rule.id, origin), issueOf(setting, available))
		} + overlay.physicsEdits.filter { it.id !in generatedIds && it.id !in inactiveInteriorGroups }.map { edit ->
			PhysicsGroup(edit, PhysicsOrigin.CUSTOM, null, enabled(edit.id, PhysicsOrigin.CUSTOM), issueOf(edit, available))
		}, overlay.physicsOrder)
		// The user's groups claim outputs in the order they were authored, then generated ones in order.
		val claimed = HashMap<String, String>()
		val resolved = rows.associateBy { it.id }.toMutableMap()
		val userFirst = overlay.physicsEdits.mapNotNull { resolved[it.id] } + rows.filter { it.id !in authored }
		for (row in userFirst) {
			if (!row.enabled || row.issue != null) continue
			val user = row.id in authored
			val owner = row.setting.outputParameters.firstNotNullOfOrNull { p -> claimed[p]?.let { p to it } }
			resolved[row.id] = when {
				owner == null -> row.also { r -> r.setting.outputParameters.forEach { claimed[it] = r.id } }
				!user && owner.second in authored -> row.copy(shadowedBy = owner.second)
				else -> row.copy(issue = PhysicsIssue(PhysicsIssue.Code.OUTPUT_TAKEN, owner.first, owner.second))
			}
		}
		return rows.map { resolved.getValue(it.id) }
	}

	fun groups(analysis: PipelineAnalysis?, config: PipelineConfig, available: Set<String>, model: org.umamo.runtime.model.PuppetModel? = null): List<PhysicsGroup> =
		groups(if (config.rigEdits.importedCmo3 != null) PhysicsGenerator.Presets(false, false, false)
			else PhysicsGenerator.Presets.present(analysis, config.hairSimulationFront, config.hairSimulationBack), PhysicsGenerator.Presets.enabled(config), config.rigEdits, available, model)

	/** What exports, in evaluation order: nothing when physics is off or the model is mesh-only. */
	fun active(analysis: PipelineAnalysis?, config: PipelineConfig, available: Set<String>, model: org.umamo.runtime.model.PuppetModel? = null): List<RigPhysicsEdit> =
		if (!config.generatePhysics || config.meshOnly) emptyList()
		else groups(analysis, config, available, model).filter { it.active }.map { it.setting }

	/** [rows] with the IDs in [order] first, in that order, and the rest after them as they came. */
	internal fun <T : Any> ordered(rows: List<T>, order: List<String>, id: (T) -> String): List<T> {
		if (order.isEmpty()) return rows
		val place = order.withIndex().associate { (i, key) -> key to i }
		return rows.withIndex().sortedWith(compareBy({ place[id(it.value)] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
	}

	private fun ordered(rows: List<PhysicsGroup>, order: List<String>) = ordered(rows, order) { it.id }

	fun issueOf(setting: RigPhysicsEdit, available: Set<String>): PhysicsIssue? {
		setting.parameters.firstOrNull { it !in available }?.let { return PhysicsIssue(PhysicsIssue.Code.MISSING_PARAMETER, it) }
		if (setting.inputs.isEmpty()) return PhysicsIssue(PhysicsIssue.Code.NO_INPUT)
		if (setting.outputs.isEmpty()) return PhysicsIssue(PhysicsIssue.Code.NO_OUTPUT)
		setting.inputs.firstOrNull { input -> setting.outputs.any { it.parameter == input.parameter } }
			?.let { return PhysicsIssue(PhysicsIssue.Code.FEEDBACK, it.parameter) }
		return null
	}
}
