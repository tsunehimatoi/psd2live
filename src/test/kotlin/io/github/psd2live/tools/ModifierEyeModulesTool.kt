package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.umamo.runtime.model.*
import org.umamo.render.eval.CpuDeformationEvaluator

/** Public procedural eye: PSD2LIVE_TOOLS=1 ./gradlew test --tests '*ModifierEyeModulesTool'. */
class ModifierEyeModulesTool {
    @Test fun review() = runBlocking {
        requireTools()
        data class Shape(val id: String, val tag: SemanticTag, val role: PartRole?, val x: Int, val y: Int, val w: Int, val h: Int, val color: Int, val oval: Boolean = false)
        val shapes = listOf(
            Shape("face", SemanticTag.FACE, null, 8, 4, 48, 56, 0xffe9c4a0.toInt(), true),
            Shape("white", SemanticTag.EYEWHITE, null, 28, 20, 22, 14, 0xfff8f6f1.toInt(), true),
            Shape("iris", SemanticTag.IRIDES, null, 35, 21, 9, 12, 0xff377c9a.toInt(), true),
            Shape("highlight", SemanticTag.IRIDES, PartRole.HIGHLIGHT, 39, 22, 3, 4, 0xffffffff.toInt(), true),
            Shape("lash", SemanticTag.EYELASH, null, 27, 19, 24, 3, 0xff292535.toInt()),
            Shape("close", SemanticTag.EYE_CLOSE, null, 28, 27, 22, 2, 0xff292535.toInt()),
            Shape("lid", SemanticTag.EYELASH, PartRole.LID, 28, 17, 22, 1, 0xff8d6055.toInt()),
        )
        val source = WorkspaceSourceArt(64, 72, shapes.mapIndexed { index, s ->
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
        val config = RigModifierPipeline.ordinaryConfig(PipelineConfig(atlasSize = 128, meshSpacing = 5, exportMoc3 = false))
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), shapes.associate {
            it.id to LayerClassificationOverride(LayerType.PRESET, it.tag, if (it.tag == SemanticTag.FACE) Side.NONE else Side.LEFT, role = it.role)
        }, emptyMap(), config.rigEdits, WorkspaceSettingsCodec.encode(config))
        val builder = WorkspacePreviewBuilder()
        val model = builder.build(document)
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "eye", document, model)
        val types = listOf("rig.scaffold", "face/legacy", "eye/fitted", "eye.wide", "eye.lid-fade", "eye.highlight", "eye.aftershock")
        val entries = types.map { type -> buildJsonObject {
            put("type", type); put("id", type.replace('/', '.'))
            put("config", buildJsonObject { if (type != "rig.scaffold") put("scaffold", "rig.scaffold")
                if (type.startsWith("eye.")) put("base", "eye.fitted")
                if (type == "eye.highlight") putJsonObject("dependencies") { put("face/legacy", "face.legacy") }
            })
            put("targets", JsonArray(model.rig.puppet.drawables.map { buildJsonObject { put("kind", "mesh"); put("id", it.id.raw) } }))
        } }
        val capture = runtime.capture()
        val result = WorkspaceDocumentCommands(runtime).execute(capture.projectId, capture.state, "Eye modifiers", listOf(
            WorkspaceDocumentOperation("modifier_add_group", buildJsonObject { put("group", "eye"); put("entries", JsonArray(entries)) })), MutationAuthor.USER).capture
        val stacked = result.model
        val recipe = RigRecipe(true, bases = RigModules.areas.associate { it.id to if (it.id == "eye") BaseChoice("eye/fitted", 2) else BaseChoice(it.legacy.id, it.legacy.version) },
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
        val rect = Bounds(21f, 12f, 58f, 40f)
        val poses = listOf(0f, .35f, 1f, 1.6f, 2f).map { mapOf("ParamEyeLOpen" to it) } +
            listOf(-25f, 25f).map { mapOf("ParamEyeLOpen" to .65f, "ParamAngleX" to it, "ParamAngleY" to -it / 2) }
        val evaluator = CpuDeformationEvaluator()
        for (pose in poses) {
            val first = evaluator.evaluate(reference.rig.puppet, pose.mapKeys { ParameterId(it.key) })
            val second = evaluator.evaluate(stacked.rig.puppet, pose.mapKeys { ParameterId(it.key) })
            for ((id, points) in second.worldPositions) {
                val expected = first.worldPositions.getValue(id)
                assertTrue(points.indices.all { abs(points[it] - expected[it]) < .03f }, "${id.raw} differs at $pose")
                assertEquals(first.opacity.getValue(id), second.opacity.getValue(id), .0001f)
            }
        }
        val labels = listOf("closed", "open 0.35", "rest 1", "wide 1.6", "wide 2", "turn left", "turn right")
        sheet(listOf("native" to reference, "stack" to stacked).flatMap { (label, preview) -> poses.mapIndexed { index, pose ->
            "$label · ${labels[index]}" to Renderer(preview, 280).render(pose, rect)
        } }, File(output("modifier-eye-modules"), "comparison.png"), columns = poses.size)
        val expected = RigIrCompiler.compile(stacked)
        WorkspaceDocumentCommands(runtime).execute(result.projectId, result.state, "Apply eye modifiers", listOf(
            WorkspaceDocumentOperation("modifier_apply", buildJsonObject { put("id", "eye.highlight") })), MutationAuthor.USER)
        assertEquals(expected, RigIrCompiler.compile(builder.build(runtime.capture().document)))
        val physics = PhysicsEngine(stacked.rig.modifierOutput!!.physics.groups.map(Physics3Json::setting),
            stacked.rig.puppet.parameters.associate { it.id.raw to PhysicsEngine.Range(it.min, it.max, it.default) })
        for (frame in 0..47) {
            val phase = frame / 47f
            val pose = mapOf("ParamEyeLOpen" to (1f - cos(phase * PI * 2).toFloat()), "ParamAngleX" to sin(phase * PI * 2).toFloat() * 25f)
            ImageIO.write(Renderer(stacked, 480).render(pose + physics.step(pose, 1f / 24), rect), "png", File(output("modifier-eye-modules"), "eye-${frame.toString().padStart(3, '0')}.png"))
        }
    }
}
