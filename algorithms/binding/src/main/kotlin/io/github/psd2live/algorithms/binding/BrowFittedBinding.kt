package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.ParameterNode
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.*
import kotlin.math.abs

/** Applies the measured height displacement in the source frame while retaining the live mesh branch. */
class BrowFittedBinding : ChangingModifierAlgorithmPlugin {
    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val measurements = BrowFittedInputs.decode(ModifierAlgorithmContext(context.values,
            mapOf(BrowFittedInputs.VERTICES to context.resources.getValue(BrowFittedInputs.VERTICES))))
        val provider = context.upstream.single { it.type == "rig.scaffold" && it.instanceId == measurements.scaffold }
        require(provider.version == 2 && provider.targets.containsAll(instance.targets))
        for (eye in measurements.eyes.values) require(context.upstream.any {
            it.type == "eye/fitted" && it.version == 2 && it.instanceId == eye.provider
        }) { "Fitted brow requires its captured fitted eye provider" }
        val scaffold = RigModifierInstance(measurements.scaffold, provider.type, provider.version, provider.targets,
            config = provider.config, outputAliases = provider.outputAliases, plugin = provider.plugin)
        val scaffoldAlgorithm = ScaffoldAlgorithm()
        fun structure(name: String, vertices: String) = scaffoldAlgorithm.buildSource(ModifierAlgorithmContext(
            context.values.getValue(name).jsonObject, mapOf(BrowFittedInputs.VERTICES to context.resources.getValue(vertices))), checkpoint)
        val ordinary = structure("before_structure", "before_meshes")
        val neutral = structure("structure", BrowFittedInputs.VERTICES)
        val canonicalTargets = measurements.layers.values.map { it.canonical }.toSet()
        val originals = neutral.drawables.filter { it.id.raw in canonicalTargets }
        val canonical = instance.copy(version = 1, targets = canonicalTargets.map { ModifierTarget("mesh", it) })
        val bySource = measurements.copy(layers = measurements.layers.values.associateBy { it.canonical })
        val generated = BrowFittedAlgorithm().project(neutral.copy(drawables = originals), canonical, bySource,
            context.resources.getValue(BrowFittedInputs.VERTICES), checkpoint)
        val definitions = parameters(measurements.leftName, measurements.rightName)
        val existing = input.parameters.associateBy { it.id }
        val added = definitions.filter { it.id !in existing }
        val targetIds = measurements.layers.keys
        val live = PuppetIr.toPuppet(RigIR(input.canvas, input.parameters + added,
            meshes = input.meshes.filter { it.id in targetIds }, deformers = input.deformers))
        val originalsById = originals.associateBy { it.id.raw }
        val generatedById = generated.drawables.associateBy { it.id.raw }
        val liveById = live.drawables.associateBy { it.id.raw }
        val ordinaryById = ordinary.drawables.associateBy { it.id.raw }
        val oldDeformers = ordinary.deformers.associateBy { it.id }
        val newDeformers = neutral.deformers.associateBy { it.id }
        val headMotion = context.values["legacy_face"] as? JsonObject
        val styledMotion = (context.values["styled_face"] as? JsonObject).orEmpty().mapNotNull { (phase, value) ->
            val source = value as? JsonObject ?: return@mapNotNull null
            val inputs = FittedFaceLattices.Inputs.decode(ModifierAlgorithmContext(source))
            if (inputs.source.styledTurn() == null) null else phase to inputs
        }.toMap()
        fun legacyFace(model: PuppetModel, structure: String, phase: String): Map<DeformerId, Deformer> {
            if (headMotion == null) return emptyMap()
            val head = ScaffoldFramesContext.decode(context.values.getValue(structure).jsonObject.getValue("frames").jsonObject, checkpoint).head
            val values = headMotion.getValue(phase).jsonArray.map { it.jsonPrimitive.float }
            val tuning = headMotion.getValue("tuning").jsonArray.map { it.jsonPrimitive.float }
            val face = LegacyFaceBinding.Measurements(head, values[0], values[1], values[2], values[3])
            val legacy = LegacyFaceTuning(yawEase = tuning[0], pitchEase = tuning[1], featurePlane = tuning[2],
                browFarShrink = tuning[3], browNearGrow = tuning[4], browShift = tuning[5], browTilt = tuning[6],
                faceLeanDown = tuning[7], faceLeanUp = tuning[8])
            val angles = listOf(LegacyFaceBinding.angleX, LegacyFaceBinding.angleY, LegacyFaceBinding.angleZ)
            val parameters = live.parameters.associateBy { it.id }
            val browWarps = head.regions.filter { it.feature == ScaffoldHead.Feature.BROW }
                .mapTo(hashSetOf()) { ScaffoldHead.featureId(it.feature, it.side) }
            val branch = model.copy(deformers = model.deformers.filter { it.id in browWarps })
            val legacyWarps = LegacyFaceBinding.bind(branch, face, legacy, headMotion.getValue("strength").jsonPrimitive.float,
                angles.map(parameters::getValue), "", checkpoint).deformers.associateBy { it.id }
            val inputs = styledMotion[phase]
            val posedWarps = if (inputs == null) legacyWarps.values.toList() else {
                // Before brow fitting, the styled turn holds the traditional XY keys at rest.
                // Those axes still define the predecessor lattice used by the residual merge.
                val posed = model.copy(drawables = emptyList(), deformers = model.deformers.map { legacyWarps[it.id] ?: it })
                val ir = PuppetIr.toIr(posed)
                val styled = FittedFaceLattices.changes(ir, inputs, checkpoint).apply(ir)
                PuppetIr.toPuppet(styled).deformers.filter { it.id in browWarps }
            }
            val resolved = if (LegacyBodyMotion.lean in parameters)
                DepthTurnMotion.withFaceLean(posedWarps, legacy.faceLeanDown, legacy.faceLeanUp) else posedWarps
            return resolved.associateBy { it.id }
        }
        val priorMotion by lazy { legacyFace(ordinary, "before_structure", "before") }
        val fittedMotion by lazy { legacyFace(neutral, "structure", "after") }
        val liveDeformers = live.deformers.associateBy { it.id }
        val oldMeshes = input.meshes.associateBy { it.id }
        val indexed = buildSet {
            input.glues.forEach { add(it.meshA); add(it.meshB) }
            input.authoring.paths.forEach { add(it.mesh) }
            input.authoring.vertexGroups.forEach { add(it.mesh) }
            input.advanced.simulations.forEach { simulation -> simulation.targets.forEach { add(it.mesh) } }
            input.advanced.colliders.forEach { it.mesh?.let(::add) }
        }
        fun branch(start: DeformerId?) = generateSequence(start) { liveDeformers.getValue(it).parent }.toList()
        val candidates = measurements.layers.filter { (id, layer) ->
            val mesh = oldMeshes.getValue(id)
            val source = ordinaryById.getValue(layer.canonical)
            val original = PuppetIr.meshToIr(source)
            val parent = source.parentDeformerId?.let { ScaffoldBinding.deformerId(scaffold, id, it.raw) }
            id !in indexed && mesh.parent == parent && mesh.geometry == original.geometry &&
                mesh.offsets == original.offsets && mesh.shapes.isEmpty()
        }
        val defaults = live.parameters.associate { it.id to it.default }
        val migrations = linkedMapOf<String, Map<DeformerId, Deformer.Warp>>()
        for ((id, layer) in candidates) {
            val source = ordinaryById.getValue(layer.canonical)
            val destination = originalsById.getValue(layer.canonical)
            if (source.parentDeformerId != destination.parentDeformerId) continue
            val updates = linkedMapOf<DeformerId, Deformer.Warp>()
            var canonical = source.parentDeformerId
            var allowed = true
            while (canonical != null) {
                checkpoint()
                val before = oldDeformers.getValue(canonical)
                val after = newDeformers.getValue(canonical)
                val actualId = DeformerId(ScaffoldBinding.deformerId(scaffold, id, canonical.raw))
                val actual = liveDeformers[actualId]
                val expectedParent = before.parent?.let { DeformerId(ScaffoldBinding.deformerId(scaffold, id, it.raw)) }
                if (actual == null || actual.parent != expectedParent || before.parent != after.parent) {
                    allowed = false
                    break
                }
                val beforeFrame = priorMotion[canonical] ?: before
                val afterFrame = fittedMotion[canonical] ?: after
                if (PuppetIr.deformerToIr(beforeFrame) != PuppetIr.deformerToIr(afterFrame)) {
                    val supported = before is Deformer.Warp && after is Deformer.Warp && actual is Deformer.Warp &&
                        before.rows == after.rows && before.columns == after.columns &&
                        actual.rows == before.rows && actual.columns == before.columns &&
                        input.advanced.colliders.none { actualId in branch(it.deformer?.let(::DeformerId)) }
                    if (!supported) {
                        allowed = false
                        break
                    }
                    val liveWarp = actual
                    val beforeWarp = beforeFrame as Deformer.Warp
                    val afterWarp = afterFrame as Deformer.Warp
                    updates[actualId] = liveWarp.copy(geometryGrid = BindingMerge.warpDelta(liveWarp.geometryGrid,
                        beforeWarp.geometryGrid, afterWarp.geometryGrid, defaults, checkpoint))
                }
                canonical = before.parent
            }
            if (allowed) migrations[id] = updates
        }
        // Retaining an authored mesh also protects every shared frame needed to place it.
        do {
            val protected = input.meshes.filter { it.id !in migrations }.flatMapTo(hashSetOf()) { mesh ->
                branch(mesh.parent?.let(::DeformerId))
            }
            val blocked = migrations.filterValues { updates -> updates.keys.any { it in protected } }.keys
        } while (migrations.keys.removeAll(blocked))
        for ((id, layer) in measurements.layers) {
            if (id in migrations || styledMotion.isEmpty()) continue
            val source = ordinaryById.getValue(layer.canonical)
            val destination = originalsById.getValue(layer.canonical)
            val sameFrame = source.parentDeformerId == destination.parentDeformerId &&
                generateSequence(source.parentDeformerId) { oldDeformers.getValue(it).parent }.all { canonical ->
                    val before = styledMotion["before"]?.let { priorMotion[canonical] } ?: oldDeformers.getValue(canonical)
                    val after = styledMotion["after"]?.let { fittedMotion[canonical] } ?: newDeformers[canonical]
                    after != null && PuppetIr.deformerToIr(before) == PuppetIr.deformerToIr(after)
                }
            require(sameFrame) { "Fitted brow v2 cannot migrate the existing brow frame. Use v1 with authored or shared frames." }
        }
        val changed = migrations.values.flatMap { it.entries }.associate { (id, warp) -> id to warp }
        val meshes = input.meshes.mapNotNull { before ->
            checkpoint()
            val layer = measurements.layers[before.id] ?: return@mapNotNull null
            val actual = liveById.getValue(before.id)
            val geometry = actual.mesh ?: return@mapNotNull null
            val source = originalsById.getValue(layer.canonical)
            val desired = generatedById.getValue(layer.canonical)
            val after = if (before.id in migrations) {
                val fitted = PuppetIr.meshToIr(desired)
                before.copy(geometry = fitted.geometry, offsets = fitted.offsets)
            } else {
                val vertices = correspond(requireNotNull(source.mesh), geometry, checkpoint)
                val baseline = neutral.copy(drawables = listOf(source.copy(id = actual.id)),
                    canvasWidth = live.canvasWidth, canvasHeight = live.canvasHeight,
                    worldOriginX = live.worldOriginX, worldOriginY = live.worldOriginY)
                val grid = GeometryResidual.geometry(live, baseline, baseline.copy(drawables = listOf(desired.copy(id = actual.id))),
                    actual.id, vertices, vertices, checkpoint)
                before.copy(offsets = PuppetIr.meshToIr(actual.copy(geometryGrid = grid)).offsets)
            }
            ModifierObjectUpdate(before, after).takeIf { before != after }
        }
        val deformers = input.deformers.mapNotNull { before ->
            val warp = changed[DeformerId(before.id)] ?: return@mapNotNull null
            val lattice = (PuppetIr.deformerToIr(warp) as io.github.psd2live.format.model.Deformer.Warp).lattice
            val after = (before as io.github.psd2live.format.model.Deformer.Warp).copy(lattice = lattice)
            ModifierObjectUpdate<io.github.psd2live.format.model.Deformer>(before, after).takeIf { before != after }
        }
        val tree = if (added.isEmpty()) null else {
            val id = instance.outputId("parameter-folder:ParamGroupBrows")
            val nodes = added.map { ParameterNode.Param(it.id) }
            val root = input.parameterTree.ifEmpty { input.parameters.map { ParameterNode.Param(it.id) } }
            ModifierFieldChange(input.parameterTree, root + ParameterNode.Group(id, measurements.groupName, true, nodes))
        }
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = added, reused = definitions.mapNotNull { existing[it.id] }),
            meshes = ModifierObjectChanges(updated = meshes), deformers = ModifierObjectChanges(updated = deformers), parameterTree = tree)
    }

    private fun correspond(source: DrawableMesh, actual: DrawableMesh, checkpoint: () -> Unit): List<VertexSource> {
        if (source.uvs.contentEquals(actual.uvs)) return List(actual.vertexCount) { VertexSource.FromOld(it) }
        return List(actual.vertexCount) { index ->
            checkpoint()
            val x = actual.uvs[index * 2]
            val y = actual.uvs[index * 2 + 1]
            val nearest = (0 until source.vertexCount).minBy { vertex ->
                val dx = source.uvs[vertex * 2].toDouble() - x
                val dy = source.uvs[vertex * 2 + 1].toDouble() - y
                dx * dx + dy * dy
            }
            val point = BindingPaths.bind(source.uvs, source.indices, x, y)
            if ((abs(source.uvs[nearest * 2] - x) <= 1e-7f && abs(source.uvs[nearest * 2 + 1] - y) <= 1e-7f) ||
                minOf(point.wa, point.wb, point.wc) < -1e-4f) VertexSource.FromOld(nearest)
            else {
                val a = point.wa.coerceAtLeast(0f)
                val b = point.wb.coerceAtLeast(0f)
                val c = point.wc.coerceAtLeast(0f)
                val sum = a + b + c
                VertexSource.BarycentricOf(point.a, point.b, point.c, a / sum, b / sum, c / sum)
            }
        }
    }

    companion object {
        fun parameters(left: String, right: String) = listOf(
            io.github.psd2live.format.model.Parameter("ParamBrowLY", left, -1f, 1f, 0f),
            io.github.psd2live.format.model.Parameter("ParamBrowRY", right, -1f, 1f, 0f),
        )

        const val VERSION = 2
        const val ENTRYPOINT = "builtin.62726f772f666974746564.v2/binding"
    }
}
