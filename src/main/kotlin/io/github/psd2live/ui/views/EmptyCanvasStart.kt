package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.RecentFile
import io.github.psd2live.ui.state.RecentFileKind
import io.github.psd2live.ui.state.recentFilesFrom
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor
import java.nio.file.Files
import java.nio.file.Path

@Composable
internal fun EmptyCanvasStart(
	recentPaths: List<String>,
	enabled: Boolean,
	openProjectShortcut: String?,
	openPsdShortcut: String?,
	onStartTutorial: (() -> Unit)?,
	onOpenProject: (() -> Unit)?,
	onOpenPsd: (() -> Unit)?,
	onOpenRecent: (String) -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val recent = remember(recentPaths) {
		recentFilesFrom(recentPaths).filter { runCatching { Files.isRegularFile(Path.of(it.path)) }.getOrDefault(false) }
	}

	BoxWithConstraints(modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
		Column(
			modifier = Modifier
				.widthIn(max = 400.dp)
				.fillMaxWidth(0.88f)
				.heightIn(max = maxHeight)
				.verticalScroll(rememberScrollState()),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			if (onStartTutorial != null) {
				StartActionRow(
					title = tr("canvas.start.quickStart"),
					subtitle = tr("canvas.start.quickStart.desc"),
					enabled = enabled,
					emphasized = true,
					onClick = onStartTutorial,
				)
			}
			if (onOpenProject != null) {
				StartActionRow(
					title = tr("canvas.start.openProject"),
					subtitle = tr("canvas.start.openProject.desc"),
					shortcut = openProjectShortcut,
					enabled = enabled,
					onClick = onOpenProject,
				)
			}
			if (onOpenPsd != null) {
				StartActionRow(
					title = tr("canvas.start.importPsd"),
					subtitle = tr("canvas.start.importPsd.desc"),
					shortcut = openPsdShortcut,
					enabled = enabled,
					onClick = onOpenPsd,
				)
			}

			Text(
				text = tr("canvas.start.recent"),
				style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
				color = colors.textMuted,
				modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
			)

			if (recent.isEmpty()) {
				Text(
					text = tr("canvas.start.recent.empty"),
					style = typography.caption.copy(fontSize = 11.sp),
					color = colors.textDisabled,
					modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
				)
			} else {
				recent.forEach { file ->
					RecentFileRow(
						file = file,
						enabled = enabled,
						onClick = { onOpenRecent(file.path) },
					)
				}
			}

			Text(
				text = tr("canvas.start.dropHint"),
				style = typography.caption.copy(fontSize = 11.sp),
				color = colors.textDisabled,
				modifier = Modifier.padding(top = 12.dp),
			)
		}
	}
}

@Composable
private fun StartActionRow(
	title: String,
	subtitle: String,
	shortcut: String? = null,
	enabled: Boolean,
	emphasized: Boolean = false,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val hovered by interactionSource.collectIsHoveredAsState()
	val shape = RoundedCornerShape(6.dp)
	val background = when {
		!enabled -> colors.panelElevated.copy(alpha = 0.55f)
		emphasized && hovered -> colors.accentHover
		emphasized -> colors.accent
		hovered -> colors.controlHover
		else -> colors.panelElevated
	}
	val titleColor = when {
		!enabled -> colors.textDisabled
		emphasized -> colors.accentText
		else -> colors.textPrimary
	}
	val subColor = when {
		!enabled -> colors.textDisabled
		emphasized -> colors.accentText.copy(alpha = 0.82f)
		else -> colors.textMuted
	}
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(background, shape)
			.border(
				BorderStroke(1.dp, when {
					emphasized -> colors.accent
					hovered && enabled -> colors.borderHover
					else -> colors.divider
				}),
				shape,
			)
			.hoverable(interactionSource)
			.clickable(
				enabled = enabled,
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
			)
			.pointerHoverIcon(
				if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
				else PointerIcon.Default,
			)
			.padding(horizontal = 14.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(
				text = title,
				style = typography.body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
				color = titleColor,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = subtitle,
				style = typography.caption.copy(fontSize = 11.sp),
				color = subColor,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		if (!shortcut.isNullOrBlank()) {
			Text(
				text = shortcut,
				style = typography.monoSmall.copy(fontSize = 10.sp),
				color = subColor,
				maxLines = 1,
			)
		}
	}
}

@Composable
private fun RecentFileRow(
	file: RecentFile,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val hovered by interactionSource.collectIsHoveredAsState()
	val shape = RoundedCornerShape(5.dp)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(if (hovered && enabled) colors.controlHover else colors.panelElevated, shape)
			.border(
				BorderStroke(1.dp, if (hovered && enabled) colors.borderHover else colors.divider),
				shape,
			)
			.hoverable(interactionSource)
			.clickable(
				enabled = enabled,
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
			)
			.pointerHoverIcon(
				if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
				else PointerIcon.Default,
			)
			.padding(horizontal = 12.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(10.dp),
	) {
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(
				text = file.name,
				style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
				color = if (enabled) colors.textPrimary else colors.textDisabled,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (file.directory.isNotEmpty()) {
				Text(
					text = file.directory,
					style = typography.caption.copy(fontSize = 10.sp),
					color = colors.textMuted,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
		Text(
			text = tr(
				when (file.kind) {
					RecentFileKind.PROJECT -> "canvas.start.kind.project"
					RecentFileKind.PSD -> "canvas.start.kind.psd"
				},
			),
			style = typography.caption.copy(fontSize = 10.sp),
			color = colors.textMuted,
		)
	}
}
