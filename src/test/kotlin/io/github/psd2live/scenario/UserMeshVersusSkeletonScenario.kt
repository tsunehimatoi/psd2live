package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceReadSession
import io.github.psd2live.core.*
import io.github.psd2live.core.quality.SkeletonBindingQuality
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import java.nio.file.Path
import kotlin.test.Test

/**
 * The user's own topology and the skeleton's regeneration commute. A sleeve gets the user's mesh and the skeleton is
 * put on, in either order; a bone then moves (the skeleton regenerates and merges again), Update Generated Rig has
 * nothing left to do, and the project reopens as it was. An older build's bad merge is found and repaired.
 */
class UserMeshVersusSkeletonScenario {
	@TempDir lateinit var temp: Path

	@Test fun rebuildThenSkeleton() = scenario(rebuildFirst = true)

	@Test fun skeletonThenRebuild() = scenario(rebuildFirst = false)

	private fun scenario(rebuildFirst: Boolean) = Studio.run(temp, Characters.figure()) {
		val generated = puppet.drawables.single { it.id.raw == mesh("sleeve_l") }.mesh!!.vertexCount
		if (rebuildFirst) { rebuildSleeve(); skeleton() } else { skeleton(); rebuildSleeve() }
		expect("user topology", puppet.drawables.single { it.id.raw == mesh("sleeve_l") }.mesh!!.vertexCount > generated) { "the sleeve lost the user's topology" }
		assertSkinned("both applied")
		skeleton(elbow = 310f, name = "move the elbow")
		assertSkinned("elbow moved")
		expect("update generated rig", !updateGenerated()) { "a settled rig still had something to update" }
		reopen()
		assertSkinned("reopened")
	}

	/** A skeleton regeneration an earlier build merged badly is reported, and Update Generated Rig repairs it once. */
	@Test fun aBadlyMergedRegenerationIsFoundAndRepaired() = Studio.run(temp, Characters.figure(), checks = emptySet()) {
		rebuildSleeve(); skeleton()
		expect("settled", WorkspaceReadSession(runtime.read()).staleRegenerations().isEmpty()) { "a fresh merge reports a repair" }
		val damaged = damageSkeletonCheckpoint()
		step("install the damaged journal") {
			forgetBuilds()
			runtime.install(runtime.state.value.state, now.projectId, damaged, builder.build(damaged), discardUnsaved = true)
		}
		val codes = inspect().getValue("quality").jsonObject.getValue("regeneration").jsonObject.getValue("findings").jsonArray
			.map { it.jsonObject.getValue("code").jsonPrimitive.content }
		expect("reported", "REGENERATION_REPAIR_AVAILABLE" in codes) { "the bad merge is not reported: $codes" }
		expect("repair", updateGenerated("repair")) { "the update repaired nothing" }
		Oracles.replays(this, "repaired")
		Oracles.quality(this, "repaired", emptySet())
		assertSkinned("repaired")
		expect("second update", !updateGenerated("update again")) { "a repaired rig still had something to update" }
	}

	private fun Studio.spec(elbow: Float) = SkeletonSpec(bones = listOf(
		SkeletonBone("upper", "Upper body", null, BoneRole.UPPER_BODY, Side.NONE, 210f, 240f, 210f, 115f, listOf(mesh("top"))),
		SkeletonBone("lower", "Lower body", null, BoneRole.LOWER_BODY, Side.NONE, 210f, 240f, 210f, 290f, listOf(mesh("skirt"))),
		SkeletonBone("armL", "Upper arm", "upper", BoneRole.UPPER_ARM, Side.LEFT, 262f, 134f, elbow, 134f, listOf(mesh("sleeve_l"))),
		SkeletonBone("foreL", "Forearm", "armL", BoneRole.FOREARM, Side.LEFT, elbow, 134f, 340f, 134f),
	))

	private fun Studio.skeleton(elbow: Float = 300f, name: String = "put the skeleton on") =
		edit(name, "skeleton_put", req("spec" to spec(elbow).toJson()))

	/** The user's own topology for the sleeve: every triangle split in four, committed as a mesh rebuild records it. */
	private fun Studio.rebuildSleeve() {
		val id = DrawableId(mesh("sleeve_l"))
		val mesh = puppet.drawables.single { it.id == id }.mesh!!
		journal("rebuild the sleeve's mesh", JsonArray(listOf(RasterMeshJournal.encode(puppet, id, subdivided(mesh)))))
	}

