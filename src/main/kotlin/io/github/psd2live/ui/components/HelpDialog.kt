package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.utils.DesktopUtils
import java.awt.Cursor
import kotlinx.coroutines.delay

enum class HelpTab {
	QUICK_START,
	PSD_SPEC,
	SHORTCUTS,
	COMMUNITY_LINKS,
	ABOUT,
}

enum class TutorialScenario {
	STANDARD,
	CUSTOM_LAYERS,
	PREVIEW_ADJUST,
	UPSCALE,
	VARIANTS,
	AGENT_MCP,
}

@Composable
fun HelpDialog(
	initialTab: HelpTab = HelpTab.QUICK_START,
	onDismiss: () -> Unit,
	onOpenUrl: (String) -> Unit = { DesktopUtils.openBrowser(it) },
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	var selectedTab by remember { mutableStateOf(initialTab) }
	var copyNotification by remember { mutableStateOf<String?>(null) }

	LaunchedEffect(copyNotification) {
		if (copyNotification != null) {
			delay(2500)
			copyNotification = null
		}
	}

	val tabTitles = listOf(
		tr("help.tab.quickstart"),
		tr("help.tab.psd_spec"),
		tr("help.tab.shortcuts"),
		tr("help.tab.links"),
		tr("help.tab.about"),
	)

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(Color(0x99000000))
			.clickable(onClick = onDismiss),
		contentAlignment = Alignment.Center,
	) {
		Column(
			modifier = Modifier
				.width(720.dp)
				.heightIn(max = 660.dp)
				.background(colors.panelBackground, RoundedCornerShape(3.dp))
				.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(3.dp))
				.clickable(enabled = false) {}
				.padding(16.dp),
		) {
			// Title Bar
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Text(
						text = tr("help.dialog.title"),
						style = typography.title.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
						color = colors.textPrimary,
					)
					Text(
						text = "v0.6.0",
						style = typography.monoSmall.copy(fontSize = 10.sp),
						color = colors.textMuted,
					)
				}
				CompactIconButton(
					onClick = onDismiss,
					modifier = Modifier.size(22.dp),
				) {
					Text(
						text = "✕",
						style = typography.caption.copy(fontWeight = FontWeight.Bold),
						color = colors.textMuted,
					)
				}
			}

			Spacer(Modifier.height(10.dp))

			// Tab Navigation
			CompactTabBar(
				tabs = tabTitles,
				selectedIndex = selectedTab.ordinal,
				onTabSelected = { selectedTab = HelpTab.entries[it] },
				modifier = Modifier.fillMaxWidth(),
			)

			Spacer(Modifier.height(12.dp))

			// Scrollable Content
			Column(
				modifier = Modifier
					.weight(1f, fill = false)
					.fillMaxWidth()
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				when (selectedTab) {
					HelpTab.QUICK_START -> DccTutorialContent(onOpenUrl)
					HelpTab.PSD_SPEC -> DccPsdSpecContent(onOpenUrl)
					HelpTab.SHORTCUTS -> DccShortcutsContent()
					HelpTab.COMMUNITY_LINKS -> DccCommunityLinksContent(
						onOpenUrl = onOpenUrl,
						onCopyLink = { url ->
							DesktopUtils.copyToClipboard(url)
							copyNotification = tr("help.links.copied")
						},
					)
					HelpTab.ABOUT -> DccAboutContent()
				}
			}

			Spacer(Modifier.height(12.dp))
			Divider(color = colors.divider, thickness = 1.dp)
			Spacer(Modifier.height(10.dp))

			// Bottom Actions & Status
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				if (copyNotification != null) {
					Text(
						text = copyNotification ?: "",
						style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
						color = colors.accent,
					)
				} else {
					Text(
						text = "PSD2Live / GPL-3.0 / tsunehimatoi",
						style = typography.caption.copy(fontSize = 11.sp),
						color = colors.textMuted,
					)
				}

				CompactButton(
					text = tr("dialog.ok"),
					onClick = onDismiss,
					isPrimary = true,
					height = 24.dp,
				)
			}
		}
	}
}

