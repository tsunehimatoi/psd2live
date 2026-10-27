package io.github.psd2live.scenario

import io.github.psd2live.core.*
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

/**
 * The rig's modules and the layers' part roles are project data like any other: a module switched off through
 * `settings.rig` changes the rig in one history step and survives a reopen, a project returned to the legacy recipe
 * stores what a project from before it did, and a role set with tags or `layer_classify` is the layer's through undo
 * and a saved project, while one taken away keeps the analysis from guessing it again.
 */
class RigModulesScenario {
	@TempDir lateinit var temp: Path

	@Test fun modulesSwitchThroughSettingsAndRolesThroughTags() = Studio.run(temp, Characters.eyed()) {
		allow("REGENERATION_CONFLICT")
		val copies = { puppet.drawables.count { it.id.raw.endsWith(SeeThroughLayers.SUFFIX) } }
		val before = copies()
		expect("default", before > 0 && WorkspaceSettingsCodec.RIG in now.document.settings) { "a new project is modern and shows its eyes through the bangs: $before" }

		settings("eyes behind the bangs", buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("modules") { putJsonObject("eye.see-through") { put("on", false) } } }
		})
		expect("switched off", !now.document.settings.getValue("seeThroughEyes").jsonPrimitive.boolean && copies() < before) {
			"the module did not switch its setting: ${now.document.settings["seeThroughEyes"]}, ${copies()} copies" }
		undo("undo the switch")
		expect("undone", copies() == before) { "undo did not bring the copies back" }

		settings("legacy rig", buildJsonObject { put(WorkspaceSettingsCodec.RIG, JsonNull) })
		expect("legacy", WorkspaceSettingsCodec.RIG !in now.document.settings) { "a legacy project stores no recipe" }
		reopen()
		expect("reopened legacy", WorkspaceSettingsCodec.decode(now.document.settings).recipe == io.github.psd2live.core.recipe.RigRecipe.Legacy) {
			"the legacy recipe did not survive a reopen" }
		settings("upgrade", buildJsonObject { putJsonObject(WorkspaceSettingsCodec.RIG) {} })
		expect("upgraded", now.document.settings[WorkspaceSettingsCodec.RIG] == JsonObject(emptyMap())) { "the upgrade stores an empty modern recipe" }

		fun role(layer: String) = now.model.analysis.layers.single { it.source.id.raw == layer }.semantic.role
		edits("tag the right iris a highlight", "layer_tag" to req("layer_ids" to listOf("iris_r"), "add" to listOf("highlight")), "rig_generate" to req())
		expect("tagged", role("iris_r") == PartRole.HIGHLIGHT && now.document.layerOverrides["iris_r"]?.role == PartRole.HIGHLIGHT) {
			"the role word did not become the layer's role: ${role("iris_r")}, ${now.document.layerOverrides["iris_r"]}" }
		val row = inspect("layers").getValue("items").jsonArray.map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == "iris_r" }
		expect("inspected", "highlight" in row.getValue("tags").jsonArray.map { it.jsonPrimitive.content }) { "inspect does not show the role: $row" }
		reopen()
		expect("reopened", role("iris_r") == PartRole.HIGHLIGHT) { "the saved project lost the role" }

		edits("no role", "layer_classify" to req("layer_id" to "iris_r", "part" to "none"), "rig_generate" to req())
		expect("cleared", role("iris_r") == null && now.document.layerOverrides["iris_r"]?.role == PartRole.PLAIN) {
			"part none did not clear the role: ${now.document.layerOverrides["iris_r"]}" }
		edits("guess again", "layer_classify" to req("layer_id" to "iris_r", "part" to "auto"), "rig_generate" to req())
		expect("auto", now.document.layerOverrides["iris_r"]?.role == null) { "part auto did not hand the role back to the analysis" }
	}

	@Test fun newProjectsCloseTheEyesWithTheFittedLidsAndTheLegacyBaseKeepsTheOldOnes() = Studio.run(temp, Characters.eyed()) {
		allow("REGENERATION_CONFLICT")
		fun drawable(layer: String) = puppet.drawables.single { it.id.raw == mesh(layer) }
		fun keys(layer: String) = drawable(layer).geometryGrid!!.axes.single { it.parameterId == StandardParameters.EYE_L_OPEN }.keys.toList()
		fun swap(layer: String) = drawable(layer).channelGrids[org.umamo.runtime.model.FormChannel.OPACITY]?.axes?.single()?.keys?.toList()
		val fitted = listOf(0f, io.github.psd2live.core.fit.EyeRig.SWAP)
		fun isFitted(label: String) = expect(label, keys("lash_l").take(2) == fitted && keys("lash_l").takeLast(3) == listOf(1f, 1.5f, 2f) && keys("white_l") == keys("lash_l") &&
			puppet.parameters.single { it.id == StandardParameters.EYE_L_OPEN }.max == 2f &&
			rides(puppet, mesh("lash_l"), "ParamEyeBallY") && puppet.parameters.any { it.id.raw == "ParamEyeLSmile" } &&
			swap("lash_l") == listOf(0f, io.github.psd2live.core.fit.EyeRig.SWAP, 1f) && swap("close_l") == listOf(0f, io.github.psd2live.core.fit.EyeRig.SWAP, 1f)) {
			"the fitted eye keys its lids at closed, the swap to the drawn closed line, the crease and on: ${keys("lash_l")} / ${keys("white_l")}, " +
				"swaps ${swap("lash_l")} / ${swap("close_l")}" }
		isFitted("new project")

		settings("legacy eyes", buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("bases") { putJsonObject("eye") { put("base", "eye/legacy") } } }
		})
		expect("legacy", keys("lash_l") == listOf(0f, 1f) && swap("lash_l") == null) { "the legacy base keys ${keys("lash_l")}" }
		undo("back to the fitted eyes")
		isFitted("undone")
		settings("rounder", buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("bases") { putJsonObject("eye") { putJsonObject("values") { put("roundness", 150) } } } }
		})
		reopen()
		isFitted("reopened")
		expect("value", WorkspaceSettingsCodec.decode(now.document.settings).recipe.baseValue("eye", "roundness") == 150f) { "the value did not survive a reopen" }
	}
	@Test fun fittedBrowsTurnAlongTheirAngleAndTheLegacyBaseKeepsTheOldOnes() = Studio.run(temp, Characters.browed()) {
		allow("REGENERATION_CONFLICT")
		fun drawable(layer: String) = puppet.drawables.single { it.id.raw == mesh(layer) }
		fun axes(layer: String) = drawable(layer).geometryGrid!!.axes.map { it.parameterId.raw to it.keys.toList() }
		fun isFitted(label: String) = expect(label, axes("brow_l").take(2) == listOf("ParamBrowLY" to listOf(-1f, 0f, 1f), "ParamBrowLAngle" to listOf(-1f, -0.5f, 0f, 0.5f, 1f)) &&
			drawable("brow_l").blendShapes.map { it.parameterId.raw }.containsAll(listOf("ParamBrowLX", "ParamBrowLForm")) &&
			puppet.parameters.any { it.id.raw == "ParamBrowRAngle" }) { "the fitted brow keys its height by its angle: ${axes("brow_l")}" }
		isFitted("new project")
		settings("legacy brows", buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("bases") { putJsonObject("brow") { put("base", "brow/legacy") } } }
		})
		expect("legacy", axes("brow_l").none { it.first == "ParamBrowLAngle" } && puppet.parameters.none { it.id.raw == "ParamBrowLAngle" }) {
			"the legacy base keys ${axes("brow_l")}" }
		undo("back to the fitted brows")
		isFitted("undone")
	}
	@Test fun fittedMouthOpensInStagesAndTheLegacyBaseKeepsTheOldOne() = Studio.run(temp, Characters.mouthed()) {
		allow("REGENERATION_CONFLICT")
		fun drawable(layer: String) = puppet.drawables.single { it.id.raw == mesh(layer) }
		fun axes(layer: String) = drawable(layer).geometryGrid!!.axes.map { it.parameterId.raw to it.keys.toList() }
		fun isFitted(label: String) = expect(label, axes("mouth") == listOf("ParamMouthForm" to io.github.psd2live.core.fit.MouthRig.FORM_KEYS.toList(),
			"ParamMouthOpenY" to io.github.psd2live.core.fit.MouthRig.OPEN_KEYS.toList(), "ParamMouthPressLipOpen" to io.github.psd2live.core.fit.MouthRig.PRESS_KEYS.toList()) &&
			drawable("mouth").blendShapes.map { it.parameterId.raw }.containsAll(listOf("ParamA", "ParamO", "ParamMouthX")) &&
			rides(puppet, mesh("face"), "ParamMouthOpenY") && puppet.parameters.any { it.id.raw == "ParamCheekPuff" }) {
			"the fitted mouth keys its form by its staged opening and the lips pressed or parted, with vowels and the jaw: ${axes("mouth")}" }
		isFitted("new project")
		settings("legacy mouth", buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("bases") { putJsonObject("mouth") { put("base", "mouth/legacy") } } }
		})
		expect("legacy", axes("mouth").last().second == listOf(0f, 0.5f, 1f) && puppet.parameters.none { it.id.raw == "ParamA" }) {
			"the legacy base keys ${axes("mouth")}" }
		undo("back to the fitted mouth")
		isFitted("undone")
	}

	@Test fun theHeadTurnsInDepthUntilTheModuleGoesOff() = Studio.run(temp, Characters.mouthed()) {
		allow("REGENERATION_CONFLICT")
		fun turns(layer: String) = rides(puppet, mesh(layer), "ParamAngleX")
		expect("new project", turns("face") && turns("mouth") && turns("back_hair")) { "every layer of the head turns on its shell" }
		settings("no depth turn", buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("modules") { putJsonObject("face.depth-turn") { put("on", false) } } }
		})
		expect("off", !turns("face") && !turns("mouth")) { "switched off, the head turns as it did" }
	}

	@Test fun hairHangsInChainsOnPendulumsOfItsOwn() = Studio.run(temp, Characters.head(hairChains = true)) {
		allow("REGENERATION_CONFLICT")
		fun chain() = puppet.deformers.singleOrNull { it.id.raw == "DeformHairChain1" } as? org.umamo.runtime.model.Deformer.Warp
		val groups = io.github.psd2live.core.PhysicsCatalog.active(now.model.analysis, now.model.config, puppet.parameters.mapTo(HashSet()) { it.id.raw }, puppet)
		expect("chains", chain()?.blendShapes?.any { it.parameterId.raw == "ParamHairBack1Y" } == true &&
			puppet.parameters.none { it.id.raw == "ParamHairBack" } && groups.any { it.id.startsWith("PhysicsHairBack1") } &&
			puppet.deformers.none { it.id.raw == "DeformHairBackPhysics" }) {
			"the back hair hangs in a chain with its own pendulums instead of the one sway: ${chain()?.blendShapes?.map { it.parameterId.raw }}, ${groups.map { it.id }}" }
		settings("legacy hair", buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("bases") { putJsonObject("hair") { put("base", "hair/legacy") } } }
		})
		expect("legacy", chain() == null && puppet.parameters.any { it.id.raw == "ParamHairBack" }) { "the legacy base sways the hair as one" }
	}

	@Test fun aMovedFeatureHandleRefitsEveryPoseAndComesBack() = Studio.run(temp, Characters.eyed()) {
		allow("REGENERATION_CONFLICT")
		fun closed() = puppet.drawables.single { it.id.raw == mesh("lash_l") }.geometryGrid!!.cells.first().form.positionDeltas.toList()
		val drawn = closed()
		val handles = io.github.psd2live.core.RigBuilder.featureHandles(now.model.analysis, now.model.config)
		expect("handles", handles.count { it.feature == "eye.L" } == 4) { "the fitted eye shows its four handles: $handles" }
		settings("lift the lid", buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("moves") { putJsonObject("eye.L") {
				put("upper", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(0)); add(kotlinx.serialization.json.JsonPrimitive(-3)) })
			} } }
		})
		expect("moved", closed() != drawn && WorkspaceSettingsCodec.decode(now.document.settings).recipe.moves("eye.L").getValue("upper").dy == -3f) {
			"moving the lid's handle refits the closing lid" }
		reopen()
		expect("reopened", closed() != drawn) { "the move survives a reopen" }
		settings("back to the fit", buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("moves") { put("eye.L", kotlinx.serialization.json.JsonNull) } }
		})
		expect("back", closed() == drawn) { "clearing the move returns the drawn fit" }
	}

	@Test fun generatedShapesAreOnesCubismTakes() = Studio.run(temp, Characters.mouthed()) {
		val kinds = puppet.parameters.associate { it.id to it.kind }
		val shapes = puppet.drawables.flatMap { it.blendShapes } + puppet.deformers.flatMap { (it as? org.umamo.runtime.model.Deformer.Warp)?.blendShapes.orEmpty() }
		expect("blend shape parameters", shapes.isNotEmpty() && shapes.all { kinds[it.parameterId] == org.umamo.runtime.model.ParameterKind.BLEND_SHAPE }) {
			"a blend shape on an ordinary parameter: ${shapes.filter { kinds[it.parameterId] != org.umamo.runtime.model.ParameterKind.BLEND_SHAPE }.map { it.parameterId.raw }.distinct()}" }
		// Shapes on an axis fold into the mesh's keyforms exactly; past the mesh's budget a warp of its own carries them closely:
		// a warp interpolates between its points as its parent carries them, so a bent parent costs a little.
		val face = puppet.drawables.single { it.id.raw == mesh("face") }
		val size = face.mesh!!.positions.size
		val random = java.util.Random(7)
		fun shape(parameter: String, keys: FloatArray, neutral: Int, limit: String? = null) = org.umamo.runtime.model.BlendShapeBinding(
			org.umamo.runtime.model.ParameterId(parameter), keys, neutral,
			keys.indices.map { k -> if (k == neutral) null else org.umamo.runtime.model.MeshForm(random.nextFloat().let { phase -> FloatArray(size) { i ->
				// A smooth move over the mesh, as a shape is, a fiftieth of its size.
				val p = face.mesh!!.positions
				val xs = p.filterIndexed { k, _ -> k % 2 == 0 }; val ys = p.filterIndexed { k, _ -> k % 2 == 1 }
				val x = (p[i - i % 2] - xs.min()) / (xs.max() - xs.min()); val y = (p[i - i % 2 + 1] - ys.min()) / (ys.max() - ys.min())
				(if (i % 2 == 0) xs.max() - xs.min() else ys.max() - ys.min()) / 50f * kotlin.math.sin(3f * x + 2f * y + 6f * phase + i % 2) } }, face.drawOrder, face.opacity) },
			limit?.let { listOf(org.umamo.runtime.model.BlendWeightLimit(org.umamo.runtime.model.ParameterId(it), listOf(
				org.umamo.runtime.model.BlendWeightLimitPoint(0f, 0f), org.umamo.runtime.model.BlendWeightLimitPoint(1f, 1f)))) }.orEmpty())
		fun check(label: String, shapes: List<org.umamo.runtime.model.BlendShapeBinding<org.umamo.runtime.model.MeshForm>>, tolerance: Float) {
			val raw = puppet.copy(drawables = puppet.drawables.map { if (it.id == face.id) it.copy(blendShapes = shapes) else it })
			val lawful = CubismBlendShapes.lawful(raw)
			val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
			val poses = listOf(emptyMap(), mapOf("ParamAngleY" to 20f, "ParamMouthOpenY" to 0.6f), mapOf("ParamAngleY" to -30f, "ParamEyeBallX" to 0.4f,
				"ParamBrowLY" to -0.7f, "ParamMouthForm" to 0.3f, "ParamMouthOpenY" to 1f))
			val error = poses.maxOf { pose ->
				val p = pose.mapKeys { org.umamo.runtime.model.ParameterId(it.key) }
				val a = evaluator.evaluate(raw, p).worldPositions.getValue(face.id); val b = evaluator.evaluate(lawful, p).worldPositions.getValue(face.id)
				a.indices.maxOf { kotlin.math.abs(a[it] - b[it]) }
			}
			val moved = lawful.drawables.single { it.id == face.id }
			expect(label, moved.blendShapes.none { it.parameterId.raw in setOf("ParamAngleX", "ParamAngleY", "ParamMouthForm") } && error <= tolerance) {
				"shapes on axes kept as blend shapes, or moved the mesh elsewhere: error $error, ${moved.blendShapes.map { it.parameterId.raw }}" }
		}
		check("folded", listOf(shape("ParamAngleY", floatArrayOf(-30f, 0f, 30f), 1, "ParamMouthOpenY")), 1e-3f)
		check("carried", listOf(shape("ParamAngleY", floatArrayOf(-30f, -15f, 0f, 15f, 30f), 2), shape("ParamAngleX", floatArrayOf(-30f, -15f, 0f, 15f, 30f), 2),
			shape("ParamMouthForm", floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f), 2, "ParamMouthOpenY")), 0.75f)
		fun carriers(model: org.umamo.runtime.model.PuppetModel) = model.deformers.count { it.id.raw.startsWith(face.id.raw + "_") }
		expect("a warp of its own", carriers(puppet) < carriers(CubismBlendShapes.lawful(puppet.copy(drawables = puppet.drawables.map { if (it.id == face.id) it.copy(blendShapes = listOf(
				shape("ParamAngleY", floatArrayOf(-30f, -15f, 0f, 15f, 30f), 2), shape("ParamAngleX", floatArrayOf(-30f, -15f, 0f, 15f, 30f), 2),
				shape("ParamMouthForm", floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f), 2, "ParamMouthOpenY"))) else it })))) {
			"past the mesh's budget the shapes did not go to a warp of the mesh's own" }
	}

	/** Whether [drawable] moves with [parameter] of its own: a shape, its keyforms or a warp made for it. */
	private fun rides(puppet: org.umamo.runtime.model.PuppetModel, drawable: String, parameter: String): Boolean {
		val d = puppet.drawables.single { it.id.raw == drawable }
		val carriers = puppet.deformers.filterIsInstance<org.umamo.runtime.model.Deformer.Warp>().filter { it.id.raw.startsWith(drawable + "_") }
		return d.blendShapes.any { it.parameterId.raw == parameter } || d.geometryGrid?.axes.orEmpty().any { it.parameterId.raw == parameter } ||
			carriers.any { w -> w.geometryGrid?.axes.orEmpty().any { it.parameterId.raw == parameter } }
	}

	@Test fun switchedVariantsCrossFadeWithoutAHole() = Studio.run(temp, Characters.mouthed()) {
		allow("REGENERATION_CONFLICT")
		edits("two variants", "layer_classify" to req("layer_id" to "back_hair", "type" to "switch", "parameter" to "ParamOutfit", "switch_id" to 0),
			"layer_classify" to req("layer_id" to "mouth", "type" to "switch", "parameter" to "ParamOutfit", "switch_id" to 1), "rig_generate" to req())
		fun opacity(layer: String) = puppet.drawables.single { it.id.raw == mesh(layer) }.channelGrids.gridsByChannel.getValue(org.umamo.runtime.model.FormChannel.OPACITY)
		fun at(layer: String, key: Float) = opacity(layer).let { grid ->
			(grid.cells.single { cell -> grid.axes.single().keys[cell.coordinate[0]] == key }.form as org.umamo.runtime.model.ChannelValue.Scalar).value }
		expect("staggered", opacity("back_hair").axes.single().keys.toList() == listOf(0f, 0.5f, 1f) && at("back_hair", 0.5f) == 1f && at("mouth", 0.5f) == 1f &&
			at("back_hair", 1f) == 0f && at("mouth", 0f) == 0f) { "halfway between two variants both show, so the change has no hole" }
	}
}
