package io.github.psd2live.core

import com.sun.jna.Pointer
import io.github.psd2live.render.GpuExecutor
import io.github.psd2live.render.GpuResources
import io.github.psd2live.util.PresentationToken
import java.lang.reflect.Proxy
import javax.swing.SwingUtilities
import kotlin.test.*

class CubismPresentationTest {
    @Test fun unchangedBundleStillCarriesTheNewActivationAndDropsQueuedOldDelivery() {
        val frames = ArrayList<PreviewFrame>()
        session(frames).use { session ->
            val old = token("old")
            val next = token("next")
            // Hold the EDT while both generations render; delivery must reject the queued old generation.
            SwingUtilities.invokeAndWait {
                session.load(bundle(1), emptyList(), old)
                session.render(request)
                session.load(bundle(1), emptyList(), next)
                session.render(request)
            }
            SwingUtilities.invokeAndWait { }
            assertTrue(frames.isNotEmpty())
            assertTrue(frames.all { it.presentation?.token === next })
        }
    }

    @Test fun failedLoadCannotTagAnOldFrameWithTheNewActivation() {
        val frames = ArrayList<PreviewFrame>()
        var fail = false
        session(frames) { fail }.use { session ->
            val old = token("old")
            session.load(bundle(1), emptyList(), old)
            session.render(request)
            SwingUtilities.invokeAndWait { }
            assertSame(old, assertNotNull(frames.last().presentation).token)
            val count = frames.size
            fail = true
            session.load(bundle(2), emptyList(), token("failed"))
            session.render(request)
            SwingUtilities.invokeAndWait { }
            assertEquals(count, frames.size)
        }
    }

    private fun token(revision: String) = PresentationToken(revision, revision, completed = null).apply { retain("view") }
    private fun bundle(version: Int) = CubismRuntimeBundle("model.model3.json", listOf(
        CubismRuntimeAsset("model.model3.json", "{}".encodeToByteArray()),
        CubismRuntimeAsset("model.moc3", byteArrayOf(version.toByte())),
    ))
    private val request = PreviewRenderRequest(16, 16, 1f, 0f, 0f, 0f, 0f, 0f,
        animationEnabled = false, nativeClock = false, parameterOverrides = emptyMap(), viewId = "view")

    private fun session(frames: MutableList<PreviewFrame>, fail: () -> Boolean = { false }): CubismSdkPreviewSession {
        val native = Proxy.newProxyInstance(CubismNativeApi::class.java.classLoader, arrayOf(CubismNativeApi::class.java)) { _, method, _ ->
            when (method.name) {
                "Live2D_InitOffscreen" -> 1
                "Live2D_CreateModelFromMemory" -> { check(!fail()) { "load failed" }; Pointer(1) }
                "Live2D_GetParameterCount", "Live2D_CopyParameterValues" -> 0
                else -> null
            }
        } as CubismNativeApi
        val executor = object : GpuExecutor {
            override val providesContext = false
            override fun execute(task: (GpuResources?) -> Unit): Boolean { task(null); return true }
        }
        return CubismSdkPreviewSession({ frames += it }, {}, null,
            { CubismNativeBinding(native, memoryModels = true, textureReplace = false) }, executor)
    }
}
