package io.github.psd2live.core.legacy

import io.github.psd2live.core.*

import kotlinx.serialization.json.*
import org.umamo.runtime.eval.*
import org.umamo.runtime.model.*

/** A materialized semantic transition is appended after every edit whose generation frame it keeps. */
internal object RigGenerationJournal {
    const val OP = "rig_generation_transition"

    fun latest(overlay: RigEditOverlay): PuppetModel? = overlay.authoringJournal.lastOrNull {
        it["op"]?.jsonPrimitive?.contentOrNull == OP
    }?.getValue("generated")?.jsonObject?.let(GeneratedRigJournalCodec::decode)

    fun retired(overlay: RigEditOverlay): Map<String, Boolean> = overlay.authoringJournal.lastOrNull {
        it["op"]?.jsonPrimitive?.contentOrNull == OP
    }?.getValue("retired")?.jsonObject?.mapValues { it.value.jsonPrimitive.boolean }.orEmpty()

    fun suppressedParameters(overlay: RigEditOverlay): Set<ParameterId> = overlay.authoringJournal.lastOrNull {
        it["op"]?.jsonPrimitive?.contentOrNull == OP
    }?.getValue("suppressed_parameters")?.jsonArray?.mapTo(HashSet()) { ParameterId(it.jsonPrimitive.content) }.orEmpty()

    fun authoredParameters(overlay: RigEditOverlay): Set<ParameterId> = overlay.authoringJournal.lastOrNull {
        it["op"]?.jsonPrimitive?.contentOrNull == OP
    }?.get("authored_parameters")?.jsonArray?.mapTo(HashSet()) { ParameterId(it.jsonPrimitive.content) }.orEmpty()

    fun encode(current: PuppetModel, previous: PuppetModel, desired: PuppetModel, activeIds: Set<DrawableId>,
               previouslyRetired: Map<String, Boolean>, visibilityOverrides: Map<String, Boolean>, suppressed: Set<ParameterId>, authoredParameters: Set<ParameterId>, textures: Map<String, JsonObject>, checkpoint: () -> Unit,
               previousProjection: PuppetModel = previous, desiredProjection: PuppetModel = desired): JsonObject {
        val existingParameters = current.parameters.mapTo(HashSet()) { it.id }
        val requiredParameters = desired.drawables.flatMap { drawable ->
            drawable.geometryGrid?.axes.orEmpty() + drawable.channelGrids.gridsByChannel.values.flatMap { it.axes }
        }.mapTo(HashSet()) { it.parameterId }
        val requiredParents = desired.drawables.mapNotNull { it.parentDeformerId }.toMutableSet()
        val seenParents = mutableSetOf<DeformerId>()
        while (requiredParents.isNotEmpty()) {
            val id = requiredParents.first(); requiredParents -= id
            if (!seenParents.add(id)) continue
            val deformer = desired.deformers.single { it.id == id }
            requiredParameters += when (deformer) {
                is Deformer.Warp -> deformer.geometryGrid?.axes.orEmpty()
                is Deformer.Rotation -> deformer.geometryGrid?.axes.orEmpty()
            }.map { it.parameterId }
            requiredParameters += deformer.channelGrids.gridsByChannel.values.flatMap { it.axes }.map { it.parameterId }
            deformer.parent?.let { requiredParents += it }
        }
        // A deleted generated axis must not reappear merely because another setting changed.
        val additions = desired.parameters.filter { it.id !in existingParameters && it.id in requiredParameters && it.id !in suppressed }
        val updates = current.parameters.mapNotNull { actual ->
            val old = previous.parameters.singleOrNull { it.id == actual.id }
            val next = desired.parameters.singleOrNull { it.id == actual.id }
            if (actual.id !in authoredParameters && old == actual && next != null && next != actual) actual to next else null
        }
        val updated = updates.associate { it.first.id to it.second }
        val context = current.copy(parameters = current.parameters.map { updated[it.id] ?: it } + additions)
        val retired = current.drawables.filter { it.id !in activeIds && previous.drawables.any { old -> old.id == it.id } }
            .associate { it.id.raw to (previouslyRetired[it.id.raw] ?: it.isVisible) }
        val changes = context.drawables.filter { it.mesh != null }.mapNotNull { drawable ->
            checkpoint()
            val next = desired.drawables.singleOrNull { it.id == drawable.id } ?: return@mapNotNull null
            val original = previous.drawables.singleOrNull { it.id == drawable.id }
            val old = original ?: next
            val mesh = requireNotNull(drawable.mesh)
            val actualUv = RasterMeshJournal.TextureCoordinates(current, drawable).toCanvas(mesh.uvs)
            fun sources(other: Drawable): List<org.umamo.edit.VertexSource> {
                val input = requireNotNull(other.mesh)
                return RasterMeshJournal.prepare(DrawableMesh(input.uvs, input.uvs, input.indices),
                    DrawableMesh(actualUv, actualUv, mesh.indices), checkpoint).sources
            }
            val geometry = if (original == null) drawable.geometryGrid else RigGenerationResidual.geometry(context, previousProjection,
                desiredProjection, drawable.id, sources(old), sources(next), checkpoint)
            val channels = if (original == null) drawable.channelGrids else RigGenerationResidual.channels(context, drawable, old, next, checkpoint)
            buildJsonObject {
                put("id", drawable.id.raw); put("expected", RasterMeshJournal.fingerprint(mesh))
                put("parent", drawable.parentDeformerId?.raw?.let(::JsonPrimitive) ?: JsonNull)
                put("geometry", geometry?.let { grid -> RasterMeshCreation.grid(grid) { JsonArray(it.positionDeltas.map(::JsonPrimitive)) } } ?: JsonNull)
                put("channels", GeneratedRigJournalCodec.channels(channels))
                // Masks are a generated relation unless an appearance edit changed the original relation.
                put("masks", JsonArray((if (original == null || drawable.maskedBy == old.maskedBy) next.maskedBy else drawable.maskedBy).map { JsonPrimitive(it.raw) }))
                put("invert_mask", if (original == null || drawable.invertMask == old.invertMask) next.invertMask else drawable.invertMask)
                put("visible", if (drawable.id.raw in retired) false else visibilityOverrides[drawable.id.raw]
                    ?: previouslyRetired[drawable.id.raw] ?: drawable.isVisible)
            }
        }
        return buildJsonObject {
            put("op", OP); put("generated", GeneratedRigJournalCodec.encode(desired))
            put("parameters", GeneratedRigJournalCodec.parameters(context.copy(parameters = additions)))
            put("parameter_updates", JsonArray(updates.map { (before, after) -> buildJsonObject {
                put("before", GeneratedRigJournalCodec.parameters(current.copy(parameters = listOf(before))).single())
                put("after", GeneratedRigJournalCodec.parameters(current.copy(parameters = listOf(after))).single())
            } }))
            put("meshes", JsonArray(changes))
            put("retired", buildJsonObject { retired.forEach { (id, visible) -> put(id, visible) } })
            put("suppressed_parameters", JsonArray(suppressed.sortedBy { it.raw }.map { JsonPrimitive(it.raw) }))
            put("authored_parameters", JsonArray(authoredParameters.sortedBy { it.raw }.map { JsonPrimitive(it.raw) }))
            put("textures", JsonObject(textures))
        }
    }

