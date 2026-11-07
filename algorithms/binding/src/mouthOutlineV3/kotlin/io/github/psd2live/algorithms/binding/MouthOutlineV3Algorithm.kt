package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.fit.FaceTurn
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.atlasPixelOf
import org.umamo.runtime.model.DrawableMesh

object MouthOutlineV3Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthOutlineV3Algorithm.ENTRYPOINT to MouthOutlineV3Algorithm.VERSION) to MouthOutlineV3Algorithm(),
    ))
}

/** Whole fitted mouth and generated lip ribbons in the preceding binding's parent frame. */
class MouthOutlineV3Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance,
                         checkpoint: () -> Unit): ModifierModelChanges {
        val context = input.context
        val values = context.values
        require(instance.version == VERSION && values.keys == setOf("schema", "outline", "before", "after", "source_meshes", "textures", "head", "components", "component_meshes", "stationary", "hair", "attachments") &&
            values["schema"] == JsonPrimitive(SCHEMA)) { "Invalid fitted mouth outline inputs" }
        val rawDigest = values.getValue("source_meshes").jsonPrimitive.content
        val raw = ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue(rawDigest), checkpoint)
        val components = Json.decodeFromJsonElement<Map<String, String>>(values.getValue("components"))
        val componentDigest = values.getValue("component_meshes").takeUnless { it == JsonNull }?.jsonPrimitive?.content
        val stationary = Json.decodeFromJsonElement<Map<String, Stationary>>(values.getValue("stationary"))
        val hair = Json.decodeFromJsonElement<Map<String, MouthOutlineHair>>(values.getValue("hair"))
        val attachments = Json.decodeFromJsonElement<Map<String, MouthOutlineHeadAttachment>>(values.getValue("attachments"))
        require(components.isEmpty() && stationary.isEmpty() && hair.isEmpty() && attachments.isEmpty() || componentDigest != null)
        val componentMeshes = componentDigest?.let {
            ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue(it), checkpoint)
        }.orEmpty()
        fun mouth(key: String): Pair<MouthFittedInputs, Set<String>> {
            val captured = values.getValue(key).jsonObject
            val digests = listOfNotNull(captured["mouth"]?.jsonObject?.get("measurement")?.jsonPrimitive?.content).toSet()
            return MouthFittedInputs.decode(ModifierAlgorithmContext(captured, context.resources.filterKeys { it in digests })) to digests
        }
        val (before, beforeDigests) = mouth("before")
        val (after, afterDigests) = mouth("after")
        val outlineValues = values.getValue("outline").jsonObject
        // The desired owner and its ribbons may refer to the same fitted measurement.
        val outlineDigest = outlineValues["mouth"]?.jsonObject?.get("measurement")?.jsonPrimitive?.content
        val ownerResources = (beforeDigests + afterDigests + rawDigest + listOfNotNull(componentDigest)) - listOfNotNull(outlineDigest).toSet()
        val ribbons = ModifierAlgorithmContext(outlineValues, context.resources - ownerResources, context.upstream)
        val outline = MouthOutlineInputs.decode(ribbons)
        require(before.source != null && after.source != null && outline.fitted != null &&
            before.sourceVertices == null && after.sourceVertices == null && before.layers.keys == outline.targets.keys + components.keys &&
            after.layers.keys == before.layers.keys && outline.targets.values.all { it.fittedProvider != null && it.contourOwner }) {
            "Fitted mouth outline requires paired whole-mouth observations"
        }
        val textures = Json.decodeFromJsonElement<Map<String, Texture>>(values.getValue("textures"))
        require(textures.keys == outline.targets.keys)
        val source = (before.layers.keys + stationary.keys + hair.keys + attachments.keys).associateWith { id -> requireNotNull(input.mesh(id)) }
        val scoped = RigIR(input.canvas, input.parameters, parts = input.parts,
            deformers = input.deformers, meshes = source.values.toList(), glues = input.glues)
        val model = PuppetIr.toPuppet(scoped)
        val live = model.drawables.associateBy { it.id.raw }
        val meshes = mutableListOf<ModifierObjectUpdate<Mesh>>()
        val frames = mutableMapOf<String, FrameTransform>()
        val head = values["head"]?.takeUnless { it == JsonNull }?.jsonObject
        val activeHead = model.parameters.any { it.id == LegacyFaceBinding.angleX || it.id == LegacyFaceBinding.angleY }
        require(!activeHead || head != null) { "Fitted mouth outline requires source frames for an active head turn" }
        val headProjection = head?.let { changeHead(scoped, input, it, instance.targets.mapTo(hashSetOf()) { target -> target.id }, checkpoint) }
        val headChanges = headProjection?.updates.orEmpty()
        val algorithm = MouthFittedAlgorithm()
        fun MouthFittedInputs.owner(id: String) = copy(layers = mapOf(id to layers.getValue(id)),
            maskSources = maskSources.filter { it == id })
        for ((id, target) in outline.targets) {
            checkpoint()
            val actual = live.getValue(id)
            val oldLayer = before.layers.getValue(id)
            val newLayer = after.layers.getValue(id)
            require(newLayer.frame ==
                Bounds(target.frame.left, target.frame.top, target.frame.right, target.frame.bottom)) {
                "Fitted mouth outline requires the desired source frame: $id"
            }
            val neutral = requireNotNull(raw[target.canonical]) { "Missing fitted mouth source topology: $id" }
            val meshTarget = ModifierTarget("mesh", id)
            require(listOfNotNull(before.head, after.head).all { head ->
                context.upstream.any { capability -> capability.instanceId == head.provider &&
                    capability.type in setOf("face/fitted", "face.depth-turn") && meshTarget in capability.targets }
            }) { "Missing fitted outline head provider for $id" }
            val texture = textures.getValue(id)
            val topology = outline.topology(target, checkpoint)
            val contour = MouthContour.ownerContour(topology.positions, topology.triangles) ?: continue
            val layer = outline.artwork.layerForTarget(id)
            val desired = MouthOutlineOwner.mesh(contour, target.frame, layer.left, layer.top,
                layer.width.toFloat(), layer.height.toFloat(), target::toCanvas)
            val parent = requireNotNull(actual.parentDeformerId)
            val old = ScaffoldMesh.build(neutral, ScaffoldArms.Parent(parent, oldLayer.frame),
                ScaffoldMesh.Geometry.Zero, checkpoint).first
            val next = DrawableMesh(desired.positions, texture.bind(desired.uvs), desired.indices)
            val local = model.copy(drawables = listOf(actual.copy(blendShapes = emptyList())))
            fun pose(mesh: DrawableMesh, positions: FloatArray, motion: MouthFittedInputs) = algorithm.project(
                local.copy(drawables = listOf(local.drawables.single().copy(mesh = mesh))), setOf(id), motion.owner(id),
                checkpoint, sourcePositions = mapOf(id to positions))
            val previous = pose(old, neutral.rigPositions, before)
            val replacement = pose(next, contour.positions, after)
            val oldOwner = previous.drawables.single()
            val newOwner = replacement.drawables.single()
            require(oldOwner.parentDeformerId == parent && newOwner.parentDeformerId == parent) {
                "Fitted mouth outline requires mesh-local lowering or an upstream head carrier: $id"
            }
            val original = source.getValue(id)
            val expected = PuppetIr.meshToIr(oldOwner)
            require(original.geometry == expected.geometry && original.offsets == expected.offsets &&
                original.shapes == expected.shapes && input.authoring.paths.none { it.mesh == id } &&
                input.authoring.vertexGroups.none { it.mesh == id } && input.glues.none { it.meshA == id || it.meshB == id }) {
                "Fitted mouth outline cannot replace authored owner topology: $id"
            }
            val transform = FrameTransform(newLayer.frame, if (head == null) oldLayer.frame else newLayer.frame)
            frames[id] = transform
            val output = transform.mesh(PuppetIr.meshToIr(newOwner))
            val updated = original.copy(geometry = output.geometry, offsets = output.offsets, shapes = output.shapes)
            if (updated != original) meshes += ModifierObjectUpdate(original, updated)
        }
        for ((id, canonical) in components) {
            checkpoint()
            val actual = live.getValue(id)
            val parent = requireNotNull(actual.parentDeformerId)
            val local = model.copy(drawables = listOf(actual.copy(blendShapes = emptyList())))
            fun pose(mesh: ScaffoldMesh.RawMesh, motion: MouthFittedInputs): Mesh {
                val frame = motion.layers.getValue(id).frame
                val data = ScaffoldMesh.build(mesh, ScaffoldArms.Parent(parent, frame),
                    ScaffoldMesh.Geometry.Zero, checkpoint).first
                val posed = algorithm.project(local.copy(drawables = listOf(local.drawables.single().copy(mesh = data))),
                    setOf(id), motion.owner(id), checkpoint, sourcePositions = mapOf(id to mesh.rigPositions))
                return PuppetIr.meshToIr(posed.drawables.single())
            }
            val original = source.getValue(id)
            val previous = pose(raw.getValue(canonical), before)
            val next = pose(componentMeshes.getValue(canonical), after)
            require(previous.parent == original.parent && next.parent == original.parent &&
                previous.geometry == original.geometry && previous.offsets == original.offsets &&
                previous.shapes == original.shapes) { "Fitted outline component differs from its source binding: $id" }
            val output = original.copy(geometry = next.geometry, offsets = next.offsets, shapes = next.shapes)
            if (output != original) meshes += ModifierObjectUpdate(original, output)
        }
        for ((id, observed) in stationary) {
            checkpoint()
            val actual = live.getValue(id)
            val original = source.getValue(id)
            val parent = requireNotNull(actual.parentDeformerId)
            fun geometry(mesh: ScaffoldMesh.RawMesh, frame: ModifierSourceBounds) =
                ScaffoldMesh.build(mesh, ScaffoldArms.Parent(parent, Bounds(frame.left, frame.top, frame.right, frame.bottom)),
                    ScaffoldMesh.Geometry.Zero, checkpoint).first
            val previous = geometry(raw.getValue(observed.canonical), observed.before)
            val expected = PuppetIr.meshToIr(actual.copy(mesh = previous)).geometry
            require(expected == original.geometry && original.shapes.isEmpty() &&
                original.offsets?.axes.isNullOrEmpty()) { "Fitted outline cannot move edited stationary geometry: $id" }
            val desired = geometry(componentMeshes.getValue(observed.canonical), observed.after)
            val replacement = original.copy(geometry = PuppetIr.meshToIr(actual.copy(mesh = desired)).geometry)
            if (replacement != original) meshes += ModifierObjectUpdate(original, replacement)
        }
        val hairChanges = linkedMapOf<String, ModifierObjectUpdate<Deformer>>()
        fun turn(phase: String): DepthTurnInputs {
            val headPhases = requireNotNull(head)
            return DepthTurnInputs.decode(ModifierAlgorithmContext(headPhases.getValue(phase).jsonObject.getValue("turn").jsonObject))
        }
        val previousTurn by lazy { turn("before") }
        val desiredTurn by lazy { turn("after") }
        if (attachments.isNotEmpty()) {
            for ((id, attachment) in attachments) {
                checkpoint()
                val original = source.getValue(id)
                val actual = live.getValue(id)
                val previous = attachment.project(model, actual, raw.getValue(attachment.canonical), attachment.before,
                    previousTurn, checkpoint)
                val desired = attachment.project(model, actual, componentMeshes.getValue(attachment.canonical), attachment.after,
                    desiredTurn, checkpoint)
                val merged = mergeAttachment(model, actual, previous to null, desired to null,
                    requireNotNull(headProjection), emptySet(), instance, checkpoint)
                val output = PuppetIr.meshToIr(merged.drawables.single())
                val replacement = original.copy(geometry = output.geometry, offsets = output.offsets, shapes = output.shapes)
                if (replacement != original) meshes += ModifierObjectUpdate(original, replacement)
            }
        }
        if (hair.isNotEmpty()) {
            val capability = context.upstream.single { it.type == "hair/chains" }
            val provider = RigModifierInstance(requireNotNull(capability.instanceId), capability.type, capability.version,
                capability.targets, config = capability.config, outputAliases = capability.outputAliases, plugin = capability.plugin)
            val byId = model.deformers.associateBy { it.id }
            for ((id, observed) in hair) {
                checkpoint()
                require(ModifierTarget("mesh", id) in provider.targets)
                val actual = live.getValue(id)
                val carrier = byId[actual.parentDeformerId] as? org.umamo.runtime.model.Deformer.Warp
                    ?: error("Fitted outline requires an existing hair chain: $id")
                require(carrier.id.raw == ScaffoldBinding.deformerId(provider, id, observed.chain, shared = true)) {
                    "Fitted outline hair carrier identity differs from its provider: $id"
                }
                val previous = observed.project(model, actual, carrier, raw.getValue(observed.canonical), observed.before,
                    previousTurn, provider, checkpoint)
                val desired = observed.project(model, actual, carrier, componentMeshes.getValue(observed.canonical), observed.after,
                    desiredTurn, provider, checkpoint)
                val original = source.getValue(id)
                val liveWarp = PuppetIr.deformerToIr(carrier) as Deformer.Warp
                val earlierFrames = instance.targets.takeWhile { it.id != id }.flatMapTo(hashSetOf()) { target ->
                    input.parentsOfMesh(target.id).map { it.id }
                }
                val merged = mergeAttachment(model, actual, previous, desired, requireNotNull(headProjection), earlierFrames,
                    instance, checkpoint)
                val output = PuppetIr.meshToIr(merged.drawables.single())
                val replacement = original.copy(geometry = output.geometry, offsets = output.offsets, shapes = output.shapes)
                if (replacement != original) meshes += ModifierObjectUpdate(original, replacement)
                val updated = PuppetIr.deformerToIr(merged.deformers.single { it.id == carrier.id }) as Deformer.Warp
                if (updated != liveWarp) {
                    val entry = ModifierObjectUpdate<Deformer>(liveWarp, updated)
                    val other = hairChanges.putIfAbsent(liveWarp.id, entry)
                    require(other == null || other == entry) { "Conflicting fitted outline hair carrier: $id" }
                }
            }
        }
        val ribbonInput = ModifierEvaluationInput(scoped, ribbons)
        val rasters = MouthOutlineAlgorithm().changes(ribbonInput, instance, checkpoint).rasters
        val updates = meshes.associate { it.after.id to it.after }
        return ModifierModelChanges(deformers = ModifierObjectChanges(updated = headChanges + hairChanges.values),
            meshes = ModifierObjectChanges(updated = meshes), rasters = rasters.map { raster ->
            ModifierRasterOutput(updates[raster.owner.id] ?: source.getValue(raster.owner.id), frames[raster.owner.id]?.mesh(raster.mesh) ?: raster.mesh,
                raster.layer, raster.pixels, raster.paths, raster.uvSpace)
        })
    }

    private fun mergeAttachment(
        model: PuppetModel,
        actual: Drawable,
        previous: Pair<Drawable, org.umamo.runtime.model.Deformer.Warp?>,
        desired: Pair<Drawable, org.umamo.runtime.model.Deformer.Warp?>,
        head: HeadProjection,
        earlierFrames: Set<String>,
        instance: RigModifierInstance,
        checkpoint: () -> Unit,
    ): PuppetModel {
        val scoped = model.copy(drawables = listOf(actual))
        val target = ModifierTarget("mesh", actual.id.raw)
        fun phase(hair: Pair<Drawable, org.umamo.runtime.model.Deformer.Warp?>, frames: Map<String, Deformer>): PuppetModel {
            val native = PuppetIr.toPuppet(RigIR(io.github.psd2live.format.model.Canvas(model.canvasWidth, model.canvasHeight),
                emptyList(), deformers = frames.values.toList())).deformers.associateBy { it.id }
            return scoped.copy(drawables = listOf(hair.first), deformers = scoped.deformers.map {
                if (it.id == hair.second?.id) requireNotNull(hair.second) else native[it.id] ?: it
            })
        }
        val before = phase(previous, head.previous)
        val after = phase(desired, head.desired)
        val merged = BindingMerge.apply(scoped, instance.copy(targets = listOf(target), outputAliases = emptyMap()),
            object : BindingMerge.Source {
                override fun provider(instance: RigModifierInstance) = instance
                override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = before to after
                override fun resolveParameter(instance: RigModifierInstance, before: io.github.psd2live.format.model.Parameter,
                                              declared: io.github.psd2live.format.model.Parameter): io.github.psd2live.format.model.Parameter? = null
                override fun static(model: PuppetModel) = BindingProjection.retain(model, emptySet(),
                    mapOf(ParameterId("ParamMouthOpenY") to 1f), checkpoint)
                override fun onlyMasksChange(before: PuppetModel, after: PuppetModel,
                                             target: ModifierTarget, branch: Set<DeformerId>) = false
                override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                    scoped.deformers.associate { it.id to it.id }
                override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                    emptyMap<PartId, PartId>()
                override fun requiredParts(model: PuppetModel, target: ModifierTarget) =
                    emptySet<PartId>()
                override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) = canonical
                override fun vertexSources(from: PuppetModel, original: Drawable,
                                           to: PuppetModel, target: Drawable,
                                           checkpoint: () -> Unit): List<org.umamo.edit.VertexSource> {
                    val source = requireNotNull(original.mesh)
                    val actual = requireNotNull(target.mesh)
                    require(source.uvs.contentEquals(actual.uvs) && source.indices.contentEquals(actual.indices)) {
                        "Fitted outline cannot migrate edited attachment topology: ${target.id.raw}"
                    }
                    return List(source.vertexCount) { org.umamo.edit.VertexSource.FromOld(it) }
                }
            }, checkpoint)
        val beforeWarp = previous.second ?: return merged
        val afterWarp = requireNotNull(desired.second)
        val id = beforeWarp.id
        val live = scoped.deformers.single { it.id == id } as org.umamo.runtime.model.Deformer.Warp
        val result = merged.deformers.single { it.id == id } as org.umamo.runtime.model.Deformer.Warp
        if (PuppetIr.deformerToIr(live) == PuppetIr.deformerToIr(beforeWarp.copy(partId = live.partId))) return merged
        // The outline visits the targets in order. Earlier targets have already updated their shared ancestors.
        val frames = head.updates.filter { it.after.id in earlierFrames }.map { it.after }
        if (frames.isEmpty()) return merged
        val runtime = PuppetIr.toPuppet(RigIR(io.github.psd2live.format.model.Canvas(model.canvasWidth, model.canvasHeight),
            emptyList(), deformers = frames)).deformers.associateBy { it.id }
        val current = scoped.copy(deformers = scoped.deformers.map { runtime[it.id] ?: it })
        fun static(phase: PuppetModel) = phase.copy(deformers = BindingProjection.retain(phase, emptySet(),
            mapOf(ParameterId("ParamMouthOpenY") to 1f), checkpoint).deformers)
        val shapes = WarpBlendResidual.warp(current, static(before), static(after), live, beforeWarp, afterWarp,
            result, org.umamo.runtime.model.DrawableId(instance.outputId("${actual.id.raw}/warp-morph:${id.raw}")), checkpoint)
        return merged.copy(deformers = merged.deformers.map { if (it.id == id) result.copy(blendShapes = shapes) else it })
    }

    private data class HeadProjection(
        val previous: Map<String, Deformer>,
        val desired: Map<String, Deformer>,
        val updates: List<ModifierObjectUpdate<Deformer>>,
    )

    private fun changeHead(
        model: RigIR,
        input: ModifierEvaluationInput,
        head: JsonObject,
        owners: Set<String>,
        checkpoint: () -> Unit,
    ): HeadProjection {
        val aliases = head.getValue("aliases").jsonObject.mapValues { it.value.jsonPrimitive.content }
        val ownerParents = owners.flatMap { input.parentsOfMesh(it) }.associateBy { it.id }
        val angles = listOf(LegacyFaceBinding.angleX, LegacyFaceBinding.angleY, LegacyFaceBinding.angleZ)
        val runtime = PuppetIr.toPuppet(RigIR(model.canvas, model.parameters))
        val parameters = runtime.parameters.associateBy { it.id }
        val active = parameters.keys
        val ids = aliases.mapKeys { DeformerId(it.key) }.mapValues { DeformerId(it.value) }
        val remap = ModelIdRemap(deformers = ids)
        fun project(name: String): Map<String, Deformer> {
            val phase = head.getValue(name).jsonObject
            val face = ModifierAlgorithmContext(phase.getValue("base").jsonObject)
            val base = LegacyFaceInputs.decode(face, checkpoint)
            val structure = LegacyFaceInputs.scaffold(face).values.getValue("frames").jsonObject
            val neutral = ScaffoldFramesContext.build(structure, checkpoint)
            val body = phase["body"]?.jsonObject
            val bodyFrames = if (body == null) neutral else {
                val frames = ScaffoldFramesContext.decode(structure, checkpoint)
                val legs = frames.standing?.legs.orEmpty().map { leg ->
                    BodyStance.Leg(Side.NONE, leg.hip.x, leg.hip.y, leg.knee.x, leg.knee.y, leg.ankle.x, leg.ankle.y, leg.floorY)
                }
                val strength = LegacyBodyInputs.finite(body.getValue("strength"))
                val torso = LegacyBodyInputs.torso(body.getValue("torso"))
                val tuning = LegacyBodyInputs.tuning(body.getValue("tuning").jsonObject)
                val stance = BodyStance(frames.body, torso, legs, frames.standing?.frame, strength, tuning)
                LegacyBodyMotion.deformers(neutral, frames.body, stance, strength, frames.head.canvasPivot.y, checkpoint = checkpoint)
            }
            val bound = LegacyFaceBinding.bind(runtime.copy(parameters = emptyList(), deformers = bodyFrames),
                base.measurements, base.tuning, base.strength, angles.map(parameters::getValue), base.folderLabel, checkpoint)
            val turn = DepthTurnInputs.decode(ModifierAlgorithmContext(phase.getValue("turn").jsonObject))
            val byId = bound.deformers.associateBy { it.id }
            val underFace = bound.deformers.filter { deformer ->
                generateSequence(deformer.parent) { byId[it]?.parent }.any { it == ScaffoldHead.faceId }
            }.mapTo(hashSetOf()) { it.id }
            val styled = FaceTurn(turn.landmarks, turn.style).takeIf { it.usable }
            val turned = if (styled != null) DepthTurnMotion.deformers(bound.deformers, styled, turn.head, turn.face,
                turn.container, turn.frames, underFace, turn.fittedEyes, turn.fittedBrows, turn.landmarks, checkpoint)
            else {
                val shell = HeadTurn(turn.container.pivotX, turn.centerY, turn.radiusX, turn.radiusY, turn.shellTurn,
                    turn.shellDepth, turn.chinY, checkpoint)
                val mouthWarp = ScaffoldHead.featureId(ScaffoldHead.Feature.MOUTH, Side.NONE)
                bound.deformers.map { deformer ->
                    if (deformer.id == mouthWarp && deformer is org.umamo.runtime.model.Deformer.Warp)
                        turn.mouthWarp?.place(deformer, shell, turn.container.pivotX, turn.radiusX, turn.container.strength, checkpoint)
                            ?: deformer
                    else deformer
                }
            }
            val leaned = LegacyBodyMotion.withFaceLean(turned, turn.leanDown, turn.leanUp)
            val projected = BindingProjection.retain(runtime.copy(deformers = leaned), active, checkpoint = checkpoint)
            return projected.deformers.filter { it.id in ids }.map { PuppetIr.deformerToIr(remap.deformer(it)) }.associateBy { it.id }
        }
        val previous = project("before")
        val desired = project("after")
        require(previous.keys == aliases.values.toSet() && desired.keys == previous.keys &&
            previous.keys.all(ownerParents::containsKey)) { "Invalid fitted outline head frame aliases" }
        val updates = previous.mapNotNull { (id, before) ->
            val after = desired.getValue(id)
            if (before == after) return@mapNotNull null
            val live = ownerParents.getValue(id)
            require(before.parent == after.parent && live.parent == before.parent) { "Fitted outline head frame has moved: $id" }
            val updated = when {
                before is Deformer.Warp && after is Deformer.Warp && live is Deformer.Warp -> {
                    require(live.rows == before.rows && live.columns == before.columns && live.shapes.isEmpty()) {
                        "Fitted outline head warp is not the source lattice: $id"
                    }
                    val lattice = if (live.lattice == before.lattice) after.lattice else {
                        val warps = PuppetIr.toPuppet(RigIR(model.canvas, model.parameters,
                            deformers = listOf(live, before, after))).deformers.filterIsInstance<org.umamo.runtime.model.Deformer.Warp>()
                        val merged = BindingMerge.warpDelta(warps[0].geometryGrid, warps[1].geometryGrid, warps[2].geometryGrid,
                            parameters.mapValues { it.value.default }, checkpoint)
                        (PuppetIr.deformerToIr(warps[0].copy(geometryGrid = merged)) as Deformer.Warp).lattice
                    }
                    live.copy(lattice = lattice, rows = after.rows, columns = after.columns, bilinear = after.bilinear)
                }
                before is Deformer.Rotation && after is Deformer.Rotation && live is Deformer.Rotation -> {
                    require(live.pivot == before.pivot) { "Fitted outline head rotation is not the source pivot: $id" }
                    live.copy(pivot = after.pivot)
                }
                else -> error("Fitted outline head frame kind changed: $id")
            }
            if (updated == live) null else ModifierObjectUpdate(live, updated)
        }
        return HeadProjection(previous, desired, updates)
    }

    private class FrameTransform(private val from: Bounds, private val to: Bounds) {
        fun mesh(mesh: Mesh): Mesh {
            if (from == to) return mesh
            val geometry = requireNotNull(mesh.geometry)
            require(mesh.shapes.isEmpty()) { "Fitted outline cannot rebase mesh-local blend shapes" }
            val width = to.width.coerceAtLeast(1e-4f)
            val height = to.height.coerceAtLeast(1e-4f)
            val scaleX = from.width.coerceAtLeast(1e-4f) / width
            val scaleY = from.height.coerceAtLeast(1e-4f) / height
            val x = (from.left - to.left) / width
            val y = (from.top - to.top) / height
            val points = geometry.positions.shared()
            return mesh.copy(
                geometry = geometry.copy(positions = Floats.wrap(FloatArray(points.size) { i ->
                    if (i % 2 == 0) points[i] * scaleX + x else points[i] * scaleY + y
                })),
                offsets = mesh.offsets?.let { grid ->
                    grid.copy(cells = grid.cells.map { cell ->
                        val offsets = cell.form.deltas.shared()
                        cell.copy(form = cell.form.copy(deltas = Floats.wrap(FloatArray(offsets.size) { i ->
                            offsets[i] * if (i % 2 == 0) scaleX else scaleY
                        })))
                    })
                },
            )
        }
    }

    @Serializable
    data class Stationary(val canonical: String, val before: ModifierSourceBounds, val after: ModifierSourceBounds)

    @Serializable
    data class Texture(
        val page: Int, val x: Float, val y: Float, val scaleX: Float, val scaleY: Float, val angle: Float,
        val width: Int, val height: Int, val originX: Float, val originY: Float, val unitsX: Float, val unitsY: Float,
    ) {
        init {
            require(page >= 0 && width > 0 && height > 0)
            require(listOf(x, y, scaleX, scaleY, angle, originX, originY, unitsX, unitsY).all(Float::isFinite))
        }

        fun bind(offsets: FloatArray): FloatArray {
            val placement = AtlasPlacement(page, x, y, scaleX, scaleY, angle)
            return FloatArray(offsets.size).also { uvs ->
                for (index in offsets.indices step 2) {
                    val pixel = atlasPixelOf(placement, (offsets[index] - originX) * unitsX, (offsets[index + 1] - originY) * unitsY)
                    uvs[index] = pixel[0] / width
                    uvs[index + 1] = pixel[1] / height
                }
            }
        }
    }

    companion object {
        const val VERSION = 3
        const val ENTRYPOINT = "builtin.6d6f7574682e6f75746c696e65.v3/outline"
        private const val SCHEMA = "mouth-outline-owner/3"

        fun capture(outline: ModifierAlgorithmContext, before: MouthFittedInputs, after: MouthFittedInputs,
                    sourceMeshes: ModifierAlgorithmResource, textures: Map<String, Texture>, head: JsonObject? = null,
                    components: Map<String, String> = emptyMap(), componentMeshes: ModifierAlgorithmResource? = null,
                    stationary: Map<String, Stationary> = emptyMap(), hair: Map<String, MouthOutlineHair> = emptyMap(),
                    attachments: Map<String, MouthOutlineHeadAttachment> = emptyMap()): ModifierAlgorithmContext {
            val previous = before.encode()
            val desired = after.encode()
            return ModifierAlgorithmContext(buildJsonObject {
                put("schema", SCHEMA)
                put("outline", outline.values)
                put("before", previous.values)
                put("after", desired.values)
                put("source_meshes", sourceMeshes.digest)
                put("textures", Json.encodeToJsonElement(textures))
                put("head", head ?: JsonNull)
                put("components", Json.encodeToJsonElement(components))
                put("stationary", Json.encodeToJsonElement(stationary))
                put("hair", Json.encodeToJsonElement(hair))
                put("attachments", Json.encodeToJsonElement(attachments))
                put("component_meshes", componentMeshes?.digest?.let(::JsonPrimitive) ?: JsonNull)
            }, outline.resources + previous.resources + desired.resources + (sourceMeshes.digest to sourceMeshes) +
                listOfNotNull(componentMeshes?.let { it.digest to it }).toMap(), outline.upstream)
        }
    }
}
