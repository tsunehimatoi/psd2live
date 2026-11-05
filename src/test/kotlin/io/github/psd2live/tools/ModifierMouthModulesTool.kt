package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.*
import io.github.psd2live.ui.views.ModifierContributions
import io.github.psd2live.ui.views.modifierName
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.*
import kotlin.test.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.umamo.runtime.model.*
import org.umamo.render.eval.CpuDeformationEvaluator

/** Public procedural mouth: PSD2LIVE_TOOLS=1 ./gradlew test --tests '*ModifierMouthModulesTool'. */
class ModifierMouthModulesTool {
    @TempDir lateinit var temp: Path
    @Test fun review() = runBlocking {
        requireTools()
        data class Shape(val id: String, val tag: SemanticTag, val role: PartRole?, val x: Int, val y: Int, val w: Int, val h: Int, val color: Int, val oval: Boolean = false)
        val shapes = listOf(
            Shape("neck", SemanticTag.NECK, null, 26, 57, 12, 15, 0xffe9c4a0.toInt()),
            Shape("neck-shadow", SemanticTag.NECK, PartRole.SHADOW, 26, 57, 12, 7, 0xffcf9f83.toInt()),
            Shape("face", SemanticTag.FACE, null, 8, 4, 48, 60, 0xffe9c4a0.toInt(), true),
            Shape("mouth", SemanticTag.MOUTH_OPEN, null, 23, 34, 19, 10, 0xff562737.toInt(), true),
            Shape("teeth-top", SemanticTag.TOOTH_T, null, 25, 34, 15, 2, 0xfff8f6ed.toInt()),
            Shape("teeth-bottom", SemanticTag.TOOTH_B, null, 27, 41, 11, 2, 0xfff8f6ed.toInt()),
            Shape("tongue", SemanticTag.TONGUE, null, 28, 39, 9, 5, 0xffdb7885.toInt(), true),
            Shape("nose", SemanticTag.NOSE, null, 30, 27, 4, 3, 0xffcf9f83.toInt(), true),
            Shape("blush", SemanticTag.FACE_DETAIL, PartRole.BLUSH, 17, 30, 8, 4, 0xffeb9b99.toInt(), true),
            Shape("skin-highlight", SemanticTag.FACE_DETAIL, PartRole.HIGHLIGHT, 24, 13, 9, 2, 0xfff8e1c5.toInt(), true),
        ).map { it.copy(x = it.x * 4, y = it.y * 4, w = it.w * 4, h = it.h * 4) }
        val source = WorkspaceSourceArt(256, 288, shapes.mapIndexed { index, s ->
            val rgba = ByteArray(s.w * s.h * 4)
            for (y in 0 until s.h) for (x in 0 until s.w) {
                val pixel = (y * s.w + x) * 4
                val visible = !s.oval || ((x + .5f) / s.w * 2f - 1f).pow(2) + ((y + .5f) / s.h * 2f - 1f).pow(2) <= 1f
                rgba[pixel] = (s.color shr 16).toByte(); rgba[pixel + 1] = (s.color shr 8).toByte(); rgba[pixel + 2] = s.color.toByte()
                rgba[pixel + 3] = if (visible) 255.toByte() else 0
            }
            WorkspaceSourceLayer(LayerId(s.id), s.id, "", SourceLayerKind.Raster, true, shapes.size - index, LayerBounds(s.x, s.y, s.w, s.h),
                1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(s.w, s.h, rgba), null, null, false)
        }, emptyList())
        val config = RigModifierPipeline.ordinaryConfig(PipelineConfig(atlasSize = 512, meshSpacing = 16, exportMoc3 = false))
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), shapes.associate {
            it.id to LayerClassificationOverride(LayerType.PRESET, it.tag, Side.NONE, role = it.role)
        }, emptyMap(), config.rigEdits, WorkspaceSettingsCodec.encode(config))
        val builder = WorkspacePreviewBuilder()
        val model = builder.build(document)
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "mouth", document, model)
        val types = listOf("rig.scaffold", "face/legacy", "mouth/fitted", "mouth.capture", "mouth.tongue", "mouth.jaw", "mouth.interior-depth", "mouth.spring", "expression.cheek", "expression.gloom", "face.skin-clip", "face.neck")
        val entries = types.map { type -> buildJsonObject {
            put("type", type); put("id", type.replace('/', '.'))
            put("config", buildJsonObject { if (type != "rig.scaffold") put("scaffold", "rig.scaffold")
                val spec = RigLegacyBindings.find(type)
                spec?.module?.base?.let { put("base", it.replace('/', '.')) }
                if (spec?.related?.isNotEmpty() == true) putJsonObject("dependencies") { spec.related.forEach { put(it, it.replace('/', '.')) } }
            })
            put("targets", JsonArray(model.rig.puppet.drawables.map { buildJsonObject { put("kind", "mesh"); put("id", it.id.raw) } }))
        } }
        val capture = runtime.capture()
        val result = WorkspaceDocumentCommands(runtime).execute(capture.projectId, capture.state, "Mouth modifiers", listOf(
            WorkspaceDocumentOperation("modifier_add_group", buildJsonObject { put("group", "mouth-and-skin"); put("entries", JsonArray(entries)) })), MutationAuthor.USER).capture
        val stacked = result.model
        val recipe = RigRecipe(true, bases = RigModules.areas.associate { it.id to if (it.id == "mouth") BaseChoice("mouth/fitted") else BaseChoice(it.legacy.id, it.legacy.version) },
            modules = RigModules.modules.associate { it.id to ModuleChoice(it.id in types) })
        val native = PSD2LivePipeline().buildPreview(source, RigModifierPipeline.ordinaryConfig(document.config()).copy(meshOnly = false, generateDeformers = true,
            recipe = recipe, rigEdits = RigEditOverlay()))
        fun canvas(puppet: PuppetModel, drawable: Drawable): DrawableMesh {
            val mesh = requireNotNull(drawable.mesh)
            val points = RasterMeshJournal.TextureCoordinates(puppet, drawable).toCanvas(mesh.uvs)
            return DrawableMesh(points, points, mesh.indices)
        }
        var migrated = native.rig.puppet
        for (mesh in stacked.rig.puppet.drawables) {
            val old = native.rig.puppet.drawables.single { it.id == mesh.id }
            val target = canvas(stacked.rig.puppet, mesh)
            val plan = RasterMeshJournal.prepare(canvas(native.rig.puppet, old), target)
            val geometry = DrawableMesh(PrimitiveResidual.transfer(requireNotNull(old.mesh).positions, plan.sources),
                RasterMeshJournal.TextureCoordinates(native.rig.puppet, old).toUvs(target.uvs), requireNotNull(mesh.mesh).indices)
            migrated = RasterMeshJournal.apply(migrated, mesh.id, plan.copy(mesh = geometry))
        }
        val reference = native.copy(rig = native.rig.copy(puppet = migrated))
        assertEquals(reference.rig.puppet.drawables.associate { it.id to it.maskedBy }, stacked.rig.puppet.drawables.associate { it.id to it.maskedBy })
        val rect = Bounds(32f, 40f, 232f, 288f)
        val poses = listOf(emptyMap(), mapOf("ParamMouthOpenY" to .7f, "ParamJawOpen" to .8f),
            mapOf("ParamMouthOpenY" to .8f, "ParamTongueOut" to .8f),
            mapOf("ParamMouthOpenY" to .6f, "ParamMouthFunnel" to .8f, "ParamCheekPuff" to .5f),
            mapOf("ParamMouthOpenY" to .6f, "ParamAngleX" to 22f, "ParamAngleY" to -14f),
            mapOf("ParamCheek" to 1f), mapOf("ParamGloom" to 1f),
            mapOf("ParamMouthOpenY" to .6f, "ParamJawOpen" to .8f, "ParamMouthSpring" to .7f, "ParamCheek" to .7f, "ParamGloom" to .6f),
            mapOf("ParamAngleZ" to 25f, "ParamAngleX" to 12f, "ParamMouthOpenY" to .5f))
        val evaluator = CpuDeformationEvaluator()
        for (pose in poses) {
            val first = evaluator.evaluate(reference.rig.puppet, pose.mapKeys { ParameterId(it.key) })
            val second = evaluator.evaluate(stacked.rig.puppet, pose.mapKeys { ParameterId(it.key) })
            for ((id, points) in second.worldPositions) {
                val expected = first.worldPositions.getValue(id)
                assertTrue(points.indices.all { abs(points[it] - expected[it]) < .03f }, "${id.raw} differs at $pose")
                assertEquals(first.opacity.getValue(id), second.opacity.getValue(id), .0001f)
                val a = first.multiplyColor[id] ?: ColorRgb.MultiplyIdentity
                val b = second.multiplyColor[id] ?: ColorRgb.MultiplyIdentity
                assertTrue(abs(a.red - b.red) < .0001f && abs(a.green - b.green) < .0001f && abs(a.blue - b.blue) < .0001f, "${id.raw} multiply color differs at $pose")
                val sa = first.screenColor[id] ?: ColorRgb.ScreenIdentity
                val sb = second.screenColor[id] ?: ColorRgb.ScreenIdentity
                assertTrue(abs(sa.red - sb.red) < .0001f && abs(sa.green - sb.green) < .0001f && abs(sa.blue - sb.blue) < .0001f, "${id.raw} screen color differs at $pose")
            }
        }
        val labels = listOf("closed", "jaw", "tongue", "funnel / puff", "turn", "cheek", "gloom", "spring / moods", "neck / skin")
        sheet(listOf("native" to reference, "stack" to stacked).flatMap { (label, preview) -> poses.mapIndexed { index, pose ->
            "$label · ${labels[index]}" to Renderer(preview, 280).render(pose, rect)
        } }, File(output("modifier-mouth-modules"), "comparison.png"), columns = poses.size)
        val expected = RigIrCompiler.compile(stacked)
        WorkspaceDocumentCommands(runtime).execute(result.projectId, result.state, "Apply mouth modifiers", listOf(
            WorkspaceDocumentOperation("modifier_apply", buildJsonObject { put("id", "face.neck") })), MutationAuthor.USER)
        assertEquals(expected, RigIrCompiler.compile(builder.build(runtime.capture().document)))
        val project = SyntheticProject(temp.resolve("project"), 1, 8, 1)
        for (doc in listOf(result.document, runtime.capture().document)) {
            val revision = WorkspaceRevisions.of(doc)
            project.tree.commit(project.tree.head().node.id, doc, revision, revision, "Mouth and skin modifiers", "user")
        }
        project.store.persistHistory(SyntheticProject.PROJECT, project.tree.state())
        val saved = File(output("modifier-mouth-modules"), "mouth.psd2live").toPath()
        ProjectRepository(writeHeadCache = false).save(project.capture(), saved)
        ProjectRepository().open(saved).use { reopened ->
            assertEquals(expected, RigIrCompiler.compile(builder.build(reopened.history.head().snapshot)))
            val stackedDocument = reopened.history.selections().single { WorkspaceRevisions.of(it.snapshot) == WorkspaceRevisions.of(result.document) }.snapshot
            assertEquals(expected, RigIrCompiler.compile(builder.build(stackedDocument)))
        }
        val files = linkedMapOf<String, ByteArray>()
        io.github.psd2live.format.compile.Compiler.export(io.github.psd2live.targets.cubism.Cmo3Target(),
            RigIrCompiler.compile(runtime.capture().model, tileArt = true), io.github.psd2live.format.compile.ExportOptions("mouth")) { path, bytes ->
            files[path] = bytes
            File(output("modifier-mouth-modules"), path).also { it.parentFile.mkdirs(); it.writeBytes(bytes) }
        }
        val imported = Cmo3ModelImport.read(files.getValue("mouth.cmo3")).puppet
        assertEquals(stacked.rig.puppet.drawables.associate { it.id to it.maskedBy }, imported.drawables.associate { it.id to it.maskedBy })
        for (pose in poses) {
            val first = evaluator.evaluate(stacked.rig.puppet, pose.mapKeys { ParameterId(it.key) })
            val read = evaluator.evaluate(imported, pose.mapKeys { ParameterId(it.key) })
            for ((id, points) in first.worldPositions) {
                val actual = read.worldPositions.getValue(id)
                assertTrue(points.indices.all { abs(points[it] - actual[it]) < .03f }, "exported ${id.raw} differs at $pose")
                assertEquals(first.opacity.getValue(id), read.opacity.getValue(id), .0001f)
                val a = first.multiplyColor[id] ?: ColorRgb.MultiplyIdentity
                val b = read.multiplyColor[id] ?: ColorRgb.MultiplyIdentity
                assertTrue(abs(a.red - b.red) < .0001f && abs(a.green - b.green) < .0001f && abs(a.blue - b.blue) < .0001f, "exported ${id.raw} color differs at $pose")
            }
        }
        val physics = WorkspacePreviewPhysics()
        for (frame in 0..47) {
            val phase = frame / 47f
            val open = (1f - cos(phase * PI * 2).toFloat()) * .45f
            val pose = mapOf("ParamMouthOpenY" to open, "ParamJawOpen" to open, "ParamTongueOut" to open * .7f,
                "ParamAngleX" to sin(phase * PI * 2).toFloat() * 20f, "ParamAngleZ" to sin(phase * PI * 2).toFloat() * 20f, "ParamCheek" to phase, "ParamGloom" to (1f - phase))
            val driven = physics.step(stacked, pose.mapKeys { ParameterId(it.key) }, emptySet(), 1f / 24, true)
            assertTrue(driven.keys.any { it.raw == "ParamMouthSpring" }, "workspace playback must include the evaluated mouth pendulum")
            ImageIO.write(Renderer(stacked, 480).render(pose + driven.mapKeys { it.key.raw }, rect), "png", File(output("modifier-mouth-modules"), "mouth-${frame.toString().padStart(3, '0')}.png"))
        }
        withContext(Dispatchers.Main) {
            val state = PSD2LiveState(previewModel = stacked, rigEdits = result.document.rigEdits)
            val reports = stacked.rig.modifierReport!!.getValue("entries").jsonArray.map { it.jsonObject }
                .filter { it["type"]?.jsonPrimitive?.content in setOf("face.skin-clip", "face.neck") }
            val originalLanguage = I18n.currentLanguage
            try { PSD2LiveViewModel().use { vm ->
                for (language in AppLanguage.entries) for (theme in ThemeCatalog.builtIns) for (scale in listOf(1f, 1.5f)) {
                    I18n.setLanguage(language, persist = false)
                    val scene = ImageComposeScene(600, 1000, coroutineContext = coroutineContext) {
                        CompactToolTheme(theme.colors, uiScale = scale, fontScale = 1.1f) {
                            Column(Modifier.fillMaxSize().background(LocalToolColors.current.panelBackground)
                                .padding(Spacing.Margin).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
                                reports.forEach { report ->
                                    Text(modifierName(report.getValue("type").jsonPrimitive.content), color = LocalToolColors.current.textPrimary,
                                        style = LocalToolTypography.current.body)
                                    ModifierContributions(report, state, vm)
                                }
                            }
                        }
                    }
                    try {
                        repeat(12) { frame -> scene.render(frame * 16_000_000L).close(); yield() }
                        scene.render(208_000_000L).use { File(output("modifier-mouth-modules"), "${language.tag}-${theme.id}-$scale-relations.png")
                            .writeBytes(requireNotNull(it.encodeToData()).bytes) }
                    } finally { scene.close() }
                }
            } } finally { I18n.setLanguage(originalLanguage, persist = false) }
        }

    }
}
