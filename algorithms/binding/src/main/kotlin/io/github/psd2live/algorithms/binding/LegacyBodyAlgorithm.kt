package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.*

/** Independent legacy body candidate, with explicit scaffold and optional head pitch input. */
class LegacyBodyAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(
        input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext, checkpoint: () -> Unit,
    ): ModifierModelChanges {
        require(instance.version == 1 && instance.targets.all { it.kind == "mesh" })
        val values = context.values
        require(values["schema"]?.jsonPrimitive?.content == LegacyBodyInputs.SCHEMA)
        val providerId = instance.config.getValue("scaffold").jsonPrimitive.content
        val capability = context.upstream.singleOrNull {
            it.capability == "rig.scaffold" && it.instanceId == providerId && instance.targets.all(it.targets::contains)
        } ?: throw IllegalArgumentException("Missing body scaffold provider $providerId")
        require(capability.version in 1..2) { "Unsupported body scaffold version" }
        val shared = capability.version == 2 || capability.outputAliases.keys.any { it.startsWith("shared/deformer:") }
        require(!shared || instance.targets.toSet() == capability.targets.toSet()) { "Shared body scaffold requires its complete target scope" }
        val provider = RigModifierInstance(providerId, "rig.scaffold", capability.version, capability.targets,
            config = capability.config, outputAliases = capability.outputAliases, plugin = capability.plugin)
        val scaffold = ModifierAlgorithmContext(values.getValue("scaffold").jsonObject,
            context.resources - LegacyBodyInputs.HEAD_POSES)
        require(scaffold.values["schema"]?.jsonPrimitive?.content == "scaffold/5") { "Body requires source scaffold inputs" }
        val frames = ScaffoldFramesContext.decode(scaffold.values.getValue("frames").jsonObject, checkpoint)
        val neutral = ScaffoldAlgorithm().buildSource(scaffold, checkpoint)
        val branches = ScaffoldBranchesContext.build(scaffold.values.getValue("branches").jsonObject,
            neutral.deformers.filter { ScaffoldFramesContext.owns(it.id) }, checkpoint)
        val tuning = LegacyBodyInputs.tuning(values.getValue("tuning").jsonObject)
        val strength = instance.config["strength"]?.let(LegacyBodyInputs::finite) ?: 1f
        require(strength in 0f..4f)
        val standing = frames.standing
        val legs = standing?.legs.orEmpty().map { leg ->
            BodyStance.Leg(Side.NONE, leg.hip.x, leg.hip.y, leg.knee.x, leg.knee.y, leg.ankle.x, leg.ankle.y, leg.floorY)
        }
        val stance = BodyStance(frames.body, LegacyBodyInputs.torso(values.getValue("torso")), legs, standing?.frame, strength, tuning)
        val moved = LegacyBodyMotion.deformers(neutral.deformers, frames.body, stance, strength, frames.head.canvasPivot.y,
            branches.armFrames, checkpoint)
        val canonical = scaffold.values.getValue("canonical_targets").jsonObject
        val headPitch = headPitchGrids(context, instance, neutral, canonical, tuning, checkpoint)
        val parameters = LegacyBodyMotion.parameters.map { id ->
            Parameter(id, values.getValue("parameter_names").jsonObject.getValue(id.raw).jsonPrimitive.content,
                if (id == LegacyBodyMotion.breath) 0f else -10f, if (id == LegacyBodyMotion.breath) 1f else 10f, 0f)
        }
        val groups = values.getValue("group_names").jsonObject.map { (id, name) ->
            ParameterNode.Group(ParameterGroupId(id), name.jsonPrimitive.content, true,
                if (id == "ParamGroupBody") parameters.map { ParameterNode.Param(it.id) } else emptyList())
        }
        val desired = neutral.copy(parameters = parameters, deformers = moved.map { headPitch[it.id] ?: it },
            parameterTree = groups, parameterLinks = listOf(ParameterLink(LegacyBodyMotion.bodyX, LegacyBodyMotion.bodyY)))
        val authored = PuppetIr.toPuppet(input)
        val comparison = BindingComparison()
        val statics = java.util.IdentityHashMap<PuppetModel, PuppetModel>()
        val projections = HashMap<String, Pair<PuppetModel, PuppetModel>>()
        val source = object : BindingMerge.Source {
            override fun provider(instance: RigModifierInstance) = provider
            override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                projections.getOrPut(target.id) {
                    val original = canonical.getValue(target.id).jsonPrimitive.content
                    if (original == target.id) neutral to desired else {
                        val remap = ModelIdRemap(drawables = mapOf(DrawableId(original) to DrawableId(target.id)))
                        remap.model(neutral) to remap.model(desired)
                    }
                }
            override fun resolveParameter(instance: RigModifierInstance, before: io.github.psd2live.format.model.Parameter,
                                          declared: io.github.psd2live.format.model.Parameter): io.github.psd2live.format.model.Parameter? = null
            override fun static(model: PuppetModel) = statics.getOrPut(model) { BindingProjection.retain(model, emptySet(), checkpoint = checkpoint) }
            override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) =
                comparison.onlyMasksChange(before, after, DrawableId(target.id), branch)
            override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                neutral.deformers.associate { it.id to DeformerId(ScaffoldBinding.deformerId(provider, target.id, it.id.raw)) }
            override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                neutral.parts.associate { it.id to PartId(ScaffoldBinding.partId(provider, target.id, it.id.raw)) }
            override fun requiredParts(model: PuppetModel, target: ModifierTarget): Set<PartId> =
                error("Legacy body cannot change scaffold membership")
            override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) =
                ScaffoldBinding.partId(instance, mesh, canonical, shared)
            override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable,
                                       checkpoint: () -> Unit): List<VertexSource> =
                BodyVertexSources.match(authored, original, target, checkpoint)
        }
        val result = BindingMerge.apply(authored, instance, source, checkpoint)
        val header = PuppetIr.toIr(result.copy(parts = emptyList(), deformers = emptyList(), drawables = emptyList(),
            sources = emptyList(), atlas = PuppetAtlas.Empty, deformPaths = emptyList(), vertexGroups = emptyList(), glues = emptyList()))
        return ModifierModelChanges(
            parameters = ModifierObjectChanges(added = header.parameters.filter { p -> input.parameters.none { it.id == p.id } }),
            parameterTree = change(input.parameterTree, header.parameterTree),
            parameterLinks = change(input.parameterLinks, header.parameterLinks),
            renderRoot = change(input.renderRoot, header.renderRoot),
            meshes = edits(input.meshes, authored.drawables, result.drawables, { it.id }, { it.id.raw }, PuppetIr::meshToIr),
            deformers = edits(input.deformers, authored.deformers, result.deformers, { it.id }, { it.id.raw }, PuppetIr::deformerToIr),
        )
    }

    private fun headPitchGrids(
        context: ModifierAlgorithmContext, instance: RigModifierInstance, neutral: PuppetModel, canonical: JsonObject,
        tuning: BodyMotionTuning, checkpoint: () -> Unit,
    ): Map<DeformerId, Deformer> {
        val byId = neutral.deformers.associateBy { it.id }
        val targets = instance.targets.map { DrawableId(canonical.getValue(it.id).jsonPrimitive.content) }.toSet()
        val headTarget = neutral.drawables.any { mesh ->
            mesh.id in targets && generateSequence(mesh.parentDeformerId) { byId.getValue(it).parent }.any { it == ScaffoldHead.rotationId }
        }
        val resource = context.resources[LegacyBodyInputs.HEAD_POSES]
        if (!headTarget) return emptyMap()
        require(resource != null) { "Legacy body requires independent head pitch grids for head targets" }
        val data = RigIrBinary.decode(resource.read())
        require(data == RigIR(data.canvas, emptyList(), deformers = data.deformers)) { "Body head input may contain only head pitch grids" }
        val grids = PuppetIr.toPuppet(data).deformers.associateBy { it.id }
        require(ScaffoldHead.headId in grids && grids.size == data.deformers.size && grids.keys.all { id ->
            val node = byId[id]
            grids[id] is Deformer.Warp && node is Deformer.Warp && generateSequence(node.parent) { byId.getValue(it).parent }.any { it == ScaffoldHead.rotationId }
        }) { "Head pitch inputs must belong to the scaffold head" }
        val headY = ParameterId("ParamAngleY")
        val headX = ParameterId("ParamAngleX")
        val headZ = ParameterId("ParamAngleZ")
        val warps = neutral.deformers.mapNotNull { node ->
            checkpoint()
            val sample = grids[node.id] as? Deformer.Warp ?: return@mapNotNull null
            require(node is Deformer.Warp && sample.rows == node.rows && sample.columns == node.columns)
            val grid = requireNotNull(sample.geometryGrid)
            require(grid.axes.any { it.parameterId == headY } && grid.axes.all { it.parameterId in setOf(headX, headY, headZ) })
            node.copy(geometryGrid = grid)
        }
        val pose = neutral.copy(parameters = listOf(Parameter(headX, headX.raw, -45f, 45f, 0f),
            Parameter(headY, headY.raw, -30f, 30f, 0f), Parameter(headZ, headZ.raw, -30f, 30f, 0f), Parameter(LegacyBodyMotion.lean, "", -10f, 10f, 0f)),
            deformers = LegacyBodyMotion.withFaceLean(warps, tuning), drawables = emptyList())
        return BindingProjection.retain(pose, setOf(LegacyBodyMotion.lean), checkpoint = checkpoint).deformers.associateBy { it.id }
    }

    private fun <T> change(before: T, after: T) = if (before == after) null else ModifierFieldChange(before, after)

    private fun <E, I> edits(
        original: List<I>, before: List<E>, after: List<E>, irId: (I) -> String, id: (E) -> String, convert: (E) -> I,
    ): ModifierObjectChanges<I> {
        val existing = original.associateBy(irId)
        val engine = before.associateBy(id)
        val added = mutableListOf<I>()
        val updated = mutableListOf<ModifierObjectUpdate<I>>()
        for (item in after) {
            val key = id(item)
            if (engine[key] === item) continue
            val previous = existing[key]
            val next = convert(item)
            if (previous == null) added += next else if (previous != next) updated += ModifierObjectUpdate(previous, next)
        }
        val kept = after.mapTo(HashSet(), id)
        return ModifierObjectChanges(added, updated, original.filter { irId(it) !in kept })
    }
}
