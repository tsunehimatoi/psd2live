package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode as PuppetParameterNode

/** Computes shared and per-eye gaze from captured lid artwork and the fitted iris carriers. */
class EyeGazeLidV3Algorithm : ReadingModifierAlgorithmPlugin {
    private data class Lid(val mesh: Mesh, val drawable: Drawable, val ancestors: List<Deformer>,
                           val existingTurn: Pair<DeformerId, Deformer.Warp>?)

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val encodedSource = sourceContext(input.context)
        val head = encodedSource.values["head_turn"]?.jsonObject
        val captured = EyeSmileInputs.decode(ModifierAlgorithmContext(
            JsonObject(encodedSource.values - "head_turn"), encodedSource.resources), checkpoint)
        val source = captured.measurements
        val scaffold = instance.config.getValue("scaffold")
        val targets = instance.targets.toSet()
        require(source.targets.keys.all { ModifierTarget("mesh", it) in targets })
        require(input.providers("rig.scaffold").any {
            it.instanceId == scaffold.jsonPrimitive.content && it.version == 2 && it.targets.containsAll(targets)
        }) { "Lid gaze v3 requires a version 2 source scaffold" }
        val fitted = requireNotNull(input.providers("eye/fitted").singleOrNull {
            it.instanceId == instance.config.getValue("base").jsonPrimitive.content && it.targets.containsAll(targets)
        }) { "Missing fitted eye provider" }
        require(fitted.version in setOf(2, 4, 5)) { "Lid gaze v3 requires fitted eyes v2, v4 or v5" }
        val preceding = input.upstream.filter { it.config["scaffold"] == scaffold && it.targets.any(targets::contains) }
        require(preceding.none { it.capability == TYPE }) {
            "Lid gaze v3 cannot replace an earlier gaze binding on the same mesh"
        }
        require(preceding.none { it.capability in coupled }) {
            "Lid gaze v3 cannot compose with preceding coupled lid bindings"
        }

        val turn = head?.let { values ->
            require(preceding.any { it.capability in headProviders && it.instanceId == values.getValue("provider").jsonPrimitive.content }) {
                "Lid gaze v3 requires the captured head turn provider"
            }
            val points = values.getValue("shell").jsonArray.map { it.jsonPrimitive.float }
            require(points.size == 7 && points.all(Float::isFinite) && points[2] > 0f && points[3] > 0f)
            HeadTurn(points[0], points[1], points[2], points[3], points[4], points[5], points[6], checkpoint)
        }
        fun carrierName(isHead: Boolean, meshName: String): String =
            (if (isHead) requireNotNull(head).getValue("turn_name") else input.context.values.getValue("shapeName"))
                .jsonPrimitive.content.replace("{meshName}", meshName)

        val fits = fits(captured, checkpoint)
        require(fits.isNotEmpty() && captured.art.layers.any { it.tag == "EYELASH" }) {
            "Lid gaze v3 needs readable fitted eye artwork"
        }
        val values = fitted.config["values"]?.jsonObject.orEmpty()
        val follow = knob(instance.config["values"]?.jsonObject.orEmpty(), "follow", 100f, 0f..200f)
        val options = EyeOptions(gaze = true, gazeFollow = follow)
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

