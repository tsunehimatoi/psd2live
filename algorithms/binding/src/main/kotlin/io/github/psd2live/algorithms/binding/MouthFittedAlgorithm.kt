package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*

/** Builds the fitted mouth transition from neutral upstream geometry and source measurements. */
class MouthFittedAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext, checkpoint: () -> Unit) =
        candidateChanges(input, instance, context, checkpoint, lowerShapes = true)

    internal fun candidateChanges(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                                  checkpoint: () -> Unit, lowerShapes: Boolean): ModifierModelChanges {
        require(instance.targets.all { it.kind == "mesh" }) { "Fitted mouth requires mesh targets" }
        val inputs = MouthFittedInputs.decode(context)
        val source = PuppetIr.toPuppet(input)
        val targets = instance.targets.mapTo(linkedSetOf()) { it.id }
        val tongues = linkedMapOf<String, String>()
        val posed = sourceCandidate(source, targets, inputs, checkpoint) { id ->
            instance.outputId("tongue-out:${id.raw}").also { tongues[it] = id.raw }
        }
        val model = if (lowerShapes) lower(posed, inputs, checkpoint) else posed
        val parameters = objectChanges(source.parameters, model.parameters, input.parameters.associateBy { it.id }, { it.id.raw }) {
            PuppetIr.parametersToIr(model.copy(parameters = listOf(it))).single()
        }
        val meshes = objectChanges(source.drawables, model.drawables, input.meshes.associateBy { it.id }, { it.id.raw }, PuppetIr::meshToIr)
        val deformers = objectChanges(source.deformers, model.deformers, input.deformers.associateBy { it.id }, { it.id.raw }, PuppetIr::deformerToIr)
        val parts = objectChanges(source.parts, model.parts, input.parts.associateBy { it.id }, { it.id.raw }) {
            PuppetIr.toIr(source.copy(parts = listOf(it), drawables = emptyList(), deformers = emptyList())).parts.single()
        }
        val textureBindings = tongues.mapNotNull { (copy, owner) -> input.textures.bindings[owner]?.let { copy to it } }.toMap()
        val hierarchy = if (tongues.isEmpty()) null else PuppetIr.toIr(model.copy(
            parts = emptyList(), drawables = emptyList(), deformers = emptyList()))
        return ModifierModelChanges(parameters = parameters, meshes = meshes, parts = parts, deformers = deformers,
            rootChildren = hierarchy?.rootChildren?.takeIf { it != input.rootChildren }?.let { ModifierFieldChange(input.rootChildren, it) },
            renderRoot = hierarchy?.renderRoot?.takeIf { it != input.renderRoot }?.let { ModifierFieldChange(input.renderRoot, it) },
            textures = textureBindings.takeIf { it.isNotEmpty() }?.let {
                ModifierFieldChange(input.textures, input.textures.copy(bindings = input.textures.bindings + it))
            })
    }

    /** Unchanged engine objects retain their identity. Convert only the objects the transition wrote. */
    private fun <E : Any, I> objectChanges(before: List<E>, after: List<E>, original: Map<String, I>, id: (E) -> String,
                                           convert: (E) -> I): ModifierObjectChanges<I> {
        val existing = before.associateBy(id)
        val additions = mutableListOf<I>()
        val updates = mutableListOf<ModifierObjectUpdate<I>>()
        for (value in after) {
            val key = id(value)
            val old = existing[key]
            if (old === value) continue
            val converted = convert(value)
            if (old == null) additions += converted
            else {
                val originalValue = original.getValue(key)
                if (originalValue != converted) updates += ModifierObjectUpdate(originalValue, converted)
            }
        }
        return ModifierObjectChanges(added = additions, updated = updates)
    }

    fun project(
        source: PuppetModel,
        targets: Set<String>,
        inputs: MouthFittedInputs,
        checkpoint: () -> Unit = {},
        onBindingInput: ((PuppetModel) -> Unit)? = null,
        tongueId: (DrawableId) -> String = { "${it.raw}_Out" },
        sourcePositions: Map<String, FloatArray> = emptyMap(),
    ): PuppetModel {
        val posed = sourceCandidate(source, targets, inputs, checkpoint, sourcePositions, tongueId)
        onBindingInput?.invoke(posed)
        return lower(posed, inputs, checkpoint)
    }

    private fun lower(posed: PuppetModel, inputs: MouthFittedInputs, checkpoint: () -> Unit): PuppetModel =
        BlendShapeLowering({ turn, name -> (if (turn) inputs.turnCarrierName else inputs.shapeCarrierName)
            .replace("{meshName}", name) }, checkpoint).lawful(posed)

    private fun sourceCandidate(
        source: PuppetModel,
        targets: Set<String>,
        inputs: MouthFittedInputs,
        checkpoint: () -> Unit,
        sourcePositions: Map<String, FloatArray> = emptyMap(),
        tongueId: (DrawableId) -> String,
    ): PuppetModel {
        val mouth = inputs.source?.rig()
        val own = inputs.layers.values.filter { covers(it, inputs, mouth) && it.opaque }
        val layered = own.any { it.role in lips }
        val lipped = own.any { it.role in lipSkin }
        val closedLine = own.any { it.tag == "MOUTH_CLOSE" }
        val byId = source.drawables.associateBy { it.id.raw }
        require(targets.all { it in byId } && inputs.layers.keys.all { it in byId }) { "Unknown fitted mouth mesh" }
        val vertexInputs = inputs.sourceVertices
        val rawMeshes = vertexInputs?.let {
            ScaffoldMeshContext.decodeRawMeshes(it.resource, checkpoint).also { meshes ->
                require(it.targets.values.all { target -> target.canonical in meshes }) { "Unknown fitted mouth canonical mesh" }
            }
        }.orEmpty()
        val head = inputs.head?.rig(checkpoint)
        val active = source.parameters.mapTo(hashSetOf()) { it.id }
        val drawables = source.drawables.map { original ->
            checkpoint()
            val layer = inputs.layers[original.id.raw]
            val geometry = original.mesh
            if (original.id.raw !in targets || layer == null || geometry == null) return@map original
            val covered = covers(layer, inputs, mouth)
            val follow = if (mouth != null && covered) follow(mouth, layer, layered, lipped) else null
            val faceSkin = mouth != null && lipped && !mouth.fit.hasLines && layer.tag == "FACE" && layer.role == null
            val mouthFace = mouth != null && (layer.role == null || layer.role == "SHADOW") &&
                (layer.tag in setOf("FACE", "NOSE") || layer.tag == "FACE_DETAIL" && mouth.fit.toMouth(layer.centroidX, layer.centroidY).second > 0f)
            val raw = vertexInputs?.targets?.get(original.id.raw)?.takeIf { it.parent == original.parentDeformerId?.raw }
                ?.let { rawMeshes.getValue(it.canonical) }
            val positions = sourcePositions[original.id.raw] ?: if (raw != null && matchesSource(raw, geometry, layer.frame, checkpoint)) raw.rigPositions
                else positions(geometry.positions, layer.frame)
            val grid = when {
                mouth != null && follow != null -> if (layer.tag == "MOUTH_CLOSE") mouth.closedGrid(positions, layer.frame, mouthForm)
                    else mouth.grid(positions, layer.frame, mouthForm, mouthOpen, follow, inputs.chin)
                mouth != null && faceSkin -> mouth.faceGrid(positions, layer.frame, mouthForm, mouthOpen, inputs.chin)
                layer.tag in openTags && inputs.fallback != null -> fallbackGrid(positions, layer, inputs.fallback)
                layer.tag in tags && original.geometryGrid == null -> KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), MeshDeltaForm(FloatArray(positions.size)))))
                else -> original.geometryGrid
            }
            val masks = if (layer.tag in components || covered && layer.tag in openTags &&
                (layer.role != null || layer.clipped) && follow in inner) {
                mouthMask(layer, inputs, byId.keys)?.let { listOf(DrawableId(it)) }.orEmpty()
            } else original.maskedBy
            val channels = original.channelGrids.gridsByChannel.toMutableMap()
            if (layer.preset && layer.tag in tags) {
                val opacity = opacity(layer.tag, closedLine && covered, inputs.closedMouth,
                    mouth?.fit?.layered != null && covered, inputs.teethFade)
                if (opacity != null) channels[FormChannel.OPACITY] = opacity
                else if (channels[FormChannel.OPACITY]?.axes?.any { it.parameterId == mouthOpen } == true)
                    channels.remove(FormChannel.OPACITY)
            }
            if (mouth?.options?.depth == true && covered && FormChannel.MULTIPLY_COLOR !in channels &&
                (follow in inner || follow == MouthRig.Follow.TONGUE ||
                    layer.tag == "MOUTH_OPEN" && layer.role == null && inputs.separateInside)) {
                channels[FormChannel.MULTIPLY_COLOR] = channel(floatArrayOf(0f, 0.25f, 1f)) { key ->
                    val light = if (key == 0f) 0.72f else if (key < 1f) 0.82f else 1f
                    ChannelValue.Color(ColorRgb(light, light * 0.97f, light * 0.97f))
                }
            }
            val updated = if (grid === original.geometryGrid && channels == original.channelGrids.gridsByChannel && masks == original.maskedBy)
                original else original.copy(geometryGrid = grid, channelGrids = ChannelGrids(channels), maskedBy = masks)
            if (mouth == null || follow == null && !mouthFace) return@map updated
            val mouthShapes = if (follow != null) {
                val closed = layer.tag == "MOUTH_CLOSE"
                val depth = when (follow) {
                    MouthRig.Follow.UPPER_TEETH -> 0.06f
                    MouthRig.Follow.LOWER_TEETH -> 0.10f
                    MouthRig.Follow.TONGUE -> 0.16f
                    MouthRig.Follow.DEEP -> 0.20f
                    MouthRig.Follow.INSIDE, MouthRig.Follow.OPENING -> 0f
                    else -> if (layer.tag == "MOUTH_OPEN" && inputs.separateInside) 0.12f else 0f
                }
                mouth.shapes(positions, layer.frame, closed,
                    { opens -> if (!opens) Float.NaN else if (closed) 0f else 1f },
                    layer.tag == "TONGUE", follow, chin = inputs.chin) +
                    if (depth > 0f && angleX in active && angleY in active)
                        mouth.depthShapes(positions, layer.frame, depth, angleX, angleY) else emptyList()
            } else mouth.faceShapes(positions, layer.frame, inputs.chin, layer.tag == "NOSE", mouthOpen,
                MouthRig.SPRING.takeIf { mouth.options.spring }, lippedSkin = faceSkin)
            val headShapes = if (head != null && layer.turnsWithHeadMesh && angleX in active && angleY in active &&
                (faceSkin || follow != null)) {
                val rest = when {
                    faceSkin -> mouth.atRestFace(positions, inputs.chin)
                    follow != null && layer.tag != "MOUTH_CLOSE" -> mouth.atRest(positions, follow, inputs.chin)
                    else -> positions
                }
                head.shapes(rest, layer.frame, HeadShell.SKIN, angleX, angleY)
            } else emptyList()
            val replaced = headShapes.mapTo(hashSetOf()) { it.parameterId }
            val retained = original.blendShapes.filterNot { it.parameterId in replaced }
            val shapes = retained + neutralize(mouthShapes.filter { it.parameterId in active } + headShapes, updated)
            if (shapes == updated.blendShapes) updated else updated.copy(blendShapes = shapes)
        }
        val parameters = source.parameters.map {
            if (it.id == mouthOpen && mouth != null && mouth.openKeys.last() > it.max) it.copy(max = mouth.openKeys.last()) else it
        }
        return withTongues(source.copy(drawables = drawables, parameters = parameters), targets, inputs, mouth, tongueId)
    }

    private fun withTongues(
        model: PuppetModel, targets: Set<String>, inputs: MouthFittedInputs, mouth: MouthRig?, tongueId: (DrawableId) -> String,
    ): PuppetModel {
        if (mouth?.fit?.layered == null || !mouth.options.capture || !mouth.options.tongue ||
            model.parameters.none { it.id == MouthRig.TONGUE_OUT }) return model
        val lower = model.drawables.filter { inputs.layers[it.id.raw]?.role in setOf("LOWER_LIP", "LOWER_LINE") }
            .maxOfOrNull { it.drawOrder } ?: return model
        val copies = model.drawables.mapNotNull { original ->
            val layer = inputs.layers[original.id.raw] ?: return@mapNotNull null
            if (original.id.raw !in targets || layer.tag != "TONGUE" || !covers(layer, inputs, mouth)) return@mapNotNull null
            val mesh = original.mesh ?: return@mapNotNull null
            val positions = positions(mesh.positions, layer.frame)
            val id = DrawableId(tongueId(original.id))
            require(model.drawables.none { it.id == id }) { "Generated tongue ID already exists: ${id.raw}" }
            val base = original.copy(id = id, maskedBy = emptyList(), channelGrids = ChannelGrids.Empty, opacity = 0f,
                drawOrder = lower + 1f, geometryGrid = mouth.grid(positions, layer.frame, mouthForm, mouthOpen,
                    MouthRig.Follow.PROTRUDING_TONGUE, inputs.chin))
            val shapes = mouth.protrudingTongueShapes(positions, layer.frame, original.opacity, chin = inputs.chin)
            val normalized = neutralize(shapes, base).mapIndexed { bindingIndex, binding ->
                if (binding.parameterId != MouthRig.TONGUE_OUT) binding else binding.copy(forms = binding.forms.mapIndexed { i, form ->
                    form?.let { MeshForm(it.positionDeltas, it.drawOrder, shapes[bindingIndex].forms[i]!!.opacity, it.multiplyColor, it.screenColor) }
                })
            }
            original.id to base.copy(blendShapes = normalized)
        }.toMap()
        if (copies.isEmpty()) return model
        val needsGap = model.drawables.any { it.drawOrder > lower && it.drawOrder < lower + 2f }
        fun children(children: List<OrgChild>): List<OrgChild> = children.flatMap { child ->
            val copy = (child as? OrgChild.Drawable)?.let { copies[it.id] }
            if (copy == null) listOf(child) else listOf(OrgChild.Drawable(copy.id), child)
        }
        val drawables = model.drawables.map { drawable ->
            if (needsGap && drawable.drawOrder > lower && inputs.layers[drawable.id.raw]?.overridesDrawOrder == false)
                drawable.copy(drawOrder = drawable.drawOrder + 1f) else drawable
        } + copies.values
        return model.copy(drawables = drawables, parts = model.parts.map { it.copy(children = children(it.children)) },
            rootChildren = children(model.rootChildren)).let { it.copy(renderRoot = it.deriveRenderRoot()) }
    }

    private fun neutralize(shapes: List<BlendShapeBinding<MeshForm>>, drawable: Drawable): List<BlendShapeBinding<MeshForm>> {
        val pose = { id: ParameterId -> if (id.raw == "ParamEyeLOpen" || id.raw == "ParamEyeROpen") 1f else 0f }
        val rest by lazy { drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, pose) }
        val order by lazy { drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, pose) }
        val multiply by lazy { drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, pose) }
        val screen by lazy { drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, pose) }
        val deltas by lazy { meshGridDefaultDeltas(drawable, pose) }
        return shapes.map { binding ->
            binding.copy(forms = binding.forms.map { form ->
                form?.let {
                    val opacity = if (binding.parameterId in MouthRig.OPENING && !it.opacity.isNaN()) it.opacity * drawable.opacity else rest
                    val base = if (binding.parameterId in angles) deltas?.takeIf { d -> d.size == it.positionDeltas.size } else null
                    val positions = if (base == null) it.positionDeltas else FloatArray(base.size) { i -> it.positionDeltas[i] + base[i] }
                    MeshForm(positions, order, opacity, multiply, screen)
                }
            })
        }
    }

    private fun mouthMask(layer: MouthFittedInputs.Layer, inputs: MouthFittedInputs, present: Set<String>): String? {
        val candidates = inputs.maskSources.mapNotNull { id -> inputs.layers[id]?.takeIf { id in present }?.let { id to it } }
        val sameSide = candidates.filter { it.second.side == layer.side }
        val exact = sameSide.filter { it.second.variant == layer.variant }
        val neutral = candidates.filter { it.second.side == "NONE" }
        return exact.ifEmpty { sameSide.ifEmpty { neutral.ifEmpty { candidates } } }.minByOrNull { (_, mask) ->
            val dx = mask.bounds.centerX - layer.bounds.centerX
            val dy = mask.bounds.centerY - layer.bounds.centerY
            dx * dx + dy * dy
        }?.first
    }

    private fun positions(vertices: FloatArray, frame: Bounds): FloatArray = FloatArray(vertices.size) { i ->
        if (i % 2 == 0) frame.left + vertices[i] * frame.width else frame.top + vertices[i] * frame.height
    }

    /** Compare the exact source normalization, including same-topology edits to rest vertices. */
    private fun matchesSource(raw: ScaffoldMesh.RawMesh, geometry: DrawableMesh, frame: Bounds, checkpoint: () -> Unit): Boolean {
        if (raw.rigPositions.size != geometry.positions.size || !raw.triangles.contentEquals(geometry.indices)) return false
        val width = frame.width.coerceAtLeast(1e-4f)
        val height = frame.height.coerceAtLeast(1e-4f)
        for (i in raw.rigPositions.indices step 2) {
            if (i % 1024 == 0) checkpoint()
            val x = (raw.rigPositions[i] - frame.left) / width
            val y = (raw.rigPositions[i + 1] - frame.top) / height
            if (x.toRawBits() != geometry.positions[i].toRawBits() || y.toRawBits() != geometry.positions[i + 1].toRawBits()) return false
        }
        return true
    }

    private fun fallbackGrid(positions: FloatArray, layer: MouthFittedInputs.Layer, fallback: MouthFittedInputs.Fallback): KeyformGrid<MeshDeltaForm> {
        val axes = listOf(KeyformAxis(mouthForm, floatArrayOf(-1f, 0f, 1f)), KeyformAxis(mouthOpen, floatArrayOf(0f, 0.5f, 1f)))
        val cells = buildList {
            for ((f, form) in axes[0].keys.withIndex()) for ((o, open) in axes[1].keys.withIndex()) {
                val deltas = FloatArray(positions.size)
                for (i in positions.indices step 2) {
                    val (x, y) = fallback.point(positions[i], positions[i + 1], layer.aperture ?: layer.bounds, form, open)
                    deltas[i] = (x - positions[i]) / layer.frame.width.coerceAtLeast(1e-4f)
                    deltas[i + 1] = (y - positions[i + 1]) / layer.frame.height.coerceAtLeast(1e-4f)
                }
                add(KeyformCell(intArrayOf(f, o), MeshDeltaForm(deltas)))
            }
        }
        return KeyformGrid(axes, cells)
    }

    private fun covers(layer: MouthFittedInputs.Layer, inputs: MouthFittedInputs, mouth: MouthRig?): Boolean =
        mouth != null && layer.variant == inputs.variant && layer.tag in tags && (layer.role == null || layer.role in roles)

    private fun follow(mouth: MouthRig, layer: MouthFittedInputs.Layer, layered: Boolean, lipped: Boolean): MouthRig.Follow {
        val whole = if (mouth.options.exactClose) MouthRig.Follow.WHOLE_TIGHT else MouthRig.Follow.WHOLE
        return when (layer.tag) {
            "TOOTH_T" -> MouthRig.Follow.UPPER_TEETH
            "TOOTH_B" -> MouthRig.Follow.LOWER_TEETH
            "TONGUE" -> MouthRig.Follow.TONGUE
            "MOUTH_CLOSE" -> whole
            else -> when (layer.role) {
                "UPPER_LINE" -> MouthRig.Follow.UPPER_LINE
                "LOWER_LINE" -> MouthRig.Follow.LOWER_LINE
                "UPPER_LIP" -> MouthRig.Follow.UPPER_LIP
                "LOWER_LIP" -> MouthRig.Follow.LOWER_LIP
                "HIGHLIGHT", "SHADOW" -> {
                    val (u, v) = mouth.fit.toMouth(layer.centroidX, layer.centroidY)
                    val top = MouthFit.sample(mouth.fit.upper, u)
                    val bottom = MouthFit.sample(mouth.fit.lower, u)
                    val margin = (bottom - top) * 0.2f
                    when {
                        v < top + margin -> MouthRig.Follow.UPPER_LIP
                        v > bottom - margin -> MouthRig.Follow.LOWER_LIP
                        layer.role == "SHADOW" -> MouthRig.Follow.DEEP
                        lipped -> MouthRig.Follow.INSIDE
                        else -> MouthRig.Follow.OPENING
                    }
                }
                else -> if (layer.clipped && layered) MouthRig.Follow.DEEP else if (layered) MouthRig.Follow.OPENING else whole
            }
        }
    }

    private fun opacity(tag: String, swap: Boolean, closed: Boolean, layered: Boolean, fadePercent: Float): KeyformGrid<ChannelValue>? {
        val fade = (fadePercent / 100f).coerceAtLeast(0.01f)
        fun fadeIn() = channel(floatArrayOf(0f, fade, 1f)) { ChannelValue.Scalar((it / fade).coerceIn(0f, 1f)) }
        fun swapTrack(closedDrawing: Boolean) = channel(floatArrayOf(0f, MouthRig.SWAP, 1f)) {
            ChannelValue.Scalar(if ((it == 0f) == closedDrawing) 1f else 0f)
        }
        return when (tag) {
            "MOUTH_CLOSE" -> if (swap) swapTrack(true) else channel(floatArrayOf(0f, 1f)) { ChannelValue.Scalar(1f - it) }
            "MOUTH" -> if (swap) swapTrack(false) else null
            "MOUTH_OPEN" -> if (swap) swapTrack(false) else if (closed) fadeIn() else null
            else -> if (layered && !swap) null else fadeIn()
        }
    }

    private fun channel(keys: FloatArray, value: (Float) -> ChannelValue) =
        KeyformGrid(listOf(KeyformAxis(mouthOpen, keys)), keys.mapIndexed { index, key -> KeyformCell(intArrayOf(index), value(key)) })

    private companion object {
        val mouthForm = ParameterId("ParamMouthForm")
        val mouthOpen = ParameterId("ParamMouthOpenY")
        val angleX = ParameterId("ParamAngleX")
        val angleY = ParameterId("ParamAngleY")
        val angles = setOf(angleX, angleY, ParameterId("ParamAngleZ"))
        val openTags = setOf("MOUTH", "MOUTH_OPEN")
        val components = setOf("TOOTH_T", "TOOTH_B", "TONGUE")
        val tags = openTags + components + "MOUTH_CLOSE"
        val lipSkin = setOf("UPPER_LIP", "LOWER_LIP")
        val lips = lipSkin + setOf("UPPER_LINE", "LOWER_LINE")
        val roles = lips + setOf("HIGHLIGHT", "SHADOW")
        val inner = setOf(MouthRig.Follow.DEEP, MouthRig.Follow.INSIDE, MouthRig.Follow.OPENING)
    }
}

/** Supplies the source binding candidate for host-side composition and lowering. */
class MouthFittedSourceAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext, checkpoint: () -> Unit) =
        MouthFittedAlgorithm().candidateChanges(input, instance, context, checkpoint, lowerShapes = false)
}
