package io.github.psd2live.ui

import androidx.compose.ui.graphics.Color
import io.github.psd2live.core.SkeletonSpec

/**
 * One color per bone, shared by everything that shows a skeleton: the bone tree, the bones on the
 * canvas and the art meshes bound to them. A mesh tinted in a bone's color is bound to that bone.
 */
internal object SkeletonPalette {
	private val colors = listOf(
		Color(0xFFEA7A78), Color(0xFF7AB7EA), Color(0xFFDBB468), Color(0xFF8ED390),
		Color(0xFFC69BE8), Color(0xFFED9F70), Color(0xFF6FD0CB), Color(0xFFE08CB7),
	)

	/** Anchor bones carry no deformer and share a neutral grey, so the limb colors stay distinct. */
	private val anchor = Color(0xFF9AA3AD)

	fun color(spec: SkeletonSpec, boneId: String): Color {
		val bone = spec.bone(boneId) ?: return anchor
		if (bone.role.anchor) return anchor
		val index = spec.bones.filterNot { it.role.anchor }.indexOfFirst { it.id == boneId }.coerceAtLeast(0)
		return colors[index % colors.size]
	}

	/** The bone [drawableId] is bound to, for tinting that mesh. */
	fun ownerColor(spec: SkeletonSpec, drawableId: String): Color? =
		spec.ownerOf(drawableId)?.let { color(spec, it.id) }
}