        val declared = parameters(source.parameterNames)
        val declaredIds = declared.mapTo(hashSetOf()) { it.id }
        val live = input.parameters
        val lids = instance.targets.mapNotNull { target ->
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
            val emotion = preceding.lastOrNull { it.capability == "eye.emotion" && target in it.targets }
            val emotionValues = emotion?.config?.get("values")?.jsonObject.orEmpty()
            val lidOptions = options.copy(
                emotion = emotion != null, emotionStrength = knob(emotionValues, "strength", 100f, 0f..200f),
                wide = wide != null, wideLift = knob(wideValues, "lift", 10f, 0f..80f),
                smile = smile != null, smileArc = .12f * knob(smileValues, "arch", 100f, 0f..200f),
                smilePush = knob(smileValues, "push", 50f, 0f..100f),
            )
            val motion = motion(lidOptions)
            fun shapes(options: EyeOptions): List<BlendShapeBinding<MeshForm>> = if (side == Side.NONE) {
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
            val gaze = shapes(lidOptions)
            if (gaze.isEmpty()) return@mapNotNull null
            val mesh = requireNotNull(input.mesh(target.id)) { "Missing fitted lid: ${target.id}" }
            val original = Floats.wrap(FloatArray(raw.rigPositions.size) { i ->
                val value = raw.rigPositions[i]
                if (material.parent == null) value
                else if (i % 2 == 0) (value - frame.left) / frame.width.coerceAtLeast(1e-4f)
                else (value - frame.top) / frame.height.coerceAtLeast(1e-4f)
            })
            val grid = if (side == Side.NONE) EyeModules.pairedLidGrid(lid, layer.id, raw.rigPositions, frame, motion)
            else EyeModules.lidGrid(lid, side, raw.rigPositions, frame, motion.rigs.getValue(side), motion.options, layer.id)
            val expected = grid?.let {
                KeyGrid(it.axes.map { axis -> KeyAxis(axis.parameterId.raw, Floats.wrap(axis.keys)) },
                    it.cells.map { cell -> KeyCell(Ints.wrap(cell.coordinate), MeshOffsets(Floats.wrap(cell.form.positionDeltas))) })
            }
            val branch = input.parentsOfMesh(target.id)
            val baseBranch = branch.dropWhile { it.id != material.parent }
            val carrier = if (mesh.parent == material.parent) null else branch.firstOrNull() as? Deformer.Warp
            require((material.parent == null || baseBranch.isNotEmpty()) &&
                (mesh.parent == material.parent || carrier != null && carrier.parent == material.parent &&
                    carrier.lattice?.axes.orEmpty().any { it.parameter in headAxes })) {
                "Lid gaze v3 requires the source frame or its head turn warp: ${target.id}"
            }
            val folded = carrier != null || mesh.offsets?.axes.orEmpty().any { it.parameter in headAxes } ||
                mesh.shapes.any { it.parameter in headAxes }
            require(!folded || turn != null && emotion == null) {
                "Lid gaze v3 needs captured head turn measurements and no preceding emotion for lowered lids: ${target.id}"
            }
            val nativeId = if (folded) requireNotNull(head).getValue("native_ids").jsonObject.getValue(target.id).jsonPrimitive.content else target.id
            val canonicalGeometry = MeshGeometry(original, Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles))
            val canonical = mesh.copy(id = nativeId, parent = material.parent, geometry = canonicalGeometry,
                offsets = expected, shapes = emptyList())
            val model = PuppetIr.toPuppet(RigIR(input.canvas, live, deformers = baseBranch, meshes = listOf(canonical)))
            val drawable = model.drawables.single()
            val mapping = requireNotNull(drawableSpaceMapping(model, emptyMap(), drawable.id))
            val world = mapping.localToWorld(original.shared())
            require((world.indices step 2).all { i ->
                val x = raw.rigPositions[i]
                val y = raw.rigPositions[i + 1]
                val canvas = source.alignment?.canvas(x, y) ?: (x to y)
                kotlin.math.abs(world[i] - canvas.first) <= .005f && kotlin.math.abs(-world[i + 1] - canvas.second) <= .005f
            }) { "Lid gaze v3 cannot migrate an edited neutral lid frame: ${target.id}" }
            val previousShapes = neutralized(shapes(lidOptions.copy(gaze = false)), drawable, live)
            val headShapes = if (!folded) emptyList() else {
                val shell = requireNotNull(DepthTurnMotion.shell(layer.tag, layer.role))
                val rest = meshGridDefaultDeltas(drawable) { id -> if (id.raw in openIds) 1f else 0f }
                val turns = requireNotNull(turn).shapes(raw.rigPositions, frame, shell, ParameterId("ParamAngleX"), ParameterId("ParamAngleY"))
                neutralized(turns, drawable, live).map { binding ->
                    binding.copy(forms = binding.forms.map { form -> form?.let {
                        if (rest == null) it else MeshForm(FloatArray(rest.size) { i -> it.positionDeltas[i] + rest[i] },
                            it.drawOrder, it.opacity, it.multiplyColor, it.screenColor)
                    } })
                }
            }
            val prior = drawable.copy(blendShapes = previousShapes + headShapes)
            val old = if (!folded) model.copy(drawables = listOf(prior)) else
                BlendShapeLowering(::carrierName, checkpoint).lawful(model.copy(drawables = listOf(prior)))
            val oldWarp = old.deformers.drop(model.deformers.size).singleOrNull()?.let { PuppetIr.deformerToIr(it) as Deformer.Warp }
            require(PuppetIr.meshToIr(old.drawables.single()).copy(id = mesh.id, parent = mesh.parent) == mesh &&
                if (oldWarp == null) carrier == null else carrier != null && oldWarp.copy(id = carrier.id, part = carrier.part) == carrier) {
                "Lid gaze v3 cannot reproduce the preceding fitted lid: ${target.id}"
            }
            val existingTurn = carrier?.let { DeformerId(requireNotNull(oldWarp).id) to it }
            Lid(mesh, drawable.copy(blendShapes = neutralized(gaze, drawable, live) + headShapes), baseBranch, existingTurn)
        }

