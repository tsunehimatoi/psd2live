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
        val schema = context.values["schema"]?.jsonPrimitive?.content
        require(schema in setOf("scaffold/1", "scaffold/2", "scaffold/3", "scaffold/4", "scaffold/5")) { "Missing scaffold author inputs" }
        val captured = if (schema == "scaffold/5") {
            require("template" !in context.resources) { "Source scaffold does not accept a generated template" }
            PuppetModel(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)
        } else PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue("template").read()))
        val template = if (schema != "scaffold/1") {
            require(captured.parts.isEmpty() && captured.rootChildren.isEmpty() && captured.rootPartId == null) {
                "Source organization must be computed by the scaffold plugin"
            }
            val organization = ScaffoldPartsContext.build(context.values.getValue("organization").jsonObject, checkpoint)
            val frames = if (schema in setOf("scaffold/3", "scaffold/4", "scaffold/5")) {
                require(captured.deformers.none { ScaffoldFramesContext.owns(it.id) }) {
                    "Source body and head frames must be computed by the scaffold plugin"
                }
                ScaffoldFramesContext.build(context.values.getValue("frames").jsonObject, checkpoint)
            } else emptyList()
            val branches = if (schema in setOf("scaffold/4", "scaffold/5")) {
                require(captured.deformers.isEmpty()) { "Source structure must be computed by the scaffold plugin" }
                ScaffoldBranchesContext.build(context.values.getValue("branches").jsonObject, frames, checkpoint)
            } else null
            val drawables = if (schema == "scaffold/5")
                ScaffoldMeshContext.build(context, requireNotNull(branches).parents, checkpoint) else captured.drawables
            captured.copy(parts = organization.parts, rootChildren = organization.rootChildren,
                deformers = frames + (branches?.deformers ?: captured.deformers), drawables = drawables)
        } else captured
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
        fun captureSource(materials: PuppetModel, canonicalTargets: Map<String, String>, organization: JsonObject,
                          frames: JsonObject, branches: JsonObject, meshes: ModifierAlgorithmContext): ModifierAlgorithmContext =
            ModifierAlgorithmContext(buildJsonObject {
                put("schema", "scaffold/5")
                put("canonical_targets", JsonObject(canonicalTargets.mapValues { JsonPrimitive(it.value) }))
                put("organization", organization)
                put("frames", frames)
                put("branches", branches)
                put("mesh_inputs", meshes.values.getValue("mesh_inputs"))
            }, resources = mapOf("materials" to materialsResource(materials, canonicalTargets.keys),
                "source_meshes" to meshes.resources.getValue("source_meshes")))

        private fun materialsResource(materials: PuppetModel, targets: Set<String>): ModifierAlgorithmResource {
            val original = PuppetModel(emptyList(), emptyList(), emptyList(),
                materials.drawables.filter { it.id.raw in targets }.map {
                    Drawable(it.id, it.id.raw, null, BlendMode.Normal, emptyList(), it.mesh, null)
                }, emptyList(), null)
            return ModifierAlgorithmResource(RigIrBinary.encode(PuppetIr.toIr(original)))
        }

        /** Capture immutable binary model resources once; no live source or application callbacks cross the boundary. */
        fun capture(template: PuppetModel, materials: PuppetModel, canonicalTargets: Map<String, String>,
                    organization: JsonObject? = null, frames: JsonObject? = null, branches: JsonObject? = null): ModifierAlgorithmContext {
            require(branches == null || frames != null) { "Source branches require body and head measurements" }
            require(frames == null || organization != null) { "Source frames require source organization" }
            val sourceIds = canonicalTargets.values.toHashSet()
            // The executor reads source hierarchy and target rest geometry, not texture inventory,
            // editor metadata or source appearance. Keep full part order for materialized aliases.
            val model = PuppetModel(template.parameters, if (organization == null) template.parts else emptyList(),
                if (branches != null) emptyList() else template.deformers.filter { frames == null || !ScaffoldFramesContext.owns(it.id) },
                template.drawables.filter { it.id.raw in sourceIds }.map {
                    Drawable(it.id, it.id.raw, it.parentDeformerId, BlendMode.Normal, emptyList(), it.mesh, it.geometryGrid)
                }, if (organization == null) template.rootChildren else emptyList(), if (organization == null) template.rootPartId else null)
            return ModifierAlgorithmContext(buildJsonObject {
                put("schema", if (organization == null) "scaffold/1" else if (frames == null) "scaffold/2" else if (branches == null) "scaffold/3" else "scaffold/4")
                organization?.let { put("organization", it) }
                frames?.let { put("frames", it) }
                branches?.let { put("branches", it) }
                put("canonical_targets", JsonObject(canonicalTargets.mapValues { JsonPrimitive(it.value) }))
            }, resources = mapOf(
                "template" to ModifierAlgorithmResource(RigIrBinary.encode(PuppetIr.toIr(model))),
                "materials" to materialsResource(materials, canonicalTargets.keys),
            ))
        }
    }
}
