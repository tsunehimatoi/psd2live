package io.github.psd2live.targets.web

import io.github.psd2live.format.compile.Compiler
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.runtime.P2lrt
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WebTargetTest {
	@Test fun playerUrlLoadsTheExportedRigWithReservedCharactersInItsName() {
		val name = "模型 A+B #1%"
		val rig = RigIR(Canvas(32f, 32f), emptyList())
		val files = mutableMapOf<String, ByteArray>()
		Compiler.export(WebTarget, rig, ExportOptions(name)) { path, bytes -> files[path] = bytes }

		val html = files.getValue("index.html").decodeToString()
		val rigUrl = assertNotNull(Regex(""""([^"]+\.p2lrt)"""").find(html)).groupValues[1]
		val uri = URI(rigUrl)
		assertNull(uri.rawFragment)
		assertEquals("$name.p2lrt", uri.path)
		assertContentEquals(P2lrt.write(rig), files.getValue(uri.path))
	}
}
