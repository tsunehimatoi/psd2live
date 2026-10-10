package io.github.psd2live.core.quality

import io.github.psd2live.core.RigRegeneration
import kotlinx.serialization.json.*

/**
 * Stable codes for what the last regeneration merge ([RigRegeneration]) reported. The rule alone decides severity
 * and category; callers and the UI read the code, never a localized message.
 */
enum class RegenerationRule(val severity: String, val category: String) {
	/** A user object moved up under the nearest surviving deformer, where it was on the canvas. */
	REGENERATION_REHOMED("info", "quality"),
	/** An object or parameter the generators dropped stays because the user changed or uses it. */
	REGENERATION_RETIRED_KEPT("info", "quality"),
	/** The user and the generators changed the same value; the user's value stays. */
	REGENERATION_CONFLICT("warning", "quality"),
	/** The generators moved a changed object under another parent; the user's changes moved with it. */
	REGENERATION_REPARENTED("info", "quality"),
	/** The generators replaced a mesh's vertices; the user's per-vertex changes moved onto them. */
	REGENERATION_TOPOLOGY_MIGRATED("info", "quality"),
	/** The user's own mesh topology stays; the generators' new mesh was not taken. */
	REGENERATION_TOPOLOGY_KEPT("warning", "quality"),
	/** The user's own mesh topology stays, under the generators' new parent and with their new keyforms moved onto it. */
	REGENERATION_TOPOLOGY_FOLLOWED("info", "quality"),
	/** A user object followed its parent's contents to the deformer the generators handed them to. */
	REGENERATION_FOLLOWED("info", "quality"),
	/** An earlier build merged a regeneration differently; this build merged it again and carried the correction forward. */
	REGENERATION_REMERGED("info", "quality"),
	/** A user object lost what it refers to and was removed. */
	REGENERATION_DROPPED("warning", "validity"),
	;

	companion object {
		fun of(kind: RigRegeneration.IssueKind): RegenerationRule = when (kind) {
			RigRegeneration.IssueKind.REHOMED -> REGENERATION_REHOMED
			RigRegeneration.IssueKind.RETIRED_KEPT -> REGENERATION_RETIRED_KEPT
			RigRegeneration.IssueKind.CONFLICT -> REGENERATION_CONFLICT
			RigRegeneration.IssueKind.REPARENTED -> REGENERATION_REPARENTED
			RigRegeneration.IssueKind.TOPOLOGY_MIGRATED -> REGENERATION_TOPOLOGY_MIGRATED
			RigRegeneration.IssueKind.TOPOLOGY_KEPT -> REGENERATION_TOPOLOGY_KEPT
			RigRegeneration.IssueKind.TOPOLOGY_FOLLOWED -> REGENERATION_TOPOLOGY_FOLLOWED
			RigRegeneration.IssueKind.FOLLOWED -> REGENERATION_FOLLOWED
			RigRegeneration.IssueKind.REMERGED -> REGENERATION_REMERGED
			RigRegeneration.IssueKind.DROPPED -> REGENERATION_DROPPED
		}
	}
}

/**
 * Observation report (version 1) of the journal's last regeneration checkpoint: one finding per issue its merge
 * reported, in merge order. The merge already happened, so nothing blocks: `can_proceed` is always true, and a
 * project without a checkpoint, or whose merge carried everything over, yields a complete report with no findings.
 */
object RegenerationQuality {
	const val VERSION = 1
	const val DOMAIN = "regeneration"
	const val CHECK_ID = "regeneration_merge"
	const val SCOPE = "What the last regeneration could not carry over cleanly when it merged the user's edits onto the generators' new output."

	fun finding(issue: RigRegeneration.Issue): JsonObject {
		val rule = RegenerationRule.of(issue.kind)
		return buildJsonObject {
			put("code", rule.name)
			put("severity", rule.severity)
			put("category", rule.category)
			put("domain", DOMAIN)
			put("target", issue.target)
			putJsonObject("evidence") {
				put("kind", issue.kind.code)
				if (issue.detail.isNotEmpty()) put("detail", issue.detail)
			}
		}
	}

	fun report(issues: List<RigRegeneration.Issue>): JsonObject = buildJsonObject {
		put("version", VERSION)
		put("domain", DOMAIN)
		put("fence", "observation")
		put("decision", if (issues.isEmpty()) "accept" else "accept_with_diagnostics")
		put("can_proceed", true)
		put("complete", true)
		put("scope", SCOPE)
		putJsonArray("checks") { add(buildJsonObject { put("id", CHECK_ID); put("scope", SCOPE); put("complete", true) }) }
		put("findings", JsonArray(issues.map(::finding)))
	}
}
