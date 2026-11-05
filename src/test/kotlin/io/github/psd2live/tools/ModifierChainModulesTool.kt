package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.*
import kotlin.test.*

/** Procedural costume and hair: PSD2LIVE_TOOLS=1 ./gradlew test --tests '*ModifierChainModulesTool'. */
class ModifierChainModulesTool {
    @TempDir lateinit var temp: Path

    @Test fun review() = runBlocking<Unit> {
        requireTools()
        data class Shape(val id: String, val tag: SemanticTag, val x: Int, val y: Int, val w: Int, val h: Int,
                         val color: Int, val side: Side = Side.NONE, val role: PartRole? = null, val oval: Boolean = false)
        val art = listOf(
            Shape("tail", SemanticTag.TAIL, 52, 57, 17, 33, 0x9b6c84, oval = true),
            Shape("wing", SemanticTag.WINGS, 43, 43, 26, 20, 0x787fa8, Side.RIGHT, oval = true),
            Shape("back hair", SemanticTag.BACK_HAIR, 12, 4, 47, 57, 0x443151, oval = true),
            Shape("animal ear", SemanticTag.EARS, 15, 0, 13, 20, 0x9b6c84, Side.LEFT, PartRole.ANIMAL, true),
            Shape("neck", SemanticTag.NECK, 29, 34, 13, 23, 0xe1ab88),
            Shape("shirt", SemanticTag.TOPWEAR, 21, 47, 30, 25, 0x497f99, oval = true),
            Shape("sleeve", SemanticTag.HANDWEAR, 14, 48, 11, 31, 0x5a91a8, Side.LEFT, oval = true),
            Shape("skirt", SemanticTag.BOTTOMWEAR, 17, 63, 40, 27, 0x7180b1, role = PartRole.SKIRT, oval = true),
            Shape("face", SemanticTag.FACE, 18, 10, 37, 35, 0xeec4a0, oval = true),
            Shape("white", SemanticTag.EYEWHITE, 36, 23, 12, 7, 0xfffff0, Side.LEFT, oval = true),
            Shape("iris", SemanticTag.IRIDES, 40, 23, 6, 8, 0x426277, Side.LEFT, oval = true),
            Shape("lash", SemanticTag.EYELASH, 36, 22, 12, 2, 0x302642, Side.LEFT),
            Shape("brow", SemanticTag.EYEBROW, 37, 18, 10, 2, 0x523659, Side.LEFT),
            Shape("mouth", SemanticTag.MOUTH, 30, 34, 12, 4, 0x904358, oval = true),
            Shape("bangs", SemanticTag.FRONT_HAIR, 14, 5, 43, 16, 0x66466c, oval = true),
            Shape("side lock", SemanticTag.FRONT_HAIR, 10, 14, 11, 42, 0x714d73, Side.RIGHT, PartRole.SIDE, true),
            Shape("ribbon", SemanticTag.NECKWEAR, 24, 48, 7, 24, 0xd48b6b, role = PartRole.RIBBON, oval = true),
            Shape("pendant", SemanticTag.NECKWEAR, 36, 48, 6, 15, 0xe7bd72, role = PartRole.PENDANT, oval = true),
        ).map { it.copy(x = it.x * 4, y = it.y * 4, w = it.w * 4, h = it.h * 4) }
        val source = WorkspaceSourceArt(288, 384, art.mapIndexed { index, shape ->
            val rgba = ByteArray(shape.w * shape.h * 4)
            for (y in 0 until shape.h) for (x in 0 until shape.w) {
                val pixel = (y * shape.w + x) * 4
                val visible = !shape.oval || ((x + .5f) / shape.w * 2f - 1f).pow(2) + ((y + .5f) / shape.h * 2f - 1f).pow(2) <= 1f
                rgba[pixel] = (shape.color shr 16).toByte(); rgba[pixel + 1] = (shape.color shr 8).toByte(); rgba[pixel + 2] = shape.color.toByte()
                rgba[pixel + 3] = if (visible) 255.toByte() else 0
            }
            WorkspaceSourceLayer(LayerId("piece-$index"), shape.id, "", SourceLayerKind.Raster, true, art.size - index,
                LayerBounds(shape.x, shape.y, shape.w, shape.h), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
                LayerRaster(shape.w, shape.h, rgba), null, null, false)
        }, emptyList())
        val config = RigModifierPipeline.ordinaryConfig(PipelineConfig(atlasSize = 1024, meshSpacing = 18, exportMoc3 = false))
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), art.mapIndexed { index, shape ->
            "piece-$index" to LayerClassificationOverride(LayerType.PRESET, shape.tag, shape.side, role = shape.role)
        }.toMap(), emptyMap(), config.rigEdits, WorkspaceSettingsCodec.encode(config))
        val builder = WorkspacePreviewBuilder()
        val empty = builder.build(document)
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "chains", document, empty)
        val bases = listOf("body/legacy", "face/legacy", "eye/fitted", "brow/fitted", "mouth/fitted", "hair/chains")
        val modules = listOf("body.bust", "body.arms", "outfit.skirt", "outfit.ribbons", "outfit.creature", "face.depth-turn")
        fun values(type: String) = when (type) {
            "hair/chains" -> mapOf("swing" to 145f, "softness" to 70f)
            "body.bust" -> mapOf("bounce" to 130f)
            "face.depth-turn" -> mapOf("turn" to 60f, "depth" to 125f)
            "outfit.skirt", "outfit.ribbons", "outfit.creature" -> mapOf("swing" to 150f)
            else -> emptyMap()
        }
        val entries = (listOf("rig.scaffold") + bases + modules).map { type -> buildJsonObject {
            put("type", type); put("id", type.replace('/', '.'))
            put("config", buildJsonObject {
                if (type != "rig.scaffold") put("scaffold", "rig.scaffold")
                val spec = RigLegacyBindings.find(type)
                if (spec?.related?.isNotEmpty() == true) putJsonObject("dependencies") { spec.related.forEach { put(it, it.replace('/', '.')) } }
                if (values(type).isNotEmpty()) put("values", JsonObject(values(type).mapValues { JsonPrimitive(it.value) }))
            })
            put("targets", JsonArray(empty.rig.puppet.drawables.map { buildJsonObject { put("kind", "mesh"); put("id", it.id.raw) } }))
        } }
        val start = runtime.capture()
        val result = WorkspaceDocumentCommands(runtime).execute(start.projectId, start.state, "Chain modifiers", listOf(
            WorkspaceDocumentOperation("modifier_add_group", buildJsonObject { put("group", "chains"); put("entries", JsonArray(entries)) })), MutationAuthor.USER).capture
        val stacked = result.model
        val stack = RigModifierStack.fromJson(result.document.rigEdits.modifierStack!!)
        val capturedSource = RigModifierSource.of(stacked)
        val chains = stack.instances.filter { RigModifierChains.kinds(it.type).isNotEmpty() }.flatMap { capturedSource.chains(it) }
        val recipe = RigRecipe(true, bases = RigModules.areas.associate { area ->
            val base = bases.firstOrNull { it.startsWith(area.id + "/") }?.let { requireNotNull(RigModules.base(it)) } ?: area.legacy
            area.id to BaseChoice(base.id, base.version, values(base.id))
        }, modules = RigModules.modules.associate { it.id to ModuleChoice(it.id in modules, it.version, values(it.id)) })
        val original = PSD2LivePipeline().buildPreview(source, RigModifierPipeline.ordinaryConfig(document.config()).copy(meshOnly = false,
            generateDeformers = true, recipe = recipe, rigEdits = RigEditOverlay()))
        val ownedByLayer = chains.associateBy { it.binding.key }
        val aliases = original.rig.generatedChains.flatMap { native ->
            val binding = RigModifierChains.Binding(native)
            val owner = ownedByLayer.getValue(binding.key)
            val stable = owner.aliases.mapKeys { owner.binding.suffix(it.key) }
            native.parameters.map { it.raw to stable.getValue(binding.suffix(it.raw)) }
        }.toMap()
        fun canvas(puppet: PuppetModel, drawable: Drawable): DrawableMesh {
            val mesh = requireNotNull(drawable.mesh)
            val points = RasterMeshJournal.TextureCoordinates(puppet, drawable).toCanvas(mesh.uvs)
            return DrawableMesh(points, points, mesh.indices)
        }
        var migrated = RigIdRemap(parameters = aliases.mapKeys { ParameterId(it.key) }.mapValues { ParameterId(it.value) }).model(original.rig.puppet)
        for (mesh in stacked.rig.puppet.drawables) {
            val old = original.rig.puppet.drawables.single { it.id == mesh.id }
            val target = canvas(stacked.rig.puppet, mesh)
            val plan = RasterMeshJournal.prepare(canvas(original.rig.puppet, old), target)
            val geometry = DrawableMesh(PrimitiveResidual.transfer(requireNotNull(old.mesh).positions, plan.sources),
                RasterMeshJournal.TextureCoordinates(original.rig.puppet, old).toUvs(target.uvs), requireNotNull(mesh.mesh).indices)
            migrated = RasterMeshJournal.apply(migrated, mesh.id, plan.copy(mesh = geometry))
        }
        val reference = original.copy(rig = original.rig.copy(puppet = migrated))
        fun dynamics(prefix: String) = aliases.filterKeys { it.startsWith(prefix) }.values.mapIndexed { index, id ->
            id to if (index % 2 == 0) .75f else -.55f
        }.toMap()
        val poses = listOf(emptyMap(), dynamics("ParamHair") + mapOf("ParamAngleX" to 22f, "ParamAngleZ" to -14f),
            dynamics("ParamCloth") + mapOf("ParamBodyAngleX" to 7f, "ParamBreath" to .8f, "ParamBustX" to .6f, "ParamArmLSwing" to -.8f),
            dynamics("ParamPart") + mapOf("ParamAngleY" to 16f, "ParamEyeLOpen" to 0f),
            dynamics("ParamHair") + dynamics("ParamCloth") + dynamics("ParamPart") + mapOf("ParamAngleX" to -45f, "ParamAngleZ" to 15f, "ParamBodyAngleX" to -6f))
        val evaluator = CpuDeformationEvaluator()
        fun compare(a: PuppetModel, b: PuppetModel, stage: String) {
            val differences = ArrayList<String>()
            for ((index, pose) in poses.withIndex()) {
                val values = pose.mapKeys { ParameterId(it.key) }
                val first = evaluator.evaluate(a, values); val second = evaluator.evaluate(b, values)
                for ((id, points) in second.worldPositions) {
                    val wanted = first.worldPositions.getValue(id)
                    val error = points.indices.maxOf { abs(points[it] - wanted[it]) }
                    if (error >= .05f) differences += "${id.raw} at pose $index: $error"
                    assertEquals(first.opacity.getValue(id), second.opacity.getValue(id), .0001f)
                }
            }
            assertTrue(differences.isEmpty(), "$stage: ${differences.joinToString()}")
        }
        compare(reference.rig.puppet, stacked.rig.puppet, "native comparison")
        val folder = output("modifier-chain-modules")
        val rect = Bounds(0f, -16f, 292f, 384f)
        val labels = listOf("rest", "hair / turn", "cloth / body", "ears / tail / wing", "combined")
        sheet(listOf("native" to reference, "stack" to stacked).flatMap { (label, preview) -> poses.mapIndexed { i, pose ->
            "$label · ${labels[i]}" to Renderer(preview, 310).render(pose, rect)
        } }, File(folder, "comparison.png"), columns = poses.size)
        val expected = RigIrCompiler.compile(stacked)
        WorkspaceDocumentCommands(runtime).execute(result.projectId, result.state, "Apply chain modifiers", listOf(
            WorkspaceDocumentOperation("modifier_apply", buildJsonObject { put("id", "face.depth-turn") })), MutationAuthor.USER)
        assertEquals(expected, RigIrCompiler.compile(builder.build(runtime.capture().document)))
        val project = SyntheticProject(temp.resolve("project"), 1, 8, 1)
        for (doc in listOf(result.document, runtime.capture().document)) {
            val revision = WorkspaceRevisions.of(doc)
            project.tree.commit(project.tree.head().node.id, doc, revision, revision, "Chain modifiers", "user")
        }
        project.store.persistHistory(SyntheticProject.PROJECT, project.tree.state())
        val saved = File(folder, "chains.psd2live").toPath()
        ProjectRepository(writeHeadCache = false).save(project.capture(), saved)
        ProjectRepository().open(saved).use { reopened ->
            assertEquals(expected, RigIrCompiler.compile(builder.build(reopened.history.head().snapshot)))
            val before = reopened.history.selections().single { WorkspaceRevisions.of(it.snapshot) == WorkspaceRevisions.of(result.document) }.snapshot
            assertEquals(expected, RigIrCompiler.compile(builder.build(before)))
        }
        val files = linkedMapOf<String, ByteArray>()
        val export = io.github.psd2live.format.compile.Compiler.export(io.github.psd2live.targets.cubism.Cmo3Target(),
            RigIrCompiler.compile(runtime.capture().model, tileArt = true), io.github.psd2live.format.compile.ExportOptions("chains")) { path, bytes ->
            files[path] = bytes
            File(folder, path).also { it.parentFile.mkdirs(); it.writeBytes(bytes) }
        }
        assertTrue(export.losses.none { it.note?.contains("KeysOutsideChannelSpan(channel=DRAW_ORDER)") == true }, export.losses.toString())
        compare(stacked.rig.puppet, Cmo3ModelImport.read(files.getValue("chains.cmo3")).puppet, "CMO3 readback")
        val clock = WorkspacePreviewPhysics()
        var peak = 0f
        repeat(60) { frame ->
            val phase = frame / 59f * (PI * 4).toFloat()
            val pose = mapOf("ParamAngleX" to sin(phase) * 45f, "ParamAngleY" to cos(phase) * 14f, "ParamAngleZ" to sin(phase * .7f) * 14f,
                "ParamBodyAngleX" to sin(phase - .3f) * 7f, "ParamBodyAngleZ" to cos(phase * .65f) * 6f,
                "ParamBreath" to (.5f + .5f * sin(phase)), "ParamEyeLOpen" to if (frame % 18 < 2) 0f else 1f)
            val driven = clock.step(stacked, pose.mapKeys { ParameterId(it.key) }, emptySet(), 1f / 24f, true)
            peak = max(peak, driven.filterKeys { it.raw in aliases.values }.values.maxOfOrNull(::abs) ?: 0f)
            ImageIO.write(Renderer(stacked, 540).render(pose + driven.mapKeys { it.key.raw }, rect), "png", File(folder, "chains-${frame.toString().padStart(3, '0')}.png"))
        }
        assertTrue(peak > .01f, "the workspace clock must drive the evaluated chain parameters")
    }
}
