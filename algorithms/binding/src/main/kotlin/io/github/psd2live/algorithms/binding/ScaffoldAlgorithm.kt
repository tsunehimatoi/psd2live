package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.withParametersSyncedFromTree
import org.umamo.runtime.model.*

/** Source-frame hierarchy algorithm executable in process or in an external worker. */
class ScaffoldAlgorithm : ContextualModifierAlgorithmPlugin {
    override fun evaluate(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext, checkpoint: () -> Unit): RigIR {
        checkpoint()
        require(instance.version in 1..2) { "Unsupported scaffold algorithm version" }
        require(context.values["schema"]?.jsonPrimitive?.content == "scaffold/1") { "Missing scaffold author inputs" }
        val template = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue("template").read()))
        val materials = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue("materials").read())).drawables.associateBy { it.id }
        val canonical = context.values.getValue("canonical_targets").jsonObject
        val projections = HashMap<String, PuppetModel>()
        val source = object : ScaffoldBinding.Source {
            override fun scaffoldFor(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit): PuppetModel {
                checkpoint()
                return projections.getOrPut(target.id) {
                    val id = canonical.getValue(target.id).jsonPrimitive.content
                    if (id == target.id) template else ModelIdRemap(drawables = mapOf(DrawableId(id) to DrawableId(target.id))).model(template)
                }
            }
            override fun originalMaterial(actual: Drawable, desired: Drawable) =
                ScaffoldBinding.isOriginalMaterial(actual, desired, materials[actual.id])
        }
        val result = ScaffoldBinding.apply(PuppetIr.toPuppet(input), instance.copy(type = "rig.scaffold"), source, checkpoint)
        return PuppetIr.toIr(result.withParametersSyncedFromTree()).copy(
            textures = input.textures, physics = input.physics, clips = input.clips, parameterRoles = input.parameterRoles,
            restPose = input.restPose, advanced = input.advanced, hitAreas = input.hitAreas)
    }

    companion object {
        /** Capture immutable binary model resources once; no live source or application callbacks cross the boundary. */
        fun capture(template: PuppetModel, materials: PuppetModel, canonicalTargets: Map<String, String>): ModifierAlgorithmContext {
            val sourceIds = canonicalTargets.values.toHashSet()
            // The executor reads source hierarchy and target rest geometry, not texture inventory,
            // editor metadata or source appearance. Keep full part order for materialized aliases.
            val frames = PuppetModel(template.parameters, template.parts, template.deformers,
                template.drawables.filter { it.id.raw in sourceIds }.map {
                    Drawable(it.id, it.id.raw, it.parentDeformerId, BlendMode.Normal, emptyList(), it.mesh, it.geometryGrid)
                }, template.rootChildren, template.rootPartId)
            // Ordinary material is used only for exact position/UV/topology comparisons.
            val original = PuppetModel(emptyList(), emptyList(), emptyList(),
                materials.drawables.filter { it.id.raw in canonicalTargets }.map {
                    Drawable(it.id, it.id.raw, null, BlendMode.Normal, emptyList(), it.mesh, null)
                }, emptyList(), null)
            return ModifierAlgorithmContext(buildJsonObject {
                put("schema", "scaffold/1")
                put("canonical_targets", JsonObject(canonicalTargets.mapValues { JsonPrimitive(it.value) }))
            }, resources = mapOf(
                "template" to ModifierAlgorithmResource(RigIrBinary.encode(PuppetIr.toIr(frames))),
                "materials" to ModifierAlgorithmResource(RigIrBinary.encode(PuppetIr.toIr(original))),
            ))
        }
    }
}
