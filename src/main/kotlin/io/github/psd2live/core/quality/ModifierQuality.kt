package io.github.psd2live.core.quality

import io.github.psd2live.core.modifier.ModifierSampler
import kotlinx.serialization.json.*

/**
 * Stable codes for what kept a modifier from its meshes as recorded ([ModifierSampler.Issue]). The rule alone decides
 * severity and category; callers and the UI read the code, never a localized message.
 */
enum class ModifierRule(val severity: String, val category: String) {
	/** A target mesh is gone; the modifier acts on the others. */
	MODIFIER_TARGET_MISSING("warning", "validity"),
	/** A setting is keyed on a parameter the rig no longer has; the modifier is left out of that mesh. */
	MODIFIER_PARAMETER_MISSING("warning", "validity"),
	/** The vertex group limiting the modifier is not on the mesh (or no longer matches it); it is left out of that mesh. */
	MODIFIER_VERTEX_GROUP_MISSING("warning", "validity"),
	/** The mesh's parents cannot be inverted at the default pose, so the modifier cannot be taken into its space. */
	MODIFIER_UNMAPPABLE("warning", "validity"),
	/** Sampling would make more keyforms than a mesh may hold; the stack is left out of that mesh. */
	MODIFIER_TOO_MANY_KEYFORMS("warning", "quality"),
	/** The keys allowed per parameter leave the keyforms farther from the modifier than its tolerance. */
	MODIFIER_TOLERANCE_EXCEEDED("info", "quality"),
	/** A stack asked to be a stage of its own depends on the mesh's own keys; it is merged into them instead. */
	MODIFIER_STAGE_UNAVAILABLE("info", "quality"),
	;

	companion object {
		fun of(kind: ModifierSampler.IssueKind): ModifierRule = when (kind) {
			ModifierSampler.IssueKind.TARGET_MISSING -> MODIFIER_TARGET_MISSING
			ModifierSampler.IssueKind.PARAMETER_MISSING -> MODIFIER_PARAMETER_MISSING
			ModifierSampler.IssueKind.VERTEX_GROUP_MISSING -> MODIFIER_VERTEX_GROUP_MISSING
			ModifierSampler.IssueKind.UNMAPPABLE -> MODIFIER_UNMAPPABLE
			ModifierSampler.IssueKind.TOO_MANY_KEYFORMS -> MODIFIER_TOO_MANY_KEYFORMS
			ModifierSampler.IssueKind.TOLERANCE_EXCEEDED -> MODIFIER_TOLERANCE_EXCEEDED
			ModifierSampler.IssueKind.STAGE_UNAVAILABLE -> MODIFIER_STAGE_UNAVAILABLE
		}
	}
}

/**
 * Observation report (version 1) of the document's modifiers: one finding per modifier and mesh the sampling could not
 * take as recorded, in stack order. Nothing blocks: `can_proceed` is always true, and a document without modifiers
 * yields a complete report with no findings.
 */
object ModifierQuality {
	const val VERSION = 1
	const val DOMAIN = "modifiers"
	const val CHECK_ID = "modifier_sampling"
	const val SCOPE = "Whether every enabled modifier reaches each of its meshes, and how close its keyforms come to it."

	fun finding(issue: ModifierSampler.Issue): JsonObject {
		val rule = ModifierRule.of(issue.kind)
		return buildJsonObject {
			put("code", rule.name)
			put("severity", rule.severity)
			put("category", rule.category)
			put("domain", DOMAIN)
			put("target", "mesh:${issue.target}")
			putJsonObject("evidence") {
				put("modifier", issue.modifier)
				if (issue.detail.isNotEmpty()) put("detail", issue.detail)
				issue.error?.let { put("error", it) }
			}
		}
	}

	fun report(issues: List<ModifierSampler.Issue>): JsonObject = buildJsonObject {
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
