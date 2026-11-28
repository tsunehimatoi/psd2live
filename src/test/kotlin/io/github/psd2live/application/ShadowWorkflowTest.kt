package io.github.psd2live.application

import io.github.psd2live.algorithms.shadow.CastShadowConfig
import io.github.psd2live.algorithms.shadow.FormShadowConfig
import io.github.psd2live.algorithms.shadow.ShadowWorldConfig
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.project.*
import io.github.psd2live.targets.cubism.Cmo3Target
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.format.art.LayerId
import java.nio.file.Path
import java.util.prefs.Preferences
import kotlin.test.*

class ShadowWorkflowTest {
    @TempDir lateinit var temp: Path

    private suspend fun createRuntime(layerCount: Int = 3): Pair<WorkspaceRuntime<RigPreviewModel>, WorkspaceDocumentCommands> {
        val source = SyntheticProject(temp.resolve("source"), layerCount, 12, 1).tree.head().snapshot
        val config = PipelineConfig(
            atlasSize = 256,
            meshSpacing = 12,
            runtimeTarget = RuntimeTarget.Cubism53,
            rigEdits = RigEditOverlay(
                parameterEdits = listOf(
                    RigParameterEdit("ParamAngleX", "Angle X", -30f, 30f, 0f, created = true),
                    RigParameterEdit("ParamAngleY", "Angle Y", -30f, 30f, 0f, created = true),
                ),
                modifierStack = RigModifierStack().toJson(),
            ),
        )
        val document = source.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>(
            { builder.build(it) },
            rebuildFrom = { doc, previous -> builder.build(doc, previous) },
        )
        val model = builder.build(document)
        runtime.install(runtime.state.value.state, "project", document, model)
        return runtime to WorkspaceDocumentCommands(runtime)
    }

