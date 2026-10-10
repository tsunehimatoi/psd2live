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
	/**
	 * An earlier build merged a regeneration differently from this one, and the difference reaches the rig (a bone that
	 * misses its mesh or moves it twice, a mesh left beside the torso): updating the generated rig repairs it.
	 */
	REGENERATION_REPAIR_AVAILABLE("warning", "quality"),
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
 * Observation report (version 2) of the journal's regenerations, two checks: one finding per issue the last checkpoint's
 * merge reported, in merge order, then one per earlier regeneration this build merges differently in a way that reaches
 * the rig ([RigMergeRepair.stale]), which an update of the generated rig repairs. Nothing blocks: `can_proceed` is
 * always true, and a project without either yields a complete report with no findings.
 */
object RegenerationQuality {
	const val VERSION = 2
	const val DOMAIN = "regeneration"
	const val CHECK_ID = "regeneration_merge"
	const val SCOPE = "What the last regeneration could not carry over cleanly when it merged the user's edits onto the generators' new output."
	const val REPAIR_CHECK_ID = "regeneration_repair"
	const val REPAIR_SCOPE = "Earlier regenerations this build merges differently, in a way that reaches the rig; updating the generated rig repairs them."

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

	/** A finding of the repair check: [issue] is a `remerged` issue of [RigMergeRepair.stale]. */
	fun repairFinding(issue: RigRegeneration.Issue): JsonObject = buildJsonObject {
		val rule = RegenerationRule.REGENERATION_REPAIR_AVAILABLE
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

	fun report(issues: List<RigRegeneration.Issue>, stale: List<RigRegeneration.Issue> = emptyList()): JsonObject = buildJsonObject {
		put("version", VERSION)
		put("domain", DOMAIN)
		put("fence", "observation")
		put("decision", if (issues.isEmpty() && stale.isEmpty()) "accept" else "accept_with_diagnostics")
		put("can_proceed", true)
		put("complete", true)
		put("scope", SCOPE)
		putJsonArray("checks") {
			add(buildJsonObject { put("id", CHECK_ID); put("scope", SCOPE); put("complete", true) })
			add(buildJsonObject { put("id", REPAIR_CHECK_ID); put("scope", REPAIR_SCOPE); put("complete", true) })
		}
		put("findings", JsonArray(issues.map(::finding) + stale.map(::repairFinding)))
	}
}
