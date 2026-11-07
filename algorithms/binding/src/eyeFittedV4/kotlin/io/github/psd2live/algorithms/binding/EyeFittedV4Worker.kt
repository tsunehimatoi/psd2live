package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*

/** Fits the eyes after rebasing the captured head turn onto their finer source frames. */
class EyeFittedV4Algorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val eyeInstance = instance.copy(type = EyeFittedSourceAlgorithm.TYPE, version = EyeFittedAlgorithm.VERSION)
        val head = context.values["fitted_head"]?.jsonObject
            ?: return EyeFittedSourceAlgorithm().changes(input, eyeInstance, context, checkpoint)
        val provider = context.upstream.single { it.capability == "rig.scaffold" }
        val headProvider = context.upstream.single { it.type == "face/fitted" && it.capability == "face/legacy" }
        require(provider.version == 2 && headProvider.version == 1 &&
            provider.instanceId == instance.config.getValue("scaffold").jsonPrimitive.content &&
            headProvider.instanceId == head.getValue("provider").jsonPrimitive.content &&
            provider.targets.toSet() == instance.targets.toSet() && headProvider.targets.toSet() == instance.targets.toSet())
        val bodyProvider = head["body_provider"]?.jsonPrimitive?.content
        val bodies = context.upstream.filter { it.capability == "body/legacy" }
        require(bodies.isEmpty() && bodyProvider == null || bodies.singleOrNull()?.let {
            it.instanceId == bodyProvider && it.type == "body/legacy" && it.version == 1 && it.targets.toSet() == instance.targets.toSet()
        } == true) { "Fitted eye v4 requires the captured legacy body provider" }
        val scaffold = RigModifierInstance(requireNotNull(provider.instanceId), provider.type, provider.version, provider.targets,
            config = provider.config, outputAliases = provider.outputAliases, plugin = provider.plugin)
        val liveMeshes = input.meshes.associateBy { it.id }
        val liveFrames = input.deformers.associateBy { it.id }
        val depths = DepthTurnAlgorithm()
        val angles = listOf(LegacyFaceBinding.angleX, LegacyFaceBinding.angleY, LegacyFaceBinding.angleZ)
        val runtime = PuppetIr.toPuppet(RigIR(input.canvas, input.parameters))
        val currentParameters = runtime.parameters.associateBy { it.id }
        val targets = instance.targets.mapTo(linkedSetOf()) {
            require(it.kind == "mesh")
            it.id
        }

        fun project(phase: String): RigIR {
            val payload = head.getValue(phase).jsonObject
            val faceContext = ModifierAlgorithmContext(payload.getValue("base").jsonObject, context.resources)
            val face = LegacyFaceInputs.decode(faceContext, checkpoint)
            val turn = DepthTurnInputs.decode(ModifierAlgorithmContext(payload.getValue("turn").jsonObject, context.resources))
            val structure = LegacyFaceInputs.scaffold(faceContext)
            val canonical = structure.values.getValue("canonical_targets").jsonObject
            require(canonical.keys == targets &&
                canonical.all { (id, value) -> id == value.jsonPrimitive.content }) {
                "Fitted eye v4 requires the primary source mesh for every target"
            }
            val meshResource = mapOf("source_meshes" to requireNotNull(turn.sourceMeshes))
            val model = ScaffoldAlgorithm().buildSource(ModifierAlgorithmContext(structure.values, meshResource), checkpoint)
            val body = payload["body"]?.jsonObject
            require((body != null) == (bodyProvider != null))
            val deformers = if (body == null) model.deformers else {
                val frames = ScaffoldFramesContext.decode(structure.values.getValue("frames").jsonObject, checkpoint)
                val standing = frames.standing
                val legs = standing?.legs.orEmpty().map { leg ->
                    BodyStance.Leg(Side.NONE, leg.hip.x, leg.hip.y, leg.knee.x, leg.knee.y, leg.ankle.x, leg.ankle.y, leg.floorY)
                }
                val strength = LegacyBodyInputs.finite(body.getValue("strength"))
                val tuning = LegacyBodyInputs.tuning(body.getValue("tuning").jsonObject)
                val torso = LegacyBodyInputs.torso(body.getValue("torso"))
                val stance = BodyStance(frames.body, torso, legs, standing?.frame, strength, tuning)
                val rotation = LegacyBodyMotion.deformers(model.deformers.filter { it.id == ScaffoldHead.rotationId },
                    frames.body, stance, strength, frames.head.canvasPivot.y, checkpoint = checkpoint).single()
                model.deformers.map { if (it.id == rotation.id) rotation else it }
            }
            val bodyParameters = if (body == null) emptyList() else LegacyBodyMotion.parameters.map(currentParameters::getValue)
            val source = runtime.copy(parameters = bodyParameters,
                deformers = deformers, drawables = model.drawables.map { mesh ->
                    val live = liveMeshes.getValue(mesh.id.raw)
                    mesh.copy(name = live.name, drawOrder = live.drawOrder, opacity = live.opacity)
                })
            val legacy = LegacyFaceBinding.bind(source, face.measurements, face.tuning, face.strength,
                angles.map(currentParameters::getValue), face.folderLabel, checkpoint)
            val turned = depths.project(legacy, source.drawables.mapTo(linkedSetOf()) { it.id.raw }, turn, checkpoint)
            val keys = (angles + bodyParameters.map { it.id }).toSet()
            return PuppetIr.toIr(BindingProjection.retain(turned, keys, checkpoint = checkpoint))
        }
        val before = project("before")
        val after = project("after")
        val representative = targets.first()
        fun alias(canonical: String) = ScaffoldBinding.deformerId(scaffold, representative, canonical)
        val priorFrames = before.deformers.associateBy { it.id }
        val nextFrames = after.deformers.associateBy { it.id }
        require(priorFrames.keys == nextFrames.keys) { "Fitted eye v4 cannot replace existing head carriers" }
        val defaults = currentParameters.mapValues { it.value.default }
        val frames = before.deformers.mapNotNull { prior ->
            checkpoint()
            val next = nextFrames.getValue(prior.id)
            if (prior == next) return@mapNotNull null
            val id = alias(prior.id)
            val live = requireNotNull(liveFrames[id]) { "Fitted eye v4 cannot resolve the changed source frame $id" }
            require(prior.parent == next.parent && live.parent == prior.parent?.let(::alias)) {
                "Fitted eye v4 cannot migrate the parent of frame $id"
            }
            val afterFrame: Deformer = when {
                prior is Deformer.Warp && next is Deformer.Warp && live is Deformer.Warp -> {
                    require(prior.columns == live.columns && prior.rows == live.rows && prior.bilinear == live.bilinear &&
                        live.shapes.isEmpty() && prior.channels == next.channels) {
                        "Fitted eye v4 cannot migrate the authored frame $id"
                    }
                    val lattice = if (live.lattice == prior.lattice) next.lattice else {
                        val warps = PuppetIr.toPuppet(RigIR(input.canvas, input.parameters,
                            deformers = listOf(live, prior, next)))
                            .deformers.filterIsInstance<org.umamo.runtime.model.Deformer.Warp>()
                        val merged = BindingMerge.warpDelta(warps[0].geometryGrid, warps[1].geometryGrid, warps[2].geometryGrid, defaults, checkpoint)
                        (PuppetIr.deformerToIr(warps[0].copy(geometryGrid = merged)) as Deformer.Warp).lattice
                    }
                    live.copy(columns = next.columns, rows = next.rows, bilinear = next.bilinear, lattice = lattice)
                }
                prior is Deformer.Rotation && next is Deformer.Rotation && live is Deformer.Rotation -> {
                    require(live.pivot == prior.pivot && live.shapes.isEmpty() && prior.channels == next.channels) {
                        "Fitted eye v4 cannot migrate the authored rotation $id"
                    }
                    live.copy(pivot = next.pivot)
                }
                else -> error("Fitted eye v4 cannot migrate frame $id")
            }
            if (afterFrame == live) null else ModifierObjectUpdate(live, afterFrame)
        }
        val oldMeshes = before.meshes.associateBy { it.id }
        val newMeshes = after.meshes.associateBy { it.id }
        val indexed = buildSet {
            input.glues.forEach { add(it.meshA); add(it.meshB) }
            input.authoring.paths.forEach { add(it.mesh) }
            input.authoring.vertexGroups.forEach { add(it.mesh) }
            input.advanced.simulations.forEach { simulation -> simulation.targets.forEach { add(it.mesh) } }
            input.advanced.colliders.forEach { it.mesh?.let(::add) }
        }
        val changed = frames.mapTo(hashSetOf()) { it.before.id }
        fun usesChangedFrame(parent: String?): Boolean {
            val visited = hashSetOf<String>()
            var id = parent
            while (id != null) {
                checkpoint()
                require(visited.add(id)) { "Cyclic fitted head branch at $id" }
                if (id in changed) return true
                id = liveFrames.getValue(id).parent
            }
            return false
        }
        val ownedFrames = priorFrames.keys.mapTo(hashSetOf(), ::alias)
        require(input.meshes.none { it.id !in oldMeshes && usesChangedFrame(it.parent) } &&
            input.deformers.none { it.id !in ownedFrames && usesChangedFrame(it.parent) } &&
            input.advanced.colliders.none { usesChangedFrame(it.deformer) }) {
            "Fitted eye v4 cannot move a head frame shared with additional authored objects"
        }
        val meshes = targets.mapNotNull { id ->
            checkpoint()
            val prior = oldMeshes[id] ?: return@mapNotNull null
            val next = newMeshes.getValue(id)
            val live = liveMeshes.getValue(id)
            val replacement = prior.geometry != next.geometry || prior.offsets != next.offsets
            if (!replacement && !usesChangedFrame(live.parent)) return@mapNotNull null
            val parent = prior.parent?.let(::alias)
            require(prior.parent == next.parent && live.parent == parent && live.geometry == prior.geometry &&
                live.offsets == prior.offsets && live.shapes.isEmpty() && id !in indexed) {
                "Fitted eye v4 cannot migrate the authored mesh $id"
            }
            if (replacement) ModifierObjectUpdate(live, live.copy(geometry = next.geometry, offsets = next.offsets)) else null
        }
        val migration = ModifierModelChanges(meshes = ModifierObjectChanges(updated = meshes),
            deformers = ModifierObjectChanges(updated = frames))
        val phaseMeshes = listOf("before", "after").map { phase ->
            head.getValue(phase).jsonObject.getValue("turn").jsonObject.getValue("sourceMeshes").jsonPrimitive.content
        }
        val eyeResources = context.resources - phaseMeshes.toSet()
        val eyeContext = ModifierAlgorithmContext(JsonObject(context.values - "fitted_head"), eyeResources, listOf(provider))
        val eye = EyeFittedAlgorithm().changes(migration.apply(input, checkpoint), eyeInstance, eyeContext, checkpoint)
        return eye.copy(meshes = combine(migration.meshes, eye.meshes) { it.id },
            deformers = combine(migration.deformers, eye.deformers) { it.id })
    }

    private fun <T> combine(first: ModifierObjectChanges<T>, second: ModifierObjectChanges<T>, id: (T) -> String): ModifierObjectChanges<T> {
        val originals = first.updated.associateBy { id(it.before) }
        val additions = second.updated.associateBy { id(it.before) }
        return ModifierObjectChanges(updated = (originals.keys + additions.keys).map { key ->
            ModifierObjectUpdate(originals[key]?.before ?: additions.getValue(key).before, additions[key]?.after ?: originals.getValue(key).after)
        }.filter { it.before != it.after })
    }

    companion object {
        const val VERSION = 4
        const val ENTRYPOINT = "builtin.6579652f666974746564.v4/fitted"
    }
}

object EyeFittedV4Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (EyeFittedV4Algorithm.ENTRYPOINT to EyeFittedV4Algorithm.VERSION) to EyeFittedV4Algorithm(),
    ))
}