        val links = input.parameterLinks
        val desiredLinks = links.toMutableList()
        for (side in listOf("L", "R")) {
            val link = ParameterLink("ParamEyeBall${side}X", "ParamEyeBall${side}Y")
            val pair = setOf(link.horizontal, link.vertical)
            require(links.none { it != link && (it.horizontal in pair || it.vertical in pair) }) {
                "Incompatible gaze parameter link for ${link.horizontal} and ${link.vertical}"
            }
            if (link !in links) desiredLinks += link
        }
        val carriers = input.context.values.getValue("carriers").jsonObject
        val carrierUpdates = instance.targets.flatMap { input.parentsOfMesh(it.id) }
            .distinctBy { it.id }.mapNotNull { carrier ->
                val side = carriers[carrier.id]?.jsonPrimitive?.content?.takeIf { it != "Both" } ?: return@mapNotNull null
                require(carrier is Deformer.Warp)
                independentGaze(carrier, side, fitted.config["tuning"]?.jsonObject.orEmpty())
            }
        // Shared gaze is an ordinary grid axis on the upstream iris warp, so the same
        // Cubism lowering used by the legacy binder must fold the lid's shared gaze.
        val carrierRefs = carriers.keys.mapNotNull(input::deformer)
        val byId = carrierUpdates.associateBy { it.before.id }
        val contextDeformers = (carrierRefs + lids.flatMap { it.ancestors }).distinctBy { it.id }
        val model = PuppetIr.toPuppet(RigIR(input.canvas, live.filterNot { it.id in declaredIds } + declared,
            deformers = contextDeformers.map { byId[it.id]?.after ?: it }))
        val lowered = BlendShapeLowering(::carrierName, checkpoint)
            .lawful(model.copy(drawables = lids.map { it.drawable }))
        val newCarriers = lowered.deformers.drop(contextDeformers.size)
        val retained = lids.mapNotNull { it.existingTurn }.toMap()
        require(retained.keys.all { id -> newCarriers.any { it.id == id } }) { "Lid gaze v3 cannot retire a preceding head turn warp" }
        val remap = ModelIdRemap(deformers = newCarriers.associate { carrier ->
            carrier.id to DeformerId(retained[carrier.id]?.id ?: ScaffoldBinding.deformerId(instance, "", carrier.id.raw, shared = true))
        })
        val addedWarps = newCarriers.filter { it.id !in retained }.map {
            PuppetIr.deformerToIr(remap.deformer(it)).also { warp -> require(input.deformer(warp.id) == null) }
        }
        val refittedWarps = newCarriers.mapNotNull { warp ->
            val before = retained[warp.id] ?: return@mapNotNull null
            val after = (PuppetIr.deformerToIr(remap.deformer(warp)) as Deformer.Warp).copy(part = before.part)
            if (after == before) return@mapNotNull null
            val owner = lids.single { it.existingTurn?.second?.id == before.id }
            require(input.meshes.none { it.id != owner.mesh.id && it.parent == before.id } &&
                input.deformers.none { it.parent == before.id }) { "Lid gaze v3 cannot refit a shared head turn warp" }
            ModifierObjectUpdate<Deformer>(before, after)
        }
        val updates = lids.zip(lowered.drawables).map { (lid, drawable) ->
            val before = lid.mesh
            val after = PuppetIr.meshToIr(remap.drawable(drawable))
            ModifierObjectUpdate(before, before.copy(parent = after.parent, geometry = after.geometry,
                offsets = after.offsets, channels = after.channels, shapes = after.shapes))
        }
        val additions = PuppetIr.parametersToIr(lowered).filter { parameter ->
            if (parameter.id !in declaredIds) return@filter false
            val existing = input.parameter(parameter.id)
            require(existing == null || existing.copy(name = parameter.name) == parameter) {
                "Incompatible lid gaze parameter: ${parameter.id}"
            }
            existing == null
        }
        val tree = input.parameterTree
        val treeModel = PuppetIr.toPuppet(RigIR(input.canvas, live, parameterTree = tree))
        val folder = PuppetParameterNode.Group(ParameterGroupId(instance.outputId("parameter-folder:ParamGroupEyes")),
            source.folderName, true,
            (eyeParameterIds + smileIds + declared.map { it.id } + emotionIds).map { PuppetParameterNode.Param(ParameterId(it)) })
        val updatedTree = BindingMerge.appendTree(treeModel, listOf(folder), additions.mapTo(hashSetOf()) { ParameterId(it.id) },
            instance.outputAliases.keys.any { it.startsWith("parameter-folder:") })
        val convertedTree = PuppetIr.toIr(treeModel.copy(parameterTree = updatedTree)).parameterTree
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = additions),
            meshes = ModifierObjectChanges(updated = updates), deformers = ModifierObjectChanges(
                added = addedWarps, updated = carrierUpdates + refittedWarps),
            parameterTree = convertedTree.takeIf { it != tree }?.let { ModifierFieldChange(tree, it) },
            parameterLinks = desiredLinks.takeIf { it != links }?.let { ModifierFieldChange(links, it) })
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

    private fun independentGaze(warp: Deformer.Warp, side: String, tuning: Map<String, JsonElement>): ModifierObjectUpdate<Deformer> {
        val x = tuning["gazeX"]?.jsonPrimitive?.float ?: 10f
        val y = tuning["gazeY"]?.jsonPrimitive?.float ?: 8.5f
        require(x in 0f..30f && y in 0f..30f)
        fun points(gazeX: Float, gazeY: Float) = Floats.wrap(FloatArray(18) { index ->
            val vertex = index / 2
            if (index % 2 == 0) vertex % 3 / 2f + gazeX * x / 100f
            else vertex / 3 / 2f - gazeY * y / 100f
        })
        val lattice = warp.lattice
        val keys = floatArrayOf(-1f, 0f, 1f)
        require(side == "L" || side == "R")
        require(warp.rows == 2 && warp.columns == 2 && warp.bilinear && warp.shapes.isEmpty() &&
            lattice != null && lattice.axes == listOf(KeyAxis("ParamEyeBallX", Floats.wrap(keys)), KeyAxis("ParamEyeBallY", Floats.wrap(keys))) &&
            lattice.cells.size == 9 && lattice.cells.all { cell ->
                val coordinate = cell.coordinate.shared()
                coordinate.size == 2 && coordinate.all { it in keys.indices } &&
                    cell.form.points == points(keys[coordinate[0]], keys[coordinate[1]])
            }) { "Lid gaze v3 requires the fitted iris gaze lattice: ${warp.id}" }
        val shapes = listOf("X", "Y").map { axis ->
            BlendBinding("ParamEyeBall$side$axis", Floats.wrap(keys), 1, keys.map { value ->
                if (value == 0f) null else LatticeShape(points(if (axis == "X") value else 0f, if (axis == "Y") value else 0f),
                    1f, Rgb.White, Rgb.Black)
            })
        }
        return ModifierObjectUpdate(warp, warp.copy(shapes = shapes))
    }

    companion object {
        const val TYPE = "eye.gaze-lid"
        const val VERSION = 3
        const val ENTRYPOINT = "builtin.6579652e67617a652d6c6964.v3/gaze"
        val WRITES = setOf("mesh", "deformer", "parameter", "hierarchy", "relations")
        private val coupled = setOf("eye.aftershock", "brow.lid-coupling", "mouth.cheek-lid")
        private val headProviders = setOf("face/fitted", "face.depth-turn")
        private val headAxes = setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ")
        val optionalUpstream = coupled + headProviders + setOf(TYPE, "eye.wide", "eye.smile", "eye.emotion")
        private val openIds = setOf("ParamEyeLOpen", "ParamEyeROpen")
        private val smileIds = listOf("ParamEyeLSmile", "ParamEyeRSmile")
        private val emotionIds = listOf("ParamEyeLForm", "ParamEyeRForm", "ParamEyeLSquint", "ParamEyeRSquint")
        private val eyeParameterIds = openIds.toList() + listOf("ParamEyeBallX", "ParamEyeBallY", "ParamEyeBallForm")

        fun parameters(names: Map<String, String>) = listOf("LX", "LY", "RX", "RY").map {
            val id = "ParamEyeBall$it"
            Parameter(id, names.getValue(id), -1f, 1f, 0f)
        }

        private fun sourceContext(context: ModifierAlgorithmContext): ModifierAlgorithmContext {
            require(context.values.keys == setOf("schema", "source", "carriers", "shapeName") &&
                context.values.getValue("schema").jsonPrimitive.content == "eye-gaze-lid/1") { "Unsupported lid gaze input schema" }
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

        private fun knob(values: Map<String, JsonElement>, id: String, default: Float, range: ClosedFloatingPointRange<Float>) =
            (values[id]?.jsonPrimitive?.float ?: default).also { require(it in range) { "Invalid eye value: $id" } } / 100f
    }
}
