package io.github.psd2live.core.modifier

import kotlinx.serialization.json.*

/** Read-only scope planning. It never treats configuration dependencies as disposable instance IDs. */
internal object ModifierApplyPlan {
    fun prepare(stack: RigModifierStack, id: String, reports: Map<String, JsonObject>,
                registry: RigModifierRegistry = RigModifierAlgorithms.registry, scope: String = "prefix"): JsonObject {
        require(scope in setOf("prefix", "selected")) { "Unknown application scope" }
        val prefix = if (scope == "selected") listOf(stack.instances[stack.index(id)]) else stack.instances.take(stack.index(id) + 1)
        val consumed = prefix.mapTo(HashSet()) { it.id }
        val remaining = stack.instances.filterNot { it.id in consumed }
        val dependencies = buildList<JsonObject> {
            for ((index, consumer) in stack.instances.withIndex()) {
                if (!consumer.enabled) continue
                val definition = stack.appliedNodes[consumer.id]?.definition(consumer) ?: registry.definitionOrNull(consumer) ?: continue
                fun edge(provider: String, reason: String) {
                    if ((provider in consumed) == (consumer.id in consumed)) return
                    add(buildJsonObject { put("provider", provider); put("consumer", consumer.id); put("reason", reason) })
                }
                definition.providers(consumer).forEach { (type, provider) -> edge(provider, "capability:$type") }
                for (required in definition.requires) for (target in consumer.targets) {
                    val explicit = definition.providers(consumer)[required]
                    stack.instances.take(index).lastOrNull {
                        it.enabled && target in it.targets &&
                            (stack.appliedNodes[it.id]?.capability?.let { capability -> registry.provides(capability, required) }
                                ?: registry.provides(it, required)) && (explicit == null || explicit == it.id)
                    }?.let { edge(it.id, "capability:$required") }
                }
                consumer.parameterProviders.forEach { (parameter, provider) -> edge(provider, "parameter:$parameter") }
                fun authoringInput(kind: String, objectId: String) {
                    stack.instances.take(index).lastOrNull { provider ->
                        provider.enabled && reports[provider.id]?.get("contributions")?.jsonArray.orEmpty().any { value ->
                            val contribution = value.jsonObject
                            contribution["kind"]?.jsonPrimitive?.content == kind &&
                                contribution["id"]?.jsonPrimitive?.content == objectId &&
                                contribution["change"]?.jsonPrimitive?.content in setOf("added", "modified")
                        }
                    }?.let { edge(it.id, "authoring:$kind:$objectId") }
                }
                stack.overrides[consumer.id].orEmpty().forEach { command ->
                    command["providers"]?.jsonArray.orEmpty().forEach { edge(it.jsonPrimitive.content, "authoring") }
                    (command["key"] as? JsonObject)?.keys.orEmpty().forEach { authoringInput("parameter", it) }
                    command["target"]?.jsonPrimitive?.contentOrNull?.takeIf { ':' in it }?.let { target ->
                        val kind = target.substringBefore(':').let { if (it in setOf("warp", "rotation")) "deformer" else it }
                        authoringInput(kind, target.substringAfter(':'))
                    }
                }
                stack.appliedNodes[consumer.id]?.patchInputs.orEmpty().forEach { authoringInput(it.kind, it.id) }
                val motions = definition.motionInputs(consumer)
                if (motions.isNotEmpty()) for (producer in stack.instances.take(index).filter { it.enabled }) {
                    (stack.appliedNodes[producer.id]?.definition(producer) ?: registry.definitionOrNull(producer))?.motionOutputs?.invoke(producer)?.intersect(motions)?.forEach {
                        edge(producer.id, "motion:$it")
                    }
                }
            }
        }.distinct()
        val blockers = buildList<JsonObject> {
            if (scope == "selected" && (id in stack.appliedNodes || !prefix.single().enabled)) add(buildJsonObject {
                put("id", id); put("reason", if (id in stack.appliedNodes) "already_applied" else "disabled")
            })
            // The whole retained result must be valid, not merely the prefix being consumed.
            for (instance in stack.instances.filter { it.enabled }) {
                val report = reports[instance.id]
                val status = report?.get("status")?.jsonPrimitive?.contentOrNull
                fun block(reason: String) { add(buildJsonObject { put("id", instance.id); put("reason", reason) }) }
                if (status != "active") block(status ?: "missing_report")
                if (report?.get("override_issues")?.jsonArray.orEmpty().any {
                    it.jsonObject.getValue("kind").jsonPrimitive.content == "orphaned"
                }) block("override_orphaned")
            }
        }.distinct()
        fun ids(values: List<RigModifierInstance>) = JsonArray(values.map { JsonPrimitive(it.id) })
        return buildJsonObject {
            put("through", id); put("mode", scope)
            put("consumed", ids(prefix)); put("materialized", ids(prefix.filter { it.enabled }))
            put("disabled", ids(prefix.filterNot { it.enabled })); put("retained", ids(remaining))
            put("baked_overrides", prefix.filter { it.enabled }.sumOf { stack.overrides[it.id].orEmpty().size })
            put("dormant_overrides", prefix.filterNot { it.enabled }.sumOf { stack.overrides[it.id].orEmpty().size })
            put("dependencies", JsonArray(dependencies)); put("blockers", JsonArray(blockers))
            put("fixed_conflicts", stack.instances.filter { it.enabled }.sumOf { instance ->
                reports[instance.id]?.get("override_issues")?.jsonArray.orEmpty().count {
                    it.jsonObject.getValue("kind").jsonPrimitive.content == "conflict"
                }
            })
            put("can_apply", blockers.isEmpty()); put("requires_result_validation", true)
        }
    }
}
