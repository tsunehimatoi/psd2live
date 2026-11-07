package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

object LegacyFaceV2Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(
        mapOf((LegacyFaceV2Algorithm.ENTRYPOINT to LegacyFaceV2Algorithm.VERSION) to LegacyFaceV2Algorithm))
}

object LegacyFaceV2Algorithm : ChangingModifierAlgorithmPlugin {
    const val TYPE = "face/legacy"
    const val VERSION = 2
    const val ENTRYPOINT = "builtin.666163652f6c6567616379.v2/binding"
    val WRITES = setOf("mesh", "deformer", "hierarchy", "relations", "parameter")

    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.isNotEmpty() && instance.targets.all { it.kind == "mesh" })
        val providerId = instance.config.getValue("scaffold").jsonPrimitive.content
        val capability = context.upstream.singleOrNull { it.capability == "rig.scaffold" && it.instanceId == providerId }
            ?: throw IllegalArgumentException("Missing face scaffold provider $providerId")
        val provider = RigModifierInstance(providerId, capability.type, capability.version, capability.targets,
            config = capability.config, outputAliases = capability.outputAliases, plugin = capability.plugin)
        require(provider.version == 2 && provider.targets.toSet() == instance.targets.toSet()) {
            "Legacy face v2 requires the complete shared scaffold target set"
        }
        val sourceInput = ModifierAlgorithmContext(context.values.getValue("face").jsonObject, context.resources)
        val scaffold = LegacyFaceInputs.scaffold(sourceInput)
        val measured = LegacyFaceInputs.decode(sourceInput, checkpoint)
        val neutral = ScaffoldAlgorithm().buildSource(scaffold, checkpoint)
        val angles = angles(measured)
        val body = context.values["body"]?.jsonObject
        val bodyCapability = context.upstream.singleOrNull { it.capability == "body/legacy" }
        require(context.upstream.count { it.capability == "body/legacy" } <= 1 &&
            (body == null) == (bodyCapability == null)) { "Invalid legacy face body input" }
        val before = if (body == null) neutral else {
            requireNotNull(bodyCapability)
            require(bodyCapability.instanceId == body.getValue("id").jsonPrimitive.content && bodyCapability.version in 1..2 &&
                bodyCapability.config["scaffold"]?.jsonPrimitive?.content == providerId && bodyCapability.targets.toSet() == instance.targets.toSet()) {
                "Legacy face v2 requires its preceding body to use the same shared scaffold"
            }
            precedingBody(neutral, scaffold, measured, body, bodyCapability, checkpoint)
        }
        val after = LegacyFaceBinding.bind(before, measured.measurements, measured.tuning, measured.strength,
            angles, measured.folderLabel, checkpoint)
        val actual = PuppetIr.toPuppet(input)
        val canonical = scaffold.values.getValue("canonical_targets").jsonObject
        val statics = java.util.IdentityHashMap<PuppetModel, PuppetModel>()
        val comparison = BindingComparison()
        val projections = HashMap<String, Pair<PuppetModel, PuppetModel>>()
        val transition = object : BindingMerge.Source {
            override fun provider(instance: RigModifierInstance) = provider
            override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                projections.getOrPut(target.id) {
                    val original = canonical.getValue(target.id).jsonPrimitive.content
                    if (original == target.id) before to after else {
                        val remap = ModelIdRemap(drawables = mapOf(DrawableId(original) to DrawableId(target.id)))
                        remap.model(before) to remap.model(after)
                    }
                }
            override fun resolveParameter(instance: RigModifierInstance, before: io.github.psd2live.format.model.Parameter,
                                          declared: io.github.psd2live.format.model.Parameter): io.github.psd2live.format.model.Parameter? = null
            override fun static(model: PuppetModel) = statics.getOrPut(model) {
                BindingProjection.retain(model, emptySet(), checkpoint = checkpoint)
            }
            override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) =
                comparison.onlyMasksChange(before, after, DrawableId(target.id), branch)
            override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                neutral.deformers.associate { it.id to DeformerId(ScaffoldBinding.deformerId(provider, target.id, it.id.raw)) }
            override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                neutral.parts.associate { it.id to PartId(ScaffoldBinding.partId(provider, target.id, it.id.raw)) }
            override fun requiredParts(model: PuppetModel, target: ModifierTarget): Set<PartId> =
                error("Legacy face cannot change scaffold membership")
            override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) =
                ScaffoldBinding.partId(instance, mesh, canonical, shared)
            override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable,
                                       checkpoint: () -> Unit) = BodyVertexSources.match(actual, original, target, checkpoint)
        }
        return LegacyFaceChanges.lower(input, actual, BindingMerge.apply(actual, instance, transition, checkpoint))
    }

    private fun precedingBody(neutral: PuppetModel, scaffold: ModifierAlgorithmContext, measured: LegacyFaceInputs.Input,
                              body: JsonObject, provider: ModifierUpstreamCapability, checkpoint: () -> Unit): PuppetModel {
        val frames = ScaffoldFramesContext.decode(scaffold.values.getValue("frames").jsonObject, checkpoint)
        val branches = ScaffoldBranchesContext.build(scaffold.values.getValue("branches").jsonObject,
            neutral.deformers.filter { ScaffoldFramesContext.owns(it.id) }, checkpoint)
        val tuning = LegacyBodyInputs.tuning(body.getValue("tuning").jsonObject)
        val strength = provider.config["strength"]?.let(LegacyBodyInputs::finite) ?: 1f
        require(strength in 0f..4f)
        val legs = frames.standing?.legs.orEmpty().map { leg ->
            BodyStance.Leg(Side.NONE, leg.hip.x, leg.hip.y, leg.knee.x, leg.knee.y, leg.ankle.x, leg.ankle.y, leg.floorY)
        }
        val stance = BodyStance(frames.body, LegacyBodyInputs.torso(body.getValue("torso")), legs, frames.standing?.frame, strength, tuning)
        val deformers = LegacyBodyMotion.deformers(neutral.deformers, frames.body, stance, strength, frames.head.canvasPivot.y,
            branches.armFrames, checkpoint)
        val defaults = LegacyFaceTuning()
        val head = LegacyFaceBinding.bind(neutral, measured.measurements, defaults, 1f, angles(measured), measured.folderLabel, checkpoint)
        val parameterNames = body.getValue("parameter_names").jsonObject
        val parameters = LegacyBodyMotion.parameters.map { id ->
            val breath = id == LegacyBodyMotion.breath
            Parameter(id, parameterNames.getValue(id.raw).jsonPrimitive.content,
                if (breath) 0f else -10f, if (breath) 1f else 10f, 0f)
        }
        val pitching = head.deformers.filterIsInstance<Deformer.Warp>().filter { warp ->
            warp.geometryGrid?.axes.orEmpty().any { it.parameterId == LegacyFaceBinding.angleY }
        }
        val pose = head.copy(parameters = head.parameters + parameters, drawables = emptyList(),
            deformers = LegacyBodyMotion.withFaceLean(pitching, tuning))
        val pitch = BindingProjection.retain(pose, setOf(LegacyBodyMotion.lean), checkpoint = checkpoint).deformers.associateBy { it.id }
        return neutral.copy(parameters = parameters, deformers = deformers.map { pitch[it.id] ?: it })
    }

    private fun angles(input: LegacyFaceInputs.Input) =
        listOf(LegacyFaceBinding.angleX, LegacyFaceBinding.angleY, LegacyFaceBinding.angleZ).mapIndexed { index, id ->
            val limit = if (index == 0) 45f else 30f
            Parameter(id, input.parameterLabels[index], -limit, limit, 0f)
        }
}
