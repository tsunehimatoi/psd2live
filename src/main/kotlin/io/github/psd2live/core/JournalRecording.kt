package io.github.psd2live.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.umamo.runtime.model.PuppetModel

/**
 * Edits made on the rig the user sees, written as journal entries for the stage they replay at.
 *
 * The user edits [shown] - the authored rig with every generator run on it - while the journal replays on the
 * authored rig itself, before the generators. So an entry keeps only what that stage reads: a key leaves out the axes
 * the generators add at their defaults ([GeneratedOverrides.journalOnly]), an edit of a keyform a generator makes is
 * merged after the generators as an override or refused ([GeneratedOverrides.capture], [GeneratedOverrides.ownedWrites]),
 * and a generator parameter an entry names is taken into the document before it ([GeneratedParameterAdoption]). The
 * entries then replay on the authored rig to what [shown] showed them doing.
 */
internal object JournalRecording {
	fun record(shown: PuppetModel, authored: PuppetModel, overlay: RigEditOverlay, edits: JsonArray,
			   skins: PrimitiveSkins = PrimitiveSkins.None, modifierReport: JsonObject? = null): List<JsonObject> {
        val graph = DocumentGenerators.graph(overlay, primitives = skins, modifierReport = modifierReport)
		val (_, compiled) = RigAuthoringJournal.compile(shown, GeneratedOverrides.capture(shown, overlay, edits, skins, graph))
		val owned = GeneratedOverrides.ownedWrites(shown, overlay, GeneratedOverrides.journalOnly(shown, authored, compiled), skins, authored, graph)
		return GeneratedParameterAdoption.adopted(shown, authored, owned)
	}
}