// ---------------------------------------------------------------------------
// 1. DCC Tutorial Content (Flat, Technical, Zero Emojis)
// ---------------------------------------------------------------------------
@Composable
private fun DccTutorialContent(onOpenUrl: (String) -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	var currentScenario by remember { mutableStateOf<TutorialScenario?>(null) }

	if (currentScenario == null) {
		// Triage / Scenario guidance view asking to select workflow
		Column(
			modifier = Modifier.fillMaxWidth(),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(
				text = tr("help.tutorial.triage.title"),
				style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
				color = colors.textPrimary,
			)

			Text(
				text = tr("help.tutorial.triage.section.core"),
				style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
				color = colors.accent,
			)

			DccTriageOptionRow(
				title = tr("help.tutorial.triage.standard.title"),
				desc = tr("help.tutorial.triage.standard.desc"),
				onClick = { currentScenario = TutorialScenario.STANDARD },
			)

			DccTriageOptionRow(
				title = tr("help.tutorial.triage.custom.title"),
				desc = tr("help.tutorial.triage.custom.desc"),
				onClick = { currentScenario = TutorialScenario.CUSTOM_LAYERS },
			)

			Spacer(Modifier.height(2.dp))

			Text(
				text = tr("help.tutorial.triage.section.advanced"),
				style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
				color = colors.accent,
			)

			DccTriageOptionRow(
				title = tr("help.tutorial.triage.preview.title"),
				desc = tr("help.tutorial.triage.preview.desc"),
				onClick = { currentScenario = TutorialScenario.PREVIEW_ADJUST },
			)

			DccTriageOptionRow(
				title = tr("help.tutorial.triage.upscale.title"),
				desc = tr("help.tutorial.triage.upscale.desc"),
				onClick = { currentScenario = TutorialScenario.UPSCALE },
			)

			DccTriageOptionRow(
				title = tr("help.tutorial.triage.variants.title"),
				desc = tr("help.tutorial.triage.variants.desc"),
				onClick = { currentScenario = TutorialScenario.VARIANTS },
			)

			DccTriageOptionRow(
				title = tr("help.tutorial.triage.agent.title"),
				desc = tr("help.tutorial.triage.agent.desc"),
				onClick = { currentScenario = TutorialScenario.AGENT_MCP },
				isWarning = true,
			)
		}
	} else {
		// Navigation bar: Back button to Triage + Scenario switcher tabs
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			CompactButton(
				text = tr("help.tutorial.triage.back"),
				onClick = { currentScenario = null },
				height = 24.dp,
			)

			// Segmented Scenario Selector (Flat DCC Toolbar style)
			Row(
				modifier = Modifier
					.background(colors.windowBackground)
					.border(BorderStroke(1.dp, colors.divider)),
			) {
				TutorialScenario.entries.forEach { scenario ->
					val isSelected = scenario == currentScenario
					val label = when (scenario) {
						TutorialScenario.STANDARD -> tr("help.tutorial.scenario.standard")
						TutorialScenario.CUSTOM_LAYERS -> tr("help.tutorial.scenario.custom")
						TutorialScenario.PREVIEW_ADJUST -> tr("help.tutorial.scenario.preview")
						TutorialScenario.UPSCALE -> tr("help.tutorial.scenario.upscale")
						TutorialScenario.VARIANTS -> tr("help.tutorial.scenario.variants")
						TutorialScenario.AGENT_MCP -> tr("help.tutorial.scenario.agent")
					}

					Box(
						modifier = Modifier
							.background(if (isSelected) colors.controlActive else Color.Transparent)
							.clickable { currentScenario = scenario }
							.padding(horizontal = 6.dp, vertical = 4.dp),
						contentAlignment = Alignment.Center,
					) {
						Text(
							text = label,
							style = typography.caption.copy(
								fontSize = 10.5.sp,
								fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
							),
							color = if (isSelected) colors.accentText else colors.textMuted,
						)
					}
				}
			}
		}

		Spacer(Modifier.height(4.dp))

		when (currentScenario) {
			TutorialScenario.STANDARD -> DccStandardWorkflowScenario(onNavigate = { currentScenario = it })
			TutorialScenario.CUSTOM_LAYERS -> DccCustomLayersScenario(onNavigate = { currentScenario = it })
			TutorialScenario.PREVIEW_ADJUST -> DccPreviewAdjustScenario()
			TutorialScenario.UPSCALE -> DccUpscaleScenario()
			TutorialScenario.VARIANTS -> DccVariantsScenario()
			TutorialScenario.AGENT_MCP -> DccAgentMcpScenario()
			null -> {}
		}
	}
}

