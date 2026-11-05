package io.github.psd2live.core

import com.sun.jna.Pointer
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.targets.cubism.Moc3Target
import io.github.psd2live.render.GpuExecutor
import io.github.psd2live.render.GpuResources
import org.umamo.runtime.model.ParameterId
import java.lang.reflect.Proxy
import javax.swing.SwingUtilities
import kotlin.test.*

class CubismParameterIdsTest {
    @Test fun longPhysicsIdsReachTheNativeModelAndSurviveReload() {
        // Two generated outputs sharing more than a full MOC3 ID record must remain distinct.
        val prefix = "modifier:simulation:" + "abcdef".repeat(12)
        val ids = listOf(ParameterId("ParamAngleX"), ParameterId(prefix + "a"), ParameterId(prefix + "b"))
        fun bundle(maximum: Float): CubismRuntimeBundle {
            val rig = RigIR(Canvas(100f, 100f), parameters = ids.map { Parameter(it.raw, it.raw, -maximum, maximum, 0f) })
            val written = Moc3Target.bundle(rig, ExportOptions("test"))
            return CubismRuntimeBundle("test.model3.json", written.files.map { CubismRuntimeAsset(it.name, it.bytes) })
        }
        val initial = bundle(30f)
        val names = cubismWrittenParameterIds(initial, ids)
        assertEquals("ParamAngleX", names.getValue(ids.first()))
        assertEquals(3, names.values.toSet().size)
        assertTrue(names.values.all { it.encodeToByteArray().size < 64 })
        assertNotEquals(ids[1].raw, names.getValue(ids[1]))
        val values = linkedMapOf<String, Float>()
        val frames = ArrayList<PreviewFrame>()
        val statuses = ArrayList<String?>()
        val native = Proxy.newProxyInstance(CubismNativeApi::class.java.classLoader, arrayOf(CubismNativeApi::class.java)) { _, method, args ->
            when (method.name) {
                "Live2D_InitOffscreen" -> 1
                "Live2D_CreateModelFromMemory" -> Pointer(1)
                "Live2D_SetParameterValue" -> { values[args[1] as String] = args[2] as Float; null }
                "Live2D_GetParameterCount" -> ids.size
                "Live2D_CopyParameterValues" -> {
                    val output = args[1] as Pointer
                    ids.forEachIndexed { index, id -> output.setFloat(index * 4L, values[names.getValue(id)] ?: 0f) }
                    ids.size
                }
                else -> null
            }
        } as CubismNativeApi
        val executor = object : GpuExecutor {
            override val providesContext = false
            override fun execute(task: (GpuResources?) -> Unit): Boolean { task(null); return true }
        }
        CubismSdkPreviewSession({ frames += it }, { statuses += it }, null,
            { CubismNativeBinding(native, memoryModels = true, textureReplace = false) }, executor).use { session ->
            session.load(initial, ids)
            session.render(PreviewRenderRequest(16, 16, 1f, 0f, 0f, 1f / 60f, 0f, 0f,
                animationEnabled = false, nativeClock = false, parameterOverrides = mapOf(ids[1] to 0.7f, ids[2] to -0.4f)))
            SwingUtilities.invokeAndWait { }
            assertEquals(0.7f, values[names.getValue(ids[1])])
            assertEquals(-0.4f, values[names.getValue(ids[2])])
            assertFalse(values.containsKey(ids[1].raw))
            assertEquals(0.7f, frames.last().parameters[ids[1]])
            session.load(bundle(40f), ids)
            SwingUtilities.invokeAndWait { }
            assertEquals(0.7f, values[names.getValue(ids[1])])
            assertEquals(-0.4f, values[names.getValue(ids[2])])
            assertTrue(statuses.all { it == null || it == "ready" }, statuses.toString())
        }
    }
}