	private fun Studio.assertSkinned(label: String) {
		expect(label, SkeletonBindingQuality.issues(puppet, now.document.rigEdits.skeleton).isEmpty()) {
			"binding issues: ${SkeletonBindingQuality.issues(puppet, now.document.rigEdits.skeleton)}"
		}
		val sleeve = puppet.drawables.single { it.id.raw == mesh("sleeve_l") }
		val axes = sleeve.geometryGrid?.axes.orEmpty().map { it.parameterId }
		expect(label, sleeve.blendShapes.none { it.parameterId in axes }) { "a bone moves the sleeve twice (blend shape and keyform axis)" }
		expect(label, motion(mesh("sleeve_l"), "ParamArmLA", 60f) > 20.0) { "the upper arm does not turn the sleeve" }
		expect(label, motion(mesh("sleeve_l"), "ParamArmLB", 60f) > 2.0) { "the forearm does not bend the sleeve" }
		expect(label, motion(mesh("sleeve_l"), "ParamSkelUpperBody", 20f) > 10.0) { "the upper body does not carry the sleeve" }
		expect(label, motion(mesh("top"), "ParamSkelUpperBody", 20f) > 10.0) { "the upper body does not bend the top" }
	}

	/** The document with its skeleton checkpoint as an older build merged it: the sleeve off its bone, keyforms gone. */
	private fun Studio.damageSkeletonCheckpoint(): io.github.psd2live.project.WorkspaceDocument {
		val sleeve = DrawableId(mesh("sleeve_l"))
		val journal = now.document.rigEdits.authoringJournal
		val generated = journal.indices.filter { RigCheckpoint.isRecord(journal[it]) && RigCheckpoint.generated(journal[it]) != null }
		fun generation(at: Int) = PuppetIr.toIr(RigCheckpoint.generated(journal[at])!!.authored.rig.puppet)
		val index = generated.zipWithNext().last { (a, b) -> generation(a) != generation(b) }.second
		val stored = RigCheckpoint.decode(journal[index])
		val bad = stored.authored.copy(rig = stored.authored.rig.copy(puppet = stored.authored.rig.puppet.let { p ->
			p.copy(drawables = p.drawables.map { if (it.id != sleeve) it else it.copy(parentDeformerId = DeformerId("DeformBodyZBreath"),
				geometryGrid = null, blendShapes = emptyList()) })
		}))
		val record = RigCheckpoint.encode(bad, stored.bindingKey, RigCheckpoint.issues(journal[index]), generated = RigCheckpoint.generated(journal[index])!!.authored)
		return now.document.copy(rigEdits = now.document.rigEdits.copy(authoringJournal = journal.toMutableList().also { it[index] = record }))
	}
}

/** Every triangle of [mesh] split in four at its edges' midpoints: a denser mesh the generators would never make. */
internal fun subdivided(mesh: DrawableMesh): DrawableMesh {
	val positions = mesh.positions.toMutableList(); val uvs = mesh.uvs.toMutableList()
	val midpoints = HashMap<Pair<Int, Int>, Int>()
	fun mid(a: Int, b: Int): Int = midpoints.getOrPut(minOf(a, b) to maxOf(a, b)) {
		positions += (mesh.positions[a * 2] + mesh.positions[b * 2]) / 2; positions += (mesh.positions[a * 2 + 1] + mesh.positions[b * 2 + 1]) / 2
		uvs += (mesh.uvs[a * 2] + mesh.uvs[b * 2]) / 2; uvs += (mesh.uvs[a * 2 + 1] + mesh.uvs[b * 2 + 1]) / 2
		positions.size / 2 - 1
	}
	val triangles = ArrayList<Int>()
	for (t in mesh.indices.indices step 3) {
		val a = mesh.indices[t]; val b = mesh.indices[t + 1]; val c = mesh.indices[t + 2]
		val ab = mid(a, b); val bc = mid(b, c); val ca = mid(c, a)
		triangles += listOf(a, ab, ca, ab, b, bc, ca, bc, c, ab, bc, ca)
	}
	return DrawableMesh(positions.toFloatArray(), uvs.toFloatArray(), triangles.toIntArray())
}
