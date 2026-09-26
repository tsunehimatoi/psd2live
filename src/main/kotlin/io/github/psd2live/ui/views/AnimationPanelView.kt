package io.github.psd2live.ui.views

import io.github.psd2live.core.SkeletonMotions
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactSectionHeader
import io.github.psd2live.ui.components.IconPause
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.previewControlCanvas
import io.github.psd2live.ui.state.previewPanelState
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

@Composable
internal fun AnimationPanelView(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val scrollState = rememberScrollState()
	val previewState = state.previewPanelState()

	Column(
		modifier = modifier
			.fillMaxSize()
			.background(colors.panelBackground)
			.verticalScroll(scrollState)
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		// 1. Master Playback & Quick Controls
		MasterPlaybackCard(viewModel, previewState, state.previewLive)

		// 2. Motions List Section
		val previewCanvas = state.previewControlCanvas()
		val motionsTitle = if (state.activeWorkspace.canvases.size > 1)
			"${viewModel.canvasTitle(previewCanvas)} · ${tr("animation.motionsTitle")}"
		else tr("animation.motionsTitle")
		CompactSectionHeader(title = motionsTitle)
		MotionsListSection(viewModel, previewState)

		Spacer(Modifier.height(8.dp))
	}
}

/** Master Playback & Controls Header Card */
@Composable
private fun MasterPlaybackCard(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	previewVisible: Boolean,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val isPlaying = state.animationEnabled && !state.meshOnly

	Box(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(4.dp))
			.background(colors.windowBackground)
			.border(BorderStroke(1.dp, if (isPlaying) colors.accent.copy(alpha = 0.5f) else colors.divider), RoundedCornerShape(4.dp))
			.padding(8.dp),
	) {
		Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
			// Row 1: Play/Pause, Reset, Status
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					CompactButton(
						text = if (isPlaying) tr("animation.pause") else tr("animation.play"),
						onClick = { viewModel.setAnimationEnabled(!state.animationEnabled) },
						leadingIcon = {
							if (isPlaying) IconPause(modifier = Modifier.size(11.dp), tint = colors.textPrimary)
							else IconPlay(modifier = Modifier.size(11.dp), tint = colors.accent)
						},
						height = 24.dp,
					)

					CompactButton(
						text = tr("animation.resetPose"),
						onClick = { viewModel.resetPreviewParameters() },
						leadingIcon = { IconReset(modifier = Modifier.size(11.dp), tint = colors.textPrimary) },
						height = 24.dp,
					)
				}

				// Status Badge
				val badgeBg = if (isPlaying) colors.accent.copy(alpha = 0.16f) else colors.controlHover
				val badgeTextColor = if (isPlaying) colors.accent else colors.textMuted
				Box(
					modifier = Modifier
						.clip(RoundedCornerShape(3.dp))
						.background(badgeBg)
						.border(BorderStroke(0.5.dp, if (isPlaying) colors.accent.copy(alpha = 0.35f) else Color.Transparent), RoundedCornerShape(3.dp))
						.padding(horizontal = 6.dp, vertical = 2.dp),
				) {
					Text(
						text = if (isPlaying) tr("animation.status.running") else tr("animation.status.paused"),
						style = typography.caption.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold),
						color = badgeTextColor,
					)
				}
			}

			Divider(color = colors.divider.copy(alpha = 0.4f), thickness = 0.5.dp)

			// Row 2: Mouse Eye Tracking Checkbox
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				CompactCheckbox(
					checked = state.mouseTrackingEnabled,
					onCheckedChange = { viewModel.setMouseTrackingEnabled(it) },
					label = tr("animation.mouseTracking"),
				)

				if (!previewVisible) {
					CompactButton(
						text = tr("window.showPreview"),
						onClick = { viewModel.ensurePreviewCanvas(focus = true) },
						height = 20.dp,
					)
				}
			}
		}
	}
}

