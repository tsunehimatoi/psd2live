package io.github.psd2live.core.sim

import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.MeshGeometry
import io.github.psd2live.format.model.RigIR
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectKeyformProjectionTest {
    @Test fun largeKeyFollowsTheCurvedParentAndLeavesRestVerticesFixed() {
        val local = floatArrayOf(0.2f, 0.2f, 0.8f, 0.25f, 0.35f, 0.85f)
        val base = PuppetIr.toPuppet(RigIR(Canvas(200f, 200f), emptyList(), meshes = listOf(
            Mesh("hem", "Hem", null, geometry = MeshGeometry(Floats.of(local), Floats.of(local), Ints.values(0, 1, 2)), offsets = null),
        )))
        val warp = Deformer.Warp(DeformerId("warp"), "Warp", null, null, 1, 1, true,
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(
                floatArrayOf(10f, 5f, 110f, 10f, 0f, 118f, 196f, 172f),
            )))))
        val mesh = base.drawables.single().copy(parentDeformerId = warp.id)
        val model = base.copy(deformers = listOf(warp), drawables = listOf(mesh))
        val scene = SimScene.build(model, RigSimEdit("sim", "Hem", SimKind.CLOTH, listOf(mesh.id.raw)))
        val space = SimSpace(model, scene)
        val map = requireNotNull(drawableSpaceMapping(model, emptyMap(), mesh.id))
        val rest = map.localToWorld(local)
        val target = map.localToWorld(floatArrayOf(0.2f, 0.2f, 0.54f, 0.56f, 0.72f, 0.65f))
        val delta = space.fromPx(FloatArray(rest.size) { target[it] - rest[it] })
        val replay = map.localToWorld(FloatArray(local.size) { local[it] + delta[it] })
        assertTrue(delta[0] == 0f && delta[1] == 0f)
        for (k in target.indices) assertEquals(target[k], replay[k], 0.11f)
    }
}