    @Test
    fun `shadow put world_set apply delete and export round-trip`(): Unit = runBlocking {
        val (runtime, commands) = createRuntime(3)
        val drawables = runtime.capture().model.rig.puppet.drawables.map { it.id.raw }
        assertEquals(3, drawables.size)
        val face = drawables[0]
        val bang1 = drawables[1]
        val bang2 = drawables[2]

        suspend fun exec(op: String, req: JsonObject) = runtime.capture().let {
            commands.execute(it.projectId, it.state, op, listOf(WorkspaceDocumentOperation(op, req)), MutationAuthor.USER)
        }

        // 1. Add cast shadow (2 bangs -> 1 face)
        exec("shadow_put", buildJsonObject {
            put("kind", "cast")
            put("name", "Bangs Cast")
            put("casters", JsonArray(listOf(JsonPrimitive(bang1), JsonPrimitive(bang2))))
            put("receivers", JsonArray(listOf(JsonPrimitive(face))))
        })
        // 2. Add form shadow on face
        exec("shadow_put", buildJsonObject {
            put("kind", "form")
            put("name", "Face Side")
            put("targets", JsonArray(listOf(JsonPrimitive(face))))
        })

        val afterAdd = runtime.capture()
        val stack = RigModifierStack.fromJson(requireNotNull(afterAdd.document.rigEdits.modifierStack))
        assertTrue(stack.instances.any { it.type == ShadowAlgorithmPlugin.TYPE_WORLD })
        val castInst = stack.instances.single { it.type == ShadowAlgorithmPlugin.TYPE_CAST }
        val formInst = stack.instances.single { it.type == ShadowAlgorithmPlugin.TYPE_FORM }

        val puppetAfterAdd = afterAdd.model.rig.puppet
        assertTrue(
            puppetAfterAdd.parts.any { it.groupMode is PartGroupMode.Isolated && it.composite.maskedBy.any { m -> m.raw == face } },
            "Expected isolated part; parts=${puppetAfterAdd.parts.map { it.id.raw to it.groupMode }}, report=${afterAdd.model.rig.modifierReport}",
        )
        assertTrue(puppetAfterAdd.drawables.any { it.invertMask })

        exec("shadow_world_set", buildJsonObject { put("composite_mode", "compat_merged") })
        val compatible = runtime.capture().model.rig.puppet
        assertFalse(compatible.parts.any { it.groupMode is PartGroupMode.Isolated && it.composite.maskedBy.any { m -> m.raw == face } })
        val merged = compatible.drawables.single { it.id.raw == castInst.outputId("merged:$face") }
        assertFalse(merged.invertMask)
        assertEquals(listOf(bang1, bang2).map { castInst.outputId("cast:$it") }.toSet(), merged.maskedBy.map { it.raw }.toSet())
        exec("shadow_world_set", buildJsonObject { put("composite_mode", "auto") })

        // 3. Update world lighting and verify preview compare toggle
        exec("shadow_world_set", buildJsonObject {
            put("azimuth", -40.0)
            put("elevation", 30.0)
            put("preview_enabled", false)
        })
        assertFalse(runtime.capture().model.rig.puppet.drawables.any { it.invertMask })

        exec("shadow_world_set", buildJsonObject {
            put("preview_enabled", true)
        })
        assertTrue(runtime.capture().model.rig.puppet.drawables.any { it.invertMask })

        // 4. Export CMO3 & verify serialization round-trip
        val ir = RigIrCompiler.compile(runtime.capture().model, tileArt = true)
        val cmo3Bytes = Cmo3.write(Cmo3Target().convert(ir, ExportOptions("shadow")).model)
        assertTrue(cmo3Bytes.isNotEmpty())
        Cmo3.read(cmo3Bytes)

        // 5. Bake/apply cast shadow into ordinary layers
        exec("shadow_apply", buildJsonObject { put("id", castInst.id) })
        val afterApply = runtime.capture()
        val appliedStack = RigModifierStack.fromJson(requireNotNull(afterApply.document.rigEdits.modifierStack))
        assertTrue(castInst.id in appliedStack.appliedNodes)
        assertTrue(afterApply.model.rig.puppet.parts.any { it.groupMode is PartGroupMode.Isolated })
        exec("shadow_world_set", buildJsonObject { put("preview_enabled", false) })
        assertTrue(runtime.capture().model.rig.puppet.parts.any { it.groupMode is PartGroupMode.Isolated })
        exec("shadow_world_set", buildJsonObject { put("preview_enabled", true) })

        // 6. Save & reopen ProjectFormatV2
        val revision = WorkspaceRevisions.of(afterApply.document)
        val store = WorkspaceStore(temp.resolve("shadow-project"))
        store.persistHistory("project", WorkspaceHistoryTree(afterApply.document, revision, revision).state())
        val reopened = WorkspaceStore(store.root).loadHistory("project")!!.head().snapshot
        assertEquals(afterApply.document.rigEdits.modifierStack, reopened.rigEdits.modifierStack)
        val restored = WorkspacePreviewBuilder().build(reopened)
        val restoredCast = restored.rig.puppet.parts.single { it.id.raw == castInst.outputId("part") }
        assertIs<PartGroupMode.Isolated>(restoredCast.groupMode)
        assertEquals(listOf(face), restoredCast.composite.maskedBy.map { it.raw })

        // 7. Delete form shadow
        exec("shadow_delete", buildJsonObject { put("id", formInst.id) })
        assertFalse(runtime.capture().model.rig.puppet.drawables.any { it.invertMask })

        // 8. Verify modifier configuration schemas include all shadow types
        assertTrue(WorkspaceModifierSchemas.configuration("shadow.world").isNotEmpty())
        assertTrue(WorkspaceModifierSchemas.configuration("shadow.cast").isNotEmpty())
        assertTrue(WorkspaceModifierSchemas.configuration("shadow.form").isNotEmpty())
        assertTrue(WorkspaceModifierSchemas.addition.isNotEmpty())
    }

    @Test
    fun `auto shadow refreshes visible source meshes without resetting the item`(): Unit = runBlocking {
        val document = shadowPortrait()
        val model = WorkspacePreviewBuilder().build(document)
        fun auto(doc: WorkspaceDocument, preview: RigPreviewModel) = WorkspaceShadowEdits.apply(
            WorkspaceDocumentOperation("shadow_auto_generate", JsonObject(emptyMap())), doc, preview)
        fun bangs(doc: WorkspaceDocument) = WorkspaceShadowEdits.readStack(doc).instances.single { it.presetGroup == "shadow:auto_bangs" }
        val first = auto(document, model)
        val item = bangs(first)
        val selected = CastShadowConfig.fromJson(item.config).casters
        val original = model.rig.puppet.drawables.first { it.name == "Bangs left" }
        val right = model.rig.puppet.drawables.first { it.name == "Bangs right" }
        assertTrue(original.id.raw in selected && right.id.raw in selected)
        assertSame(first, auto(first, model))
        val customized = WorkspaceShadowEdits.apply(WorkspaceDocumentOperation("shadow_put", buildJsonObject {
            put("id", item.id)
            put("distance", 13)
            put("enabled", false)
        }), first, model)
        val extra = original.copy(id = DrawableId("second-fringe-mesh"))
        val rig = model.rig
        val puppet = rig.puppet.copy(drawables = rig.puppet.drawables.map { if (it == right) it.copy(isVisible = false) else it } + extra,
            rootChildren = rig.puppet.rootChildren + OrgChild.Drawable(extra.id))
        val changed = model.copy(rig = rig.copy(puppet = puppet,
            layerIdByDrawableId = rig.layerIdByDrawableId + (extra.id.raw to rig.ownLayerIdByDrawableId.getValue(original.id.raw))))
        val refreshed = bangs(auto(customized, changed))
        val config = CastShadowConfig.fromJson(refreshed.config)
        assertEquals(item.id, refreshed.id)
        assertFalse(refreshed.enabled)
        assertEquals(13f, config.distance)
        assertTrue(original.id.raw in config.casters && extra.id.raw in config.casters)
        assertFalse(right.id.raw in config.casters)
    }

