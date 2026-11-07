package io.github.psd2live.core.modifier

/** All catalog consumers use the same installed package snapshot. */
internal object ModifierPluginCatalog {
    fun definitions(store: ModifierPluginStore = ModifierPluginStore.shared,
                    captured: List<RigModifierDefinition> = emptyList(),
                    resolveRasters: ModifierRasterResolver = RigModifierRasterAssets::resolve): List<RigModifierDefinition> = store.snapshot().flatMap { entry ->
        entry.manifest.algorithms.flatMap { algorithm -> (algorithm.aliases + algorithm.type).map { type ->
            // Only bundled stable aliases can use host-captured author input adapters.
            val adapter = captured.singleOrNull { it.type in algorithm.aliases && (type == algorithm.type || it.type == type) && it.version == algorithm.version &&
                it.plugin is ContextualModifierAlgorithmPlugin && entry.reference.id.startsWith("builtin.") }
            if (adapter != null) {
                fun native(instance: RigModifierInstance): RigModifierInstance {
                    // Reject only consumers of this exact package. An unused incompatible historical
                    // version must not prevent unrelated projects from constructing their registry.
                    require(adapter.requires == algorithm.requires && adapter.exclusiveFamily == algorithm.exclusiveFamily &&
                        adapter.composableKinds == algorithm.composableKinds && adapter.provides == algorithm.provides) {
                        "Bundled source adapter contract mismatch: $type (${entry.reference.id}@${entry.reference.version})"
                    }
                    return instance.copy(type = adapter.type)
                }
                val worker = object : ContextualModifierAlgorithmPlugin, ModifierOptionalUpstream, ModifierVersionSelection {
                    override val optionalUpstream = adapter.optionalUpstream
                    override val canonicalOptInType = (adapter.plugin as? ModifierVersionSelection)?.canonicalOptInType

                    override fun evaluate(input: io.github.psd2live.format.model.RigIR, instance: RigModifierInstance,
                                          context: ModifierAlgorithmContext, checkpoint: () -> Unit): io.github.psd2live.format.model.RigIR =
                        ModifierPluginWorkers.evaluate(entry, algorithm, input, instance, checkpoint, store,
                            context = context, resolveRasters = resolveRasters, declaredParameters = adapter.parameters(native(instance)))
                }
                adapter.copy(type = type, pluginReference = entry.reference, plugin = worker, evaluate = worker::evaluate,
                    parameters = { adapter.parameters(native(it)) },
                    authorContext = { adapter.authorContext(native(it)) },
                    validate = { rig, instance -> adapter.validate(rig, native(instance)) },
                    providers = { adapter.providers(native(it)) },
                    resolveParameter = { instance, before, declared -> adapter.resolveParameter(native(instance), before, declared) },
                    references = { before, after, instance -> adapter.references(before, after, native(instance)) },
                    cacheReads = algorithm.reads + algorithm.writes, upstreamInputs = algorithm.contextVersion >= 2)
            } else {
                val worker = object : ContextualModifierAlgorithmPlugin {
                    override fun evaluate(input: io.github.psd2live.format.model.RigIR, instance: RigModifierInstance,
                                          context: ModifierAlgorithmContext, checkpoint: () -> Unit): io.github.psd2live.format.model.RigIR =
                        ModifierPluginWorkers.evaluate(entry, algorithm, input, instance, checkpoint, store, context = context, resolveRasters = resolveRasters)
                }
                RigModifierDefinition(type, algorithm.version, requires = algorithm.requires, provides = algorithm.provides,
                references = { before, after, _ -> ModifierPluginOutput.sidecars(before, after) },
                parameters = { algorithm.parameters.map { it.definition() } },
                exclusiveFamily = algorithm.exclusiveFamily, composableKinds = algorithm.composableKinds,
                validate = { _, instance ->
                    require(instance.targets.all { it.kind in algorithm.targets }) { "Unsupported plugin target" }
                    ModifierPluginSchema.validate(instance.config, algorithm.configSchema)
                }, evaluate = { rig, instance, checkpoint -> ModifierPluginWorkers.evaluate(entry, algorithm, rig, instance, checkpoint, store, resolveRasters = resolveRasters) },
                pluginReference = entry.reference, plugin = worker, cacheReads = algorithm.reads + algorithm.writes, upstreamInputs = algorithm.contextVersion >= 2)
            }
        } }
    }

    fun available(instance: RigModifierInstance): Boolean = available(instance.type, instance.version, instance.plugin)

    fun available(type: String, version: Int, plugin: ModifierPluginReference? = null): Boolean {
        val reference = plugin ?: BuiltinModifierPlugins.reference(type, version)
        val store = ModifierPluginStore.shared
        return reference?.let { store.resolution(it).available } ?: store.builtinsEnabled()
    }

    fun algorithm(type: String, version: Int? = null): Pair<InstalledModifierPlugin, ModifierPluginAlgorithm>? {
        val store = ModifierPluginStore.shared
        return store.snapshot().asSequence().flatMap { entry ->
            val matching = entry.manifest.algorithms.filter { it.type == type && (version == null || it.version == version) }
            if (matching.isEmpty() || !store.resolution(entry.reference).available) emptySequence()
            else matching.asSequence().map { entry to it }
        }.maxWithOrNull(compareBy<Pair<InstalledModifierPlugin, ModifierPluginAlgorithm>> { it.second.version }
            .thenBy { ModifierPluginVersion.parse(it.first.reference.version, legacy = true) })
    }

    fun requireAvailable(stack: RigModifierStack) {
        val missing = stack.instances.filter { it.enabled && !available(it) }
        require(missing.isEmpty()) { "Missing or disabled algorithm plugins: ${missing.joinToString { "${it.id} (${it.type})" }}" }
    }
}
