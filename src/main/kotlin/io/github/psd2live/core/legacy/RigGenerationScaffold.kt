package io.github.psd2live.core.legacy

import io.github.psd2live.core.*

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/** New automatically generated objects receive their own frames; existing authored parents never move. */
internal object RigGenerationScaffold {
    const val OP = "rig_generation_scaffold"

    fun rename(model: PuppetModel, current: PuppetModel, epoch: Int): PuppetModel {
        val reserved = current.deformers.mapTo(HashSet()) { it.id.raw }
        val ids = model.deformers.associate { deformer ->
            var candidate = "Generated${epoch}_${deformer.id.raw}"
            var ordinal = 2
            while (!reserved.add(candidate)) candidate = "Generated${epoch}_${deformer.id.raw}_${ordinal++}"
            deformer.id to DeformerId(candidate)
        }
        return model.copy(deformers = model.deformers.map { deformer -> when (deformer) {
            is Deformer.Warp -> deformer.copy(id = ids.getValue(deformer.id), parent = deformer.parent?.let(ids::getValue))
            is Deformer.Rotation -> deformer.copy(id = ids.getValue(deformer.id), parent = deformer.parent?.let(ids::getValue))
        } }, drawables = model.drawables.map { it.copy(parentDeformerId = it.parentDeformerId?.let(ids::getValue)) })
    }

    fun encode(model: PuppetModel, current: PuppetModel, targetIds: Set<DrawableId>): JsonObject {
        val required = mutableSetOf<DeformerId>()
        for (drawable in model.drawables.filter { it.id in targetIds }) {
            var parent = drawable.parentDeformerId
            while (parent != null && required.add(parent)) parent = model.deformers.single { it.id == parent }.parent
        }
        return buildJsonObject {
            put("op", OP)
            put("parameters", GeneratedRigJournalCodec.parameters(model.copy(parameters = model.parameters.filter { parameter -> current.parameters.none { it.id == parameter.id } })))
            put("deformers", JsonArray(model.deformers.filter { it.id in required }.map(GeneratedRigJournalCodec::deformer)))
        }
    }

    fun replay(input: PuppetModel, command: JsonObject): PuppetModel {
        require(command.keys == setOf("op", "parameters", "deformers")) { "Invalid generation scaffold" }
        val parameters = GeneratedRigJournalCodec.parameters(command.getValue("parameters").jsonArray)
        require(parameters.map { it.id }.distinct().size == parameters.size && parameters.none { p -> input.parameters.any { it.id == p.id } }) {
            "Generation scaffold parameter already exists"
        }
        val context = input.copy(parameters = input.parameters + parameters)
        val deformers = command.getValue("deformers").jsonArray.map { GeneratedRigJournalCodec.deformer(it.jsonObject, context) }
        require(deformers.map { it.id }.distinct().size == deformers.size && deformers.none { d -> context.deformers.any { it.id == d.id } }) {
            "Generation scaffold deformer already exists"
        }
        val result = context.copy(deformers = context.deformers + deformers)
        require(deformers.all { it.parent == null || result.deformers.any { parent -> parent.id == it.parent } }) { "Generation scaffold parent is missing" }
        return result
    }
}
