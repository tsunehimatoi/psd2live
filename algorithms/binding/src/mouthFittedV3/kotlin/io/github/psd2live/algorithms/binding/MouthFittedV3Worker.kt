package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Deformer as IrDeformer
import io.github.psd2live.format.model.ParameterNode
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

object MouthFittedV3Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthFittedV3Algorithm.ENTRYPOINT to MouthFittedV3Algorithm.VERSION) to MouthFittedV3Algorithm(),
    ))
}

/** Projects the fitted mouth and refreshes the styled head field from the same measured source. */
class MouthFittedV3Algorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val scaffold = context.upstream.single { it.type == "rig.scaffold" }
        val providerId = context.values.getValue("head").jsonPrimitive.content
        val head = context.upstream.single { it.type == "face/fitted" && it.instanceId == providerId }
        require(scaffold.version == 2 && scaffold.instanceId == instance.config.getValue("scaffold").jsonPrimitive.content &&
            scaffold.targets.toSet() == instance.targets.toSet() && head.targets.toSet() == instance.targets.toSet())
        val observed = context.values.getValue("providers").jsonObject
        require(observed.all { (type, id) ->
            context.upstream.any { it.type == type && it.instanceId == id.jsonPrimitive.content &&
                it.config["scaffold"] == instance.config["scaffold"] && it.targets.toSet() == instance.targets.toSet() }
        }) { "Fitted mouth v3 requires its observed source bindings" }
        val provider = RigModifierInstance(requireNotNull(scaffold.instanceId), scaffold.type, scaffold.version,
            scaffold.targets, config = scaffold.config, outputAliases = scaffold.outputAliases, plugin = scaffold.plugin)
        val mouth = MouthFittedInputs.decode(ModifierAlgorithmContext(context.values.getValue("mouth").jsonObject,
            context.resources - setOf("raw", "rawBefore"), context.upstream))
        val rig = requireNotNull(mouth.source).rig()
        val reach = rig.openKeys.last()
        val definitions = MouthLegacyAlgorithm.parameters(context.values.getValue("formName").jsonPrimitive.content,
            context.values.getValue("openName").jsonPrimitive.content).map {
            if (it.id == "ParamMouthOpenY") it.copy(max = reach) else it
        }
        require(input.parameters.none { existing -> definitions.any { it.id == existing.id } })
        val runtime = PuppetIr.toPuppet(RigIR(input.canvas, input.parameters))
        fun phase(key: String) = SourceHeadProjection.Phase.decode(
            ModifierAlgorithmContext(context.values.getValue(key).jsonObject), checkpoint)
        val previousPhase = phase("before")
        val desiredPhase = phase("after")
        val previous = SourceHeadProjection.project(runtime, previousPhase, checkpoint).associateBy { it.id }
        val desired = SourceHeadProjection.project(runtime, desiredPhase, checkpoint).associateBy { it.id }
        require(previous.keys == desired.keys)
        val representative = instance.targets.first().id
        fun alias(id: DeformerId) = DeformerId(ScaffoldBinding.deformerId(provider, representative, id.raw))
        val aliases = previous.keys.associateWith(::alias)
        val remap = ModelIdRemap(deformers = aliases)
        val defaults = runtime.parameters.associate { it.id to it.default }
        val live = PuppetIr.toPuppet(input.copy(parameters = input.parameters + definitions))
        val affected = previous.keys.associateBy(::alias)
        val deformers = live.deformers.map { actual ->
            checkpoint()
            val canonical = affected[actual.id] ?: return@map actual
            val old = remap.deformer(previous.getValue(canonical))
            val next = remap.deformer(desired.getValue(canonical))
            if (PuppetIr.deformerToIr(old) == PuppetIr.deformerToIr(next)) return@map actual
            require(actual.parent == old.parent && old.parent == next.parent) { "Fitted mouth cannot migrate an authored head parent" }
            when {
                actual is Deformer.Warp && old is Deformer.Warp && next is Deformer.Warp -> {
                    require(actual.rows == old.rows && actual.columns == old.columns && actual.blendShapes.isEmpty())
                    actual.copy(rows = next.rows, columns = next.columns, isQuadTransform = next.isQuadTransform,
                        geometryGrid = BindingMerge.warpDelta(actual.geometryGrid, old.geometryGrid, next.geometryGrid, defaults, checkpoint))
                }
                actual is Deformer.Rotation && old is Deformer.Rotation && next is Deformer.Rotation -> {
                    require((PuppetIr.deformerToIr(actual) as IrDeformer.Rotation).pivot ==
                        (PuppetIr.deformerToIr(old) as IrDeformer.Rotation).pivot)
                    actual.copy(geometryGrid = next.geometryGrid)
                }
                else -> error("Fitted mouth cannot migrate a different head deformer kind")
            }
        }
        val parentIds = context.values.getValue("parents").jsonObject
        val beforeFrames = context.values.getValue("beforeFrames").jsonObject
        val raw = ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue("raw"), checkpoint)
        val rawBefore = ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue("rawBefore"), checkpoint)
        val indexed = buildSet {
            input.glues.forEach { add(it.meshA); add(it.meshB) }
            input.authoring.paths.forEach { add(it.mesh) }
            input.authoring.vertexGroups.forEach { add(it.mesh) }
            input.advanced.simulations.forEach { simulation -> simulation.targets.forEach { add(it.mesh) } }
            input.advanced.colliders.forEach { it.mesh?.let(::add) }
        }
        fun sourceMesh(source: ScaffoldMesh.RawMesh, frame: Bounds, parent: DeformerId?) =
            if (parent == null) DrawableMesh(source.rigPositions, source.atlasUvs, source.triangles)
            else ScaffoldMesh.build(source, ScaffoldArms.Parent(parent, frame), ScaffoldMesh.Geometry.Zero, checkpoint).first
        val prepared = live.drawables.map { drawable ->
            val measurement = mouth.layers[drawable.id.raw] ?: return@map drawable
            val parent = parentIds.getValue(drawable.id.raw).jsonPrimitive.contentOrNull?.let(::DeformerId)?.let(::alias)
            require(drawable.parentDeformerId == parent) { "Fitted mouth cannot migrate the parent of ${drawable.id.raw}" }
            if (measurement.tag !in mouthTags) return@map drawable
            val source = raw.getValue(drawable.id.raw)
            val old = beforeFrames.getValue(drawable.id.raw).jsonObject
            val oldParent = old.getValue("parent").jsonPrimitive.contentOrNull?.let(::DeformerId)?.let(::alias)
            val bounds = old.getValue("bounds").jsonArray.map { it.jsonPrimitive.float }
            val original = sourceMesh(rawBefore.getValue(drawable.id.raw), Bounds(bounds[0], bounds[1], bounds[2], bounds[3]), oldParent)
            val current = requireNotNull(drawable.mesh)
            val nativeGeometry = current.positions.contentEquals(original.positions) &&
                current.uvs.contentEquals(original.uvs) && current.indices.contentEquals(original.indices)
            require(oldParent == parent && nativeGeometry && drawable.id.raw !in indexed &&
                drawable.blendShapes.isEmpty() && drawable.geometryGrid?.axes.orEmpty().isEmpty() &&
                drawable.geometryGrid?.cells.orEmpty().all { cell -> cell.form.positionDeltas.all { it == 0f } }) {
                "Fitted mouth cannot replace an edited mouth source mesh: ${drawable.id.raw} " +
                    "(source geometry matches: $nativeGeometry, source parent matches: ${oldParent == parent})"
            }
            val mesh = sourceMesh(source, measurement.frame, parent)
            drawable.copy(mesh = mesh, geometryGrid = KeyformGrid(emptyList(), listOf(
                KeyformCell(IntArray(0), MeshDeltaForm(FloatArray(source.rigPositions.size))))), blendShapes = emptyList())
        }
        val result = MouthFittedAlgorithm().project(live.copy(drawables = prepared, deformers = deformers),
            mouth.layers.keys, mouth, checkpoint, sourcePositions = raw.mapValues { it.value.rigPositions })
        val styled = context.values.getValue("styled").jsonObject
        val styles = styled.mapValues { (id, source) ->
            val original = live.drawables.single { it.id.raw == id }
            val values = source.jsonObject
            val tag = values.getValue("tag").jsonPrimitive.content
            val lowering = BlendShapeLowering({ turn, name ->
                (if (turn) mouth.turnCarrierName else mouth.shapeCarrierName).replace("{meshName}", name)
            }, checkpoint)
            fun project(name: String, sourceMesh: ScaffoldMesh.RawMesh, turn: DepthTurnInputs,
                        scaffoldFrames: List<Deformer>): Pair<Drawable, Deformer?> {
                val frame = values.getValue(name).jsonObject
                val parent = alias(DeformerId(frame.getValue("parent").jsonPrimitive.content))
                val bounds = frame.getValue("bounds").jsonArray.map { it.jsonPrimitive.float }
                val sample = SourceHeadProjection.styledMesh(original, sourceMesh, Bounds(bounds[0], bounds[1], bounds[2], bounds[3]),
                    parent, tag, turn, checkpoint)
                val model = lowering.lawful(live.copy(drawables = listOf(sample), deformers = scaffoldFrames))
                val projected = model.drawables.single()
                return projected to model.deformers.drop(scaffoldFrames.size).singleOrNull()
            }
            val (old, oldCarrier) = project("before", rawBefore.getValue(id), previousPhase.turn, previous.values.map(remap::deformer))
            val (next, nextCarrier) = project("after", raw.getValue(id), desiredPhase.turn, desired.values.map(remap::deformer))
            require((oldCarrier == null) == (nextCarrier == null)) { "Fitted mouth cannot migrate a styled mesh carrier: $id" }
            val previousMesh = PuppetIr.meshToIr(old)
            val actualMesh = PuppetIr.meshToIr(original)
            if (previousMesh == PuppetIr.meshToIr(next) && oldCarrier == nextCarrier) return@mapValues original to null
            val owned = oldCarrier?.let {
                val parent = requireNotNull(original.parentDeformerId)
                require(old.parentDeformerId == it.id)
                val actual = live.deformers.single { frame -> frame.id == parent }
                val oldRemap = ModelIdRemap(deformers = mapOf(it.id to parent))
                require(PuppetIr.deformerToIr(oldRemap.deformer(it)) == PuppetIr.deformerToIr(actual)) {
                    "Fitted mouth cannot migrate an edited styled head carrier: $id"
                }
                ModelIdRemap(deformers = mapOf(requireNotNull(nextCarrier).id to parent))
            } ?: ModelIdRemap()
            require(previousMesh.copy(parent = actualMesh.parent) == actualMesh &&
                (oldCarrier != null || old.parentDeformerId == original.parentDeformerId)) {
                "Fitted mouth cannot migrate an edited styled head mesh: $id"
            }
            owned.drawable(next) to nextCarrier?.let(owned::deformer)
        }
        val known = live.deformers.mapTo(hashSetOf()) { it.id }
        val added = result.deformers.filter { it.id !in known }
        val carrierIds = added.associate { carrier ->
            carrier.id to DeformerId(ScaffoldBinding.deformerId(instance, representative, carrier.id.raw, shared = true))
        }
        val carriers = ModelIdRemap(deformers = carrierIds)
        val styledFrames = styles.values.mapNotNull { it.second }.associateBy { it.id }
        val resultDeformers = result.deformers.map { PuppetIr.deformerToIr(styledFrames[it.id] ?: carriers.deformer(it)) }.associateBy { it.id }
        val resultMeshes = result.drawables.map { PuppetIr.meshToIr(styles[it.id.raw]?.first ?: carriers.drawable(it)) }.associateBy { it.id }
        val nodes = input.parameterTree.ifEmpty { input.parameters.map { ParameterNode.Param(it.id) } }
        val group = ParameterNode.Group(instance.outputId("parameter-folder:ParamGroupMouth"),
            context.values.getValue("groupName").jsonPrimitive.content, true, definitions.map { ParameterNode.Param(it.id) })
        val pose = input.restPose + ("ParamMouthOpenY" to 1f)
        return ModifierModelChanges(
            meshes = ModifierObjectChanges(updated = input.meshes.filter { it.id in mouth.layers || it.id in styled }.mapNotNull { before ->
                val after = resultMeshes.getValue(before.id)
                if (before == after) null else ModifierObjectUpdate(before, after)
            }),
            deformers = ModifierObjectChanges<IrDeformer>(added = carrierIds.values.map { resultDeformers.getValue(it.raw) },
                updated = input.deformers.mapNotNull { before ->
                    val after = resultDeformers.getValue(before.id)
                    if (before == after) null else ModifierObjectUpdate(before, after)
                }),
            parameters = ModifierObjectChanges(added = definitions),
            parameterTree = ModifierFieldChange(input.parameterTree, nodes + group),
            restPose = ModifierFieldChange(input.restPose, pose),
        )
    }

    companion object {
        const val VERSION = 3
        const val ENTRYPOINT = "builtin.6d6f7574682f666974746564.v3/binding"
        val WRITES = setOf("parameter", "mesh", "deformer", "hierarchy", "restPose")
        private val mouthTags = setOf("MOUTH", "MOUTH_OPEN", "MOUTH_CLOSE", "TOOTH_T", "TOOTH_B", "TONGUE")
    }
}
