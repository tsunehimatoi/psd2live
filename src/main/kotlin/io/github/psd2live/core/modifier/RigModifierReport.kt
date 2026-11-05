package io.github.psd2live.core.modifier

import kotlinx.serialization.json.*

/** Derived observation data. Instance configuration and ownership remain in the document's stack. */
internal fun RigModifierResult.report(): JsonObject = buildJsonObject {
    val names = mapOf("mesh" to rig.meshes.associate { it.id to it.name }, "deformer" to rig.deformers.associate { it.id to it.name },
        "part" to rig.parts.associate { it.id to it.name }, "parameter" to rig.parameters.associate { it.id to it.name },
        "physics" to rig.physics.groups.associate { it.id to it.name }, "motion" to rig.clips.associate { it.id to it.name })
    fun name(kind: String, id: String): String? = names[if (kind == "warp") "deformer" else kind]?.get(id)
    putJsonArray("entries") {
        for (entry in entries) addJsonObject {
            put("id", entry.instance.id)
            put("type", entry.instance.type)
            put("version", entry.instance.version)
            put("enabled", entry.instance.enabled)
            put("status", entry.status.name.lowercase())
            putJsonArray("targets") {
                entry.instance.targets.forEach { target -> addJsonObject { put("kind", target.kind); put("id", target.id) } }
            }
            put("parameter_providers", JsonObject(entry.instance.parameterProviders.mapValues { JsonPrimitive(it.value) }))
            put("missing_dependencies", JsonArray(entry.missingDependencies.sorted().map(::JsonPrimitive)))
            putJsonArray("owned_keyforms") {
                entry.ownedKeyforms.forEach { form -> addJsonObject {
                    put("kind", form.kind); put("id", form.id); put("parameter", form.parameter)
                    name(form.kind, form.id)?.let { put("object_name", it) }
                    name("parameter", form.parameter)?.let { put("parameter_name", it) }
                    form.channel?.let { put("channel", it) }
                    form.key?.let { put("key", JsonObject(it.mapValues { JsonPrimitive(it.value) })) }
                } }
            }
            putJsonArray("override_issues") {
                entry.overrideIssues.forEach { issue -> addJsonObject {
                    put("kind", issue.kind.wire); put("target", issue.target)
                    put("key", JsonObject(issue.key.mapValues { JsonPrimitive(it.value) }))
                    put("points", issue.points); put("total", issue.total)
                    put("reason", issue.reason?.wire?.let(::JsonPrimitive) ?: JsonNull)
                    put("channel", issue.channel?.let(::JsonPrimitive) ?: JsonNull)
                } }
            }
            putJsonArray("contributions") {
                for (contribution in entry.contributions) addJsonObject {
                    put("kind", contribution.kind); put("id", contribution.id); put("change", contribution.change.name.lowercase())
                    name(if (contribution.mask != null) "mesh" else contribution.kind, contribution.mask?.target ?: contribution.id)?.let { put("name", it) }
                    if (contribution.kind == "physics" && RigLegacyBindings.find(entry.instance.type)?.hasPhysics == true) {
                        val slot = contribution.physicsSlot ?: entry.instance.localOutputName(contribution.id)
                            ?.takeIf { it.startsWith("physics:") }?.removePrefix("physics:")
                        slot?.let { put("physics_slot", it) }
                    }
                    contribution.mask?.let { mask -> putJsonObject("mask") {
                        put("target", mask.target)
                        put("before", JsonArray(mask.before.map(::JsonPrimitive))); put("after", JsonArray(mask.after.map(::JsonPrimitive)))
                        put("before_inverted", mask.beforeInverted); put("after_inverted", mask.afterInverted)
                    } }
                }
            }
        }
    }
}
