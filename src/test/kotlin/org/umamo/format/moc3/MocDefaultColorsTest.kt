package org.umamo.format.moc3

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.github.psd2live.core.PSD2LivePipeline
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.umamo.format.moc3.encode.MocEncoder
import org.umamo.format.moc3.encode.MocLowering
import org.umamo.format.moc3.moc.CanvasInfo
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.model.ArtMesh
import org.umamo.format.moc3.model.ArtMeshKeyform
import org.umamo.format.moc3.model.KeyformBinding
import org.umamo.format.moc3.model.RenderOrderChild
import org.umamo.format.moc3.model.RenderOrderGroup
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class MocDefaultColorsTest {
    private fun document(version: MocVersion) = MocDocument(
        version, CanvasInfo(100f, 50f, 50f, 100f, 100f), emptyList(), mapOf(0 to KeyformBinding(0, emptyList())),
        emptyList(), emptyList(), listOf(ArtMesh(
            id = "mesh", textureIndex = 0, constantFlags = 0,
            parentPartIndex = -1, parentDeformerIndex = -1,
            vertexUvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
            triangleIndices = shortArrayOf(0, 1, 2), maskDrawableIndices = intArrayOf(),
            keyformBindingIndex = 0,
            keyforms = listOf(ArtMeshKeyform(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), 1f, 0f, null, null)),
        )), emptyList(), listOf(RenderOrderGroup(listOf(RenderOrderChild(0, 0, 0)))),
    )

    @Test fun freshModelsIncludeIdentityColorsWithoutBlendShapes() {
        for (version in listOf(MocVersion.V42, MocVersion.V50, MocVersion.V53)) {
            val sections = MocLowering.lower(document(version))
            val counts = ByteBuffer.wrap(sections.getValue(0)).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1, counts.getInt(23 * 4), "$version multiply row count")
            assertEquals(1, counts.getInt(24 * 4), "$version screen row count")
            for ((section, expected) in listOf(
                Section.COLOR_MULTIPLY_R to 1f, Section.COLOR_MULTIPLY_G to 1f,
                Section.COLOR_MULTIPLY_B to 1f, Section.COLOR_SCREEN_R to 0f,
                Section.COLOR_SCREEN_G to 0f, Section.COLOR_SCREEN_B to 0f,
            )) {
                val bytes = sections.getValue(section.indexIn(version))
                assertEquals(4, bytes.size)
                assertEquals(expected, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).float)
            }
        }
    }

    interface Core : Library {
        fun csmHasMocConsistency(moc: Pointer, size: Int): Int
    }

    @Test fun freshModelsPassOfficialCoreConsistencyCheck() {
        val corePath = System.getenv("PSD2LIVE_TEST_CUBISM_CORE")
        assumeTrue(!corePath.isNullOrBlank(), "Set PSD2LIVE_TEST_CUBISM_CORE to the official Core library")
        val core = Native.load(corePath, Core::class.java)
        for (version in MocVersion.entries) {
            val bytes = MocEncoder.bakeFresh(version, document(version))
            Memory(bytes.size.toLong() + 63).use { memory ->
                val aligned = memory.share((64 - Pointer.nativeValue(memory) % 64) % 64)
                aligned.write(0, bytes, 0, bytes.size)
                assertEquals(1, core.csmHasMocConsistency(aligned, bytes.size), "$version rejected by Core")
            }
        }
    }

    @Test fun psdPreviewBundlesPassOfficialCoreConsistencyCheck() {
        val corePath = System.getenv("PSD2LIVE_TEST_CUBISM_CORE")
        assumeTrue(!corePath.isNullOrBlank(), "Set PSD2LIVE_TEST_CUBISM_CORE to the official Core library")
        val core = Native.load(corePath, Core::class.java)
        for (sample in listOf("ds", "tml")) {
            val preview = PSD2LivePipeline().buildPreview(Path.of("examples/$sample/psd-input/$sample.psd"))
            val bytes = preview.runtimeBundle.assets.single { it.path.endsWith(".moc3") }.bytes
            Memory(bytes.size.toLong() + 63).use { memory ->
                val aligned = memory.share((64 - Pointer.nativeValue(memory) % 64) % 64)
                aligned.write(0, bytes, 0, bytes.size)
                assertEquals(1, core.csmHasMocConsistency(aligned, bytes.size), "$sample preview rejected by Core")
            }
        }
    }
}