@Composable
private fun DccTriageOptionRow(
	title: String,
	desc: String,
	onClick: () -> Unit,
	isWarning: Boolean = false,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(if (isHovered) colors.controlActive else colors.panelElevated, RoundedCornerShape(2.dp))
			.border(BorderStroke(1.dp, if (isHovered) colors.accent else colors.divider), RoundedCornerShape(2.dp))
			.hoverable(interactionSource)
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
			.padding(horizontal = 12.dp, vertical = 10.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Text(
			text = title,
			style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
			color = if (isWarning) colors.warning else colors.textPrimary,
		)
		Text(
			text = desc,
			style = typography.caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
			color = colors.textMuted,
		)
	}
}

@Composable
private fun DccStandardWorkflowScenario(onNavigate: (TutorialScenario) -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Text(
		text = tr("help.tutorial.standard.intro"),
		style = typography.caption.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
		color = colors.textPrimary,
	)

	// Step 1: 导入 PSD
	DccStepRow(
		title = tr("help.tutorial.standard.step1.title"),
		desc = tr("help.tutorial.standard.step1.desc"),
	)

	// Step 2: 一键导出
	DccStepRow(
		title = tr("help.tutorial.standard.step2.title"),
		desc = tr("help.tutorial.standard.step2.desc"),
	)

	Spacer(Modifier.height(4.dp))

	// 可选流程（带链接跳转到独立标签页）
	DccOptionalWorkflowsSection(onNavigate = onNavigate)
}

@Composable
private fun DccCustomLayersScenario(onNavigate: (TutorialScenario) -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Text(
		text = tr("help.tutorial.custom.intro"),
		style = typography.caption.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
		color = colors.textPrimary,
	)

	DccStepRow(title = tr("help.tutorial.custom.step1.title"), desc = tr("help.tutorial.custom.step1.desc"))
	DccStepRow(title = tr("help.tutorial.custom.step2.title"), desc = tr("help.tutorial.custom.step2.desc"))
	DccStepRow(title = tr("help.tutorial.custom.step3.title"), desc = tr("help.tutorial.custom.step3.desc"))

	Spacer(Modifier.height(4.dp))

	// 可选流程（带链接跳转到独立标签页）
	DccOptionalWorkflowsSection(onNavigate = onNavigate)
}

@Composable
private fun DccPreviewAdjustScenario() {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Text(
		text = tr("help.tutorial.preview.intro"),
		style = typography.caption.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
		color = colors.textPrimary,
	)

	DccStepRow(title = tr("help.tutorial.preview.step1.title"), desc = tr("help.tutorial.preview.step1.desc"))
	DccStepRow(title = tr("help.tutorial.preview.step2.title"), desc = tr("help.tutorial.preview.step2.desc"))
}

@Composable
private fun DccUpscaleScenario() {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Text(
		text = tr("help.tutorial.upscale.intro"),
		style = typography.caption.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
		color = colors.textPrimary,
	)

	DccStepRow(title = tr("help.tutorial.upscale.step1.title"), desc = tr("help.tutorial.upscale.step1.desc"))
	DccStepRow(title = tr("help.tutorial.upscale.step2.title"), desc = tr("help.tutorial.upscale.step2.desc"))
}

@Composable
private fun DccOptionalWorkflowsSection(onNavigate: (TutorialScenario) -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.panelElevated, RoundedCornerShape(2.dp))
			.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(2.dp))
			.padding(10.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text(
			text = tr("help.tutorial.optional.title"),
			style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
			color = colors.textPrimary,
		)
		Text(
			text = tr("help.tutorial.optional.desc"),
			style = typography.caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
			color = colors.textMuted,
		)

		Column(
			verticalArrangement = Arrangement.spacedBy(6.dp),
		) {
			DccOptionalPointRow(
				title = tr("help.tutorial.optional.opt1.title"),
				desc = tr("help.tutorial.optional.opt1.desc"),
				onClick = { onNavigate(TutorialScenario.PREVIEW_ADJUST) },
			)
			DccOptionalPointRow(
				title = tr("help.tutorial.optional.opt2.title"),
				desc = tr("help.tutorial.optional.opt2.desc"),
				onClick = { onNavigate(TutorialScenario.UPSCALE) },
			)
			DccOptionalPointRow(
				title = tr("help.tutorial.optional.opt3.title"),
				desc = tr("help.tutorial.optional.opt3.desc"),
				onClick = { onNavigate(TutorialScenario.VARIANTS) },
			)
			DccOptionalPointRow(
				title = tr("help.tutorial.optional.opt4.title"),
				desc = tr("help.tutorial.optional.opt4.desc"),
				onClick = { onNavigate(TutorialScenario.AGENT_MCP) },
			)
		}
	}
}

