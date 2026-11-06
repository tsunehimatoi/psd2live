package example

import io.github.psd2live.algorithms.binding.*
import org.umamo.runtime.model.ParameterId
import kotlin.test.*

class BrowRigTest {
    private fun fit(): BrowFit {
        val rgba = ByteArray(20 * 4 * 4) { 0xff.toByte() }
        return assertNotNull(BrowFit.of(20, 4, rgba, 10, 8, Side.LEFT, 1f to 0f, 50f, { x, y -> x to y }))
    }

    @Test fun capturedRasterProducesHeightAngleAndCoupledShapesWithoutHostTypes() {
        val fit = fit()
        assertEquals(Side.LEFT, fit.side)
        assertEquals(17, fit.centre.size)
        val rig = BrowRig(fit, 4f, BrowOptions(shape = true, blink = true, spring = true))
        val center = fit.toRig(.5f, BrowFit.sample(fit.centre, .5f))
        val neutral = rig.delta(center.first, center.second)
        assertEquals(0f, neutral.first, 1e-6f)
        assertEquals(0f, neutral.second, 1e-6f)
        val raised = rig.delta(center.first, center.second, y = 1f)
        assertEquals(-4f, raised.second, 1e-5f)
        val points = floatArrayOf(center.first, center.second)
        val frame = Bounds(0f, 0f, 100f, 100f)
        val grid = rig.grid(points, frame, ParameterId("browY"), ParameterId("browAngle"))
        assertEquals(2, grid.axes.size)
        val shapes = rig.shapes(points, frame, ParameterId("browX"), ParameterId("browForm"),
            ParameterId("eyeOpen"), true, ParameterId("smile"), ParameterId("spring"))
        assertEquals(setOf("spring", "browX", "browForm", "eyeOpen", "smile"), shapes.map { it.parameterId.raw }.toSet())
        assertContentEquals(floatArrayOf(center.first, center.second), points)
    }

    @Test fun fittingAndMotionRespectCancellation() {
        assertFailsWith<InterruptedException> {
            BrowFit.of(0, 0, byteArrayOf(), 0, 0, Side.NONE, 1f to 0f, 0f, { x, y -> x to y }) {
                throw InterruptedException()
            }
        }
        val rig = BrowRig(fit(), 4f, BrowOptions()) { throw InterruptedException() }
        assertFailsWith<InterruptedException> {
            rig.grid(floatArrayOf(10f, 10f), Bounds(0f, 0f, 100f, 100f), ParameterId("y"), ParameterId("a"))
        }
    }
}
