package io.github.psd2live.project

import io.github.psd2live.application.WorkspaceSettingsDraft
import io.github.psd2live.core.Side
import io.github.psd2live.core.fit.*
import io.github.psd2live.core.recipe.*
import kotlinx.serialization.json.*
import kotlin.test.*

class EyePartSettingsTest {
	@Test fun correctionsSurviveHistoryAndARealProjectArchive(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) = kotlinx.coroutines.runBlocking<Unit> {
		val project = SyntheticProject(root, layers = 1, size = 8, revisions = 1)
		val initial = project.tree.head()
		val document = initial.snapshot.copy(settings = buildJsonObject { put("rig", WorkspaceSettingsCodec.encodeRecipe(recipe)) })
		val revision = WorkspaceRevisions.of(document)
		project.tree.commit(initial.node.id, document, revision, revision, "Correct eye structure", "user")
		val corrected = project.tree.head().node.id
		assertTrue(WorkspaceSettingsCodec.decode(project.tree.checkout(initial.node.id).snapshot.settings).recipe.eyeParts.isEmpty())
		assertEquals(recipe, WorkspaceSettingsCodec.decode(project.tree.checkout(corrected).snapshot.settings).recipe)
		project.store.persistHistory(SyntheticProject.PROJECT, project.tree.state())
		val archive = root.resolve("eyes.psd2live")
		ProjectRepository(writeHeadCache = false).save(project.capture(), archive)
		ProjectRepository().open(archive).use { reopened ->
			assertEquals(recipe, WorkspaceSettingsCodec.decode(reopened.history.head().snapshot.settings).recipe)
			val records = reopened.history.selections()
			assertEquals(2, records.size)
			assertEquals(recipe, WorkspaceSettingsCodec.decode(records.last().snapshot.settings).recipe)
			assertTrue(WorkspaceSettingsCodec.decode(records.first().snapshot.settings).recipe.eyeParts.isEmpty())
		}
	}
	private val correction = EyePartOverride("source-digest", Side.LEFT, EyeRegion.UPPER, EyePrimitive.TUFT,
		listOf(EyeContact(EyeRegion.UPPER, 0.4f, 0.5f, EyePoint(0.45f, -0.1f))),
		listOf(EyePoint(0f, 0f), EyePoint(1f, 0f)))
	private val recipe = RigRecipe.Modern.copy(eyeParts = mapOf("layer" to mapOf("part" to correction, "other" to correction)))
	private fun patch(block: JsonObjectBuilder.() -> Unit) = buildJsonObject {
		putJsonObject("rig") { putJsonObject("eyeParts") { block() } }
	}
	@Test fun typedCorrectionsRoundTripAndPatchWithoutDiscardingOtherComponents() {
		assertEquals(recipe, WorkspaceSettingsCodec.decodeRecipe(WorkspaceSettingsCodec.encodeRecipe(recipe)))
		val patch = buildJsonObject { putJsonObject("eyeParts") { putJsonObject("layer") { putJsonObject("part") { put("region", "lower") } } } }
		val changed = WorkspaceSettingsCodec.mergeRecipe(recipe, patch).first
		assertEquals(correction.copy(region = EyeRegion.LOWER), changed.eyeParts["layer"]?.get("part"))
		assertEquals(correction, changed.eyeParts["layer"]?.get("other"))
	}
	@Test fun resetThenSetPreservesItsOrderAcrossAContinuousSettingsGesture() {
		val reset = patch { put("layer", JsonNull) }
		val set = patch { putJsonObject("layer") { put("part", EyePartOverrideCodec.encode(correction)) } }
		val coalesced = WorkspaceSettingsDraft.coalesce(listOf(reset, set))
		val final = coalesced.fold(recipe) { current, update -> WorkspaceSettingsCodec.mergeRecipe(current, update.getValue("rig")).first }
		assertEquals(mapOf("layer" to mapOf("part" to correction)), final.eyeParts)
		val empty = WorkspaceSettingsCodec.mergeRecipe(final, buildJsonObject { put("eyeParts", JsonNull) }).first
		assertTrue(empty.eyeParts.isEmpty())
	}
	@Test fun invalidCoordinatesAndUnknownFieldsAreRejected() {
		assertFailsWith<IllegalArgumentException> { correction.copy(path = listOf(EyePoint(1f, 0f), EyePoint(0f, 0f))) }
		assertFailsWith<IllegalArgumentException> { correction.copy(contacts = listOf(EyeContact(EyeRegion.UPPER, 1f, 0f, EyePoint(0f, 0f)))) }
		assertFailsWith<IllegalArgumentException> { EyePartOverrideCodec.decode(JsonObject(EyePartOverrideCodec.encode(correction) + ("typo" to JsonPrimitive(true)))) }
	}
	@Test fun settingsNodeRequiresANewerReaderOnlyWhenCorrectionsExist(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) {
		val before = buildJsonObject { putJsonObject("settings") { put("rig", WorkspaceSettingsCodec.encodeRecipe(RigRecipe.Modern)) } }
		assertEquals(ProjectFormatV2.NODE_SCHEMA, ProjectFormatV2.schemaOf("settings", before))
		val snapshot = buildJsonObject { putJsonObject("settings") { put("rig", WorkspaceSettingsCodec.encodeRecipe(recipe)) } }
		assertEquals(ProjectFormatV2.NODE_SCHEMA_EYE_PARTS, ProjectFormatV2.schemaOf("settings", snapshot))
		val index = ProjectFormatV2.split(root, snapshot)
		assertEquals(snapshot, ProjectFormatV2.join(root, index))
	}
}
