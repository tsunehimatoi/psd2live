package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Deformer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.DeformerId

object MouthOutlineV2Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthOutlineV2Algorithm.ENTRYPOINT to MouthOutlineV2Algorithm.VERSION) to MouthOutlineV2Algorithm(),
    ))
}

/** Source-only outline for a legacy mouth under a static scaffold. */
class MouthOutlineV2Algorithm : ReadingModifierAlgorithmPlugin {
    @Serializable
    data class Owner(
        val canonical: String,
        val parent: String,
        val frame: ModifierSourceBounds,
        val motionFrame: ModifierSourceBounds,
        val aperture: ModifierSourceBounds,
        val texture: Texture,
    )

    @Serializable
    data class Texture(
        val page: Int,
        val x: Float,
        val y: Float,
        val scaleX: Float,
        val scaleY: Float,
        val angle: Float,
        val width: Int,
        val height: Int,
        val originX: Float,
        val originY: Float,
        val unitsX: Float,
        val unitsY: Float,
    ) {
        fun frame() = MouthOutlineLegacyCandidate.TextureFrame(AtlasPlacement(page, x, y, scaleX, scaleY, angle),
            width, height, originX, originY, unitsX, unitsY)
    }

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        val context = input.context
        val values = context.values
        require(instance.version == VERSION && values.keys == setOf("schema", "outline", "owners", "source_meshes") &&
            values["schema"] == JsonPrimitive(SCHEMA)) { "Invalid mouth outline v2 inputs" }
        val owners = Json.decodeFromJsonElement<Map<String, Owner>>(values.getValue("owners"))
        require(owners.isNotEmpty()) { "Mouth outline v2 requires a readable whole mouth" }
        val scaffoldId = instance.config.getValue("scaffold").jsonPrimitive.content
        val scaffold = context.upstream.singleOrNull { it.type == "rig.scaffold" && it.instanceId == scaffoldId && it.version == 2 }
        val mouth = context.upstream.singleOrNull { it.type == "mouth/legacy" && it.version == 2 && it.config["scaffold"]?.jsonPrimitive?.content == scaffoldId }
        require(scaffold != null && mouth != null && owners.keys.all { id ->
            val target = ModifierTarget("mesh", id)
            target in scaffold.targets && target in mouth.targets
        }) { "Mouth outline v2 requires legacy mouth v2 on the same scaffold v2" }
        val digest = values.getValue("source_meshes").jsonPrimitive.content
        val raw = ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue(digest), checkpoint)
        val neutral = owners.mapValues { (id, owner) ->
            checkpoint()
            require(input.mesh(id)?.shapes?.isEmpty() == true) { "Mouth outline v2 requires owners without blend shapes" }
            require(input.parentsOfMesh(id).all { parent -> when (parent) {
                is Deformer.Warp -> parent.lattice?.axes?.isEmpty() == true && parent.shapes.isEmpty()
                is Deformer.Rotation -> parent.pivot?.axes?.isEmpty() == true && parent.shapes.isEmpty()
            } }) { "Mouth outline v2 requires a static parent chain" }
            val source = raw.getValue(owner.canonical)
            val parent = DeformerId(owner.parent)
            val frame = owner.frame
            val bounds = Bounds(frame.left, frame.top, frame.right, frame.bottom)
            val mesh = ScaffoldMesh.build(source, ScaffoldArms.Parent(parent, bounds), ScaffoldMesh.Geometry.Zero, checkpoint).first
            MouthOutlineLegacyCandidate.NeutralOwner(parent, mesh, source.rigPositions, owner.motionFrame, owner.aperture, owner.texture.frame())
        }
        val outline = ModifierAlgorithmContext(values.getValue("outline").jsonObject, context.resources - digest, context.upstream)
        // The capture already expresses the desired geometry in the retained scaffold frame. The preceding
        // legacy mouth's offsets still use its separately measured motion frame.
        val changes = MouthOutlineLegacyCandidate().changes(input, instance, neutral, checkpoint, outline)
        // The workspace v1 entry only publishes ribbon paths. Preserve that authoring boundary here.
        return changes.copy(authoring = null)
    }

    companion object {
        const val TYPE = "mouth.outline"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.6d6f7574682e6f75746c696e65.v2/outline"
        private const val SCHEMA = "mouth-outline-owner/2"

        fun capture(outline: ModifierAlgorithmContext, owners: Map<String, Owner>, sourceMeshes: ModifierAlgorithmResource): ModifierAlgorithmContext =
            ModifierAlgorithmContext(buildJsonObject {
                put("schema", SCHEMA)
                put("outline", outline.values)
                put("owners", Json.encodeToJsonElement(owners))
                put("source_meshes", sourceMeshes.digest)
            }, outline.resources + (sourceMeshes.digest to sourceMeshes))
    }
}
