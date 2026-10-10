package io.github.psd2live.core.quality

import io.github.psd2live.core.GeneratedOverrideIssue
import io.github.psd2live.core.GeneratedOverrideIssueKind
import kotlinx.serialization.json.*
import io.github.psd2live.core.legacy.SupersededEntryNote

/**
 * Stable codes for generated-override findings. The rule alone decides severity and category; callers and the
 * UI read the code, never a localized message.
 */
enum class GeneratedOverrideRule(val severity: String, val category: String) {
	/** The generator and the user's override both moved some points; the user's values were kept. */
	GENERATED_OVERRIDE_CONFLICT("warning", "quality"),
	/** The generated keyform the override edits is gone or reshaped; the override has no effect. */
	GENERATED_OVERRIDE_ORPHANED("warning", "quality"),
	/**
	 * A journal entry before a version 2 split addressed only what that split supersedes and failed there, so replay
	 * skipped it; what it did is gone with the superseded mesh, as the split itself already decided.
	 */
	SUPERSEDED_ENTRY_SKIPPED("info", "coverage"),
	;

	companion object {
		fun of(issue: GeneratedOverrideIssue): GeneratedOverrideRule = when (issue.kind) {
			GeneratedOverrideIssueKind.CONFLICT -> GENERATED_OVERRIDE_CONFLICT
			GeneratedOverrideIssueKind.ORPHANED -> GENERATED_OVERRIDE_ORPHANED
		}
	}
}

/**
 * Observation report for generated overrides (report version 3): two checks - one finding per override record that
 * did not apply as recorded, then one info finding per journal entry the replay skipped because it addresses only
 * what a later version 2 split supersedes - each in journal order. Neither blocks, so `can_proceed` is always true;
 * a project without either yields a complete report with no findings.
 */
object GeneratedOverrideQuality {
	const val VERSION = 3
	const val DOMAIN = "overrides"
	const val CHECK_ID = "generated_overrides"
	const val SCOPE = "Edits of swing and baked-simulation keyforms, merged per control point or vertex with the current generation. " +
		"Only whether each override applied is checked, not how the merged shape looks."
	const val SUPERSEDED_CHECK_ID = "superseded_entries"
	const val SUPERSEDED_SCOPE = "Journal entries before a version 2 split that failed only on the meshes it supersedes and replayed as no-ops."
	/** Evidence `kind` of a [GeneratedOverrideRule.SUPERSEDED_ENTRY_SKIPPED] finding. */
	const val SUPERSEDED_KIND = "superseded_entry"

	fun finding(note: SupersededEntryNote): JsonObject {
		val rule = GeneratedOverrideRule.SUPERSEDED_ENTRY_SKIPPED
		return buildJsonObject {
			put("code", rule.name)
			put("severity", rule.severity)
			put("category", rule.category)
			put("domain", DOMAIN)
			put("target", "journal:${note.index}")
			putJsonObject("evidence") {
				put("kind", SUPERSEDED_KIND)
				put("index", note.index)
				put("op", note.op)
				putJsonArray("targets") { note.targets.forEach { add(it) } }
				put("detail", note.detail)
			}
		}
	}

	fun finding(issue: GeneratedOverrideIssue): JsonObject {
		val rule = GeneratedOverrideRule.of(issue)
		return buildJsonObject {
			put("code", rule.name)
			put("severity", rule.severity)
			put("category", rule.category)
			put("domain", DOMAIN)
			put("target", issue.target)
			putJsonObject("evidence") {
				put("kind", issue.kind.wire)
				put("generator", issue.generator)
				putJsonObject("key") { issue.key.toSortedMap().forEach { (id, value) -> put(id, value) } }
				put("points", issue.points)
				put("total", issue.total)
				issue.reason?.let { put("reason", it.wire) }
			}
		}
	}

	fun report(issues: List<GeneratedOverrideIssue>, notes: List<SupersededEntryNote> = emptyList()): JsonObject = buildJsonObject {
		put("version", VERSION)
		put("domain", DOMAIN)
		put("fence", "observation")
		put("decision", if (issues.isEmpty() && notes.isEmpty()) "accept" else "accept_with_diagnostics")
		put("can_proceed", true)
		put("complete", true)
		put("scope", SCOPE)
		putJsonArray("checks") {
			add(buildJsonObject { put("id", CHECK_ID); put("scope", SCOPE); put("complete", true) })
			add(buildJsonObject { put("id", SUPERSEDED_CHECK_ID); put("scope", SUPERSEDED_SCOPE); put("complete", true) })
		}
		put("findings", JsonArray(issues.map(::finding) + notes.map(::finding)))
	}
}
