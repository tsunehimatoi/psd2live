package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithms.physics.PhysicsGroups
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.PhysicsGroup
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.Deformer as IrDeformer
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/** Binds each skirt's waistband, panel sway and hem flare in the live torso branch. */
class SkirtAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val source = SkirtInputs.decode(input.context)
        val dependencies = instance.config["dependencies"]?.jsonObject.orEmpty()
        val providers = dependencies + ("rig.scaffold" to instance.config.getValue("scaffold"))
        require(providers.keys == setOf("rig.scaffold", "body/legacy", "face/legacy"))
        val upstream = providers.mapValues { (capability, owner) ->
            input.providers(capability).singleOrNull {
                it.instanceId == owner.jsonPrimitive.content && it.targets.containsAll(instance.targets)
            } ?: error("Missing skirt provider: $capability")
        }
        val structure = upstream.getValue("rig.scaffold")
        val scaffold = RigModifierInstance(requireNotNull(structure.instanceId), structure.type, structure.version,
            structure.targets, config = structure.config, outputAliases = structure.outputAliases, plugin = structure.plugin)
        val body = SkirtInputs.bounds(source.body)
        val values = instance.config["values"]?.jsonObject.orEmpty()
        require(values.keys.all { it == "swing" })
        val swing = values["swing"]?.jsonPrimitive?.float ?: 100f
        require(swing in 30f..200f)
        fun plan(ribbons: Boolean) = ChainMotion.plan(
            source.layers.filter { ribbons || it.kind == "SKIRT" }.map {
                ChainMotion.Layer(it.id, it.tag, SkirtInputs.bounds(it.bounds), it.pixels, ChainKind.valueOf(it.kind))
            }, Float.MAX_VALUE, { _, frame -> frame.top + frame.height * .12f }, swing / 100f, .5f,
        ).associateBy { it.layerId }
        val plain = plan(false)
        val mixed = if (source.ribbonTargets.isEmpty()) plain else plan(true)
        val layers = source.layers.filter { it.kind == "SKIRT" && !it.paired }.flatMap { layer ->
            layer.meshes.map { it to layer }
        }.toMap()
        val selected = instance.targets.filter { it.id in layers }
        if (selected.isEmpty()) {
            require(instance.config["physicsEnabled"]?.jsonPrimitive?.booleanOrNull == false ||
                instance.config["physics"]?.jsonObject.orEmpty().isEmpty()) { "Unavailable skirt physics group" }
            return ModifierModelChanges()
        }
        val raw = ScaffoldMeshContext.decodeRawMeshes(input.context.resources.getValue("source_meshes"), checkpoint)
        val beforeParameters = input.parameters
        val parameters = linkedMapOf<String, Parameter>()
        val warps = linkedMapOf<String, IrDeformer>()
        val active = linkedMapOf<String, ChainMotion>()
        val meshes = buildList {
            for (target in selected) {
                checkpoint()
                val id = target.id
                val layer = layers.getValue(id)
                val chain = (if (id in source.ribbonTargets) mixed else plain).getValue(layer.id)
                val aliases = chain.aliases(instance)
                val prior = active.putIfAbsent(chain.key, chain)
                require(prior == null || prior == chain) { "Conflicting skirt chain order for ${layer.id}" }
                for ((native, alias) in aliases) parameters.putIfAbsent(alias, Parameter(alias,
                    layer.parameterName.replace(TOKEN, native.removePrefix(chain.kind.prefix)), -1f, 1f, 0f,
                    blend = true, keys = Floats.values(-1f, 0f, 1f)))
                val before = requireNotNull(input.mesh(id)) { "Missing skirt mesh: $id" }
                val branch = input.parentsOfMesh(id)
                val model = PuppetIr.toPuppet(RigIR(input.canvas, beforeParameters, deformers = branch, meshes = listOf(before)))
                val mesh = model.drawables.single()
                val anchor = DeformerId(ScaffoldBinding.deformerId(scaffold, id, ScaffoldHead.breathId.raw))
                require(branch.any { it.id == anchor.raw }) { "Skirt $id requires the torso branch" }
                val part = PartId(ScaffoldBinding.partId(scaffold, id, ScaffoldParts.Slot.BODY.id.raw))
                val warpId = DeformerId(ScaffoldBinding.deformerId(instance, id, "SourceChain:${chain.key}", scaffold.version >= 2))
                val native = warp(chain, warpId, layer.warpName, anchor, body, part, aliases)
                val carrier = if (mesh.parentDeformerId == anchor) native else BindingFrames.reparent(model, mesh, native, anchor,
                    mesh.parentDeformerId, instance.outputId("$id/carrier-frame"), checkpoint)
                val encoded = PuppetIr.deformerToIr(carrier)
                val existing = warps.putIfAbsent(encoded.id, encoded)
                require(existing == null || existing == encoded) { "Conflicting shared skirt carrier: $warpId" }
                val sourceMesh = raw.getValue(layer.id)
                val moved = move(model, mesh, carrier, anchor, body, chain.frame, sourceMesh, checkpoint)
                val geometry = PuppetIr.meshToIr(moved)
                add(ModifierObjectUpdate(before, before.copy(parent = geometry.parent, geometry = geometry.geometry,
                    offsets = geometry.offsets, shapes = geometry.shapes)))
            }
        }
        val added = parameters.values.filter { candidate ->
            val existing = beforeParameters.firstOrNull { it.id == candidate.id } ?: return@filter true
            require(existing.copy(name = candidate.name) == candidate) { "Incompatible skirt parameter: ${candidate.id}" }
            false
        }
        val tree = input.parameterTree
        val parameterView = RigIR(input.canvas, beforeParameters, parameterTree = tree)
        val nextTree = if (added.isEmpty()) tree else HairChainsParameterTree.append(parameterView,
            instance.outputId("parameter-folder:ParamGroupPhysics"), source.folder, added.map { it.id })
        val physics = physics(input, instance, active.values, (beforeParameters.map { it.id } + parameters.keys).toSet(), source, checkpoint)
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = added), meshes = ModifierObjectChanges(updated = meshes),
            deformers = ModifierObjectChanges(added = warps.values.toList()), physicsGroups = physics,
            parameterTree = nextTree.takeIf { it != tree }?.let { ModifierFieldChange(tree, it) })
    }

    private fun move(model: PuppetModel, mesh: Drawable, carrier: Deformer.Warp, anchor: DeformerId, body: Bounds,
                     frame: Bounds, raw: ScaffoldMesh.RawMesh, checkpoint: () -> Unit): Drawable {
        val geometry = requireNotNull(mesh.mesh)
        val next = mesh.copy(parentDeformerId = carrier.id)
        fun normalized(bounds: Bounds) = FloatArray(raw.rigPositions.size) { i ->
            if (i % 2 == 0) (raw.rigPositions[i] - bounds.left) / bounds.width.coerceAtLeast(1e-4f)
            else (raw.rigPositions[i] - bounds.top) / bounds.height.coerceAtLeast(1e-4f)
        }
        if (mesh.parentDeformerId == anchor && geometry.positions.contentEquals(normalized(body)) &&
            mesh.blendShapes.isEmpty() && mesh.geometryGrid?.cells.orEmpty().all { it.form.positionDeltas.all { delta -> delta == 0f } }) {
            return next.copy(mesh = DrawableMesh(normalized(frame), geometry.uvs, geometry.indices))
        }
        val staged = model.copy(deformers = model.deformers + carrier, drawables = listOf(next))
        return BindingFrames.carriedToParent(model, staged, mesh.id, checkpoint).drawables.single()
    }

    private fun warp(chain: ChainMotion, id: DeformerId, name: String, parent: DeformerId, body: Bounds, part: PartId,
                     aliases: Map<String, String>): Deformer.Warp {
        val rows = ChainMotion.ROWS
        val columns = ChainMotion.COLUMNS
        val frame = chain.frame
        val carrier = ScaffoldPairs.identityWarp(id, name, parent, frame, body, part, rows, columns)
        val rest = FloatArray((rows + 1) * (columns + 1) * 2)
        var i = 0
        for (row in 0..rows) for (column in 0..columns) {
            rest[i++] = frame.left + frame.width * column / columns
            rest[i++] = frame.top + frame.height * row / rows
        }
        val shapes = chain.shapes(rest, rows, columns) { points -> FloatArray(points.size) { index ->
            if (index % 2 == 0) (points[index] - body.left) / body.width.coerceAtLeast(1e-4f)
            else (points[index] - body.top) / body.height.coerceAtLeast(1e-4f)
        } }.map { it.copy(parameterId = ParameterId(aliases.getValue(it.parameterId.raw))) }
        return carrier.copy(blendShapes = shapes)
    }

    private fun physics(input: ModifierEvaluationInput, instance: RigModifierInstance, active: Collection<ChainMotion>,
                        available: Set<String>, source: SkirtInputs.Measurements, checkpoint: () -> Unit):
        ModifierObjectChanges<PhysicsGroup> {
        if (instance.config["physicsEnabled"]?.jsonPrimitive?.booleanOrNull == false) return ModifierObjectChanges()
        val rules = linkedMapOf<String, RigPhysicsEdit>()
        for (chain in active) {
            checkpoint()
            val aliases = chain.aliases(instance)
            for (rule in ChainMotion.physics(available + aliases.keys, .5f) { key, name ->
                (if (key == "model.physics.clothChain") source.swayLabel else source.flareLabel).replace(TOKEN, name)
            }.filter { rule -> rule.outputs.all { it.parameter in aliases } }) {
                val slot = "chain:${chain.key}:${chain.suffix(rule.outputs.first().parameter)}"
                rules[slot] = rule.copy(outputs = rule.outputs.map { it.copy(parameter = aliases.getValue(it.parameter)) })
            }
        }
        val overrides = instance.config["physics"]?.jsonObject.orEmpty()
        require(overrides.keys.all { it in rules }) { "Unavailable skirt physics group" }
        val before = input.physics
        val groups = rules.map { (slot, native) ->
            val edit = overrides[slot]?.jsonObject?.let(RigPhysicsEdit::fromJson) ?: native
            require(edit.inputs.isNotEmpty() && edit.outputs.isNotEmpty() && edit.parameters.all { it in available } &&
                edit.inputs.none { source -> edit.outputs.any { it.parameter == source.parameter } }) { "Invalid skirt physics $slot" }
            PhysicsGroups.fromSetting(edit.copy(id = instance.outputId("physics:$slot")))
        }
        val reused = groups.mapNotNull { group -> before.groups.firstOrNull { it.copy(id = group.id, name = group.name) == group } }
        val additions = groups.filter { group -> reused.none { it.copy(id = group.id, name = group.name) == group } }
        val impulses = ExpressionAlgorithm.addPhysicsInputs(RigIR(input.canvas, input.parameters,
            physics = before.copy(groups = additions)), listOf(instance), source.impulses).physics.groups
        PhysicsGroups.validateAppend(before, impulses)
        return ModifierObjectChanges(added = impulses, reused = reused.distinct())
    }

    companion object {
        const val TYPE = "outfit.skirt"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.6f75746669742e736b697274.v2/skirt"
        const val TOKEN = "{chain}"
        val WRITES = setOf("mesh", "deformer", "parameter", "hierarchy", "physics")
    }
}
