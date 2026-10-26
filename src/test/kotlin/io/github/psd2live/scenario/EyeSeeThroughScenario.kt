package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceFailure
import io.github.psd2live.core.MaterializedRigCodec
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.core.SeeThroughLayers
import io.github.psd2live.core.authoredMasksOf
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.RuntimeTarget
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every part of the eyes shows through the bangs by default: the bangs open a window - clipped inverted by the whites
 * and lashes, with a copy of them clipped by those at the remaining opacity - so the irides and highlights their whites
 * clip show as they are; closed-eye lines, which fade, are copied over the bangs as brows are. The window can be turned
 * off, survives a reopen, reaches the moc3, cmo3 and p2lrt exports, and never reaches the document's own masks.
 */
// The eyes keep the legacy lids here: a fitted eye's lashes swap with its closed line, and a part that fades shows
// through as a copy rather than a window, which is what this scenario does not test.
class EyeSeeThroughScenario {
	@TempDir lateinit var temp: Path

	@Test fun eyesShowThroughAWindowInTheBangsByDefault() = Studio.run(temp, Characters.eyed(legacyEyes = true)) {
		val hair = mesh("front_hair")
		val whites = listOf(mesh("white_r"), mesh("white_l")); val lashes = listOf(mesh("lash_r"), mesh("lash_l"))
		val irides = listOf(mesh("iris_r"), mesh("iris_l")); val closes = listOf(mesh("close_r"), mesh("close_l"))
		val window = SeeThroughLayers.copyId(DrawableId(hair)).raw
		fun drawable(id: String) = puppet.drawables.single { it.id.raw == id }
		fun rig() = now.model.rig

		expect("default", now.model.config.seeThroughEyes) { "the eyes are not on by default" }
		val candidates = rig().seeThrough.orEmpty().filter { it.eyes }.map { it.original }.toSet()
		expect("candidates", candidates == (whites + lashes + irides + closes).toSet()) { "the eye parts the bangs cover: $candidates" }

		fun opened(label: String) {
			expect(label, rig().seeThroughWindows.keys == setOf(hair) && rig().seeThroughWindows.getValue(hair).toSet() == (whites + lashes).toSet()) {
				"windows: ${rig().seeThroughWindows}" }
			val bang = drawable(hair); val copy = drawable(window)
			expect(label, bang.invertMask && bang.maskedBy.map { it.raw }.toSet() == (whites + lashes).toSet()) {
				"the bangs are clipped by ${bang.maskedBy} inverted ${bang.invertMask}" }
			val next = puppet.drawables.filter { it.drawOrder > bang.drawOrder && it.id.raw !in rig().seeThroughCopies }.minOfOrNull { it.drawOrder }
			expect(label, !copy.invertMask && copy.maskedBy == bang.maskedBy && !copy.isSelectable &&
				copy.opacity == bang.opacity * (1f - now.model.config.seeThroughOpacity) && copy.parentDeformerId == bang.parentDeformerId &&
				copy.drawOrder > bang.drawOrder && (next == null || copy.drawOrder < next) && rig().seeThroughCopies[window] == hair) {
				"the window's copy: masks ${copy.maskedBy}, opacity ${copy.opacity}, order ${copy.drawOrder} over ${bang.drawOrder}, next $next" }
			// The irides keep their whites' clipping and get no copy: they show through the whites' window.
			for (iris in irides) expect(label, drawable(iris).maskedBy.isNotEmpty() && drawable(iris).maskedBy.all { it.raw in whites } &&
				SeeThroughLayers.copyId(DrawableId(iris)).raw !in rig().seeThroughCopies) { "iris $iris: ${drawable(iris).maskedBy}" }
			// The closed-eye lines fade with the lids, which a mask would not: copied over the bangs, after them.
			for (close in closes) {
				val made = drawable(SeeThroughLayers.copyId(DrawableId(close)).raw)
				expect(label, made.maskedBy == listOf(DrawableId(hair)) && made.channelGrids[FormChannel.OPACITY] != null &&
					made.drawOrder > copy.drawOrder) { "closed line copy ${made.maskedBy} at ${made.drawOrder}, the window at ${copy.drawOrder}" }
			}
			// Just in front of the bangs in their part.
			val children = puppet.parts.single { part -> part.children.any { it is OrgChild.Drawable && it.id.raw == hair } }.children
			expect(label, children.indexOf(OrgChild.Drawable(DrawableId(window))) == children.indexOf(OrgChild.Drawable(DrawableId(hair))) - 1) {
				"the window's copy is not just in front of the bangs: $children" }
		}
		opened("default")
		// The document's bangs have no masks: what the inspector shows and edits from.
		expect("authored", rig().authoredMasksOf(drawable(hair)).let { it.maskedBy.isEmpty() && !it.invertMask } &&
			now.model.authored.rig.puppet.drawables.single { it.id.raw == hair }.maskedBy.isEmpty()) { "the window reached the document" }

		val failure = WorkspaceFailure.from(refused("fade the window's copy", "rig_edit_structure",
			req("edits" to listOf(mapOf("action" to "static", "kind" to "mesh", "id" to window, "opacity" to 0.2)))))
		expect("refused", failure.code == "generated_copy" && failure.details["original"]?.jsonPrimitive?.content == hair) { "refused as ${failure.toJson()}" }

		val journal = now.document.rigEdits.authoringJournal
		edit("raise the see-through opacity", "settings_update", req("changes" to mapOf("seeThroughOpacity" to 0.8)))
		expect("replayed", now.document.rigEdits.authoringJournal == journal) { "the opacity change regenerated the rig" }
		opened("opacity")

		edit("export moc3 too", "settings_update", req("changes" to mapOf("exportMoc3" to true)))
		val moc3 = moc3()
		exportMatchesEditor(this, moc3)
		val bang = moc3.drawables.single { it.id.raw == hair }
		expect("moc3", bang.invertMask && bang.maskedBy.map { it.raw }.toSet() == (whites + lashes).toSet() &&
			moc3.drawables.single { it.id.raw == window }.maskedBy == bang.maskedBy) { "the moc3 bangs: ${bang.maskedBy} inverted ${bang.invertMask}" }
		val cmo3 = export("cmo3").single { it.toString().endsWith(".cmo3") }
		val readBack = step("read the cmo3 back") { Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(cmo3)).root as CModelSource) }
		val written = readBack.drawables.single { it.id.raw == hair }
		expect("cmo3", written.invertMask && written.maskedBy.map { it.raw }.toSet() == (whites + lashes).toSet() &&
			readBack.drawables.any { it.id.raw == window }) { "the cmo3 bangs: ${written.maskedBy} inverted ${written.invertMask}" }
		val ir = RigIrCompiler.compile(now.model)
		val compiled = ir.meshes.single { it.id == hair }
		expect("p2lrt", compiled.invertMask && compiled.maskedBy.toSet() == (whites + lashes).toSet()) { "the compiled bangs: ${compiled.maskedBy}" }

		// Off, the bangs are as the document has them and nothing of the eyes is made.
		edit("turn the eyes off", "settings_update", req("changes" to mapOf("seeThroughEyes" to false)))
		expect("off", rig().seeThroughWindows.isEmpty() && drawable(hair).maskedBy.isEmpty() && !drawable(hair).invertMask &&
			puppet.drawables.none { it.id.raw == window } && closes.none { SeeThroughLayers.copyId(DrawableId(it)).raw in rig().seeThroughCopies }) {
			"the eyes still show through: ${rig().seeThroughCopies} ${rig().seeThroughWindows}" }
		reopen("reopen with the eyes off")
		expect("off reopened", !now.model.config.seeThroughEyes && rig().seeThroughWindows.isEmpty()) { "turning the eyes off did not last" }
		edit("turn the eyes on", "settings_update", req("changes" to mapOf("seeThroughEyes" to true)))
		opened("on again")
	}

	/**
	 * The window's limits, made by hand on [Characters.eyed]'s finished rig: bangs with masks of their own keep them, a
	 * target without inverted masks opens none, a preview moving the bangs re-places the window's copy, and the
	 * candidates of a stored rig recorded by the 3.6.5 rules are read as unknown, so the preview generates.
	 */
	@Test fun windowsOpenOnlyWhereTheyCan() = Studio.run(temp, Characters.eyed(legacyEyes = true), checks = emptySet()) {
		val hair = mesh("front_hair"); val white = mesh("white_r")
		val window = SeeThroughLayers.copyId(DrawableId(hair))
		val authored = now.model.authored.rig.puppet
		val candidates = now.model.rig.seeThrough.orEmpty()
		val settings = SeeThroughLayers.Settings.of(now.model.config)
		fun windowParts(notes: List<SeeThroughLayers.Note>) = notes.filter { it.original in listOf(white, mesh("white_l"), mesh("lash_r"), mesh("lash_l")) }

		// The finished rig before the copies is the authored one here: no journal, no overrides.
		val plain = SeeThroughLayers.apply(authored, candidates, settings)
		assertEquals(setOf(hair), plain.windows.keys)
		val clipped = authored.copy(drawables = authored.drawables.map { if (it.id.raw == hair) it.copy(maskedBy = listOf(DrawableId(mesh("face")))) else it })
		val kept = SeeThroughLayers.apply(clipped, candidates, settings)
		assertTrue(kept.windows.isEmpty() && kept.model.drawables.single { it.id.raw == hair }.maskedBy == listOf(DrawableId(mesh("face"))))
		assertEquals(setOf(SeeThroughLayers.DropReason.BANGS_CLIPPED), windowParts(kept.notes).map { it.reason }.toSet())
		assertEquals(4, windowParts(kept.notes).size)
		val old = SeeThroughLayers.apply(authored, candidates, SeeThroughLayers.Settings.of(now.model.config.copy(runtimeTarget = RuntimeTarget.Cubism33)))
		assertTrue(old.windows.isEmpty() && old.model.drawables.none { it.id == window })
		assertEquals(setOf(SeeThroughLayers.DropReason.TARGET_UNSUPPORTED), windowParts(old.notes).map { it.reason }.toSet())
		// Brows only: the eyes are the settings' alone.
		assertTrue(SeeThroughLayers.apply(authored, candidates, settings.copy(eyes = false)).let { it.windows.isEmpty() && it.copies.isEmpty() })

		// A preview raising the bangs re-places the window's copy as the commit would.
		val raised = SeeThroughLayers.refresh(puppet.copy(drawables = puppet.drawables.map { if (it.id.raw == hair) it.copy(drawOrder = 995f) else it }), now.model)
		assertTrue(raised.drawables.single { it.id == window }.drawOrder > 995f, "the window's copy stays under the raised bangs")

		// A stored authored rig whose candidates followed the 3.6.5 rules (lashes and closed lines only) is not trusted.
		val stored = MaterializedRigCodec.encode(now.model.authored, "key")
		assertEquals(candidates, MaterializedRigCodec.decode(stored.header, stored.objects).authored.rig.seeThrough)
		val older = JsonObject(stored.header - "see_through_rules")
		assertEquals(null, MaterializedRigCodec.decode(older, stored.objects).authored.rig.seeThrough)
	}

	@Test fun projectsKeepTheEyeSettingTheySaved() {
		// 3.6.5 saved the setting, off by default, when it named only the eye lines: such a project keeps it off.
		val saved = WorkspaceSettingsCodec.encode(PipelineConfig(seeThroughEyes = false))
		assertEquals(false, WorkspaceSettingsCodec.decode(saved).seeThroughEyes)
		// One saved before the setting has none of it; a new one shows the eyes through.
		val before = JsonObject(WorkspaceSettingsCodec.encode(PipelineConfig()) - "seeThroughBrows" - "seeThroughEyes" - "seeThroughOpacity")
		assertEquals(false, WorkspaceSettingsCodec.decode(before).seeThroughEyes)
		assertEquals(true, WorkspaceSettingsCodec.decode(WorkspaceSettingsCodec.encode(PipelineConfig())).seeThroughEyes)
		// A document with the eyes off makes nothing of them.
		Studio.run(Files.createTempDirectory(temp, "off"), Characters.eyed().let { it.copy(settings = WorkspaceSettingsCodec.encode(
			PipelineConfig(atlasSize = 512, generatePhysics = false, exportMoc3 = false, seeThroughEyes = false))) }) {
			expect("off", now.model.rig.seeThroughWindows.isEmpty() && now.model.rig.seeThroughCopies.isEmpty()) {
				"eye see-through without the setting: ${now.model.rig.seeThroughCopies}" }
		}
	}
}