/** Professional DCC Motions List Section */
@Composable
private fun MotionsListSection(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
) {
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		// Idle Motion
		DccMotionRow(
			name = "Idle",
			title = tr("export.motion.idle"),
			enabled = state.motionIdle,
			onEnabledChange = { viewModel.setMotionIdle(it) },
			onPlay = { viewModel.triggerMotion("Idle") },
			isLoop = true,
			durationSec = 6.0f,
			curveCount = 5,
			affectedParams = listOf(
				"ParamBreath" to "0.0 ~ 1.0",
				"ParamAngleZ" to "-2.0° ~ +2.0°",
				"ParamBodyAngleX" to "-1.2° ~ +1.2°",
				"ParamEyeLOpen" to "0.0 ~ 1.0",
				"ParamEyeROpen" to "0.0 ~ 1.0",
			),
			activeMotionName = viewModel.activeMotionName,
			isAnimRunning = state.animationEnabled,
		)

		// Blink Motion
		DccMotionRow(
			name = "Blink",
			title = tr("export.motion.blink"),
			enabled = state.motionBlink,
			onEnabledChange = { viewModel.setMotionBlink(it) },
			onPlay = { viewModel.triggerMotion("Blink") },
			isLoop = false,
			durationSec = 1.2f,
			curveCount = 2,
			affectedParams = listOf(
				"ParamEyeLOpen" to "0.0 ~ 1.0",
				"ParamEyeROpen" to "0.0 ~ 1.0",
			),
			activeMotionName = viewModel.activeMotionName,
			isAnimRunning = state.animationEnabled,
		)

		// Nod Motion
		DccMotionRow(
			name = "Nod",
			title = tr("export.motion.nod"),
			enabled = state.motionNod,
			onEnabledChange = { viewModel.setMotionNod(it) },
			onPlay = { viewModel.triggerMotion("Nod") },
			isLoop = false,
			durationSec = 2.0f,
			curveCount = 4,
			affectedParams = listOf(
				"ParamAngleY" to "-18.0° ~ +6.0°",
				"ParamBodyAngleY" to "-4.0° ~ +1.5°",
				"ParamEyeLOpen" to "0.75 ~ 1.0",
				"ParamEyeROpen" to "0.75 ~ 1.0",
			),
			activeMotionName = viewModel.activeMotionName,
			isAnimRunning = state.animationEnabled,
		)

		// Shake Motion
		DccMotionRow(
			name = "Shake",
			title = tr("export.motion.shake"),
			enabled = state.motionShake,
			onEnabledChange = { viewModel.setMotionShake(it) },
			onPlay = { viewModel.triggerMotion("Shake") },
			isLoop = false,
			durationSec = 2.0f,
			curveCount = 3,
			affectedParams = listOf(
				"ParamAngleX" to "-20.0° ~ +20.0°",
				"ParamBodyAngleX" to "-3.0° ~ +3.0°",
				"ParamAngleZ" to "-2.0° ~ +2.0°",
			),
			activeMotionName = viewModel.activeMotionName,
			isAnimRunning = state.animationEnabled,
		)

		// Skeleton one-shots: only the ones the current skeleton can actually play.
		val skeleton = state.previewModel?.config?.rigEdits?.skeleton
		for ((name, key, tracks) in listOf(
			Triple("TailSwing", "export.motion.tailSwing", SkeletonMotions.tailSwing(skeleton)),
			Triple("Crouch", "export.motion.crouch", SkeletonMotions.crouch(skeleton)),
			Triple("WeightShift", "export.motion.weightShift", SkeletonMotions.weightShift(skeleton)),
		)) {
			if (tracks.isEmpty()) continue
			DccMotionRow(
				name = name,
				title = tr(key),
				enabled = state.motionSkeleton,
				onEnabledChange = { viewModel.setMotionSkeleton(it) },
				onPlay = { viewModel.triggerMotion(name) },
				isLoop = false,
				durationSec = tracks.maxOf { it.second.last().first },
				curveCount = tracks.size,
				affectedParams = tracks.map { (id, points) ->
					val low = points.minOf { it.second }
					val high = points.maxOf { it.second }
					id to "%.1f ~ %.1f".format(low, high)
				},
				activeMotionName = viewModel.activeMotionName,
				isAnimRunning = state.animationEnabled,
			)
		}
	}
}