@Composable
private fun DccOptionalPointRow(
	title: String,
	desc: String,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(if (isHovered) colors.controlActive else colors.windowBackground, RoundedCornerShape(2.dp))
			.border(BorderStroke(1.dp, if (isHovered) colors.accent else colors.divider), RoundedCornerShape(2.dp))
			.hoverable(interactionSource)
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
			.padding(horizontal = 10.dp, vertical = 8.dp),
		verticalArrangement = Arrangement.spacedBy(2.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = title,
				style = typography.caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
				color = colors.accent,
			)
			Text(
				text = tr("help.tutorial.optional.action"),
				style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
				color = colors.accent,
			)
		}
		Text(
			text = desc,
			style = typography.caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
			color = colors.textMuted,
		)
	}
}

@Composable
private fun DccStepRow(title: String, desc: String, badge: String? = null) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.panelElevated, RoundedCornerShape(2.dp))
			.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(2.dp))
			.padding(10.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			if (badge != null) {
				Text(
					text = badge,
					style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
					color = colors.accent,
				)
			}
			Text(
				text = title,
				style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
				color = colors.textPrimary,
			)
		}
		Text(
			text = desc,
			style = typography.caption.copy(fontSize = 11.sp, lineHeight = 16.5.sp),
			color = colors.textMuted,
		)
	}
}

@Composable
private fun DccVariantsScenario() {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Text(
		text = tr("help.tutorial.variants.intro"),
		style = typography.caption.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
		color = colors.textPrimary,
	)

	DccStepRow(title = tr("help.tutorial.variants.step1.title"), desc = tr("help.tutorial.variants.step1.desc"))
	DccStepRow(title = tr("help.tutorial.variants.step2.title"), desc = tr("help.tutorial.variants.step2.desc"))
	DccStepRow(title = tr("help.tutorial.variants.step3.title"), desc = tr("help.tutorial.variants.step3.desc"))
}

@Composable
private fun DccAgentMcpScenario() {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	// Prominent instability warning box
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.panelElevated, RoundedCornerShape(2.dp))
			.border(BorderStroke(1.dp, colors.warning), RoundedCornerShape(2.dp))
			.padding(10.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Text(
			text = tr("help.tutorial.agent.warning"),
			style = typography.caption.copy(fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
			color = colors.warning,
		)
	}

	Text(
		text = tr("help.tutorial.agent.intro"),
		style = typography.caption.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
		color = colors.textPrimary,
	)

	DccAgentScopeItem(
		title = tr("help.tutorial.agent.ready.title"),
		desc = tr("help.tutorial.agent.ready.desc"),
		indicatorColor = colors.success,
	)
	DccAgentScopeItem(
		title = tr("help.tutorial.agent.beta.title"),
		desc = tr("help.tutorial.agent.beta.desc"),
		indicatorColor = colors.warning,
	)
	DccAgentScopeItem(
		title = tr("help.tutorial.agent.unsupported.title"),
		desc = tr("help.tutorial.agent.unsupported.desc"),
		indicatorColor = colors.error,
	)

	Text(
		text = tr("help.tutorial.agent.tip"),
		style = typography.caption.copy(fontSize = 11.sp),
		color = colors.accent,
	)
}

@Composable
private fun DccAgentScopeItem(title: String, desc: String, indicatorColor: Color) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.panelElevated)
			.border(BorderStroke(1.dp, colors.divider))
			.padding(10.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Text(
			text = title,
			style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
			color = indicatorColor,
		)
		Text(
			text = desc,
			style = typography.caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
			color = colors.textPrimary,
		)
	}
}

