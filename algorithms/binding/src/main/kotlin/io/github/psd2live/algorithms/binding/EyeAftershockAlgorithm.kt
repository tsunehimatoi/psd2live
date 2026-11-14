package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithms.physics.PhysicsGroups
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsNormalization
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.PhysicsGroup
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode as PuppetParameterNode

/** Computes the blink spring's lid shapes and pendulums from the captured fitted-eye artwork. */
class EyeAftershockAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val captured = EyeSmileInputs.decode(sourceContext(input.context), checkpoint)
        val source = captured.measurements
        val scaffold = instance.config.getValue("scaffold")
        val targets = instance.targets.toSet()
        require(source.targets.keys.all { ModifierTarget("mesh", it) in targets })
        require(input.providers("rig.scaffold").any {
            it.instanceId == scaffold.jsonPrimitive.content && it.version == 2 && it.targets.containsAll(targets)
        }) { "Blink aftershock v2 requires a version 2 source scaffold" }
        val fitted = requireNotNull(input.providers("eye/fitted").singleOrNull {
            it.instanceId == instance.config.getValue("base").jsonPrimitive.content && it.targets.containsAll(targets)
        }) { "Missing fitted eye provider" }
        require(fitted.version in 1..4) { "Blink aftershock v2 requires fitted eyes v1..v4" }
        val preceding = input.upstream.filter { it.config["scaffold"] == scaffold && it.targets.any(targets::contains) }
        require(preceding.none { it.capability == TYPE }) {
            "Blink aftershock v2 cannot replace an earlier aftershock on the same mesh"
        }
        require(preceding.none { it.capability in coupled }) {
            "Blink aftershock v2 cannot compose with preceding coupled lid bindings"
        }

        val fits = fits(captured, checkpoint)
        if (fits.isEmpty() || captured.art.layers.none { it.tag == "EYELASH" }) return ModifierModelChanges()
        val values = fitted.config["values"]?.jsonObject.orEmpty()
        val softness = knob(instance.config["values"]?.jsonObject.orEmpty(), "softness", 100f, 0f..200f)
        val options = EyeOptions(aftershock = true, softness = softness)
        val artwork = EyeArtworkFitting.fit(captured.art, source.artworkStamps, fits, source.corrections) { x, y ->
            source.alignment?.point(x, y) ?: (x to y)
        }
        val motions = HashMap<EyeOptions, FittedEyeMotion>()
        fun motion(options: EyeOptions) = motions.getOrPut(options) {
            FittedEyeMotion(fits.mapValues { (side, fit) ->
                EyeRig(fit.copy(artwork = artwork[side]), knob(values, "lowerShare", 5f, 0f..50f),
                    knob(values, "roundness", 100f, 40f..160f), knob(values, "lashSquash", 60f, 20f..100f),
                    options.smileArc, options.smilePush, options.wideLift)
            }, options)
        }

        val declared = parameters(source, fits.keys)
        val live = input.parameters
        val additions = declared.filter { parameter ->
            val existing = input.parameter(parameter.id)
            require(existing == null || existing.copy(name = parameter.name) == parameter) {
                "Incompatible blink aftershock parameter: ${parameter.id}"
            }
            existing == null
        }
        val updates = instance.targets.mapNotNull { target ->
            checkpoint()
            val material = source.targets[target.id] ?: return@mapNotNull null
            val layer = captured.art.layerForTarget(target.id)
            val lid = EyeModules.meshOf(layer.tag, layer.role) ?: return@mapNotNull null
            val raw = captured.topology.getValue(material.layer)
            val frame = material.frame.let { Bounds(it.left, it.top, it.right, it.bottom) }
            val side = Side.valueOf(layer.side)
            val wide = preceding.lastOrNull { it.capability == "eye.wide" && target in it.targets }
            val smile = preceding.lastOrNull { it.capability == "eye.smile" && target in it.targets }
            val wideValues = wide?.config?.get("values")?.jsonObject.orEmpty()
            val smileValues = smile?.config?.get("values")?.jsonObject.orEmpty()
            val motion = motion(options.copy(
                wide = wide != null, wideLift = knob(wideValues, "lift", 10f, 0f..80f),
                smile = smile != null, smileArc = .12f * knob(smileValues, "arch", 100f, 0f..200f),
                smilePush = knob(smileValues, "push", 50f, 0f..100f),
            ))
            val shapes = if (side == Side.NONE) {
                val owner = List(raw.rigPositions.size / 2) { i ->
                    motion.sideAt(layer.id, raw.rigPositions[i * 2], raw.rigPositions[i * 2 + 1])
                }
                motion.rigs.flatMap { (eyeSide, eye) ->
                    EyeModules.lidShapes(lid, eyeSide, raw.rigPositions, frame, eye, options, layer.id).map { shape ->
                        shape.copy(forms = shape.forms.map { form -> form?.let {
                            MeshForm(FloatArray(it.positionDeltas.size) { i -> if (owner[i / 2] == eyeSide) it.positionDeltas[i] else 0f })
                        } })
                    }
                }
            } else motion.rigs[side]?.let { EyeModules.lidShapes(lid, side, raw.rigPositions, frame, it, options, layer.id) }.orEmpty()
            if (shapes.isEmpty()) return@mapNotNull null
            val mesh = requireNotNull(input.mesh(target.id)) { "Missing fitted lid: ${target.id}" }
            val geometry = requireNotNull(mesh.geometry)
            val loweredCarrier = mesh.parent != material.parent
            require(!loweredCarrier || (mesh.parent != null && input.deformer(mesh.parent!!)?.parent == material.parent)) {
                "Blink aftershock v2 cannot migrate a fitted lid carrier: ${target.id}"
            }
            if (!loweredCarrier) {
                val original = Floats.wrap(FloatArray(raw.rigPositions.size) { i ->
                    val value = raw.rigPositions[i]
                    if (material.parent == null) value
                    else if (i % 2 == 0) (value - frame.left) / frame.width.coerceAtLeast(1e-4f)
                    else (value - frame.top) / frame.height.coerceAtLeast(1e-4f)
                })
                require(geometry.positions.size == original.size &&
                    (0 until original.size).all { kotlin.math.abs(geometry.positions[it] - original[it]) <= 1e-4f } &&
                    geometry.uvs == Floats.wrap(raw.atlasUvs) &&
                    geometry.indices == Ints.wrap(raw.triangles)) { "Blink aftershock v2 requires the captured lid topology and positions: ${target.id}" }
                val grid = if (side == Side.NONE) EyeModules.pairedLidGrid(lid, layer.id, raw.rigPositions, frame, motion)
                else EyeModules.lidGrid(lid, side, raw.rigPositions, frame, motion.rigs.getValue(side), motion.options, layer.id)
                val expected = grid?.let {
                    KeyGrid(it.axes.map { axis -> KeyAxis(axis.parameterId.raw, Floats.wrap(axis.keys)) },
                        it.cells.map { cell -> KeyCell(Ints.wrap(cell.coordinate), MeshOffsets(Floats.wrap(cell.form.positionDeltas))) })
                }
                require(mesh.offsets == expected || (expected != null &&
                    expected.axes.all { axis -> mesh.offsets?.axes?.any { it.parameter == axis.parameter } == true })) {
                    "Blink aftershock v2 cannot migrate edited or lowered lid geometry: ${target.id}"
                }
            } else {
                require(geometry.positions.size == raw.rigPositions.size &&
                    geometry.uvs == Floats.wrap(raw.atlasUvs) &&
                    geometry.indices == Ints.wrap(raw.triangles)) { "Blink aftershock v2 requires the captured lid topology and positions: ${target.id}" }
            }
            val branch = input.parentsOfMesh(target.id)
            val model = PuppetIr.toPuppet(RigIR(input.canvas, live, deformers = branch, meshes = listOf(mesh)))
            val drawable = model.drawables.single()
            val mapping = requireNotNull(drawableSpaceMapping(model, emptyMap(), drawable.id))
            // The evaluator uses y-up world coordinates while the captured artwork uses y-down canvas pixels.
            val world = mapping.localToWorld(geometry.positions.shared())
            require((world.indices step 2).all { i ->
                val x = raw.rigPositions[i]
                val y = raw.rigPositions[i + 1]
                val canvas = source.alignment?.canvas(x, y) ?: (x to y)
                kotlin.math.abs(world[i] - canvas.first) <= .5f && kotlin.math.abs(-world[i + 1] - canvas.second) <= .5f
            }) { "Blink aftershock v2 cannot migrate an edited neutral lid frame: ${target.id}" }
            val scaledShapes = if (!loweredCarrier) shapes else {
                val corners = mapping.localToWorld(floatArrayOf(0f, 0f, 1f, 1f))
                val carrierW = kotlin.math.abs(corners[2] - corners[0]).coerceAtLeast(1e-4f)
                val carrierH = kotlin.math.abs(corners[3] - corners[1]).coerceAtLeast(1e-4f)
                val refW = if (material.parent == null) 1f else frame.width
                val refH = if (material.parent == null) 1f else frame.height
                val sx = refW / carrierW
                val sy = refH / carrierH
                shapes.map { binding ->
                    binding.copy(forms = binding.forms.map { form ->
                        form?.let {
                            MeshForm(FloatArray(it.positionDeltas.size) { k ->
                                it.positionDeltas[k] * (if (k % 2 == 0) sx else sy)
                            }, it.drawOrder, it.opacity, it.multiplyColor, it.screenColor)
                        }
                    })
                }
            }
            val generated = PuppetIr.meshToIr(drawable.copy(blendShapes = neutralized(scaledShapes, drawable, live))).shapes
            ModifierObjectUpdate(mesh, mesh.copy(shapes = mesh.shapes + generated))
        }

        val tree = input.parameterTree
        val treeModel = PuppetIr.toPuppet(RigIR(input.canvas, live, parameterTree = tree))
        val folder = PuppetParameterNode.Group(ParameterGroupId(instance.outputId("parameter-folder:ParamGroupPhysics")),
            input.context.values.getValue("folder").jsonPrimitive.content, true,
            declared.map { PuppetParameterNode.Param(ParameterId(it.id)) })
        val updatedTree = BindingMerge.appendTree(treeModel, listOf(folder), additions.mapTo(hashSetOf()) { ParameterId(it.id) },
            instance.outputAliases.keys.any { it.startsWith("parameter-folder:") })
        val convertedTree = PuppetIr.toIr(treeModel.copy(parameterTree = updatedTree)).parameterTree
        val physics = physicsChanges(input, instance, (live + additions).mapTo(hashSetOf()) { it.id })
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = additions),
            meshes = ModifierObjectChanges(updated = updates), physicsGroups = physics,
            parameterTree = convertedTree.takeIf { it != tree }?.let { ModifierFieldChange(tree, it) })
    }

    private fun neutralized(shapes: List<BlendShapeBinding<MeshForm>>, drawable: Drawable,
                            parameters: List<Parameter>): List<BlendShapeBinding<MeshForm>> {
        val rest = { id: ParameterId -> if (id.raw in openIds) 1f else 0f }
        val defaults = parameters.associate { ParameterId(it.id) to it.default }
        val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, { defaults[it] ?: 0f })
        val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, rest)
        val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, rest)
        val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, rest)
        return shapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
            form?.let { MeshForm(it.positionDeltas, order, opacity, multiply, screen) }
        }) }
    }

    private fun physicsChanges(input: ModifierEvaluationInput, instance: RigModifierInstance,
                               available: Set<String>): ModifierObjectChanges<PhysicsGroup> {
        if (instance.config["physicsEnabled"]?.jsonPrimitive?.boolean == false) return ModifierObjectChanges()
        val names = input.context.values.getValue("physicsNames").jsonArray.map { it.jsonPrimitive.content }
        val overrides = instance.config["physics"]?.jsonObject.orEmpty()
        val settings = settings(names).filter { rule -> rule.parameters.all { it in available } }
        require(overrides.keys.all { id -> settings.any { it.id == id } }) { "Unavailable blink aftershock physics slot" }
        if (settings.isEmpty()) return ModifierObjectChanges()
        val existing = input.physics.groups
        val additions = mutableListOf<PhysicsGroup>()
        val reused = mutableListOf<PhysicsGroup>()
        for (rule in settings) {
            val setting = overrides[rule.id]?.jsonObject?.let(RigPhysicsEdit::fromJson) ?: rule
            require(setting.inputs.isNotEmpty() && setting.outputs.isNotEmpty() && setting.parameters.all { it in available } &&
                setting.inputs.none { it.parameter in setting.outputParameters }) { "Invalid blink aftershock physics inputs or outputs" }
            val group = PhysicsGroups.fromSetting(setting.copy(id = instance.outputId("physics:${rule.id}")))
            val compatible = (existing + additions).firstOrNull { it.copy(id = group.id, name = group.name) == group }
            if (compatible != null) {
                if (compatible in existing && compatible !in reused) reused += compatible
                continue
            }
            val outputs = group.outputs.mapTo(hashSetOf()) { it.parameter }
            require((existing + additions).none { it.id == group.id || it.outputs.any { output -> output.parameter in outputs } }) {
                "Conflicting blink aftershock physics group: ${group.id}"
            }
            additions += group
        }
        return ModifierObjectChanges(added = additions, reused = reused)
    }

    companion object {
        const val TYPE = "eye.aftershock"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.6579652e616674657273686f636b.v2/aftershock"
        val WRITES = setOf("mesh", "parameter", "hierarchy", "physics")
        private val coupled = setOf("eye.gaze-lid", "eye.emotion", "brow.lid-coupling", "mouth.cheek-lid")
        val optionalUpstream = coupled + setOf(TYPE, "eye.wide", "eye.smile")
        private val openIds = setOf("ParamEyeLOpen", "ParamEyeROpen")

        fun context(source: ModifierAlgorithmContext, folder: String, physicsNames: List<String>) =
            ModifierAlgorithmContext(buildJsonObject {
                put("schema", "eye-aftershock/1")
                put("source", source.values)
                put("folder", folder)
                put("physicsNames", JsonArray(physicsNames.map(::JsonPrimitive)))
            }, source.resources)

        fun parameters(context: ModifierAlgorithmContext): List<Parameter> {
            val captured = EyeSmileInputs.decode(sourceContext(context)) {}
            if (captured.art.layers.none { it.tag == "EYELASH" }) return emptyList()
            val fits = fits(captured) {}
            return if (fits.isEmpty()) emptyList() else parameters(captured.measurements, fits.keys)
        }

        private fun sourceContext(context: ModifierAlgorithmContext): ModifierAlgorithmContext {
            require(context.values.keys == setOf("schema", "source", "folder", "physicsNames") &&
                context.values.getValue("schema").jsonPrimitive.content == "eye-aftershock/1") { "Unsupported blink aftershock input schema" }
            return ModifierAlgorithmContext(context.values.getValue("source").jsonObject, context.resources)
        }

        private fun fits(captured: EyeSmileInputs.Companion.Decoded, checkpoint: () -> Unit): Map<Side, EyeFit> {
            val source = captured.measurements
            return EyeFitting.fit(captured.art, source.faceCenterX) { x, y ->
                source.alignment?.point(x, y) ?: (x to y)
            }.fits.mapValues { (side, fit) ->
                checkpoint()
                fit.moved(source.moves[side.name].orEmpty())
            }
        }

        private fun parameters(source: EyeSmileInputs, fitted: Set<Side>) = listOf(Side.LEFT to "L", Side.RIGHT to "R").map { (side, code) ->
            val id = "ParamEye${code}Bounce"
            val blend = side in fitted
            Parameter(id, source.parameterNames.getValue(id), -1f, 1f, 0f, blend = blend,
                keys = if (blend) Floats.wrap(floatArrayOf(-1f, 0f, 1f)) else null)
        }

        private fun settings(names: List<String>) = listOf("L", "R").mapIndexed { index, side ->
            RigPhysicsEdit(id = "PhysicsEye${side}Bounce", name = names[index],
                inputs = listOf(PhysicsInput("ParamEye${side}Open", 100f, PhysicsSourceType.X)),
                outputs = listOf(PhysicsOutput("ParamEye${side}Bounce", 2, 1.4f)),
                segments = listOf(PhysicsSegment(1f, 0.9f, 0.6f, 1.6f), PhysicsSegment(1.2f, 0.85f, 0.45f, 1.8f)),
                normalization = PhysicsNormalization(-1f, 0f, 1f, -10f, 0f, 10f))
        }

        private fun knob(values: Map<String, JsonElement>, id: String, default: Float, range: ClosedFloatingPointRange<Float>) =
            (values[id]?.jsonPrimitive?.float ?: default).also { require(it in range) { "Invalid eye value: $id" } } / 100f
    }
}
