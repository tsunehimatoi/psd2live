package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.MeshGeometry
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableMesh

object MouthOutlineLegacyWorker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthOutlineLegacyPackage.TYPE to 1) to MouthOutlineLegacyPackage(),
    ))
}

/** Candidate package transport. The neutral resource contains only source topology and atlas UVs. */
class MouthOutlineLegacyPackage : ReadingModifierAlgorithmPlugin {
    @Serializable
    private data class Owner(
        val parent: String?,
        val frame: ModifierSourceBounds,
        val aperture: ModifierSourceBounds,
        val page: Int?,
        val pageX: Float?,
        val pageY: Float?,
        val pageScaleX: Float?,
        val pageScaleY: Float?,
        val pageRotation: Float?,
        val width: Int,
        val height: Int,
        val offsetX: Float,
        val offsetY: Float,
        val scaleX: Float,
        val scaleY: Float,
    ) {
        fun texture() = MouthOutlineLegacyCandidate.TextureFrame(page?.let {
            AtlasPlacement(it, requireNotNull(pageX), requireNotNull(pageY), requireNotNull(pageScaleX),
                requireNotNull(pageScaleY), requireNotNull(pageRotation))
        }, width, height, offsetX, offsetY, scaleX, scaleY)
    }

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        val context = input.context
        val values = context.values
        require(values.keys == setOf("schema", "outline", "owners", "topology") && values["schema"] == JsonPrimitive(SCHEMA))
        val owners = Json.decodeFromJsonElement<Map<String, Owner>>(values.getValue("owners"))
        require(owners.keys.all { id -> context.upstream.any { provider ->
            provider.capability == "mouth/legacy" && ModifierTarget("mesh", id) in provider.targets
        } }) { "Missing legacy mouth provider for outline owner" }
        val digest = values.getValue("topology").jsonPrimitive.content
        val topology = ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue(digest), checkpoint)
        require(topology.keys == owners.keys)
        val neutral = owners.mapValues { (id, owner) ->
            checkpoint()
            val raw = topology.getValue(id)
            val source = raw.rigPositions
            val parent = owner.parent?.let(::DeformerId)
            val frame = owner.frame
            val positions = if (parent == null) source else FloatArray(source.size) { index ->
                if (index % 2 == 0) (source[index] - frame.left) / (frame.right - frame.left).coerceAtLeast(1e-4f)
                else (source[index] - frame.top) / (frame.bottom - frame.top).coerceAtLeast(1e-4f)
            }
            MouthOutlineLegacyCandidate.NeutralOwner(parent, DrawableMesh(positions, raw.atlasUvs, raw.triangles),
                source, frame, owner.aperture, owner.texture())
        }
        val outline = ModifierAlgorithmContext(values.getValue("outline").jsonObject, context.resources - digest, context.upstream)
        return MouthOutlineLegacyCandidate().changes(input, instance, neutral, checkpoint, outline, retainParentFrame = true)
    }

    companion object {
        const val TYPE = "psd2live.mouth-outline-source/outline"
        private const val SCHEMA = "mouth-outline-owner/1"

        fun capture(outline: ModifierAlgorithmContext, neutral: Map<String, MouthOutlineLegacyCandidate.NeutralOwner>): ModifierAlgorithmContext {
            val meshes = neutral.map { (id, owner) -> Mesh(id, id, null, offsets = null, geometry = MeshGeometry(
                Floats.wrap(owner.sourcePoints), Floats.wrap(owner.mesh.uvs), Ints.wrap(owner.mesh.indices))) }
            val resource = ModifierAlgorithmResource(RigIrBinary.encode(RigIR(Canvas(0f, 0f), emptyList(), meshes = meshes)))
            val owners = neutral.mapValues { (_, owner) ->
                val texture = owner.texture
                val page = texture.placement
                Owner(owner.parent?.raw, owner.frame, owner.aperture, page?.pageIndex, page?.positionX, page?.positionY,
                    page?.scaleX, page?.scaleY, page?.rotationDegrees, texture.width, texture.height, texture.offsetX,
                    texture.offsetY, texture.scaleX, texture.scaleY)
            }
            return ModifierAlgorithmContext(buildJsonObject {
                put("schema", SCHEMA)
                put("outline", outline.values)
                put("owners", Json.encodeToJsonElement(owners))
                put("topology", resource.digest)
            }, outline.resources + (resource.digest to resource), outline.upstream)
        }
    }
}
