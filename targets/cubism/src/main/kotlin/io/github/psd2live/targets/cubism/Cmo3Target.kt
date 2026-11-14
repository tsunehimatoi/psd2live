package io.github.psd2live.targets.cubism

import io.github.psd2live.bridge.PuppetIr

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.ColorBlend
import io.github.psd2live.format.model.RigIR
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.raster.RasterImage
import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.interop.cmo3.Cmo3GraphIndex
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.MeshDeltaForm

/**
 * A Cubism Editor project (`.cmo3`): the baked rig with editable source layers and physics. Generator
 * intent is not kept; the editor opens the result as plain deformers and keyforms.
 *
 * A cmo3 carries editor GUIDs, which Cubism Editor expects to be unique, so the file is not byte-stable
 * across exports; its content is. The `timestamp` setting (epoch milliseconds) sets the recorded export
 * time, 0 by default. [decorate] lets the host add editor-only metadata it alone knows.
 *
 * The rig's clips go to a Cubism Animator project beside it (`<base>.can3`, see [Can3]) unless `clips` is false.
 */
public class Cmo3Target(
	private val decorate: (CModelSource) -> Unit = {},
) : ExportTarget {
	override val id: String = "cmo3"
	override val family: TargetFamily = TargetFamily.RIG
	override val description: String = "Cubism Editor project (.cmo3)"
	override val settings: List<TargetSetting> = listOf(TargetSetting.Text("timestamp", "epoch milliseconds"), TargetSetting.CLIPS)
	override val capabilities: CapabilityProfile = CapabilityProfile(
		warpLattice = true, parameterGrid = 3, blendShapes = true, timeline = true,
		physics = PhysicsSupport.PARAMETER_PENDULUM, blendModes = ColorBlend.entries.toSet(),
		masks = MaskSupport.TEXTURE_ALPHA, keyedDrawOrder = true, glue = true,
	)

	/**
	 * The converted graph and its lowering notices, before serialization. A layer whose source raster is not its
	 * canvas rectangle at one pixel per canvas unit is written as [Cmo3LayerArt] (the internal `layer_art`
	 * setting) says, at canvas resolution by default.
	 */
	public fun convert(ir: RigIR, options: ExportOptions): Cmo3Conversion.Result {
		val textured = materializeTextureSources(ir)
		val layerArt = Cmo3LayerArt.of(options.setting(Cmo3LayerArt.SETTING))
		val lowered = if (layerArt == Cmo3LayerArt.CANVAS) Cmo3LayerArtLowering.canvasResolution(textured) else textured
		val native = if (layerArt == Cmo3LayerArt.NATIVE) Cmo3LayerArtLowering.nativeResolution(textured) else null
		return convertLowered(lowered, options) { root -> native?.invoke(root); decorate(root) }
	}

	/** CMO3 stores concrete texture bindings, not the runtime's mesh-to-mesh binding alias. */
	private fun materializeTextureSources(ir: RigIR): RigIR {
		if (ir.meshes.none { it.textureSource != null }) return ir
		val originals = ir.meshes.associateBy { it.id }
		val bindings = ir.textures.bindings.toMutableMap()
		val meshes = ir.meshes.map { mesh ->
			val sourceId = mesh.textureSource ?: return@map mesh
			val source = requireNotNull(originals[sourceId]) { "Missing texture source $sourceId for ${mesh.id}" }
			// Resolve against the input, never against already materialized meshes: the runtime
			// chooses the referenced mesh's binding once and keeps the consumer's own UVs/forms.
			val page = ir.textures.bindings[sourceId] ?: source.page
			bindings[mesh.id] = page
			mesh.copy(textureSource = null, page = page, tile = source.tile)
		}
		return ir.copy(meshes = meshes, textures = ir.textures.copy(bindings = bindings))
	}

	/**
	 * The texture-size losses of writing [ir]: one per tile whose atlas texels per canvas unit exceed its cmo3
	 * layer's, which Cubism Editor's atlas regeneration would drop. None for `layer_art=native`.
	 */
	public fun textureLosses(ir: RigIR, options: ExportOptions): List<LossEntry> =
		if (Cmo3LayerArt.of(options.setting(Cmo3LayerArt.SETTING)) == Cmo3LayerArt.NATIVE) emptyList()
		else Cmo3LayerArtLowering.losses(Cmo3LayerArtLowering.canvasResolution(materializeTextureSources(ir)))

	/** [textureLosses] in one line for a log; null when there are none. */
	public fun textureLossSummary(ir: RigIR, options: ExportOptions): String? =
		if (Cmo3LayerArt.of(options.setting(Cmo3LayerArt.SETTING)) == Cmo3LayerArt.NATIVE) null
		else Cmo3LayerArtLowering.summary(Cmo3LayerArtLowering.canvasResolution(materializeTextureSources(ir)))

	/**
	 * The can3 of [ir]'s clips on [converted], the cmo3 of the same export; null when `clips` is false or no clip
	 * has a curve the Animator holds.
	 */
	public fun can3(ir: RigIR, options: ExportOptions, converted: Cmo3Conversion.Result): ByteArray? {
		if (!options.flag(TargetSetting.CLIPS.key, true)) return null
		val parts = Cmo3GraphIndex(converted.model.root as CModelSource).partByIdStr
		val partGuids = parts.mapNotNull { (id, part) -> Cmo3Import.uuidOf(part.guid)?.let { id to it } }.toMap()
		return Can3.write(ir, options.baseName, partGuids)
	}

	/** What [can3] leaves out of [ir]'s clips. */
	public fun can3Losses(ir: RigIR, options: ExportOptions): List<LossEntry> =
		if (options.flag(TargetSetting.CLIPS.key, true)) Can3.losses(ir) else emptyList()

	private fun convertLowered(ir: RigIR, options: ExportOptions, decorate: (CModelSource) -> Unit): Cmo3Conversion.Result {
		val puppet = PuppetIr.toPuppet(ir)
		// CMO3 stores rest meshes in canvas space, but evaluates mesh forms in their parent's space.
		// Keep an explicit static form for unkeyed children before rebasing their rest mesh for export.
		val withStaticChildren = puppet.copy(drawables = puppet.drawables.map { drawable ->
			val mesh = drawable.mesh
			if (drawable.parentDeformerId == null || mesh == null || drawable.geometryGrid != null) drawable
			else drawable.copy(geometryGrid = KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), MeshDeltaForm(FloatArray(mesh.positions.size))))))
		})
		val exportPuppet = restMeshesToCanvasSpace(withStaticChildren, ir.restPose.mapKeys { ParameterId(it.key) })
		val pages = ir.textures.pages.mapIndexed { index, page ->
			require(page.png.size > 0) { "Texture page $index has no pixels" }
			Cmo3Conversion.AtlasPage(page.png.shared(), page.width, page.height)
		}
		val art = ir.textures.tileArt.associate { AtlasTileId(it.tile) to RasterImage(it.width, it.height, it.rgba.shared()) }
		val converted = Cmo3Conversion.freshCmo3(
			puppet = exportPuppet,
			pages = pages,
			pageIndexByDrawableId = ir.textures.bindings,
			modelName = options.baseName,
			nowMillis = options.setting("timestamp")?.toLongOrNull() ?: 0L,
			obfuscateKey = 0x42,
			tileRasters = { tile -> art[tile] },
		)
		val root = converted.model.root as CModelSource
		if (ir.physics.groups.isNotEmpty()) Cmo3Physics.inject(root, ir.physics.groups, ir.physics.fps?.toInt() ?: 0)
		decorate(root)
		return converted
	}

	/** Authoring vertex groups and runtime sidecars which CMO3 cannot store. */
	public fun sidecarLosses(ir: RigIR): List<LossEntry> = buildList {
		ir.authoring.vertexGroups.forEach { group ->
			add(LossEntry(group.mesh, Feature.STRUCTURE, Handling.DROPPED,
				note = "Vertex group ${group.name} (${group.kind}) is not stored in cmo3"))
		}
		if (ir.advanced.expressions.isNotEmpty()) add(LossEntry(ir.advanced.expressions.joinToString(",") { it.id }, Feature.STRUCTURE, Handling.DROPPED,
			note = "Expressions are not stored in cmo3 or can3; the moc3 export writes them as exp3"))
		if (ir.advanced.pose != null) add(LossEntry("*", Feature.STRUCTURE, Handling.DROPPED,
			note = "The pose is not stored in cmo3; the moc3 export writes it as pose3"))
	}

	override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
		val converted = convert(ir, options)
		val can3 = can3(ir, options, converted)
		val losses = CapabilityScan.scan(ir, capabilities, options) + Moc3Target.losses(ir, options, converted.report.notices) +
			textureLosses(ir, options) + can3Losses(ir, options) + sidecarLosses(ir)
		return object : LoweredExport {
			override val losses: List<LossEntry> = losses
			override fun write(sink: OutputSink) {
				sink.write("${options.baseName}.cmo3", Cmo3.write(converted.model))
				can3?.let { sink.write("${options.baseName}.can3", it) }
			}
		}
	}
}