// ---------------------------------------------------------------------------
// 2. DCC PSD Layer Spec Content (Tabular Key-Value)
// ---------------------------------------------------------------------------
@Composable
private fun DccPsdSpecContent(onOpenUrl: (String) -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Text(
		text = tr("help.spec.intro"),
		style = typography.caption.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
		color = colors.textPrimary,
	)

	// Head & Facial Group
	DccSpecTable(
		title = tr("help.spec.group.head"),
		rows = listOf(
			tr("help.spec.head.face"),
			tr("help.spec.head.hairFront"),
			tr("help.spec.head.hairBack"),
			tr("help.spec.head.eyelash"),
			tr("help.spec.head.eyewhite"),
			tr("help.spec.head.irides"),
			tr("help.spec.head.eyebrow"),
			tr("help.spec.head.nose"),
			tr("help.spec.head.mouth"),
			tr("help.spec.head.mouthInternals"),
		),
	)

	// Body Group
	DccSpecTable(
		title = tr("help.spec.group.body"),
		rows = listOf(
			tr("help.spec.body.neck"),
			tr("help.spec.body.topwear"),
			tr("help.spec.body.limbs"),
		),
	)

	// Important Notes
	DccSpecTable(
		title = tr("help.spec.group.notes"),
		rows = listOf(
			tr("help.spec.note.eyelash"),
			tr("help.spec.note.mouth"),
			tr("help.spec.note.body"),
		),
	)
}

@Composable
private fun DccSpecTable(title: String, rows: List<String>) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.panelElevated)
			.border(BorderStroke(1.dp, colors.divider)),
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.background(colors.controlBackground)
				.padding(horizontal = 10.dp, vertical = 6.dp),
		) {
			Text(
				text = title,
				style = typography.caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
				color = colors.textPrimary,
			)
		}
		rows.forEachIndexed { index, rowText ->
			if (index > 0) {
				Divider(color = colors.divider, thickness = 1.dp)
			}
			Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
				Text(
					text = rowText,
					style = typography.caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
					color = colors.textPrimary,
				)
			}
		}
	}
}

// ---------------------------------------------------------------------------
// 3. DCC Shortcuts Content (Tabular Key-Value)
// ---------------------------------------------------------------------------
@Composable
private fun DccShortcutsContent() {
	val projectShortcuts = listOf(
		tr("help.shortcuts.openPsd") to "Ctrl+O",
		tr("help.shortcuts.openProject") to "Ctrl+Shift+P",
		tr("help.shortcuts.saveProject") to "Ctrl+S",
		tr("help.shortcuts.saveProjectAs") to "Ctrl+Shift+S",
		tr("help.shortcuts.reexportPsd") to "Ctrl+E",
		tr("help.shortcuts.generate") to "Ctrl+G",
		tr("help.shortcuts.openOutput") to "Ctrl+Shift+O",
		tr("help.shortcuts.settings") to "Ctrl+,",
		tr("help.shortcuts.help") to "F1",
	)

	val viewShortcuts = listOf(
		tr("help.shortcuts.zoomWheel") to "Wheel",
		tr("help.shortcuts.panCanvas") to "Middle Drag / Space+Drag",
		tr("help.shortcuts.selectLayer") to "Left Click",
		tr("help.shortcuts.fitCenter") to "F / 0 / Home",
		tr("help.shortcuts.zoomReset") to "Ctrl+0",
		tr("help.shortcuts.zoomStep") to "Ctrl + / Ctrl -",
	)

	val toolShortcuts = listOf(
		tr("help.shortcuts.textureUpscale") to "Ctrl+U",
		tr("help.shortcuts.agentConnect") to "Ctrl+Shift+A",
		tr("help.shortcuts.historyTree") to "Ctrl+H",
	)

	DccShortcutGroup(title = tr("help.shortcuts.group.project"), shortcuts = projectShortcuts)
	DccShortcutGroup(title = tr("help.shortcuts.group.view"), shortcuts = viewShortcuts)
	DccShortcutGroup(title = tr("help.shortcuts.group.tools"), shortcuts = toolShortcuts)
}