    @Test
    fun `auto cast excludes only the actual painted caster and receiver pair`(): Unit = runBlocking {
        val document = shadowPortrait()
        val original = WorkspacePreviewBuilder().build(document)
        val rig = original.rig
        val puppet = rig.puppet
        val leftHair = puppet.drawables.first { it.name == "Bangs left" }
        val rightHair = puppet.drawables.first { it.name == "Bangs right" }
        val face = puppet.drawables.first { it.name == "Face" }
        fun layer(mesh: DrawableId) = original.analysis.layers.first { it.source.id.raw == rig.ownLayerIdByDrawableId[mesh.raw] }
        fun rename(layer: ClassifiedLayer, id: String = layer.source.id.raw, name: String) = layer.copy(source =
            (WorkspaceSourceLayer.copyOf(layer.source, layer.source.order) as WorkspaceSourceLayer).copy(id = LayerId(id), name = name))
        val sourceHair = layer(leftHair.id)
        val namedHair = rename(sourceHair, name = "front hair lock")
        val paint = rename(sourceHair, "paint-source", "front hair shadow lock").copy(semantic = sourceHair.semantic.copy(role = PartRole.SHADOW))
        val extraFace = rename(layer(face.id), "alternate-face-source", "Face alternate")
        val faceMesh = face.copy(id = DrawableId("alternate-face-mesh"))
        val paintMesh = leftHair.copy(id = DrawableId("paint-mesh"), maskedBy = listOf(face.id))
        val preview = original.copy(analysis = original.analysis.copy(
            layers = original.analysis.layers.map { if (it == sourceHair) namedHair else it } + listOf(paint, extraFace)),
            rig = rig.copy(puppet = puppet.copy(drawables = puppet.drawables + listOf(faceMesh, paintMesh),
                rootChildren = puppet.rootChildren + listOf(OrgChild.Drawable(faceMesh.id), OrgChild.Drawable(paintMesh.id))),
                layerIdByDrawableId = rig.layerIdByDrawableId + mapOf(faceMesh.id.raw to extraFace.source.id.raw, paintMesh.id.raw to paint.source.id.raw)))
        fun generate(doc: WorkspaceDocument, include: Boolean? = null) = WorkspaceShadowEdits.apply(
            WorkspaceDocumentOperation("shadow_auto_generate", buildJsonObject {
                include?.let { put("include_covered", it) }
            }), doc, preview)
        fun matrix(doc: WorkspaceDocument) = CastShadowConfig.fromJson(WorkspaceShadowEdits.readStack(doc).instances
            .single { it.presetGroup == "shadow:auto_bangs" }.config).receiversByCaster.orEmpty()
        val auto = generate(document)
        assertEquals(listOf(faceMesh.id.raw), matrix(auto)[leftHair.id.raw])
        assertEquals(setOf(face.id.raw, faceMesh.id.raw), matrix(auto)[rightHair.id.raw]?.toSet())
        val includePainted = generate(auto, true)
        assertEquals(setOf(face.id.raw, faceMesh.id.raw), matrix(includePainted)[leftHair.id.raw]?.toSet())
        assertSame(includePainted, generate(includePainted))
    }

