package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId

/** Couples fitted lids to the brow sides supported by the live brow meshes. */
class BrowLidV5Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val targets = instance.targets.toSet()
        val scaffold = instance.config.getValue("scaffold")
        fun provider(type: String, id: JsonElement) = requireNotNull(input.providers(type).singleOrNull {
            it.instanceId == id.jsonPrimitive.content && it.targets.containsAll(targets) &&
                (type == "rig.scaffold" || it.config["scaffold"] == scaffold)
        }) { "Missing brow and lid provider: $type" }
        require(provider("rig.scaffold", scaffold).version == 2) { "Brow and lid v5 requires a shared v2 scaffold" }
        val brow = provider("brow/fitted", instance.config.getValue("base"))
        require(brow.type == "brow/fitted" && brow.version in 1..3) { "Brow and lid v5 requires fitted brows v1 through v3" }
        val eye = provider("eye/fitted", instance.config.getValue("dependencies").jsonObject.getValue("eye/fitted"))
        require(eye.type == "eye/fitted" && eye.version in setOf(2, 5)) { "Brow and lid v5 requires fitted eyes v2 or v5" }
        val preceding = input.upstream.filter { it.config["scaffold"] == scaffold && it.targets.any(targets::contains) }
        require(preceding.none { it.capability in unsupported }) {
            "Brow and lid v5 does not support repeated coupling or a separate depth turn. Use v1 for those stacks."
        }

        val context = input.context
        val names = context.values.getValue("carrier_names").jsonObject
        val nativeIds = context.values.getValue("native_ids").jsonObject
        val browIds = context.values.getValue("brow_meshes").jsonArray.map { it.jsonPrimitive.content }
        require(browIds.distinct().size == browIds.size && browIds.all { ModifierTarget("mesh", it) in brow.targets })
        val browCover = context.values.getValue("brow_cover").jsonPrimitive.float.also { require(it in 0f..1f) }
        val captured = EyeWideInputs.decode(ModifierAlgorithmContext(
            JsonObject(context.values - setOf("carrier_names", "native_ids", "brow_cover", "brow_meshes")), context.resources), checkpoint)
        val materials = captured.measurements
        require(names.keys == targets.mapTo(hashSetOf()) { it.id } && names.keys == nativeIds.keys &&
            materials.targets.keys.all { it in names })
        val align = { x: Float, y: Float -> materials.alignment?.point(x, y) ?: (x to y) }
        val fits = EyeFitting.fit(captured.art, materials.faceCenterX, align).fits.mapValues { (side, fit) ->
            checkpoint()
            fit.moved(materials.moves[side.name].orEmpty())
        }
        require(fits.isNotEmpty() && captured.art.layers.any { it.tag == "EYELASH" }) {
            "Brow and lid v5 requires readable fitted lid artwork"
        }
        val artwork = EyeArtworkFitting.fit(captured.art, materials.artworkStamps, fits, materials.corrections, align)
        val lower = knob(eye.config, "lowerShare", 5f, 0f..50f)
        val roundness = knob(eye.config, "roundness", 100f, 40f..160f)
        val squash = knob(eye.config, "lashSquash", 60f, 20f..100f)
        val strength = knob(instance.config, "strength", 100f, 0f..200f) * (1f + .6f * browCover)
        val motions = HashMap<EyeOptions, FittedEyeMotion>()
        val parameters = input.parameters
        val defaults = parameters.associate { ParameterId(it.id) to it.default }
        val atDefault = { id: ParameterId -> defaults[id] ?: 0f }
        val carriers = LinkedHashMap<String, Deformer>()
        val updatedCarriers = LinkedHashMap<String, ModifierObjectUpdate<Deformer>>()
        // These upstream grids keep gaze and brow parameters ordinary during local lowering.
        val gazeAxes = setOf("ParamEyeBallX", "ParamEyeBallY")
        val browAxes = setOf("ParamBrowLY", "ParamBrowRY", "ParamBrowLAngle", "ParamBrowRAngle")
        val ordinaryAxes = gazeAxes + browAxes
        val browTargets = browIds.toSet()
        val ordinaryMeshes by lazy {
            targets.mapNotNull { target ->
                val mesh = input.mesh(target.id) ?: return@mapNotNull null
                mesh.copy(shapes = emptyList()).takeIf {
                    mesh.offsets?.axes.orEmpty().any { axis -> axis.parameter in gazeAxes || target.id in browTargets && axis.parameter in browAxes }
                }
            }
        }
        val coupledSides by lazy {
            listOf(Side.LEFT to "ParamBrowLY", Side.RIGHT to "ParamBrowRY").mapNotNullTo(hashSetOf()) { (side, id) ->
                side.takeIf { ordinaryMeshes.any { mesh ->
                    mesh.id in browTargets && mesh.offsets?.axes.orEmpty().any { it.parameter == id }
                } }
            }
        }
        val ordinaryWarps by lazy {
            targets.flatMap { input.parentsOfMesh(it.id) }.distinctBy { it.id }
                .filterIsInstance<Deformer.Warp>().filter { warp ->
                    warp.lattice?.axes.orEmpty().any { it.parameter in ordinaryAxes }
                }.map { it.copy(shapes = emptyList()) }
        }
        val updates = instance.targets.mapNotNull { target ->
            checkpoint()
            val material = materials.targets[target.id] ?: return@mapNotNull null
            val layer = captured.art.layerForTarget(target.id)
            val lid = EyeModules.meshOf(layer.tag, layer.role)
            val iris = layer.tag == "IRIDES" && layer.role != "HIGHLIGHT"
            if (lid == null && !iris) return@mapNotNull null
            val side = Side.valueOf(layer.side)
            if (side != Side.NONE && side !in fits) return@mapNotNull null
            val raw = captured.topology.getValue(material.layer)
            val positions = raw.rigPositions
            val frame = material.frame.run { Bounds(left, top, right, bottom) }
            fun previous(type: String) = preceding.lastOrNull { it.capability == type && target in it.targets }
            val wide = previous("eye.wide")
            val smile = previous("eye.smile")
            val gaze = previous("eye.gaze-lid")
            val emotion = previous("eye.emotion")
            val aftershock = previous("eye.aftershock")
            val options = EyeOptions(browCoupling = true, browStrength = strength,
                gaze = gaze != null, gazeFollow = knob(gaze?.config, "follow", 100f, 0f..200f),
                emotion = emotion != null, emotionStrength = knob(emotion?.config, "strength", 100f, 0f..200f),
                aftershock = aftershock != null, softness = knob(aftershock?.config, "softness", 100f, 0f..200f),
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
            fun shapes(options: EyeOptions): List<BlendShapeBinding<MeshForm>> {
                if (lid == null) {
                    if (side !in coupledSides) return emptyList()
                    val rig = motion.rigs[side] ?: return emptyList()
                    val bounds = layer.bounds.run { Bounds(left, top, right, bottom) }
                    return EyeModules.pupilShapes(side, positions, frame, rig.fit.iris ?: bounds, options)
                }
                return motion.rigs.filterKeys { side == Side.NONE || side == it }.flatMap { (owner, rig) ->
                    val settings = if (owner in coupledSides) options else options.copy(browCoupling = false)
                    val local = EyeModules.lidShapes(lid, owner, positions, frame, rig, settings, layer.id)
                    if (side != Side.NONE) local else local.map { shape -> shape.copy(forms = shape.forms.map { form ->
                        form?.let { MeshForm(FloatArray(positions.size) { i -> if (vertexOwners[i / 2] == owner) it.positionDeltas[i] else 0f }) }
                    }) }
                }
            }
            val coupledShapes = shapes(options)
            if (coupledShapes.none { it.parameterId.raw in browAxes }) return@mapNotNull null
            require(coupledShapes.map { it.parameterId.raw }.filter { it in browAxes }.all { id ->
                val parameter = input.parameter(id)
                if (parameter == null) id.endsWith("Angle") && aftershock == null
                else !parameter.blend && parameter.default == 0f && parameter.min == -1f && parameter.max == 1f &&
                    ordinaryMeshes.any { mesh -> mesh.id in browTargets && mesh.offsets?.axes.orEmpty().any { it.parameter == id } }
            }) { "Brow and lid v5 requires ordinary fitted brow axes" }
            val mesh = requireNotNull(input.mesh(target.id))
            val points = Floats.wrap(FloatArray(positions.size) { i ->
                val point = positions[i]
                if (material.parent == null) point
                else if (i % 2 == 0) (point - frame.left) / frame.width.coerceAtLeast(1e-4f)
                else (point - frame.top) / frame.height.coerceAtLeast(1e-4f)
            })
            val grid = if (lid == null) null else if (side == Side.NONE) EyeModules.pairedLidGrid(lid, layer.id, positions, frame, motion)
            else EyeModules.lidGrid(lid, side, positions, frame, motion.rigs.getValue(side), options, layer.id)
            val expected = grid?.let {
                KeyGrid(it.axes.map { axis -> KeyAxis(axis.parameterId.raw, Floats.wrap(axis.keys)) },
                    it.cells.map { cell -> KeyCell(Ints.wrap(cell.coordinate), MeshOffsets(Floats.wrap(cell.form.positionDeltas))) })
            }
            val nativeId = nativeIds.getValue(target.id).jsonPrimitive.content
            val name = names.getValue(target.id).jsonPrimitive.content
            val branch = input.parentsOfMesh(target.id)
            val baseBranch = if (material.parent == null) emptyList() else branch.dropWhile { it.id != material.parent }
            require(material.parent == null || baseBranch.isNotEmpty()) { "Missing captured fitted lid frame: ${target.id}" }
            val base = mesh.copy(id = nativeId, parent = material.parent,
                geometry = MeshGeometry(points, Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles)),
                offsets = if (iris) mesh.offsets else expected, shapes = emptyList())
            val model = PuppetIr.toPuppet(RigIR(input.canvas, parameters,
                meshes = listOf(base) + ordinaryMeshes.filter { it.id != mesh.id },
                deformers = baseBranch + ordinaryWarps.filter { warp -> branch.none { it.id == warp.id } }))
            val drawable = model.drawables.first()
            val mapping = requireNotNull(drawableSpaceMapping(model, emptyMap(), drawable.id))
            val world = mapping.localToWorld(points.shared())
            require((world.indices step 2).all { i ->
                val x = positions[i]
                val y = positions[i + 1]
                val source = materials.alignment?.canvas(x, y) ?: (x to y)
                // World space is y-up, while captured source positions are y-down.
                kotlin.math.abs(world[i] - source.first) <= .005f && kotlin.math.abs(-world[i + 1] - source.second) <= .005f
            }) { "Brow and lid v5 cannot migrate an edited neutral lid frame: ${target.id}" }
            val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, atDefault)
            val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, atDefault)
            val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, atDefault)
            val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, atDefault)
            fun lowered(shapes: List<BlendShapeBinding<MeshForm>>) = BlendShapeLowering({ _, _ -> name }, checkpoint).lawful(model.copy(
                drawables = listOf(drawable.copy(blendShapes = shapes.map { shape -> shape.copy(forms = shape.forms.map { form ->
                    form?.let { MeshForm(it.positionDeltas, order, opacity, multiply, screen) }
                }) })) + model.drawables.drop(1)))
            val before = lowered(shapes(options.copy(browCoupling = false)))
            val after = lowered(coupledShapes)
            require(after.parameters == model.parameters && before.parameters == model.parameters &&
                after.deformers.take(model.deformers.size) == model.deformers &&
                before.deformers.take(model.deformers.size) == model.deformers) {
                "Brow and lid v5 cannot change existing parameter kinds or parent deformers"
            }
            // Lowering can regroup axes while keeping the original carrier slot alive.
            // Match the source slots to their upstream owners before replacing any live geometry.
            val old = before.deformers.drop(model.deformers.size)
            val nextCarriers = after.deformers.drop(model.deformers.size)
            val previousIds = old.associate { item ->
                val id = preceding.asSequence().filter { target in it.targets && it.instanceId != null }.map { provider ->
                    val owner = RigModifierInstance(requireNotNull(provider.instanceId), provider.type, provider.version,
                        provider.targets, config = provider.config, outputAliases = provider.outputAliases, plugin = provider.plugin)
                    ScaffoldBinding.deformerId(owner, target.id, item.id.raw, shared = true)
                }.distinct().singleOrNull { id -> branch.any { it.id == id } }
                item.id to DeformerId(requireNotNull(id) { "Cannot resolve prior lid carrier ${item.id.raw}" })
            }
            val previousRemap = ModelIdRemap(deformers = previousIds)
            val prior = PuppetIr.meshToIr(previousRemap.drawable(before.drawables.first()))
            require(prior.parent == mesh.parent && prior.geometry == mesh.geometry && prior.offsets == mesh.offsets &&
                prior.shapes == mesh.shapes && prior.channels == mesh.channels) {
                "Cannot reproduce preceding fitted lid geometry or channels: ${target.id}"
            }
            val remap = ModelIdRemap(deformers = nextCarriers.associate {
                it.id to (previousIds[it.id] ?: DeformerId(ScaffoldBinding.deformerId(instance, target.id, it.id.raw, shared = true)))
            })
            require(old.all { item -> nextCarriers.any { it.id == item.id } }) { "Cannot retire a preceding fitted lid carrier" }
            for (item in old) {
                val expectedPrior = PuppetIr.deformerToIr(previousRemap.deformer(item))
                val actualPrior = branch.single { it.id == expectedPrior.id }
                require(actualPrior is Deformer.Warp && expectedPrior is Deformer.Warp &&
                    actualPrior == expectedPrior.copy(part = actualPrior.part)) {
                    "Cannot reproduce preceding fitted lid carrier: ${actualPrior.id}"
                }
            }
            for (item in nextCarriers) {
                val converted = PuppetIr.deformerToIr(remap.deformer(item))
                val original = branch.singleOrNull { it.id == converted.id }
                if (original == null) {
                    val existing = carriers.putIfAbsent(converted.id, converted)
                    require(existing == null || existing == converted) { "Incompatible shared lid carrier: ${converted.id}" }
                } else {
                    require(original is Deformer.Warp && converted is Deformer.Warp)
                    val replacement = converted.copy(part = original.part)
                    if (replacement != original) {
                        val update = ModifierObjectUpdate<Deformer>(original, replacement)
                        val existing = updatedCarriers.putIfAbsent(original.id, update)
                        require(existing == null || existing == update) { "Incompatible shared lid carrier: ${original.id}" }
                    }
                }
            }
            val result = PuppetIr.meshToIr(remap.drawable(after.drawables.first()))
            val next = mesh.copy(parent = result.parent, geometry = result.geometry,
                offsets = result.offsets, channels = result.channels, shapes = result.shapes)
            ModifierObjectUpdate(mesh, next).takeIf { mesh != next }
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = updates),
            deformers = ModifierObjectChanges(added = carriers.values.toList(), updated = updatedCarriers.values.toList()))
    }

    companion object {
        const val TYPE = "brow.lid-coupling"
        const val VERSION = 5
        const val ENTRYPOINT = "builtin.62726f772e6c69642d636f75706c696e67.v5/coupling"
        val WRITES = setOf("mesh", "deformer")
        private val unsupported = setOf(TYPE, ENTRYPOINT, "face.depth-turn", "mouth.cheek-lid")
        val optionalUpstream = unsupported + setOf("eye.smile", "eye.wide", "eye.gaze-lid", "eye.emotion", "eye.aftershock")

        private fun knob(config: JsonObject?, name: String, default: Float, range: ClosedFloatingPointRange<Float>) =
            (config?.get("values")?.jsonObject?.get(name)?.jsonPrimitive?.float ?: default).also { require(it in range) } / 100f
    }
}
