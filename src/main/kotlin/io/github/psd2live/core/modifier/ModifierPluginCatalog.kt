package io.github.psd2live.core.modifier

/** All catalog consumers use the same installed package snapshot. */
internal object ModifierPluginCatalog {
    fun definitions(): List<RigModifierDefinition> = ModifierPluginStore.shared.snapshot().flatMap { entry ->
        entry.manifest.algorithms.map { algorithm ->
            RigModifierDefinition(algorithm.type, algorithm.version, requires = algorithm.requires,
                references = { before, after, _ -> ModifierPluginOutput.sidecars(before, after) },
                parameters = { algorithm.parameters.map { it.definition() } },
                exclusiveFamily = algorithm.exclusiveFamily, composableKinds = algorithm.composableKinds,
                validate = { _, instance ->
                    require(instance.targets.all { it.kind in algorithm.targets }) { "Unsupported plugin target" }
                    ModifierPluginSchema.validate(instance.config, algorithm.configSchema)
                }, evaluate = { rig, instance, checkpoint -> ModifierPluginWorkers.evaluate(entry, algorithm, rig, instance, checkpoint) },
                pluginReference = entry.reference, cacheReads = algorithm.reads + algorithm.writes)
        }
    }

    fun available(instance: RigModifierInstance): Boolean = instance.plugin?.let { reference ->
        if (reference.id.startsWith("builtin.")) BuiltinModifierPlugins.matches(instance) && ModifierPluginStore.shared.builtinEnabled(reference.id)
        else ModifierPluginStore.shared.resolution(reference).available
    } ?: BuiltinModifierPlugins.reference(instance.type, instance.version)?.let { ModifierPluginStore.shared.builtinEnabled(it.id) }
        ?: ModifierPluginStore.shared.builtinsEnabled()

    fun algorithm(type: String, version: Int? = null): Pair<InstalledModifierPlugin, ModifierPluginAlgorithm>? =
        ModifierPluginStore.shared.snapshot().filter { ModifierPluginStore.shared.resolution(it.reference).available }.flatMap { entry -> entry.manifest.algorithms.map { entry to it } }
            .filter { it.second.type == type && (version == null || it.second.version == version) }
            .maxWithOrNull(compareBy<Pair<InstalledModifierPlugin, ModifierPluginAlgorithm>> { it.second.version }.thenBy { ModifierPluginVersion.parse(it.first.reference.version, legacy = true) })

    fun requireAvailable(stack: RigModifierStack) {
        val missing = stack.instances.filter { it.enabled && !available(it) }
        require(missing.isEmpty()) { "Missing or disabled algorithm plugins: ${missing.joinToString { "${it.id} (${it.type})" }}" }
    }
}