/** Professional DCC Single Motion Row */
@Composable
private fun DccMotionRow(
	name: String,
	title: String,
	enabled: Boolean,
	onEnabledChange: (Boolean) -> Unit,
	onPlay: () -> Unit,
	isLoop: Boolean,
	durationSec: Float,
	curveCount: Int,
	affectedParams: List<Pair<String, String>>,
	activeMotionName: String?,
	isAnimRunning: Boolean,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var showSettings by remember { mutableStateOf(false) }

	var fadeInSec by remember { mutableStateOf(0.5f) }
	var fadeOutSec by remember { mutableStateOf(0.5f) }
	var weightScale by remember { mutableStateOf(1.0f) }

	val isCurrentlyPlaying = (name.equals(activeMotionName, ignoreCase = true)) ||
		(name.equals("Idle", ignoreCase = true) && isAnimRunning && enabled && activeMotionName == null)

	Box(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(4.dp))
			.background(colors.windowBackground)
			.border(
				BorderStroke(1.dp, if (isCurrentlyPlaying) colors.accent.copy(alpha = 0.5f) else colors.divider),
				RoundedCornerShape(4.dp),
			)
			.padding(6.dp),
	) {
		Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
			// Main Row: Checkbox, Title, Badges, Play, Settings Panel Toggle
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					CompactCheckbox(
						checked = enabled,
						onCheckedChange = onEnabledChange,
					)

					Text(
						text = title,
						style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
						color = if (enabled) colors.textPrimary else colors.textMuted,
					)

					if (isCurrentlyPlaying) {
						Box(
							modifier = Modifier
								.clip(RoundedCornerShape(3.dp))
								.background(colors.accent.copy(alpha = 0.16f))
								.padding(horizontal = 4.dp, vertical = 1.dp),
						) {
							Text(
								text = if (isLoop) tr("animation.looping") else tr("animation.playing"),
								style = typography.caption.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Bold),
								color = colors.accent,
							)
						}
					}
				}

				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(4.dp),
				) {
					// Metadata Tags
					DccTag(label = if (isLoop) tr("animation.loop") else tr("animation.once"))
					DccTag(label = "${durationSec}s")
					DccTag(label = "$curveCount ${tr("animation.curves")}")

					Spacer(Modifier.width(2.dp))

					// Play Trigger
					CompactButton(
						text = tr("animation.trigger"),
						onClick = onPlay,
						leadingIcon = { IconPlay(modifier = Modifier.size(9.dp), tint = colors.accent) },
						height = 20.dp,
					)

					// Settings Panel Button
					CompactButton(
						text = if (showSettings) tr("animation.hideSettings") else tr("animation.settingsPanel"),
						onClick = { showSettings = !showSettings },
						height = 20.dp,
					)
				}
			}

			// Collapsible Professional Settings Panel
			AnimatedVisibility(
				visible = showSettings,
				enter = expandVertically() + fadeIn(),
				exit = shrinkVertically() + fadeOut(),
			) {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.clip(RoundedCornerShape(3.dp))
						.background(colors.panelBackground)
						.border(BorderStroke(0.5.dp, colors.divider), RoundedCornerShape(3.dp))
						.padding(6.dp),
					verticalArrangement = Arrangement.spacedBy(6.dp),
				) {
					Text(
						text = tr("animation.motionSettings"),
						style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
						color = colors.textPrimary,
					)

					// Transition Timers & Weights
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(8.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp),
							modifier = Modifier.weight(1f),
						) {
							Text(
								text = tr("animation.fadeInTime"),
								style = typography.caption.copy(fontSize = 9.sp),
								color = colors.textMuted,
							)
							CompactNumberSpinner(
								value = fadeInSec.toDouble(),
								onValueChange = { fadeInSec = it.toFloat().coerceIn(0f, 5f) },
								step = 0.1,
								decimals = 1,
								height = 20.dp,
								modifier = Modifier.width(54.dp),
							)
						}

						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp),
							modifier = Modifier.weight(1f),
						) {
							Text(
								text = tr("animation.fadeOutTime"),
								style = typography.caption.copy(fontSize = 9.sp),
								color = colors.textMuted,
							)
							CompactNumberSpinner(
								value = fadeOutSec.toDouble(),
								onValueChange = { fadeOutSec = it.toFloat().coerceIn(0f, 5f) },
								step = 0.1,
								decimals = 1,
								height = 20.dp,
								modifier = Modifier.width(54.dp),
							)
						}
					}

					Divider(color = colors.divider.copy(alpha = 0.3f), thickness = 0.5.dp)

					// Controlled Parameters
					Text(
						text = tr("animation.affectedParams"),
						style = typography.caption.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Medium),
						color = colors.textPrimary,
					)

					Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
						affectedParams.forEach { (paramId, range) ->
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.SpaceBetween,
								verticalAlignment = Alignment.CenterVertically,
							) {
								Text(
									text = paramId,
									style = typography.caption.copy(
										fontSize = 9.sp,
										fontFamily = FontFamily.Monospace,
										fontWeight = FontWeight.Medium,
									),
									color = colors.accent,
								)
								Text(
									text = range,
									style = typography.caption.copy(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
									color = colors.textMuted,
								)
							}
						}
					}
				}
			}
		}
	}
}

/** Mini DCC Tag Chip */
@Composable
private fun DccTag(label: String) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Box(
		modifier = Modifier
			.clip(RoundedCornerShape(2.dp))
			.background(colors.controlHover)
			.padding(horizontal = 4.dp, vertical = 1.dp),
	) {
		Text(
			text = label,
			style = typography.caption.copy(fontSize = 8.5.sp),
			color = colors.textMuted,
		)
	}
}
