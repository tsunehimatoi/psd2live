package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId

/** Rebuilds the cheek-driven lid shapes from fitted eye artwork and lowers them against the live mouth axis. */
class MouthCheekLidAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val targets = instance.targets.toSet()
        val scaffold = instance.config.getValue("scaffold")
        fun provider(type: String, id: JsonElement) = requireNotNull(input.providers(type).singleOrNull {
            it.instanceId == id.jsonPrimitive.content && it.targets.containsAll(targets) &&
                (type == "rig.scaffold" || it.config["scaffold"] == scaffold)
        }) { "Missing cheek and lid provider: $type" }
        require(provider("rig.scaffold", scaffold).version == 2) { "Cheek and lid v2 requires a shared v2 scaffold" }
        val mouth = provider("mouth/fitted", instance.config.getValue("base"))
        require(mouth.version in 1..2) { "Cheek and lid v2 requires fitted mouths v1 or v2" }
        val eye = provider("eye/fitted", instance.config.getValue("dependencies").jsonObject.getValue("eye/fitted"))
        require(eye.version in 1..2) { "Cheek and lid v2 requires fitted eyes v1 or v2" }
        val preceding = input.upstream.filter { it.config["scaffold"] == scaffold && it.targets.any(targets::contains) }
        require(preceding.none { it.capability in unsupported }) {
            "Cheek and lid v2 requires lids without preceding depth turn or ordinary lid coupling. Use v1 for those stacks."
        }

        val context = input.context
        require(context.values.keys == setOf("schema", "source", "carriers") &&
            context.values.getValue("schema").jsonPrimitive.content == "mouth-cheek-lid/1")
        val captured = EyeSmileInputs.decode(ModifierAlgorithmContext(context.values.getValue("source").jsonObject, context.resources), checkpoint)
        val materials = captured.measurements
        val carrierNames = context.values.getValue("carriers").jsonObject
        require(carrierNames.keys == targets.mapTo(hashSetOf()) { it.id } && materials.targets.keys.all { it in carrierNames })
        val align = { x: Float, y: Float -> materials.alignment?.point(x, y) ?: (x to y) }
        val fits = EyeFitting.fit(captured.art, materials.faceCenterX, align).fits.mapValues { (side, fit) ->
            checkpoint()
            fit.moved(materials.moves[side.name].orEmpty())
        }
        require(fits.isNotEmpty() && captured.art.layers.any { it.tag == "EYELASH" }) {
            "Cheek and lid v2 requires readable fitted lid artwork"
        }
        val artwork = EyeArtworkFitting.fit(captured.art, materials.artworkStamps, fits, materials.corrections, align)
        val lower = knob(eye.config, "lowerShare", 5f, 0f..50f)
        val roundness = knob(eye.config, "roundness", 100f, 40f..160f)
        val squash = knob(eye.config, "lashSquash", 60f, 20f..100f)
        val cheek = knob(instance.config, "strength", 100f, 0f..200f)
        val motions = HashMap<EyeOptions, FittedEyeMotion>()
        val parameters = input.parameters
        val mouthForm = requireNotNull(input.parameter(MOUTH_FORM))
        require(!mouthForm.blend && mouthForm.min == -1f && mouthForm.max == 1f && mouthForm.default == 0f)
        // Lowering needs a live grid using MouthForm to keep it an ordinary parameter.
        val mouthMesh = mouth.targets.asSequence().mapNotNull { input.mesh(it.id) }
            .firstOrNull { mesh -> mesh.id !in materials.targets && mesh.offsets?.axes.orEmpty().any { it.parameter == MOUTH_FORM } }
        requireNotNull(mouthMesh) { "Cheek and lid v2 requires a live fitted mouth form grid" }
        val defaults = parameters.associate { ParameterId(it.id) to it.default }
        val atDefault = { id: ParameterId -> defaults[id] ?: 0f }
        val carriers = LinkedHashMap<String, Deformer>()
        val updates = instance.targets.mapNotNull { target ->
            checkpoint()
            val material = materials.targets[target.id] ?: return@mapNotNull null
            val layer = captured.art.layerForTarget(target.id)
            val lid = EyeModules.meshOf(layer.tag, layer.role) ?: return@mapNotNull null
            val side = Side.valueOf(layer.side)
            if (side != Side.NONE && side !in fits) return@mapNotNull null
            val raw = captured.topology.getValue(material.layer)
            val positions = raw.rigPositions
            val frame = material.frame.run { Bounds(left, top, right, bottom) }
            val wide = preceding.lastOrNull { it.capability == "eye.wide" && target in it.targets }
            val smile = preceding.lastOrNull { it.capability == "eye.smile" && target in it.targets }
            val options = EyeOptions(cheekPush = true, cheekStrength = cheek,
                wide = wide != null, wideLift = knob(wide?.config, "lift", 10f, 0f..80f),
                smile = smile != null, smileArc = .12f * knob(smile?.config, "arch", 100f, 0f..200f),
                smilePush = knob(smile?.config, "push", 50f, 0f..100f))
            val motion = motions.getOrPut(options) {
                FittedEyeMotion(fits.mapValues { (owner, fit) ->
                    EyeRig(fit.copy(artwork = artwork[owner]), lower, roundness, squash, options.smileArc, options.smilePush, options.wideLift)
                }, options)
            }
            val vertexOwners = if (side == Side.NONE) List(positions.size / 2) { i ->
                motion.sideAt(layer.id, positions[i * 2], positions[i * 2 + 1])
            } else emptyList()
            val shapes = motion.rigs.filterKeys { side == Side.NONE || side == it }.flatMap { (owner, rig) ->
                val local = EyeModules.lidShapes(lid, owner, positions, frame, rig, options, layer.id)
                if (side != Side.NONE) local else local.map { shape -> shape.copy(forms = shape.forms.map { form ->
                    form?.let { MeshForm(FloatArray(positions.size) { i -> if (vertexOwners[i / 2] == owner) it.positionDeltas[i] else 0f }) }
                }) }
            }
            val mesh = requireNotNull(input.mesh(target.id))
            val points = Floats.wrap(FloatArray(positions.size) { i ->
                val point = positions[i]
                if (material.parent == null) point
                else if (i % 2 == 0) (point - frame.left) / frame.width.coerceAtLeast(1e-4f)
                else (point - frame.top) / frame.height.coerceAtLeast(1e-4f)
            })
            val grid = if (side == Side.NONE) EyeModules.pairedLidGrid(lid, layer.id, positions, frame, motion)
            else EyeModules.lidGrid(lid, side, positions, frame, motion.rigs.getValue(side), options, layer.id)
            val expected = grid?.let {
                KeyGrid(it.axes.map { axis -> KeyAxis(axis.parameterId.raw, Floats.wrap(axis.keys)) },
                    it.cells.map { cell -> KeyCell(Ints.wrap(cell.coordinate), MeshOffsets(Floats.wrap(cell.form.positionDeltas))) })
            }
            require(mesh.shapes.none { it.parameter == MOUTH_FORM } &&
                mesh.offsets?.axes.orEmpty().none { it.parameter == MOUTH_FORM }) {
                "Cheek and lid v2 requires no existing mouth form cheek-lid tracks: ${target.id}"
            }
            val carrier = carrierNames.getValue(target.id).jsonObject
            val nativeId = carrier.getValue("id").jsonPrimitive.content
            val name = carrier.getValue("name").jsonPrimitive.content
            val branch = input.parentsOfMesh(target.id).mapTo(hashSetOf()) { it.id }
            // Shared gaze is keyed on the iris carriers, which need not be ancestors
            // of this lid. Retain those ordinary axes as read-only lowering context.
            val contextDeformers = input.deformers.map { deformer ->
                if (deformer.id in branch) deformer else when (deformer) {
                    is Deformer.Warp -> deformer.copy(shapes = emptyList())
                    is Deformer.Rotation -> deformer.copy(shapes = emptyList())
                }
            }
            val model = PuppetIr.toPuppet(RigIR(input.canvas, parameters,
                meshes = listOf(mesh.copy(id = nativeId), mouthMesh.copy(shapes = emptyList())),
                deformers = contextDeformers))
            val drawable = model.drawables.first()
            val mapping = requireNotNull(drawableSpaceMapping(model, emptyMap(), drawable.id))
            val world = mapping.localToWorld(requireNotNull(mesh.geometry).positions.shared())
            require((world.indices step 2).all { i ->
                val x = positions[i]
                val y = positions[i + 1]
                val source = materials.alignment?.canvas(x, y) ?: (x to y)
                // World space is y-up, while captured source positions are y-down.
                kotlin.math.abs(world[i] - source.first) <= .5f && kotlin.math.abs(-world[i + 1] - source.second) <= .5f
            }) { "Cheek and lid v2 cannot migrate an edited neutral lid frame: ${target.id}" }
            val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, atDefault)
            val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, atDefault)
            val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, atDefault)
            val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, atDefault)
            val newShapes = shapes.map { shape -> shape.copy(forms = shape.forms.map { form ->
                form?.let { MeshForm(it.positionDeltas, order, opacity, multiply, screen) }
            }) }
            val lowered = BlendShapeLowering({ _, _ -> name }, checkpoint).lawful(model.copy(
                drawables = listOf(drawable.copy(blendShapes = drawable.blendShapes + newShapes)) + model.drawables.drop(1)))
            require(lowered.parameters == model.parameters && lowered.deformers.take(model.deformers.size) == model.deformers) {
                "Cheek and lid v2 cannot change existing parameter kinds or parent deformers: " +
                    "parameters=${lowered.parameters.filter { it !in model.parameters }.map { it.id }}, " +
                    "deformers=${lowered.deformers.take(model.deformers.size).filter { it !in model.deformers }.map { it.id }}"
            }
            val added = lowered.deformers.drop(model.deformers.size)
            val remap = ModelIdRemap(deformers = added.associate {
                it.id to DeformerId(ScaffoldBinding.deformerId(instance, target.id, it.id.raw, shared = true))
            })
            for (item in added) {
                val converted = PuppetIr.deformerToIr(remap.deformer(item))
                val existing = carriers.putIfAbsent(converted.id, converted)
                require(existing == null || existing == converted) { "Incompatible shared lid carrier: ${converted.id}" }
            }
            val result = PuppetIr.meshToIr(remap.drawable(lowered.drawables.first()))
            val next = mesh.copy(parent = result.parent, geometry = result.geometry,
                offsets = result.offsets, channels = result.channels, shapes = result.shapes)
            ModifierObjectUpdate(mesh, next).takeIf { mesh != next }
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = updates), deformers = ModifierObjectChanges(added = carriers.values.toList()))
    }

    companion object {
        const val TYPE = "mouth.cheek-lid"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.6d6f7574682e636865656b2d6c6964.v2/coupling"
        val WRITES = setOf("mesh", "deformer")
        private const val MOUTH_FORM = "ParamMouthForm"
        private val unsupported = setOf(TYPE)
        val optionalUpstream = setOf(TYPE, "face/fitted", "face.depth-turn", "eye.gaze-lid", "brow.lid-coupling", "eye.smile", "eye.wide")

        fun context(source: ModifierAlgorithmContext, carriers: Map<String, Pair<String, String>>) =
            ModifierAlgorithmContext(buildJsonObject {
                put("schema", "mouth-cheek-lid/1")
                put("source", source.values)
                put("carriers", JsonObject(carriers.mapValues { (_, carrier) -> buildJsonObject {
                    put("id", carrier.first)
                    put("name", carrier.second)
                } }))
            }, source.resources)

        private fun knob(config: JsonObject?, name: String, default: Float, range: ClosedFloatingPointRange<Float>) =
            (config?.get("values")?.jsonObject?.get(name)?.jsonPrimitive?.float ?: default).also { require(it in range) } / 100f
    }
}
