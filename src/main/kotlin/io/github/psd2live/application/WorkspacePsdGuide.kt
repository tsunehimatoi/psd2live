package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.ModifierPartConfig
import io.github.psd2live.core.modifier.ModifierStatus
import io.github.psd2live.core.modifier.RigLegacyBindings
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.project.OwnedProjectSource
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.config
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import org.umamo.runtime.model.Parameter
import java.nio.file.Path

/** A candidate belongs to the import page until the ordinary source-install boundary publishes it. */
internal class WorkspacePsdGuide(
    val path: Path,
    val storeRoot: Path,
    val owned: OwnedProjectSource,
    val base: Candidate,
) {
    data class Candidate(val document: WorkspaceDocument, val model: RigPreviewModel)
    /** Immutable renderer inputs, independent of the currently installed project's views and pose. */
    class Preview(val model: RigPreviewModel, val bounds: Bounds, val visible: Set<String>)

    fun preview(candidate: Candidate): Preview = Preview(candidate.model, candidate.model.analysis.anchors.character,
        candidate.document.renderVisibleLayers(candidate.model))

    data class Turn(val instance: RigModifierInstance, val x: Parameter, val y: Parameter)
    data class Split(val id: String, val names: List<String>, val sides: List<Side>) {
        fun operation() = WorkspaceDocumentOperation("source_split_components", buildJsonObject {
            put("layer_id", id)
            put("names", JsonArray(names.map(::JsonPrimitive)))
            put("sides", JsonArray(sides.map { JsonPrimitive(it.name.lowercase()) }))
        })
    }

    private val builder = WorkspacePreviewBuilder(deferRuntimeBundles = true)

    suspend fun splits(candidate: Candidate): Map<String, MeshComponentSplit.Plan> {
        val context = currentCoroutineContext()
        return runInterruptible(Dispatchers.Default) {
            candidate.model.analysis.layers.mapNotNull { layer ->
                context.ensureActive()
                val id = layer.source.id.raw
                WorkspacePartitionEdits.componentPlan(candidate.model, id, context::ensureActive)?.let { id to it }
            }.toMap()
        }
    }

    suspend fun defaultSplits(candidate: Candidate): List<Split> {
        val namesById = candidate.model.analysis.layers.associate { it.source.id.raw to it.source.name }
        return splits(candidate).map { (id, plan) -> defaultSplit(id, namesById.getValue(id), plan) }
    }

    suspend fun split(candidate: Candidate, decisions: List<Split>): Candidate {
        val context = currentCoroutineContext()
        val document = runInterruptible(Dispatchers.Default) {
            decisions.fold(candidate.document) { document, decision ->
                WorkspacePartitionEdits.apply(decision.operation(), document, candidate.model,
                    WorkspaceRasterWork.Direct.cancellable(context), freshSource = true)
            }
        }
        return Candidate(document, builder.build(document, candidate.model))
    }

    suspend fun bindings(candidate: Candidate, overrides: Map<String, LayerClassificationOverride>): PsdGuideBindings.Result =
        runInterruptible(Dispatchers.Default) { PsdGuideBindings.inspect(candidate.model.analysis, overrides) }

    suspend fun preset(candidate: Candidate, bindings: PsdGuideBindings.Result, id: String, richness: String): Candidate {
        val classified = candidate.document.copy(layerOverrides = candidate.document.layerOverrides + bindings.overrides)
        val baseline = if (classified.layerOverrides == candidate.document.layerOverrides) candidate.model
            else builder.build(classified, candidate.model)
        val context = currentCoroutineContext()
        val document = runInterruptible(Dispatchers.Default) {
            WorkspacePresetSwitch.apply(classified, id, WorkspaceRasterWork.Direct.cancellable(context), baseline, richness)
        }
        return Candidate(document, builder.build(document, baseline))
    }

    fun turn(candidate: Candidate): Turn? {
        val active = candidate.model.modifierEvaluation?.entries.orEmpty()
            .filter { it.status == ModifierStatus.ACTIVE }.map { it.instance }
        val instance = active.lastOrNull { it.type == "face.depth-turn" }
            ?: active.lastOrNull { it.type == "face" && it.config["mode"]?.jsonPrimitive?.contentOrNull == "legacy" &&
                ModifierPartConfig.enabled(it.config, "depthTurn") }
            ?: active.lastOrNull { it.type == RigLegacyBindings.FITTED_FACE ||
                it.type == "face" && it.config["mode"]?.jsonPrimitive?.contentOrNull != "legacy" }
            ?: return null
        val parameters = candidate.model.rig.puppet.parameters.associateBy { it.id }
        return Turn(instance, parameters[StandardParameters.ANGLE_X] ?: return null, parameters[StandardParameters.ANGLE_Y] ?: return null)
    }

    fun turnValues(turn: Turn): Map<String, Float> {
        val instance = turn.instance
        val config = instance.config
        val sourceConfig = if (instance.type == "face" && config["mode"]?.jsonPrimitive?.contentOrNull == "legacy") {
            config["subConfigs"]?.jsonObject?.get("face.depth-turn")?.jsonObject ?: JsonObject(emptyMap())
        } else {
            config
        }
        val stored = sourceConfig["values"]?.jsonObject.orEmpty()
        return io.github.psd2live.core.recipe.RigModules.FACE_DEPTH_TURN.knobs.associate { knob ->
            knob.id to (stored[knob.id]?.jsonPrimitive?.floatOrNull ?: knob.default)
        }
    }

    private fun turnRequest(turn: Turn, values: Map<String, Float>): JsonObject {
        val instance = turn.instance
        val config = instance.config
        fun updated(original: JsonObject) = JsonObject(original + ("values" to
            JsonObject(original["values"]?.jsonObject.orEmpty() + values.mapValues { JsonPrimitive(it.value) })))
        val next = if (instance.type == "face" && instance.config["mode"]?.jsonPrimitive?.contentOrNull == "legacy") {
            val type = "face.depth-turn"
            val children = config["subConfigs"]?.jsonObject.orEmpty()
            val child = children[type]?.jsonObject ?: JsonObject(emptyMap())
            JsonObject(config + ("subConfigs" to JsonObject(children + (type to updated(child)))))
        } else updated(config)
        return buildJsonObject {
            put("id", instance.id)
            put("type", instance.type)
            put("config", next)
        }
    }

    suspend fun withStyle(candidate: Candidate, turn: Turn, values: Map<String, Float>): Candidate {
        val context = currentCoroutineContext()
        val document = runInterruptible(Dispatchers.Default) {
            WorkspaceModifierEdits.apply(WorkspaceDocumentOperation("modifier_update", turnRequest(turn, values)),
                candidate.document, candidate.model, WorkspaceRasterWork.Direct.cancellable(context))
        }
        val styled = Candidate(document, builder.build(document, candidate.model))
        if (turn(styled)?.instance?.id != turn.instance.id) {
            styled.model.modifierBuildOwner?.takeUnless { it.published || it === candidate.model.modifierBuildOwner }?.close()
            error("Head turn could not be generated")
        }
        return styled
    }

    suspend fun prepareStyle(candidate: Candidate, turn: Turn, values: Map<String, Float>): Preview =
        WorkspaceTurnStylePreview.modelSlots.withPermit {
            val model = WorkspaceDocumentCommands.buildTurnStyleStack(candidate.document, candidate.model,
                turnRequest(turn, values), shareOwner = false)
            try {
                val entry = model.modifierEvaluation?.entries?.firstOrNull { it.instance.id == turn.instance.id }
                require(entry?.status == ModifierStatus.ACTIVE) { entry?.error ?: "Head turn could not be generated" }
                // Keep the immutable render data; each static style build can release its worker immediately.
                Preview(model.copy(), candidate.model.analysis.anchors.face, candidate.document.renderVisibleLayers(model))
            } finally {
                model.modifierBuildOwner?.takeUnless { it.published }?.close()
            }
        }

    companion object {
        fun defaultSplit(layerId: String, layerName: String, plan: MeshComponentSplit.Plan): Split {
            val components = plan.components
            val count = components.size
            val horizontal = count == 2 &&
                kotlin.math.abs(components[0].centerX - components[1].centerX) >=
                kotlin.math.abs(components[0].centerY - components[1].centerY)
            val names = MutableList(count) { "" }
            val sides = MutableList(count) { Side.NONE }
            when {
                count == 2 && horizontal -> {
                    val suffixes = listOf("r", "l")
                    val lrSides = listOf(Side.RIGHT, Side.LEFT)
                    components.indices.sortedBy { components[it].centerX }.forEachIndexed { position, component ->
                        names[component] = "$layerName-${suffixes[position]}"
                        sides[component] = lrSides[position]
                    }
                }
                count == 2 && !horizontal -> {
                    val suffixes = listOf("t", "b")
                    components.indices.sortedBy { components[it].centerY }.forEachIndexed { position, component ->
                        names[component] = "$layerName-${suffixes[position]}"
                    }
                }
                else -> {
                    for (i in 0 until count) {
                        names[i] = "$layerName-${i + 1}"
                    }
                }
            }
            return Split(layerId, names, sides)
        }
    }
}
