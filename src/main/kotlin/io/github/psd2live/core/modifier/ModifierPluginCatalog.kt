package io.github.psd2live.core.modifier

/** All catalog consumers use the same installed package snapshot. */
internal object ModifierPluginCatalog {
    fun definitions(store: ModifierPluginStore = ModifierPluginStore.shared,
                    captured: List<RigModifierDefinition> = emptyList(),
                    resolveRasters: ModifierRasterResolver = RigModifierRasterAssets::resolve,
                    references: Set<ModifierPluginReference>? = null,
                    workerSession: ModifierPluginWorkers.Session? = null): List<RigModifierDefinition> {
        val defaults = store.defaultPackages().mapTo(HashSet()) { it.reference }
        return store.runnablePackages()
        .filter { references == null || it.reference in references }.flatMap { entry ->
        val isBuiltin = entry.reference.id.startsWith("builtin.")
        entry.manifest.algorithms.flatMap { algorithm ->
            val types = if (isBuiltin) (algorithm.aliases + algorithm.type) else listOf(algorithm.type)
            types.map { type ->
            // Builtins match legacy aliases directly; standalone packages match only an explicit authorContext contract.
            val adapter = captured.singleOrNull {
                it.version == algorithm.version && it.plugin is ContextualModifierAlgorithmPlugin && if (isBuiltin) {
                    it.type in algorithm.aliases && (type == algorithm.type || it.type == type)
                } else {
                    algorithm.authorContext != null && it.type == algorithm.authorContext
                }
            }
            if (adapter != null) {
                fun native(instance: RigModifierInstance): RigModifierInstance {
                    // Reject only consumers of this exact package. An unused incompatible historical
                    // version must not prevent unrelated projects from constructing their registry.
                    require(adapter.requires == algorithm.requires && adapter.exclusiveFamily == algorithm.exclusiveFamily &&
                        adapter.composableKinds == algorithm.composableKinds && adapter.provides == algorithm.provides &&
                        (isBuiltin || adapter.changeWrites?.let { allowed -> algorithm.writes.all { it in allowed } } != false)) {
                        "Source adapter contract mismatch: $type (${entry.reference.id}@${entry.reference.version})"
                    }
                    return instance.copy(type = adapter.type)
                }
                val worker = object : ContextualModifierAlgorithmPlugin, ModifierOptionalUpstream, ModifierVersionSelection {
                    override val optionalUpstream = adapter.optionalUpstream
                    override val canonicalOptInType = (adapter.plugin as? ModifierVersionSelection)?.canonicalOptInType

                    override fun evaluate(input: io.github.psd2live.format.model.RigIR, instance: RigModifierInstance,
                                          context: ModifierAlgorithmContext, checkpoint: () -> Unit): io.github.psd2live.format.model.RigIR =
                        ModifierPluginWorkers.evaluate(entry, algorithm, input, instance, checkpoint, store,
                            context = context, resolveRasters = resolveRasters, declaredParameters = adapter.parameters(native(instance)), session = workerSession)
                }
                adapter.copy(type = type, pluginReference = entry.reference, plugin = worker,
                    parameters = { adapter.parameters(native(it)) },
                    authorContext = { (adapter.workerAuthorContext ?: adapter.authorContext)(native(it)) },
                    validate = { rig, instance -> adapter.validate(rig, native(instance)) },
                    providers = { adapter.providers(native(it)) },
                    resolveParameter = { instance, before, declared -> adapter.resolveParameter(native(instance), before, declared) },
                    references = { before, after, instance -> adapter.references(before, after, native(instance)) },
                    cacheReads = algorithm.reads + algorithm.writes, upstreamInputs = algorithm.contextVersion >= 2)
            } else {
                val worker = object : ContextualModifierAlgorithmPlugin {
                    override fun evaluate(input: io.github.psd2live.format.model.RigIR, instance: RigModifierInstance,
                                          context: ModifierAlgorithmContext, checkpoint: () -> Unit): io.github.psd2live.format.model.RigIR =
                        ModifierPluginWorkers.evaluate(entry, algorithm, input, instance, checkpoint, store, context = context,
                            resolveRasters = resolveRasters, session = workerSession)
                }
                RigModifierDefinition(type, algorithm.version, requires = algorithm.requires, provides = algorithm.provides,
                references = { before, after, _ -> ModifierPluginOutput.sidecars(before, after) },
                parameters = { algorithm.parameters.map { it.definition() } },
                exclusiveFamily = algorithm.exclusiveFamily, composableKinds = algorithm.composableKinds,
                validate = { _, instance ->
                    require(instance.targets.all { it.kind in algorithm.targets }) { "Unsupported plugin target" }
                    ModifierPluginSchema.validate(instance.config, algorithm.configSchema)
                },
                pluginReference = entry.reference, plugin = worker, cacheReads = algorithm.reads + algorithm.writes, upstreamInputs = algorithm.contextVersion >= 2)
            }
        }.map { it.copy(defaultSelection = entry.reference in defaults) } }
    }
    }

