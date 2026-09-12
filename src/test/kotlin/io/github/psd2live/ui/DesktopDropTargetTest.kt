package io.github.psd2live.ui

import io.github.psd2live.ui.utils.DesktopDropTarget
import java.awt.Canvas
import java.awt.Component
import java.awt.GraphicsEnvironment
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.nio.file.Files
import javax.swing.JFrame
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDropTargetTest {

	private var tempDir: File? = null

	@BeforeTest
	fun setup() {
		tempDir = Files.createTempDirectory("psd2live-drop-test").toFile()
		DesktopDropTarget.clearInstalledComponentsForTesting()
	}

	@AfterTest
	fun teardown() {
		tempDir?.deleteRecursively()
		DesktopDropTarget.clearInstalledComponentsForTesting()
	}

	@Test
	fun testResolveDropAction_PsdFile() {
		val psd1 = File(tempDir, "character.psd").apply { writeText("dummy psd") }
		val psd2 = File(tempDir, "model.PSD").apply { writeText("dummy PSD uppercase") }

		val action1 = DesktopDropTarget.resolveDropAction(listOf(psd1))
		assertIs<DesktopDropTarget.DroppedAction.OpenPsd>(action1)
		assertEquals(psd1.absolutePath, action1.file.absolutePath)
		assertNull(action1.outputDir)

		val action2 = DesktopDropTarget.resolveDropAction(listOf(psd2))
		assertIs<DesktopDropTarget.DroppedAction.OpenPsd>(action2)
		assertEquals(psd2.absolutePath, action2.file.absolutePath)
	}

	@Test
	fun testResolveDropAction_ProjectFile() {
		val proj1 = File(tempDir, "rig.psd2live").apply { writeText("dummy project") }
		val proj2 = File(tempDir, "RIG.PSD2LIVE").apply { writeText("dummy project uppercase") }

		val action1 = DesktopDropTarget.resolveDropAction(listOf(proj1))
		assertIs<DesktopDropTarget.DroppedAction.OpenProject>(action1)
		assertEquals(proj1.absolutePath, action1.file.absolutePath)

		val action2 = DesktopDropTarget.resolveDropAction(listOf(proj2))
		assertIs<DesktopDropTarget.DroppedAction.OpenProject>(action2)
		assertEquals(proj2.absolutePath, action2.file.absolutePath)
	}

	@Test
	fun testResolveDropAction_ProjectTakesPriorityOverPsd() {
		val psd = File(tempDir, "character.psd").apply { writeText("psd") }
		val proj = File(tempDir, "character.psd2live").apply { writeText("project") }

		// Even if PSD is first in list, project takes precedence
		val action = DesktopDropTarget.resolveDropAction(listOf(psd, proj))
		assertIs<DesktopDropTarget.DroppedAction.OpenProject>(action)
		assertEquals(proj.absolutePath, action.file.absolutePath)
	}

	@Test
	fun testResolveDropAction_PsdWithCompanionOutputDir() {
		val psd = File(tempDir, "character.psd").apply { writeText("psd") }
		val outDir = File(tempDir, "export_folder").apply { mkdirs() }

		val action = DesktopDropTarget.resolveDropAction(listOf(psd, outDir))
		assertIs<DesktopDropTarget.DroppedAction.OpenPsd>(action)
		assertEquals(psd.absolutePath, action.file.absolutePath)
		assertEquals(outDir.absolutePath, action.outputDir?.absolutePath)
	}

	@Test
	fun testResolveDropAction_DirectoryContainingProject() {
		val folder = File(tempDir, "project_dir").apply { mkdirs() }
		val innerProj = File(folder, "character.psd2live").apply { writeText("inner project") }

		val action = DesktopDropTarget.resolveDropAction(listOf(folder))
		assertIs<DesktopDropTarget.DroppedAction.OpenProject>(action)
		assertEquals(innerProj.absolutePath, action.file.absolutePath)
	}

	@Test
	fun testResolveDropAction_DirectoryContainingPsd() {
		val folder = File(tempDir, "artwork_dir").apply { mkdirs() }
		val innerPsd = File(folder, "character.psd").apply { writeText("inner psd") }

		val action = DesktopDropTarget.resolveDropAction(listOf(folder))
		assertIs<DesktopDropTarget.DroppedAction.OpenPsd>(action)
		assertEquals(innerPsd.absolutePath, action.file.absolutePath)
	}

	@Test
	fun testResolveDropAction_DirectoryWithoutModelTreatedAsOutputDir() {
		val folder = File(tempDir, "empty_dir").apply { mkdirs() }

		val action = DesktopDropTarget.resolveDropAction(listOf(folder))
		assertIs<DesktopDropTarget.DroppedAction.SetOutputDir>(action)
		assertEquals(folder.absolutePath, action.dir.absolutePath)
	}

	@Test
	fun testResolveDropAction_UnsupportedFiles() {
		val txt = File(tempDir, "readme.txt").apply { writeText("notes") }
		val action = DesktopDropTarget.resolveDropAction(listOf(txt))
		assertIs<DesktopDropTarget.DroppedAction.Unsupported>(action)
		assertTrue(action.message.isNotEmpty())

		val emptyAction = DesktopDropTarget.resolveDropAction(emptyList())
		assertIs<DesktopDropTarget.DroppedAction.Unsupported>(emptyAction)
	}

	@Test
	fun testExtractDroppedFiles_JavaFileListFlavor() {
		val file1 = File(tempDir, "a.psd").apply { writeText("a") }
		val file2 = File(tempDir, "b.psd2live").apply { writeText("b") }

		val transferable = object : Transferable {
			override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
			override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavor == DataFlavor.javaFileListFlavor
			override fun getTransferData(flavor: DataFlavor?): Any {
				if (flavor == DataFlavor.javaFileListFlavor) return listOf(file1, file2)
				throw UnsupportedFlavorException(flavor)
			}
		}

		val extracted = DesktopDropTarget.extractDroppedFiles(transferable)
		assertEquals(2, extracted.size)
		assertEquals(file1.absolutePath, extracted[0].absolutePath)
		assertEquals(file2.absolutePath, extracted[1].absolutePath)
	}

	@Test
	fun testExtractDroppedFiles_UriListFlavor() {
		val file = File(tempDir, "c.psd").apply { writeText("c") }
		val uriListFlavor = DataFlavor("text/uri-list;class=java.lang.String")

		val transferable = object : Transferable {
			override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(uriListFlavor)
			override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavor == uriListFlavor
			override fun getTransferData(flavor: DataFlavor?): Any {
				if (flavor == uriListFlavor) {
					return "# Comments\r\n" + file.toURI().toString() + "\r\n"
				}
				throw UnsupportedFlavorException(flavor)
			}
		}

		val extracted = DesktopDropTarget.extractDroppedFiles(transferable)
		assertEquals(1, extracted.size)
		assertEquals(file.absolutePath, extracted[0].absolutePath)
	}

	@Test
	fun testExtractDroppedFiles_StringFlavor() {
		val file = File(tempDir, "d.psd").apply { writeText("d") }

		val transferable = object : Transferable {
			override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor)
			override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavor == DataFlavor.stringFlavor
			override fun getTransferData(flavor: DataFlavor?): Any {
				if (flavor == DataFlavor.stringFlavor) {
					return "\"${file.absolutePath}\""
				}
				throw UnsupportedFlavorException(flavor)
			}
		}

		val extracted = DesktopDropTarget.extractDroppedFiles(transferable)
		assertEquals(1, extracted.size)
		assertEquals(file.absolutePath, extracted[0].absolutePath)
	}

	@Test
	fun testRecursiveInstallationOnComponents() {
		if (GraphicsEnvironment.isHeadless()) {
			return
		}

		val frame = JFrame("Test Frame")
		try {
			val panel = JPanel()
			val innerCanvas = Canvas()
			panel.add(innerCanvas)
			frame.contentPane.add(panel)

			val dummyListener = object : java.awt.dnd.DropTargetAdapter() {
				override fun drop(dtde: java.awt.dnd.DropTargetDropEvent?) {}
			}

			DesktopDropTarget.installRecursively(frame, dummyListener)

			assertNotNull(frame.dropTarget, "Frame must have dropTarget")
			assertNotNull(panel.dropTarget, "Panel must have dropTarget")
			assertNotNull(innerCanvas.dropTarget, "Inner Canvas must have dropTarget")

			// Dynamically added child component should automatically get dropTarget
			val dynamicCanvas = Canvas()
			panel.add(dynamicCanvas)
			assertNotNull(dynamicCanvas.dropTarget, "Dynamically added Canvas must receive dropTarget via ContainerListener")
		} finally {
			frame.dispose()
		}
	}
}