    @Test
    fun `shadow bindings follow both depth slices and subsequent mesh renames`() {
        val source = ModifierTarget("mesh", "source")
        val receiver = ModifierTarget("mesh", "face")
        val stack = RigModifierStack()
            .add(ShadowAlgorithmPlugin.TYPE_CAST, 1, listOf(source, receiver), CastShadowConfig(casters = listOf(source.id), receivers = listOf(receiver.id),
                receiversByCaster = mapOf(source.id to listOf(receiver.id))).toJson(), presetGroup = "shadow:auto_bangs")
            .add(ShadowAlgorithmPlugin.TYPE_FORM, 1, listOf(source), FormShadowConfig(targets = listOf(source.id)).toJson())
        val back = ModifierTarget("mesh", "back")
        val front = ModifierTarget("mesh", "front")
        val newReceiver = ModifierTarget("mesh", "renamed-face")
        val (cast, form) = stack.retargetDepthSplit(source.id, back.id, "source-layer", "back-layer", emptyMap(), frontMesh = front.id)
            .retarget(mapOf(receiver to listOf(newReceiver))).instances
        assertEquals(listOf(back, front, newReceiver), cast.targets)
        assertEquals(listOf(back.id, front.id), CastShadowConfig.fromJson(cast.config).casters)
        assertEquals(listOf(newReceiver.id), CastShadowConfig.fromJson(cast.config).receivers)
        assertEquals(mapOf(back.id to listOf(newReceiver.id), front.id to listOf(newReceiver.id)), CastShadowConfig.fromJson(cast.config).receiversByCaster)
        assertEquals(listOf(back.id, front.id), FormShadowConfig.fromJson(form.config).targets)
        val baked = cast.capability()
        val reopened = RigModifierStack.fromJson(RigModifierStack(appliedCapabilities = listOf(baked)).toJson())
        assertEquals("shadow:auto_bangs", reopened.appliedCapabilities.single().presetGroup)
    }

    @Test
    fun `the first shadow edit converts legacy content without dropping authored rig objects`(): Unit = runBlocking {
        val base = shadowPortrait()
        val edits = base.rigEdits.copy(modifierStack = null, parameterEdits = listOf(
            RigParameterEdit("ParamLegacyCheck", "Legacy Check", -1f, 1f, 0f, created = true),
        ))
        val legacy = base.copy(rigEdits = edits,
            settings = WorkspaceSettingsCodec.encode(base.config().copy(meshOnly = true, rigEdits = edits)))
        val builder = WorkspacePreviewBuilder()
        val before = builder.build(legacy)
        assertTrue(before.rig.puppet.parameters.any { it.id.raw == "ParamLegacyCheck" })
        val changed = WorkspaceShadowEdits.apply(WorkspaceDocumentOperation("shadow_world_set", buildJsonObject {
            put("azimuth", -25)
        }), legacy, before)
        assertNotNull(WorkspaceShadowEdits.findWorld(WorkspaceShadowEdits.readStack(changed)))
        val after = builder.build(changed)
        val previous = RigIrCompiler.compile(before, tileArt = true)
        val actual = RigIrCompiler.compile(after, tileArt = true)
        assertEquals(previous.meshes, actual.meshes)
        assertEquals(previous.parameters.sortedBy { it.id }, actual.parameters.sortedBy { it.id })
    }

    @Test
    fun `installed shadow package workers resolve all declared entry points`() {
        val aliases = listOf(ShadowAlgorithmPlugin.TYPE_WORLD, ShadowAlgorithmPlugin.TYPE_CAST, ShadowAlgorithmPlugin.TYPE_FORM)
        val artifacts = aliases.map { assertNotNull(BundledAlgorithmArtifacts.find(it, 1)) }
        val preferences = Preferences.userRoot().node("shadow-worker-test/${temp.fileName}")
        val store = ModifierPluginStore(temp.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, artifacts)
            val target = ModifierTarget("mesh", "skin")
            val input = RigIR(Canvas(1f, 1f), emptyList(), meshes = listOf(Mesh(target.id, "Skin", null, geometry = null, offsets = null)))
            val context = ModifierAlgorithmContext(buildJsonObject {
                put(ShadowWorldConfig.CONTEXT_KEY, ShadowWorldConfig(previewEnabled = false).toJson())
            })
            ModifierPluginWorkers.openSession(store).use { session ->
                artifacts.zip(aliases).forEach { (artifact, alias) ->
                    val entry = store.snapshot().single { it.reference == artifact.reference }
                    val algorithm = entry.manifest.algorithms.single()
                    val invocation = RigModifierInstance("shadow-test", alias, 1, listOf(target), plugin = entry.reference)
                    assertEquals(input, ModifierPluginWorkers.evaluate(entry, algorithm, input, invocation, {}, store,
                        timeoutMillis = 15_000, context = context, session = session), alias)
                }
            }
        } finally {
            preferences.removeNode()
        }
    }
}