    fun available(instance: RigModifierInstance, store: ModifierPluginStore = ModifierPluginStore.shared): Boolean {
        instance.plugin?.let { exact ->
            if (store.packages().any { it.reference == exact && it.manifest.algorithms.any { algorithm ->
                    algorithm.version == instance.version && (algorithm.type == instance.type || instance.type in algorithm.aliases)
                } }) return store.resolution(exact).available
        }
        val resolvedVersion = BuiltinModifierPlugins.resolveVersion(instance.type, instance.version, instance.plugin)
        val builtin = BuiltinModifierPlugins.reference(instance.type, resolvedVersion)
        val reference = instance.plugin ?: builtin ?: return false
        val isBuiltin = reference == builtin || BuiltinModifierPlugins.matches(instance)
        if (!isBuiltin) {
            val manifest = store.packages().firstOrNull { it.reference == reference }?.manifest ?: return false
            if (manifest.algorithms.none {
                    it.version == instance.version && (it.type == instance.type || instance.type in it.aliases)
                }) return false
        }
        return store.resolution(if (isBuiltin) (builtin ?: reference) else reference).available
    }

    fun available(type: String, version: Int, plugin: ModifierPluginReference? = null,
                  store: ModifierPluginStore = ModifierPluginStore.shared): Boolean {
        if (plugin != null) store.packages().firstOrNull { it.reference == plugin }?.let { entry ->
            if (entry.manifest.algorithms.any { it.version == version && (it.type == type || type in it.aliases) })
                return store.resolution(plugin).available
            if (!BuiltinModifierPlugins.isHistoricalBuiltinReference(type, version, plugin)) return false
        }
        val resolvedVersion = BuiltinModifierPlugins.resolveVersion(type, version, plugin)
        val builtin = BuiltinModifierPlugins.reference(type, resolvedVersion)
        val reference = plugin?.takeUnless {
            it.id == "builtin" || BuiltinModifierPlugins.isHistoricalBuiltinReference(type, version, it)
        } ?: builtin
        return reference?.let { store.resolution(it).available } ?: store.builtinsEnabled()
    }

    fun algorithm(type: String, version: Int? = null, store: ModifierPluginStore = ModifierPluginStore.shared): Pair<InstalledModifierPlugin, ModifierPluginAlgorithm>? {
        return store.defaultPackages().asSequence().flatMap { entry ->
            val matching = entry.manifest.algorithms.filter { it.type == type && (version == null || it.version == version) }
            if (matching.isEmpty() || !store.resolution(entry.reference).available) emptySequence()
            else matching.asSequence().map { entry to it }
        }.maxWithOrNull(compareBy<Pair<InstalledModifierPlugin, ModifierPluginAlgorithm>> { it.second.version }
            .thenBy { ModifierPluginVersion.parse(it.first.reference.version, legacy = true) })
    }

    fun requireAvailable(stack: RigModifierStack) {
        val missing = stack.instances.filter { it.enabled && !available(it) }
        require(missing.isEmpty()) { "Missing or disabled algorithm plugins: ${missing.joinToString { "${it.id} (${it.type}@${it.version})" }}" }
    }
}
