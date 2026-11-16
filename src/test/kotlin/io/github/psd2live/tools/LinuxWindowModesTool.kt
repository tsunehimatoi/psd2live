package io.github.psd2live.tools

import androidx.compose.material.Text
import androidx.compose.ui.awt.ComposeWindow
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.WindowMode
import io.github.psd2live.ui.utils.MainWindowModes
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window
import java.util.concurrent.FutureTask
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** Opt-in WM checks: PSD2LIVE_TOOLS=1 ./gradlew :test --tests '*LinuxWindowModesTool'. */
class LinuxWindowModesTool {
    private fun <T> edt(block: () -> T): T {
        val task = FutureTask<T> { block() }
        if (EventQueue.isDispatchThread()) task.run() else EventQueue.invokeAndWait(task)
        return task.get()
    }

    private fun clientArea(window: Window): Rectangle = window.insets.let { border ->
        Rectangle(window.x + border.left, window.y + border.top,
            window.width - border.left - border.right, window.height - border.top - border.bottom)
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!edt(condition)) {
            check(System.nanoTime() < deadline) { "Timed out: $description" }
            Thread.sleep(50)
        }
    }

    private fun withWindow(block: (ComposeWindow) -> Unit) {
        requireTools()
        assumeTrue(MainWindowModes.isLinux && !GraphicsEnvironment.isHeadless())
        val mode = AppSettings.windowMode
        lateinit var uninstall: () -> Unit
        val window = edt {
            AppSettings.windowMode = WindowMode.WINDOWED
            ComposeWindow().apply {
                title = "PSD2Live Linux GUI regression"
                setContent { Text("Linux window regression") }
                setBounds(180, 160, 900, 600)
                uninstall = MainWindowModes.install(this)
                isVisible = true
                MainWindowModes.apply(this, WindowMode.WINDOWED)
            }
        }
        try {
            await("window shown") { window.isShowing }
            block(window)
        } finally {
            edt { uninstall(); window.dispose(); AppSettings.windowMode = mode }
        }
    }

    @Test @Timeout(40)
    fun windowManagerMaximizesAndRestoresGeometry() = withWindow { window ->
        // The WM's ordinary AWT frame is the oracle: getScreenInsets is wrong on unequal KDE monitors.
        val reference = edt {
            Frame("PSD2Live work area reference", window.graphicsConfiguration).apply {
                bounds = window.bounds
                isVisible = true
                extendedState = Frame.MAXIMIZED_BOTH
            }
        }
        try {
            await("reference maximized") { reference.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH }
            Thread.sleep(300)
            // KWin/AWT may keep invisible resize borders outside the work area. Compare client rectangles,
            // not outer frame bounds (which can differ even for two correctly maximized windows).
            val expected = edt { clientArea(reference) }
            edt { reference.dispose() }
            val normal = edt { window.bounds }
            edt { AppSettings.windowMode = WindowMode.MAXIMIZED; MainWindowModes.apply(window) }
            await("app maximized to WM work area $expected") { clientArea(window) == expected }
            edt { AppSettings.windowMode = WindowMode.WINDOWED; MainWindowModes.apply(window) }
            await("restored geometry") { window.bounds == normal }
            // An external state request must update the application without undoing the WM's geometry.
            edt { window.extendedState = Frame.MAXIMIZED_BOTH }
            await("external maximize followed") { AppSettings.windowMode == WindowMode.MAXIMIZED && clientArea(window) == expected }
            edt { window.extendedState = Frame.NORMAL }
            await("external restore followed") { AppSettings.windowMode == WindowMode.WINDOWED && window.bounds == normal }
            if (java.awt.Toolkit.getDefaultToolkit().isFrameStateSupported(Frame.MAXIMIZED_VERT)) {
                edt { window.extendedState = Frame.MAXIMIZED_BOTH }
                await("full maximize before partial") { AppSettings.windowMode == WindowMode.MAXIMIZED }
                edt { window.extendedState = Frame.MAXIMIZED_VERT }
                await("native partial maximize") { window.extendedState == Frame.MAXIMIZED_VERT && AppSettings.windowMode == WindowMode.WINDOWED }
                edt { MainWindowModes.apply(window) } // As Main.kt does after observing a settings change.
                Thread.sleep(200)
                assertEquals(Frame.MAXIMIZED_VERT, edt { window.extendedState })
                assertEquals(normal.size, edt { MainWindowModes.windowedSize(window) })
                edt { window.extendedState = Frame.NORMAL }
                await("partial maximize restored") { window.bounds == normal }
            }
        } finally { edt { reference.dispose() } }
    }

}
