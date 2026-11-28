package io.github.psd2live.tools

import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceShadowEdits
import io.github.psd2live.application.shadowPortrait
import io.github.psd2live.core.Bounds
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.RuntimeTarget
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test

class ShadowVisualTool {
    @Test fun render() = runBlocking<Unit> {
        requireTools()
        val out = output("shadow-visual")
        val phase = System.getenv("PSD2LIVE_SHADOW_VISUAL_PHASE") ?: "current"
        val builder = WorkspacePreviewBuilder()
        for (scale in listOf(1, 2)) {
            val doc = shadowPortrait(scale)
            val source = builder.build(doc)
            val generated = WorkspaceShadowEdits.apply(WorkspaceDocumentOperation("shadow_auto_generate", buildJsonObject {}), doc, source)
            val auto = builder.build(generated)
            val merged = WorkspaceShadowEdits.apply(WorkspaceDocumentOperation("shadow_world_set", buildJsonObject {
                put("composite_mode", "compat_merged")
            }), generated, auto)
            val angled = WorkspaceShadowEdits.apply(WorkspaceDocumentOperation("shadow_world_set", buildJsonObject {
                put("azimuth", -55); put("elevation", 34)
            }), generated, auto)
            val frame = Bounds(0f, 0f, 240f * scale, 250f * scale)
            val compatible = builder.build(merged)
            val images = listOf("Original" to source, "Automatic" to auto, "Merged" to compatible, "Light from left" to builder.build(angled))
                .map { (title, model) -> title to Renderer(model, 340).render(emptyMap(), frame) }
            sheet(images, File(out, "$phase-${scale}x.png"), columns = 4)
            if (phase != "before") {
                checkShadows(images.map { it.second })
                if (scale == 1) {
                    val native = Moc3Import.fromMocDocument(Moc3.read(auto.runtimeBundle.assets.single { it.path.endsWith(".moc3") }.bytes), null)
                    check(native.parts.any { it.groupMode is PartGroupMode.Isolated && it.composite.maskedBy.isNotEmpty() })
                    val legacy = builder.build(generated.copy(settings = WorkspaceSettingsCodec.encode(auto.config.copy(runtimeTarget = RuntimeTarget.Cubism50))))
                    val legacyMoc = Moc3Import.fromMocDocument(Moc3.read(legacy.runtimeBundle.assets.single { it.path.endsWith(".moc3") }.bytes), null)
                    check(legacyMoc.parts.none { it.groupMode is PartGroupMode.Isolated })
                    check(legacyMoc.drawables.any { it.maskedBy.size >= 2 && !it.invertMask })
                }
            }
            println("$scale x ${auto.modifierEvaluation?.entries?.map { it.instance.type to it.status }}")
        }
    }

    private fun checkShadows(images: List<BufferedImage>) {
        val (original, auto, merged, lightFromLeft) = images
        val zoom = original.height / 250f
        fun pixel(image: BufferedImage, x: Int, y: Int) = image.getRGB((x * zoom).toInt(), (y * zoom).toInt())
        fun darkening(image: BufferedImage, x: Int, y: Int): Int =
            (pixel(original, x, y) ushr 8 and 255) - (pixel(image, x, y) ushr 8 and 255)
        for (image in listOf(auto, merged)) {
            check(darkening(image, 77, 102) > 5 && darkening(image, 160, 98) > 5) { "Both sides of the fringe should cast a shadow" }
            check(darkening(image, 120, 185) > 8) { "The neck should receive the chin shadow" }
            for ((x, y) in listOf(120 to 127, 120 to 209, 40 to 140, 112 to 67, 120 to 229)) {
                check(pixel(original, x, y) == pixel(image, x, y)) { "Shadow spread to the clear area at ($x, $y)" }
            }
        }
        check(darkening(auto, 59, 132) > darkening(auto, 169, 139) + 6) { "Right light should shade the left cheek" }
        check(darkening(lightFromLeft, 169, 139) > darkening(lightFromLeft, 59, 132) + 6) { "Left light should shade the right cheek" }
    }
}