    fun replay(input: PuppetModel, command: JsonObject): PuppetModel {
        require(command.keys == setOf("op", "generated", "parameters", "parameter_updates", "meshes", "retired", "suppressed_parameters", "authored_parameters", "textures")) { "Invalid rig generation transition" }
        command.getValue("textures").jsonObject.values.forEach { asset ->
            val saved = asset.jsonObject
            require(saved.keys == setOf("recipe", "layers")) { "Invalid generation texture asset" }
            val records = saved.getValue("recipe").jsonArray.map { it.jsonObject }
            require(records.size == 2 && records.map { it.getValue("op").jsonPrimitive.content }.toSet() == setOf(RigGenerationBaseline.OP, MeshGenerationBaseline.OP)) { "Invalid generation texture recipe" }
            records.forEach { record -> when (record.getValue("op").jsonPrimitive.content) {
                RigGenerationBaseline.OP -> RigGenerationBaseline.replay(input, record)
                MeshGenerationBaseline.OP -> MeshGenerationBaseline.replay(input, record)
            } }
        }
        GeneratedRigJournalCodec.decode(command.getValue("generated").jsonObject)
        val additions = GeneratedRigJournalCodec.parameters(command.getValue("parameters").jsonArray)
        require(additions.map { it.id }.distinct().size == additions.size) { "Duplicate generation transition parameter" }
        val updates = command.getValue("parameter_updates").jsonArray.map { element ->
            val update = element.jsonObject
            val before = GeneratedRigJournalCodec.parameters(JsonArray(listOf(update.getValue("before")))).single()
            val after = GeneratedRigJournalCodec.parameters(JsonArray(listOf(update.getValue("after")))).single()
            require(before.id == after.id && input.parameters.singleOrNull { it.id == before.id } == before) { "Generated parameter update has changed" }
            before.id to after
        }.also { require(it.map { update -> update.first }.distinct().size == it.size) { "Duplicate generation parameter update" } }.toMap()
        val parameters = input.parameters.map { updates[it.id] ?: it } + additions.filter { addition -> input.parameters.none { it.id == addition.id } }
        var model = input.copy(parameters = parameters)
        val changes = command.getValue("meshes").jsonArray.map { it.jsonObject }
        require(changes.map { it.getValue("id").jsonPrimitive.content }.distinct().size == changes.size) { "Duplicate generation transition mesh" }
        val byId = changes.associateBy { it.getValue("id").jsonPrimitive.content }
        model = model.copy(drawables = model.drawables.map { drawable ->
            val change = byId[drawable.id.raw] ?: return@map drawable
            val mesh = requireNotNull(drawable.mesh)
            require(RasterMeshJournal.fingerprint(mesh) == change.getValue("expected").jsonPrimitive.content &&
                drawable.parentDeformerId?.raw == change.getValue("parent").jsonPrimitive.contentOrNull) { "Rig generation transition topology or parent changed" }
            val geometry = change["geometry"]?.takeIf { it != JsonNull }?.jsonObject?.let { encoded ->
                RasterMeshCreation.decodeGrid(encoded, model) { form -> MeshDeltaForm(form.jsonArray.map { it.jsonPrimitive.float }.toFloatArray().also {
                    require(it.size == mesh.positions.size && it.all(Float::isFinite)) { "Invalid migrated generation keyform" }
                }) }
            }
            val channels = GeneratedRigJournalCodec.channels(change.getValue("channels").jsonObject, model)
            // Blend forms refer to the grid at the old parameter defaults. A generated switch
            // range update can move that default beyond the old channel's keys, so rebasing
            // against the already updated definitions would silently change the author's delta.
            val before = meshGridDefaultDeltas(drawable) { id -> input.parameters.singleOrNull { it.id == id }?.default ?: 0f } ?: FloatArray(mesh.positions.size)
            val replacement = drawable.copy(geometryGrid = geometry, channelGrids = channels)
            val after = meshGridDefaultDeltas(replacement) { id -> parameters.singleOrNull { it.id == id }?.default ?: 0f } ?: FloatArray(mesh.positions.size)
            fun scalar(d: Drawable, channel: FormChannel, definitions: List<Parameter>) = d.channelGrids.scalarAt(channel,
                if (channel == FormChannel.OPACITY) d.opacity else d.drawOrder,
                paramValue = { id -> definitions.singleOrNull { it.id == id }?.default ?: 0f })
            fun color(d: Drawable, channel: FormChannel, definitions: List<Parameter>) = d.channelGrids.colorAt(channel,
                if (channel == FormChannel.MULTIPLY_COLOR) d.multiplyColor else d.screenColor,
                paramValue = { id -> definitions.singleOrNull { it.id == id }?.default ?: 0f })
            val beforeMultiply = color(drawable, FormChannel.MULTIPLY_COLOR, input.parameters); val afterMultiply = color(replacement, FormChannel.MULTIPLY_COLOR, parameters)
            val beforeScreen = color(drawable, FormChannel.SCREEN_COLOR, input.parameters); val afterScreen = color(replacement, FormChannel.SCREEN_COLOR, parameters)
            fun adjust(value: ColorRgb, old: ColorRgb, next: ColorRgb) = ColorRgb(value.red - old.red + next.red, value.green - old.green + next.green, value.blue - old.blue + next.blue)
            replacement.copy(blendShapes = drawable.blendShapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
                form?.let { MeshForm(FloatArray(form.positionDeltas.size) { index -> form.positionDeltas[index] - before[index] + after[index] },
                    form.drawOrder - scalar(drawable, FormChannel.DRAW_ORDER, input.parameters) + scalar(replacement, FormChannel.DRAW_ORDER, parameters),
                    form.opacity - scalar(drawable, FormChannel.OPACITY, input.parameters) + scalar(replacement, FormChannel.OPACITY, parameters),
                    adjust(form.multiplyColor, beforeMultiply, afterMultiply), adjust(form.screenColor, beforeScreen, afterScreen)) }
            }) }, maskedBy = change.getValue("masks").jsonArray.map { DrawableId(it.jsonPrimitive.content) }.also { masks ->
                require(masks.all { id -> model.drawables.any { it.id == id } }) { "Generation transition mask is missing" }
            }, invertMask = change.getValue("invert_mask").jsonPrimitive.boolean,
                isVisible = change.getValue("visible").jsonPrimitive.boolean)
        })
        require(byId.keys.all { id -> model.drawables.any { it.id.raw == id } }) { "Generation transition mesh is missing" }
        return model
    }
}
