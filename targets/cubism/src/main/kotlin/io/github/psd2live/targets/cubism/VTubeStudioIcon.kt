package io.github.psd2live.targets.cubism

import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.compile.PlacedRaster
import io.github.psd2live.format.compile.PngEncoder
import io.github.psd2live.format.compile.PoseGeometry
import io.github.psd2live.format.compile.render.IrColors
import io.github.psd2live.format.compile.render.SoftwareRasterizer
import io.github.psd2live.format.model.RigIR
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.render.eval.CpuDeformationEvaluator
import kotlin.math.roundToInt

private const val ICON_SIZE = 512
private const val ICON_PADDING = 24

/** Renders the model's default pose and fits its visible artwork into VTube Studio's square icon. */
internal fun vtubeStudioIcon(ir: RigIR, notices: List<ExportNotice>): ByteArray? {
	val omitted = notices.filterIsInstance<ExportNotice.UnsupportedChange>().mapNotNullTo(HashSet()) { notice ->
		when (notice.reason) {
			ExportNoticeReason.SketchPartIsNotRuntimeContent,
			ExportNoticeReason.HiddenPartOmittedByExportOption,
			ExportNoticeReason.HiddenDrawableOmittedByExportOption,
			ExportNoticeReason.UnkeyedDrawableUnderDeformerHasNoParentGeometry -> notice.subject
			else -> null
		}
	}
	val drawableRig = ir.copy(meshes = ir.meshes.filterNot { it.id in omitted }.map { mesh ->
		if (mesh.maskedBy.any { it in omitted }) mesh.copy(maskedBy = mesh.maskedBy.filterNot(omitted::contains)) else mesh
	})
	if (drawableRig.meshes.none { it.visible && it.geometry != null } || ir.textures.pages.isEmpty()) return null

	val geometry = CpuDeformationEvaluator().evaluate(PuppetIr.toPuppet(ir), emptyMap())
	val pose = PoseGeometry(
		// Convert world positions to canvas pixels (y down).
		geometry.worldPositions.entries.associate { (id, xy) -> id.raw to FloatArray(xy.size) { if (it % 2 == 0) xy[it] else -xy[it] } },
		geometry.opacity.mapKeys { it.key.raw }, geometry.drawOrder.mapKeys { it.key.raw },
	)
	val rasterizer = SoftwareRasterizer(drawableRig)
	val colors = IrColors(drawableRig)
	val meshColors = colors.at(emptyMap())
	val partColors = colors.parts(emptyMap())
	val overview = FrameSpec.canvas(ir, ICON_SIZE)
	val bounds = PlacedRaster(0, 0, rasterizer.render(pose, meshColors, overview, parts = partColors)).trimmed() ?: return null
	val overviewScale = minOf(overview.outputWidth / overview.width, overview.outputHeight / overview.height)
	val fit = (ICON_SIZE - 2 * ICON_PADDING).toFloat() / maxOf(bounds.image.width, bounds.image.height)
	val frame = FrameSpec(
		bounds.left / overviewScale, bounds.top / overviewScale,
		bounds.image.width / overviewScale, bounds.image.height / overviewScale,
		(bounds.image.width * fit).roundToInt().coerceAtLeast(1), (bounds.image.height * fit).roundToInt().coerceAtLeast(1),
	)
	val artwork = rasterizer.render(pose, meshColors, frame, parts = partColors)
	val pixels = IntArray(ICON_SIZE * ICON_SIZE)
	val left = (ICON_SIZE - artwork.width) / 2
	val top = (ICON_SIZE - artwork.height) / 2
	for (row in 0 until artwork.height) {
		artwork.argb.copyInto(pixels, (row + top) * ICON_SIZE + left, row * artwork.width, (row + 1) * artwork.width)
	}
	return PngEncoder.encode(ICON_SIZE, ICON_SIZE, pixels)
}
