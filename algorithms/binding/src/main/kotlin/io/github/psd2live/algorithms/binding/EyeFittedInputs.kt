package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Source measurements and identity references for eye/fitted. Geometry is in the scaffold's aligned space. */
@Serializable
data class EyeFittedInputs(
    val faceCenterX: Float,
    val alignment: Alignment? = null,
    val meshAlignment: Alignment? = alignment,
    val materials: Map<String, Material>,
    val artworkStamps: Map<String, String>,
    val corrections: Map<String, Map<String, EyePartOverride>> = emptyMap(),
    val moves: Map<String, Map<String, Pair<Float, Float>>> = emptyMap(),
    val parameterNames: Map<String, String>,
    val folderName: String,
    val gazeCarriers: Set<String> = emptySet(),
    val openings: Map<String, EyeOpeningAssociation.Layer> = emptyMap(),
    val tuning: Tuning = Tuning(),
) {
    @Serializable
    data class Alignment(val pivotX: Float, val pivotY: Float, val cosine: Float, val sine: Float) {
        fun point(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - pivotX
            val dy = y - pivotY
            return (pivotX + cosine * dx + sine * dy) to (pivotY - sine * dx + cosine * dy)
        }
        fun canvas(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - pivotX
            val dy = y - pivotY
            return (pivotX + cosine * dx - sine * dy) to (pivotY + sine * dx + cosine * dy)
        }
    }

    @Serializable
    data class Material(
        val canonical: String,
        val parent: String?,
        val frame: ModifierSourceBounds,
        val alignedBounds: ModifierSourceBounds,
        val alignedCentroidX: Float,
        val alignedCentroidY: Float,
        val fallbackWhites: List<ModifierSourceBounds> = emptyList(),
        val opening: EyeOpeningAssociation.Layer,
        val aligned: Boolean = true,
        val ordinaryMask: String? = null,
        val preset: Boolean = true,
        val useSourceColumns: Boolean = true,
    )

    @Serializable
    data class Tuning(
        val gazeX: Float = 10f, val gazeY: Float = 8.5f,
        val blinkDepth: Float = 38f, val blinkCorner: Float = 34f, val eyelashSquash: Float = 88f,
        val irisJellySquash: Float = 4.5f, val irisJellyStretch: Float = 11f,
    )

    fun capture(sources: ModifierSourceInputs, sourceMeshes: ModifierAlgorithmResource): ModifierAlgorithmContext {
        val source = sources.context()
        require(SOURCE_MESHES !in source.resources)
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", SCHEMA)
            put("source", source.values)
            put("eyes", Json.encodeToJsonElement(this@EyeFittedInputs))
        }, source.resources + (SOURCE_MESHES to sourceMeshes))
    }

    companion object {
        const val SCHEMA = "eye-fitted/1"
        private const val SOURCE_MESHES = "scaffold_source_meshes"

        data class Decoded(
            val input: EyeFittedInputs,
            val source: ModifierSourceInputs,
            val scaffold: Map<String, ScaffoldMesh.RawMesh>,
        )

        fun decode(context: ModifierAlgorithmContext, checkpoint: () -> Unit): Decoded {
            require(context.values.keys == setOf("schema", "source", "eyes") &&
                context.values.getValue("schema").jsonPrimitive.content == SCHEMA) { "Unsupported fitted eye input schema" }
            val inputs = Json.decodeFromJsonElement<EyeFittedInputs>(context.values.getValue("eyes"))
            val source = ModifierSourceInputs.from(ModifierAlgorithmContext(
                context.values.getValue("source").jsonObject, context.resources - SOURCE_MESHES))
            val meshes = ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue(SOURCE_MESHES), checkpoint)
            require(inputs.materials.keys == source.targets.keys &&
                inputs.materials.values.all { it.canonical in meshes }) { "Invalid fitted eye material references" }
            return Decoded(inputs, source, meshes)
        }
    }
}
