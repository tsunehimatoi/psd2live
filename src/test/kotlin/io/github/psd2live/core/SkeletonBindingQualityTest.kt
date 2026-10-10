package io.github.psd2live.core

import io.github.psd2live.core.SkeletonCharacterFixture.drawableOf
import io.github.psd2live.core.SkeletonCharacterFixture.layer
import io.github.psd2live.core.quality.SkeletonBindingQuality
import io.github.psd2live.core.quality.SkeletonBindingRule
import kotlinx.serialization.json.*
import kotlin.test.*

/** What the skeleton's reach report finds on a small figure with a torso and a left arm. */
class SkeletonBindingQualityTest {
	private companion object {
		val source = SkeletonCharacterFixture.source(
			layer("face", 5, intArrayOf(170, 30, 250, 110)),
			layer("top", 3, intArrayOf(160, 115, 260, 240)),
			layer("skirt", 1, intArrayOf(165, 235, 255, 290)),
			layer("sleeve", 4, intArrayOf(258, 120, 340, 148)),
		)
		val config = SkeletonCharacterFixture.config(mapOf(
			"face" to LayerClassificationOverride(tag = SemanticTag.FACE),
			"top" to LayerClassificationOverride(tag = SemanticTag.TOPWEAR),
			"skirt" to LayerClassificationOverride(tag = SemanticTag.BOTTOMWEAR),
			"sleeve" to LayerClassificationOverride(SemanticTag.HANDWEAR, Side.LEFT),
		))
		val plain by lazy { PSD2LivePipeline().buildPreview(source, config) }
	}

	private fun spec(vararg extra: String): SkeletonSpec = SkeletonSpec(bones = listOf(
		SkeletonBone("upper", "Upper body", null, BoneRole.UPPER_BODY, Side.NONE, 210f, 240f, 210f, 115f, listOf(plain.drawableOf("top"))),
		SkeletonBone("lower", "Lower body", null, BoneRole.LOWER_BODY, Side.NONE, 210f, 240f, 210f, 290f, listOf(plain.drawableOf("skirt"))),
		SkeletonBone("armL", "Upper arm", "upper", BoneRole.UPPER_ARM, Side.LEFT, 262f, 134f, 335f, 134f, listOf(plain.drawableOf("sleeve")) + extra),
	))

	private fun build(spec: SkeletonSpec, journal: List<JsonObject> = emptyList()) =
		PSD2LivePipeline().buildPreview(source, config.copy(rigEdits = RigEditOverlay(skeleton = spec, authoringJournal = journal))).rig.puppet

	private fun bind(id: String, parent: String) = buildJsonObject {
		put("op", "structure"); putJsonArray("edits") {
			add(buildJsonObject { put("action", "bind"); put("kind", "mesh"); put("id", id); put("parent_id", parent); put("space", "canvas") })
		}
	}

	@Test fun aGeneratedSkeletonReachesEverything() {
		val spec = spec()
		assertEquals(emptyList(), SkeletonBindingQuality.issues(build(spec), spec))
	}

	@Test fun aMeshHungBesideTheTorsoWarpAndABindingOfAMissingMeshAreReported() {
		val spec = spec("ArtMeshGone")
		val top = plain.drawableOf("top")
		val issues = SkeletonBindingQuality.issues(build(spec, listOf(bind(top, "DeformBodyZBreath"))), spec)
		assertEquals(listOf(SkeletonBindingRule.SKELETON_BINDING_MISSING to "ArtMeshGone", SkeletonBindingRule.SKELETON_TORSO_BYPASSED to top),
			issues.map { it.rule to it.mesh })
		val report = SkeletonBindingQuality.report(issues)
		assertTrue(report.getValue("can_proceed").jsonPrimitive.boolean)
		assertEquals(listOf("info", "warning"), report.getValue("findings").jsonArray.map { it.jsonObject.getValue("severity").jsonPrimitive.content })
	}

	@Test fun aSleeveTheJournalPlacesOnTheBreathWarpStillFollowsItsBonesAndTheTorso() {
		// The canvas skin keys it on the arm and the body halves: neither rule fires.
		val spec = spec()
		val issues = SkeletonBindingQuality.issues(build(spec, listOf(bind(plain.drawableOf("sleeve"), "DeformBodyZBreath"))), spec)
		assertEquals(emptyList(), issues)
	}
}