@Composable
private fun DccShortcutGroup(title: String, shortcuts: List<Pair<String, String>>) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.panelElevated)
			.border(BorderStroke(1.dp, colors.divider)),
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.background(colors.controlBackground)
				.padding(horizontal = 10.dp, vertical = 6.dp),
		) {
			Text(
				text = title,
				style = typography.caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
				color = colors.textPrimary,
			)
		}

		shortcuts.forEachIndexed { index, (action, shortcut) ->
			if (index > 0) {
				Divider(color = colors.divider, thickness = 1.dp)
			}
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 10.dp, vertical = 5.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = action,
					style = typography.caption.copy(fontSize = 11.sp),
					color = colors.textPrimary,
				)
				Text(
					text = shortcut,
					style = typography.monoSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
					color = colors.accent,
				)
			}
		}
	}
}

// ---------------------------------------------------------------------------
// 4. DCC Community Links Content (Flat Table with Action Buttons)
// ---------------------------------------------------------------------------
@Composable
private fun DccCommunityLinksContent(
	onOpenUrl: (String) -> Unit,
	onCopyLink: (String) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Text(
		text = tr("help.links.intro"),
		style = typography.caption.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
		color = colors.textPrimary,
	)

	val links = listOf(
		Triple(tr("help.links.github.title"), DesktopUtils.GITHUB_REPO_URL, tr("help.links.github.desc")),
		Triple(tr("help.links.issues.title"), DesktopUtils.GITHUB_ISSUES_URL, tr("help.links.issues.desc")),
		Triple(tr("help.links.releases.title"), DesktopUtils.GITHUB_RELEASES_URL, tr("help.links.releases.desc")),
		Triple(tr("help.links.docs.title"), DesktopUtils.GITHUB_DOCS_URL, tr("help.links.docs.desc")),
	)

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.panelElevated)
			.border(BorderStroke(1.dp, colors.divider)),
	) {
		links.forEachIndexed { index, (title, url, desc) ->
			if (index > 0) {
				Divider(color = colors.divider, thickness = 1.dp)
			}
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(10.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Column(
					modifier = Modifier.weight(1f).padding(end = 12.dp),
					verticalArrangement = Arrangement.spacedBy(2.dp),
				) {
					Text(
						text = title,
						style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
						color = colors.textPrimary,
					)
					Text(
						text = desc,
						style = typography.caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
						color = colors.textMuted,
					)
					Text(
						text = url,
						style = typography.monoSmall.copy(fontSize = 10.sp),
						color = colors.textMuted,
					)
				}

				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					CompactButton(
						text = tr("help.links.openInBrowser"),
						onClick = { onOpenUrl(url) },
						isPrimary = true,
						height = 22.dp,
					)
					CompactButton(
						text = tr("help.links.copyUrl"),
						onClick = { onCopyLink(url) },
						height = 22.dp,
					)
				}
			}
		}
	}
}

// ---------------------------------------------------------------------------
// 5. DCC About Content (Technical Spec Sheet)
// ---------------------------------------------------------------------------
@Composable
private fun DccAboutContent() {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	val javaVersion = System.getProperty("java.version").orEmpty()
	val javaVendor = System.getProperty("java.vendor").orEmpty()
	val osName = System.getProperty("os.name").orEmpty()
	val osArch = System.getProperty("os.arch").orEmpty()

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.panelElevated)
			.border(BorderStroke(1.dp, colors.divider))
			.padding(12.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(
				text = "PSD2Live",
				style = typography.title.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
				color = colors.textPrimary,
			)
			Text(
				text = "v0.6.0",
				style = typography.monoSmall.copy(fontSize = 11.sp),
				color = colors.accent,
			)
		}

		Text(
			text = tr("help.about.tagline"),
			style = typography.body.copy(fontSize = 11.5.sp),
			color = colors.textPrimary,
		)

		Divider(color = colors.divider, thickness = 1.dp)

		Text(
			text = tr("help.about.license"),
			style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
			color = colors.textPrimary,
		)

		Text(
			text = tr("help.about.disclaimer"),
			style = typography.caption.copy(fontSize = 10.5.sp, lineHeight = 15.sp),
			color = colors.textMuted,
		)

		Text(
			text = tr("help.about.sdkNotice"),
			style = typography.caption.copy(fontSize = 10.5.sp, lineHeight = 15.sp),
			color = colors.textMuted,
		)

		Divider(color = colors.divider, thickness = 1.dp)

		Text(
			text = tr("help.about.runtimeInfo", javaVersion, javaVendor, osName, osArch),
			style = typography.monoSmall.copy(fontSize = 10.sp),
			color = colors.textMuted,
		)
	}
}
