package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.Parameter as IrParameter
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.withDeformerDeleted
import org.umamo.runtime.model.*

/** Builds the chain lattice and pendulums from source geometry and the preceding hair planes. */
class HairChainsAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        val source = HairChainsInputs.decode(context)
        val targets = instance.targets.mapTo(HashSet()) { it.id }
        for (required in listOf("rig.scaffold", "body/legacy", "face/legacy")) {
            val provider = if (required == "rig.scaffold") instance.config["scaffold"]
                else instance.config["dependencies"]?.jsonObject?.get(required)
            require(instance.targets.all { target -> context.upstream.any {
                it.capability == required && it.instanceId == provider?.jsonPrimitive?.content && target in it.targets
            } }) { "${instance.id}: missing hair chain capability $required" }
        }
        val values = instance.config["values"]?.jsonObject.orEmpty()
        val swing = knob(values, "swing", 100f, 30f..200f)
        val softness = knob(values, "softness", 50f, 0f..100f)
        val layers = source.layers.associateBy { it.sourceId }
        val measured = source.layers.map { ChainMotion.Layer(it.sourceId, it.plane, it.bounds, it.pixels, it.kind) }
        val planned = ChainMotion.plan(measured, source.radiusX * 2f, { kind, frame ->
            when (kind) {
                ChainKind.FRONT -> (source.eyeY - 0.45f * source.radiusY).coerceIn(frame.top, frame.bottom)
                ChainKind.SIDE -> (source.eyeY - 0.2f * source.radiusY).coerceIn(frame.top, frame.bottom)
                ChainKind.BACK -> source.eyeY.coerceIn(frame.top, frame.bottom)
                ChainKind.TAIL -> frame.top + frame.height * 0.08f
                ChainKind.AHOGE -> frame.bottom - frame.height * 0.1f
                else -> error("Non-hair chain in hair source")
            }
        }, swing, softness)
        val bindable = planned.filterNot { layers.getValue(it.layerId).paired }.associateBy { it.layerId }
        val measurements = measured.associateBy { it.id }
        val byLayer = source.layers.associate { layer ->
            layer.sourceId to when {
                layer.paired -> null
                layer.shading -> ChainMotion.owner(measurements.getValue(layer.sourceId), planned, measurements)?.takeIf { it.layerId in bindable }
                else -> bindable[layer.sourceId]
            }
        }
        val meshOwners = source.layers.flatMap { layer -> layer.meshes.mapNotNull { id ->
            byLayer[layer.sourceId]?.takeIf { id in targets }?.let { id to it }
        } }.toMap()
        val meshes = input.meshes.associateBy { it.id }
        val inputDeformers = input.deformers.associateBy { it.id }
        val active = instance.targets.mapNotNull { meshOwners[it.id] }.distinct()
        val replacePlanes = source.planes.filterValues { it.sway != null }.keys
        val affected = source.layers.flatMap { layer ->
            layer.meshes.filter { it in targets && (it in meshOwners || layer.plane in replacePlanes || instance.config["tuning"] != null) }.map { it to layer }
        }.toMap()
        val model = PuppetIr.toPuppet(RigIR(input.canvas, input.parameters, parts = input.parts, deformers = input.deformers,
            meshes = input.meshes.filter { it.id in affected }))
        val branch = model.deformers.associateBy { it.id.raw }
        val drawables = model.drawables.associateBy { it.id.raw }
        val parameters = linkedMapOf<String, IrParameter>()
        val newWarps = linkedMapOf<String, Deformer.Warp>()
        val oldCarriers = linkedSetOf<String>()
        val tuned = linkedMapOf<String, ModifierObjectUpdate<io.github.psd2live.format.model.Deformer>>()
        val tuning = instance.config["tuning"]?.jsonObject.orEmpty()
        val defaults = model.parameters.associate { it.id to it.default }
        val updatedMeshes = buildList {
            for (target in instance.targets) {
                checkpoint()
                val layer = affected[target.id] ?: continue
                val before = meshes.getValue(target.id)
                val sample = drawables.getValue(target.id)
                val plane = source.planes.getValue(layer.plane)
                val capability = context.upstream.single { it.capability == "rig.scaffold" && target in it.targets }
                val provider = RigModifierInstance(requireNotNull(capability.instanceId), capability.type, capability.version,
                    capability.targets, config = capability.config, outputAliases = capability.outputAliases, plugin = capability.plugin)
                fun deformer(canonical: String) = ScaffoldBinding.deformerId(provider, target.id, canonical)
                val follow = DeformerId(deformer(plane.follow))
                if (tuning.isNotEmpty() && follow.raw !in tuned) {
                    val native = HairFollowMotion.defaults(layer.plane == "Front")
                    val prefix = layer.plane.lowercase() + "Hair"
                    fun value(id: String, default: Float) = tuning[id]?.jsonPrimitive?.float ?: default
                    val desired = HairFollowMotion.Tuning(value(prefix + "Turn", native.yaw), value(prefix + "Tilt", native.pitch),
                        if (layer.plane == "Front") value("frontHairPerspective", native.perspective) else native.perspective,
                        value("hairRollHold", native.hold))
                    if (native != desired) {
                        val live = branch[follow.raw] as? Deformer.Warp ?: error("Missing hair follow Warp")
                        val grid = BindingMerge.warpDelta(live.geometryGrid, HairFollowMotion.grid(plane.frame, source.head, native),
                            HairFollowMotion.grid(plane.frame, source.head, desired), defaults, checkpoint)
                        val beforeWarp = inputDeformers.getValue(follow.raw)
                        val afterWarp = PuppetIr.deformerToIr(live.copy(geometryGrid = grid))
                        if (beforeWarp != afterWarp) tuned[follow.raw] = ModifierObjectUpdate(beforeWarp, afterWarp)
                    }
                }
                val sway = plane.sway?.let { branch[deformer(it)] } as? Deformer.Warp
                val bypass = sway != null && sample.parentDeformerId == sway.id && isNeutralSway(sway, follow)
                val anchor = follow
                val actualParent = if (bypass) follow else sample.parentDeformerId
                val chain = meshOwners[target.id]
                if (bypass) oldCarriers += requireNotNull(sway).id.raw
                if (chain == null && !bypass) continue
                val destination = if (chain == null) follow else {
                    val owner = layers.getValue(chain.layerId)
                    val key = chain.key
                    val id = DeformerId(ScaffoldBinding.deformerId(instance, target.id, "SourceChain:$key", provider.version >= 2))
                    val aliases = chain.aliases(instance)
                    for ((native, alias) in aliases) {
                        parameters.putIfAbsent(alias, IrParameter(alias,
                            owner.parameterName.replace(NAME_TOKEN, native.removePrefix(chain.kind.prefix)), -1f, 1f, 0f, blend = true, keys = Floats.of(floatArrayOf(-1f, 0f, 1f))))
                    }
                    val rawWarp = warp(chain, id, owner.warpName, anchor, plane.frame,
                        PartId(ScaffoldBinding.partId(provider, target.id, plane.part)), aliases)
                    val newWarp = if (anchor == actualParent) rawWarp else BindingFrames.reparent(model, sample, rawWarp, anchor,
                        actualParent, instance.outputId("${target.id}/carrier-frame"), checkpoint)
                    val existing = newWarps.putIfAbsent(id.raw, newWarp)
                    require(existing == null || PuppetIr.deformerToIr(existing) == PuppetIr.deformerToIr(newWarp)) {
                        "${instance.id}: incompatible shared hair carrier $id"
                    }
                    id
                }
                val next = sample.copy(parentDeformerId = destination)
                val staged = model.copy(deformers = model.deformers + newWarps.values,
                    drawables = model.drawables.map { if (it.id == sample.id) next else it })
                val moved = if (actualParent == anchor && chain != null) {
                    // Both frames are affine here. Keep the geometry and all pose offsets in the new units.
                    affine(next, plane.frame, chain.frame)
                } else if (bypass && chain == null) {
                    model.withDeformerDeleted(requireNotNull(sway).id).drawables.single { it.id == sample.id }
                } else BindingFrames.carriedToParent(model, staged, sample.id, checkpoint).drawables.single { it.id == sample.id }
                val geometry = PuppetIr.meshToIr(moved)
                val after = before.copy(parent = geometry.parent, geometry = geometry.geometry, offsets = geometry.offsets, shapes = geometry.shapes)
                if (before != after) add(ModifierObjectUpdate(before, after))
            }
        }
        val added = parameters.values.filter { candidate ->
            input.parameters.singleOrNull { it.id == candidate.id }?.let { existing ->
                require(existing.copy(name = candidate.name) == candidate) { "${instance.id}: incompatible chain parameter ${candidate.id}" }
                false
            } ?: true
        }
        val edited = updatedMeshes.associate { it.before.id to it.after }
        val deleted = oldCarriers.filter { carrier ->
            input.meshes.none { (edited[it.id] ?: it).parent == carrier } &&
                input.deformers.none { it.parent == carrier } && newWarps.values.none { it.parent?.raw == carrier }
        }.map(inputDeformers::getValue)
        val groupId = instance.outputId("parameter-folder:ParamGroupPhysics")
        val tree = appendParameters(input, groupId, source.physicsFolder, added.map { it.id })
        val physics = HairChainsPhysics.groups(input, instance, active, parameters.keys, softness, source, checkpoint)
        return ModifierModelChanges(
            parameters = ModifierObjectChanges(added = added),
            parameterTree = if (tree == input.parameterTree) null else ModifierFieldChange(input.parameterTree, tree),
            meshes = ModifierObjectChanges(updated = updatedMeshes),
            deformers = ModifierObjectChanges(added = newWarps.values.map(PuppetIr::deformerToIr), updated = tuned.values.toList(), removed = deleted),
            physicsGroups = ModifierObjectChanges(added = physics),
        )
    }

    private fun warp(chain: ChainMotion, id: DeformerId, name: String, parent: DeformerId, parentFrame: Bounds, part: PartId,
                     aliases: Map<String, String>): Deformer.Warp {
        val rows = ChainMotion.ROWS
        val columns = ChainMotion.COLUMNS
        val frame = chain.frame
        val warp = ScaffoldPairs.identityWarp(id, name, parent, frame, parentFrame, part, rows, columns)
        val rest = FloatArray((rows + 1) * (columns + 1) * 2)
        var i = 0
        for (row in 0..rows) for (column in 0..columns) {
            rest[i++] = frame.left + frame.width * column / columns
            rest[i++] = frame.top + frame.height * row / rows
        }
        val shapes = chain.shapes(rest, rows, columns) { points -> FloatArray(points.size) { index ->
            if (index % 2 == 0) (points[index] - parentFrame.left) / parentFrame.width.coerceAtLeast(1e-4f)
            else (points[index] - parentFrame.top) / parentFrame.height.coerceAtLeast(1e-4f)
        } }.map { it.copy(parameterId = ParameterId(aliases.getValue(it.parameterId.raw))) }
        return warp.copy(blendShapes = shapes)
    }

    private fun isNeutralSway(warp: Deformer.Warp, follow: DeformerId): Boolean {
        if (warp.parent != follow || warp.blendShapes.isNotEmpty() || warp.channelGrids != ChannelGrids.Empty ||
            warp.opacity != 1f || !warp.isQuadTransform) return false
        val unit = Bounds(0f, 0f, 1f, 1f)
        val expected = ScaffoldPairs.identityWarp(warp.id, warp.name, follow, unit, unit,
            warp.partId ?: return false, warp.rows, warp.columns)
        val grid = warp.geometryGrid ?: return true
        return grid.axes.isEmpty() && grid.cells.singleOrNull()?.form?.controlPoints
            ?.contentEquals(requireNotNull(expected.geometryGrid).cells.single().form.controlPoints) == true
    }

    private fun affine(drawable: Drawable, from: Bounds, to: Bounds): Drawable {
        val mesh = requireNotNull(drawable.mesh)
        fun map(points: FloatArray, delta: Boolean) = FloatArray(points.size) { index ->
            val x = index % 2 == 0
            val sourceScale = (if (x) from.width else from.height).coerceAtLeast(1e-4f)
            val destScale = (if (x) to.width else to.height).coerceAtLeast(1e-4f)
            val origin = if (x) from.left else from.top
            val destination = if (x) to.left else to.top
            val scaled = points[index] * sourceScale
            (if (delta) scaled else (scaled + origin) - destination) / destScale
        }
        return drawable.copy(mesh = DrawableMesh(map(mesh.positions, false), mesh.uvs, mesh.indices),
            geometryGrid = drawable.geometryGrid?.let { grid -> KeyformGrid(grid.axes, grid.cells.map {
                KeyformCell(it.coordinate, MeshDeltaForm(map(it.form.positionDeltas, true)))
            }) },
            blendShapes = drawable.blendShapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
                form?.let { MeshForm(map(it.positionDeltas, true), it.drawOrder, it.opacity, it.multiplyColor, it.screenColor) }
            }) })
    }

    private fun appendParameters(input: RigIR, folder: String, name: String, ids: List<String>): List<io.github.psd2live.format.model.ParameterNode> {
        if (ids.isEmpty()) return input.parameterTree
        return HairChainsParameterTree.append(input, folder, name, ids)
    }

    private fun knob(values: Map<String, JsonElement>, key: String, default: Float, range: ClosedFloatingPointRange<Float>) =
        ((values[key]?.jsonPrimitive?.float ?: default).also { require(it in range) { "Invalid hair chain $key" } }) / 100f

    companion object {
        const val NAME_TOKEN = "{chain}"
        val WRITES = setOf("deformer", "mesh", "parameter", "hierarchy", "physics")
    }
}
