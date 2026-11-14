package io.github.psd2live.project

import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigEditOverlay
import kotlinx.serialization.json.JsonObject
import org.umamo.format.art.*

/** Immutable aggregate captured by each append-only history node. Raster buffers are never mutated. */
data class WorkspaceDocument(
	val source: SourceArt,
	val layerVisibility: Map<String, Boolean>,
	val deletedLayerIds: Set<String>,
	val layerOverrides: Map<String, LayerClassificationOverride>,
	val parentOverrides: Map<String, String?>,
	val rigEdits: RigEditOverlay,
    val settings: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
	val meshOverrides: Map<String, io.github.psd2live.core.MeshSettings> = emptyMap(),
	/** Original raster geometry used to generate the rig when subsequent paint keeps its bindings. */
	val generationSource: SourceArt? = null,
	/** Pixels at the last explicit mesh rebuild, used by generated contour textures. */
	val meshSource: SourceArt? = null,
	/**
	 * Per-layer texture settings by source layer ID (density, lock, atlas pin). Absent or default entries mean
	 * the automatic atlas budget; the atlas packer reads them through [PipelineConfig.textureOverrides].
	 */
	val textureOverrides: Map<String, TextureOverride> = emptyMap(),
	/** Materials, user tags and generation state ([WorkspaceLayout]); empty in documents written before Layout. */
	val layout: WorkspaceLayout = WorkspaceLayout.Empty,
) {
	/** [textureOverrides] without entries that change nothing, as stored and hashed. */
	val storedTextureOverrides: Map<String, TextureOverride>
		get() = if (textureOverrides.values.none { it.isDefault }) textureOverrides else textureOverrides.filterValues { !it.isDefault }
}

/**
 * The document stores raw v1 settings: what the user or Agent chose, never the value generation derived from
 * them. [WorkspaceSettingsPolicy.effective] is applied only where generation, export or queries consume them.
 */
internal fun WorkspaceDocument.rawConfig(base: PipelineConfig = PipelineConfig()): PipelineConfig =
    WorkspaceSettingsCodec.decode(settings, base).copy(
        layerVisibility = layerVisibility,
        deletedLayerIds = deletedLayerIds,
        layerOverrides = layerOverrides,
        parentOverrides = parentOverrides,
        rigEdits = rigEdits,
        meshOverrides = meshOverrides,
        generationSource = generationSource,
        meshSource = meshSource,
        atlasBudget = WorkspaceSettingsCodec.decodeAtlasBudget(settings),
        textureOverrides = storedTextureOverrides,
        atlasArrangement = AtlasArrangementCodec.decode(settings),
        allowEmptyArt = true,
        deferRig = !layout.generation.generated,
        rigPendingLayerIds = (if (layout.generation.generated) layout.generation.pendingLayerIds
            else source.layers.mapTo(LinkedHashSet()) { it.id.raw }) + layout.referenceLayerIds,
    )

/** The document, rather than a renderer or UI projection, supplies all durable generation inputs. */
internal fun WorkspaceDocument.config(base: PipelineConfig = PipelineConfig()): PipelineConfig =
    WorkspaceSettingsPolicy.effective(rawConfig(base))

/** The one raw-to-effective rule for the three settings that other settings gate. */
internal object WorkspaceSettingsPolicy {
    /** The generated-motion switches the desktop has always kept exportMotions in step with. */
    fun motionOptionOn(config: PipelineConfig): Boolean = config.motionIdle || config.motionBlink ||
        config.motionNod || config.motionShake || config.motionSkeleton

    fun hasMotion(config: PipelineConfig): Boolean =
        (config.motionBasic && (config.motionIdle || config.motionBlink || config.motionNod || config.motionShake)) ||
            config.motionSkeleton || config.rigEdits.motionClips.any { it.builtin == null && it.enabled }

    /**
     * Mesh-only gates deformers, motions and physics. A raw false is honoured except where v1 files cannot tell
     * it from the old derived value: exportMotions=false with every generated motion off was written by the
     * desktop itself, and custom clips still exported then, so that combination keeps exporting them.
     */
    fun effective(raw: PipelineConfig): PipelineConfig = raw.copy(
        generateDeformers = if (raw.rigEdits.importedCmo3 != null) raw.generateDeformers else !raw.meshOnly && raw.generateDeformers,
        exportMotions = !raw.meshOnly && hasMotion(raw) && (raw.exportMotions || !motionOptionOn(raw)),
        generatePhysics = raw.generatePhysics && !raw.meshOnly,
    )

    /** Undo [effective] on a config generation returned, so it can be stored as the document's raw settings. */
    fun restoreRaw(generated: PipelineConfig, raw: PipelineConfig): PipelineConfig = generated.copy(
        meshOnly = raw.meshOnly, generateDeformers = raw.generateDeformers,
        exportMotions = raw.exportMotions, generatePhysics = raw.generatePhysics)
}
