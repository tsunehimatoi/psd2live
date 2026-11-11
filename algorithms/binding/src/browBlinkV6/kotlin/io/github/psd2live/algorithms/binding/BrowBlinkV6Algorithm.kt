package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm

object BrowBlinkV6Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (BrowBlinkV6Algorithm.ENTRYPOINT to BrowBlinkV6Algorithm.VERSION) to BrowBlinkV6Algorithm(),
    ))
}

/** Computes blink motion from the preceding shape's source measurements; never replays a host blink template. */
class BrowBlinkV6Algorithm : ReadingModifierAlgorithmPlugin {

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        require(instance.targets.isNotEmpty() && instance.targets.distinct().size == instance.targets.size)
        val scaffoldId = instance.config.getValue("scaffold")
        val context = input.context
        fun provider(type: String, version: Int, id: JsonElement): ModifierUpstreamCapability {
            val actual = requireNotNull(input.providers(type).singleOrNull()) {
                "Brow blink v6 requires one unambiguous $type provider"
            }
            require(actual.instanceId == id.jsonPrimitive.content && actual.type == type && actual.version == version &&
                actual.targets.containsAll(instance.targets) && (type == "rig.scaffold" || actual.config["scaffold"] == scaffoldId)) {
                "Brow blink v6 requires its captured $type@$version provider"
            }
            return actual
        }
        provider("rig.scaffold", 2, scaffoldId)
        val fitted = provider("brow/fitted", 4, instance.config.getValue("base"))
        provider("face/fitted", 3, context.values.getValue("face"))
        val eye = provider("eye/fitted", 5, context.values.getValue("eye"))
        val shape = provider("brow.shape", 6, context.values.getValue("shape"))
        val lid = provider("brow.lid-coupling", 8, context.values.getValue("lid"))
        require(listOf(fitted, shape, lid).all { it.targets.toSet() == instance.targets.toSet() } &&
            shape.config["base"] == instance.config["base"] && lid.config["base"] == instance.config["base"] &&
            lid.config["dependencies"]?.jsonObject?.get("eye/fitted") == JsonPrimitive(eye.instanceId)) {
            "Brow blink v6 requires the same shape6 and lid8 targets and fitted providers"
        }
        val preceding = input.upstream.filter { it.targets.any(instance.targets::contains) }
        require(preceding.none { it.capability in unsupported || it.type in unsupported }) {
            "Brow blink v6 does not support preceding blink, spring, cheek coupling or separate depth turn"
        }
        val direct = directVersions.mapValues { (type, version) ->
            val candidates = preceding.filter { it.capability == type }
            require(candidates.size <= 1) { "Ambiguous brow blink eye provider: $type" }
            candidates.singleOrNull()?.also {
                require(it.type == type && it.version == version && it.config["scaffold"] == scaffoldId &&
                    it.config["base"] == JsonPrimitive(eye.instanceId) && it.targets.containsAll(instance.targets)) {
                    "Brow blink v6 requires $type@$version on its fitted eye"
                }
            }
        }
        val turn = RigIrBinary.decode(context.resources.getValue("brow_turn").read())
        val nativeIds = context.values.getValue("native_ids").jsonObject
        val shapeNames = context.values.getValue("carrier_names").jsonObject
        val turnName = context.values.getValue("turn_name").jsonPrimitive.content
        val fittedContext = ModifierAlgorithmContext(
            JsonObject(context.values - setOf("native_ids", "turn_name", "face", "eye", "shape", "lid", "carrier_names")),
            context.resources - "brow_turn", input.upstream)
        val source = BrowShapeInputs.decode(fittedContext, checkpoint)
        require(source.targets.size == 1 && source.eyeProvider == eye.instanceId && source.targets.values.single().eye != null) {
            "Brow blink v6 requires one fitted brow paired with its captured eye"
        }
        val measured = turn.meshes.associateBy { it.id }
        require(turn.meshes.all { mesh -> mesh.shapes.isNotEmpty() && mesh.shapes.all {
            it.parameter in setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ")
        } }) { "Brow blink v6 requires the captured fallback head turn" }
        require(source.targets.keys == measured.keys && measured.keys == nativeIds.keys && measured.keys == shapeNames.keys &&
            source.targets.keys.all { ModifierTarget("mesh", it) in instance.targets })
        val parameters = input.parameters
        val lift = value(fitted.config, "lift", 45f, .2f..1f)
        val strength = value(shape.config, "strength", 100f, 0f..2f)
        val dip = value(instance.config, "dip", 100f, 0f..2f)
        val wide = direct["eye.wide"] != null
        val smile = direct["eye.smile"] != null
        val shaped = source.targets.map { (id, target) ->
            val live = requireNotNull(input.mesh(id))
            val raw = measured.getValue(id)
            val mesh = live.copy(parent = target.parent, geometry = raw.geometry, offsets = null, shapes = emptyList())
            ModifierObjectUpdate(bind(input, mesh, target, lift, strength, dip, wide, smile, false, checkpoint),
                bind(input, mesh, target, lift, strength, dip, wide, smile, true, checkpoint))
        }
        val owners = input.upstream.distinctBy { it.instanceId }.mapNotNull { provider ->
            provider.instanceId?.let { id ->
                RigModifierInstance(id, provider.type, provider.version, provider.targets,
                    config = provider.config, outputAliases = provider.outputAliases, plugin = provider.plugin)
            }
        }
        val scaffold = owners.single { it.type == "rig.scaffold" }
        val added = mutableListOf<Deformer>()
        val updated = mutableListOf<ModifierObjectUpdate<Deformer>>()
        val meshes = shaped.mapNotNull { (before, after) ->
            checkpoint()
            val live = requireNotNull(input.mesh(before.id))
            val ancestry = input.parentsOfMesh(live.id)
            val native = measured.getValue(live.id)
            val nativeDeformers = turn.deformers.associateBy { it.id }
            val branch = generateSequence(native.parent) { nativeDeformers.getValue(it).parent }.map(nativeDeformers::getValue).toList()
            val canonical = nativeIds.getValue(live.id).jsonPrimitive.content
            val ordinaryAxes = setOfNotNull(EyeModules.open(source.targets.getValue(live.id).fit.side).raw,
                EyeModules.smile(source.targets.getValue(live.id).fit.side).raw.takeIf { smile })
            // Lid v8 can place ordinary openness/smile axes on either a mesh or a carrier lattice.
            // Observe both without borrowing their output as this brow's motion.
            val witnessMeshes = eye.targets.filter { it.id != live.id }.mapNotNull { input.mesh(it.id) }
                .filter { mesh -> mesh.offsets?.axes.orEmpty().any { it.parameter in ordinaryAxes } }
                .map { it.copy(parent = null, shapes = emptyList(), channels = emptyMap()) }
            val witnessWarps = eye.targets.filter { it.id != live.id }.flatMap { input.parentsOfMesh(it.id) }
                .distinctBy { it.id }.filterIsInstance<Deformer.Warp>()
                .filter { warp -> warp.lattice?.axes.orEmpty().any { it.parameter in ordinaryAxes } }
                .map { it.copy(parent = null, part = null, shapes = emptyList(), channels = emptyMap()) }
            val meshAxes = witnessMeshes.flatMap { it.offsets?.axes.orEmpty() }
            val carrierAxes = witnessWarps.flatMap { it.lattice?.axes.orEmpty() }
            for (axis in ordinaryAxes)
                BrowBlinkV6Witnesses.check(requireNotNull(input.parameter(axis)), meshAxes, carrierAxes)
            fun lower(mesh: Mesh): PuppetModel {
                val withTurn = mesh.copy(id = canonical, parent = native.parent, shapes = mesh.shapes + native.shapes)
                val model = PuppetIr.toPuppet(RigIR(input.canvas, parameters, deformers = branch + witnessWarps, meshes = listOf(withTurn) + witnessMeshes))
                val drawable = model.drawables.first()
                val neutral = drawable.copy(blendShapes = drawable.blendShapes.map { binding ->
                    binding.copy(forms = binding.forms.map { form ->
                        form?.let { MeshForm(it.positionDeltas, live.drawOrder, live.opacity, drawable.multiplyColor, drawable.screenColor) }
                    })
                })
                val lowered = BlendShapeLowering({ head, name -> if (head) turnName.replace("{meshName}", name) else shapeNames.getValue(live.id).jsonPrimitive.content },
                    checkpoint).lawful(model.copy(drawables = listOf(neutral) + model.drawables.drop(1)))
                require(lowered.parameters == model.parameters)
                return lowered.copy(drawables = listOf(lowered.drawables.first().copy(id = org.umamo.runtime.model.DrawableId(live.id))),
                    deformers = lowered.deformers.filterNot { carrier -> witnessWarps.any { it.id == carrier.id.raw } })
            }
            val prior = lower(before)
            val next = lower(after)
            val target = ModifierTarget("mesh", live.id)
            val existing = ancestry.associateBy { it.id }
            val aliases = linkedMapOf<DeformerId, DeformerId>()
            for (carrier in prior.deformers) {
                val id = owners.asSequence().filter { target in it.targets }
                    .map { ScaffoldBinding.deformerId(it, live.id, carrier.id.raw, shared = true) }.distinct()
                    .singleOrNull { it in existing }
                aliases[carrier.id] = DeformerId(requireNotNull(id) { "Cannot resolve preceding brow frame: ${carrier.id.raw}" })
            }
            require(aliases.values.map { it.raw }.toSet() == existing.keys) { "Brow blink v6 requires the complete preceding brow branch" }
            require(next.deformers.map { it.id }.containsAll(prior.deformers.map { it.id })) { "Cannot retire a preceding brow carrier" }
            for (carrier in next.deformers) {
                aliases.putIfAbsent(carrier.id, DeformerId(ScaffoldBinding.deformerId(instance, live.id, carrier.id.raw, shared = true)))
            }
            require(prior.drawables.single().parentDeformerId?.let(aliases::get)?.raw == live.parent)
            val parts = prior.deformers.mapNotNull { carrier ->
                val livePart = existing[aliases.getValue(carrier.id).raw]?.part ?: return@mapNotNull null
                carrier.partId?.let { it to PartId(livePart) }
            }.toMap()
            val remap = ModelIdRemap(deformers = aliases, parts = parts)
            val expectedMesh = PuppetIr.meshToIr(remap.drawable(prior.drawables.single()))
            require(live.parent == expectedMesh.parent && live.geometry == expectedMesh.geometry &&
                live.offsets == expectedMesh.offsets && live.shapes == expectedMesh.shapes && live.channels.isEmpty()) {
                "Brow blink v6 requires the exact preceding brow topology and height grid: ${live.id}"
            }
            for (carrier in prior.deformers) {
                val expected = PuppetIr.deformerToIr(remap.deformer(carrier))
                require(existing[expected.id] == expected) {
                    "Brow blink v6 requires the exact preceding brow frame: ${expected.id}"
                }
            }
            val liveParts = parts.values.distinct().map { requireNotNull(input.part(it.raw)) }
            val current = PuppetIr.toPuppet(RigIR(input.canvas, parameters, parts = liveParts, deformers = ancestry, meshes = listOf(live),
                textures = input.textures, authoring = input.authoring))
            val transfer = object : BindingMerge.Source {
                override fun provider(instance: RigModifierInstance) = scaffold
                override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = prior to next
                override fun resolveParameter(instance: RigModifierInstance, before: Parameter, declared: Parameter): Parameter? = null
                override fun static(model: PuppetModel) = BindingProjection.retain(model, emptySet(), mapOf(ParameterId("ParamMouthOpenY") to 1f))
                override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) = false
                override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = aliases
                override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = parts
                override fun requiredParts(model: PuppetModel, target: ModifierTarget) = emptySet<PartId>()
                override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) =
                    requireNotNull(parts[PartId(canonical)]).raw
                override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable,
                                           checkpoint: () -> Unit): List<VertexSource> =
                    BodyVertexSources.match(to, original, target, checkpoint)
            }
            val geometryOnly = instance.copy(targets = listOf(target), outputAliases = instance.outputAliases.filterKeys {
                !it.startsWith("parameter-folder:")
            })
            val merged = BindingMerge.apply(current, geometryOnly, transfer, checkpoint)
            require(merged.deformers.map { it.id.raw }.containsAll(existing.keys))
            for (deformer in merged.deformers.map(PuppetIr::deformerToIr)) {
                val original = existing[deformer.id]
                if (original == null) added += deformer
                else if (original != deformer) {
                    val update = ModifierObjectUpdate(original, deformer)
                    BrowShapeCarrierScope.check(input, listOf(update), live.id, existing.keys, checkpoint)
                    updated += update
                }
            }
            val result = PuppetIr.meshToIr(merged.drawables.single())
            ModifierObjectUpdate(live, result).takeIf { live != result }
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = meshes),
            deformers = ModifierObjectChanges(added = added, updated = updated))
    }

    private fun bind(
        input: ModifierEvaluationInput, mesh: Mesh, target: BrowShapeInputs.Target, lift: Float, strength: Float,
        dip: Float, wide: Boolean, smile: Boolean, blink: Boolean, checkpoint: () -> Unit,
    ): Mesh {
        val side = if (target.fit.side == Side.LEFT) "L" else "R"
        val height = ParameterId("ParamBrow${side}Y")
        val angle = ParameterId("ParamBrow${side}Angle")
        for (id in listOf(height, angle)) require(input.parameter(id.raw)?.let {
            it.min == -1f && it.max == 1f && it.default == 0f && !it.blend
        } == true) { "Brow blink v6 requires ordinary fitted brow controls" }
        val open = EyeModules.open(target.fit.side)
        val smileId = EyeModules.smile(target.fit.side).takeIf { smile }
        require(input.parameter(open.raw)?.let {
            it.min == 0f && it.max == (if (wide) 2f else 1f) && it.default == 1f && !it.blend
        } == true) { "Brow blink v6 requires the fitted eye openness control" }
        if (smileId != null) require(input.parameter(smileId.raw)?.let {
            it.min == 0f && it.max == 1f && it.default == 0f && !it.blend
        } == true) { "Brow blink v6 requires the same-side ordinary smile control" }
        val raw = target.raw
        val positions = FloatArray(raw.rigPositions.size) { i ->
            if (i % 1024 == 0) checkpoint()
            val value = raw.rigPositions[i]
            if (target.parent == null) value
            else if (i % 2 == 0) (value - target.frame.left) / target.frame.width.coerceAtLeast(1e-4f)
            else (value - target.frame.top) / target.frame.height.coerceAtLeast(1e-4f)
        }
        require(mesh.geometry == MeshGeometry(Floats.wrap(positions), Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles))) {
            "Brow blink v6 requires captured source topology in the fitted frame"
        }
        val eye = requireNotNull(target.eye)
        val travel = eye.opening * eye.width * lift
        val rig = BrowRig(target.fit, travel,
            BrowOptions(shape = true, shapeStrength = strength, blink = blink, blinkDip = dip), checkpoint)
        val grid = rig.grid(raw.rigPositions, target.frame, height, angle)
        val shapes = rig.shapes(raw.rigPositions, target.frame, ParameterId("ParamBrow${side}X"),
            ParameterId("ParamBrow${side}Form"), open, wide, smileId).map { shape ->
            BlendBinding(shape.parameterId.raw, Floats.wrap(shape.keys), shape.neutralIndex, shape.forms.map { form ->
                form?.let { MeshShape(Floats.wrap(it.positionDeltas), mesh.drawOrder, mesh.opacity, mesh.multiply, mesh.screen) }
            })
        }
        return mesh.copy(offsets = offsets(grid), shapes = shapes)
    }

    private fun offsets(grid: KeyformGrid<MeshDeltaForm>) = KeyGrid(
        grid.axes.map { KeyAxis(it.parameterId.raw, Floats.wrap(it.keys)) },
        grid.cells.map { KeyCell(Ints.wrap(it.coordinate), MeshOffsets(Floats.wrap(it.form.positionDeltas))) },
    )

    private fun value(config: JsonObject, key: String, default: Float, range: ClosedFloatingPointRange<Float>): Float =
        ((config["values"]?.jsonObject?.get(key)?.jsonPrimitive?.float ?: default) / 100f).also {
            require(it in range) { "Invalid brow shape $key" }
        }

    companion object {
        const val TYPE = "brow.blink"
        const val VERSION = 6
        const val ENTRYPOINT = "builtin.62726f772e626c696e6b.v6/blink"
        val requires = setOf("rig.scaffold", "face/fitted", "eye/fitted", "brow/fitted", "brow.shape", "brow.lid-coupling")
        val directVersions = mapOf("eye.smile" to 3, "eye.wide" to 3, "eye.gaze-lid" to 3, "eye.emotion" to 3,
            "eye.highlight" to 3, "eye.aftershock" to 4, "eye.squash" to 4)
        val unsupported = setOf(TYPE, "brow.spring", "mouth.cheek-lid", "face.depth-turn")
        val optionalUpstream = directVersions.keys + unsupported
    }
}
