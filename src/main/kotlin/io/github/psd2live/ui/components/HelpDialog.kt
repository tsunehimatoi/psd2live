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
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.Keymap
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.state.ShortcutCategory
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.tutorial.TutorialId
import io.github.psd2live.ui.utils.DesktopUtils
import kotlinx.coroutines.delay

enum class HelpTab {
	QUICK_START,
	PSD_SPEC,
	SHORTCUTS,
	COMMUNITY_LINKS,
	ABOUT,
}

@Composable
fun HelpDialog(
	initialTab: HelpTab = HelpTab.QUICK_START,
	keymap: Keymap = Keymap.DEFAULT,
	onDismiss: () -> Unit,
	onOpenUrl: (String) -> Unit = { DesktopUtils.openBrowser(it) },
	onStartInteractiveTutorial: ((TutorialId) -> Unit)? = null,
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
						text = "v1.0.0",
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

			CompactTabBar(
				tabs = tabTitles,
				selectedIndex = selectedTab.ordinal,
				onTabSelected = { selectedTab = HelpTab.entries[it] },
				modifier = Modifier.fillMaxWidth(),
			)

			Spacer(Modifier.height(12.dp))

			Column(
				modifier = Modifier
					.weight(1f, fill = false)
					.fillMaxWidth()
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				when (selectedTab) {
					HelpTab.QUICK_START -> InteractiveTutorialCatalog(
						onStartInteractiveTutorial = onStartInteractiveTutorial,
					)
					HelpTab.PSD_SPEC -> DccPsdSpecContent(onOpenUrl)
					HelpTab.SHORTCUTS -> DccShortcutsContent(keymap)
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

@Composable
private fun InteractiveTutorialCatalog(
	onStartInteractiveTutorial: ((TutorialId) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Column(
		modifier = Modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text(
			text = tr("tutorial.catalog.title"),
			style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
			color = colors.textPrimary,
		)
		Text(
			text = tr("tutorial.catalog.desc"),
			style = typography.caption.copy(fontSize = 11.sp),
			color = colors.textMuted,
		)

		if (onStartInteractiveTutorial != null) {
			CompactButton(
				text = tr("tutorial.basic.start"),
				onClick = { onStartInteractiveTutorial(TutorialId.BASIC) },
				isPrimary = true,
				height = 26.dp,
			)

			Text(
				text = tr("tutorial.catalog.progressive"),
				style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
				color = colors.accent,
			)

			TutorialId.progressiveOrder.forEachIndexed { index, id ->
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.background(colors.panelElevated, RoundedCornerShape(3.dp))
						.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(3.dp))
						.clickable { onStartInteractiveTutorial(id) }
						.padding(horizontal = 10.dp, vertical = 8.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Text(
						text = "%02d".format(index),
						style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
						color = colors.accent,
					)
					Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
						Text(
							text = tr(id.titleKey),
							style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
							color = colors.textPrimary,
						)
						Text(
							text = tr(id.descKey),
							style = typography.caption.copy(fontSize = 10.sp),
							color = colors.textMuted,
						)
					}
				}
			}
		}
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
			tr("help.spec.head.headwear"),
			tr("help.spec.head.eyelash"),
			tr("help.spec.head.eyewhite"),
			tr("help.spec.head.eyeClose"),
			tr("help.spec.head.irides"),
			tr("help.spec.head.eyebrow"),
			tr("help.spec.head.nose"),
			tr("help.spec.head.mouth"),
			tr("help.spec.head.mouthClose"),
			tr("help.spec.head.mouthInternals"),
			tr("help.spec.head.ears"),
		),
	)

	// Body Group
	DccSpecTable(
		title = tr("help.spec.group.body"),
		rows = listOf(
			tr("help.spec.body.neck"),
			tr("help.spec.body.topwear"),
			tr("help.spec.body.bottomwear"),
			tr("help.spec.body.limbs"),
			tr("help.spec.body.neckwear"),
		),
	)

	// Extra Group
	DccSpecTable(
		title = tr("help.spec.group.extra"),
		rows = listOf(
			tr("help.spec.extra.tail"),
			tr("help.spec.extra.wings"),
			tr("help.spec.extra.objects"),
		),
	)

	// Important Notes
	DccSpecTable(
		title = tr("help.spec.group.notes"),
		rows = listOf(
			tr("help.spec.note.eyelash"),
			tr("help.spec.note.mouth"),
			tr("help.spec.note.body"),
			tr("help.spec.note.unknown"),
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
private fun DccShortcutsContent(keymap: Keymap) {
	// Pointer gestures have no key to rebind, so they stay literal.
	DccShortcutGroup(
		title = tr("help.shortcuts.group.view"),
		shortcuts = listOf(
			tr("help.shortcuts.zoomWheel") to "Wheel",
			tr("help.shortcuts.panCanvas") to "Middle Drag / Left Drag",
			tr("help.shortcuts.selectLayer") to "Left Click",
		),
	)

	// Menu-only commands: no key to show, but the page still documents them.
	DccShortcutGroup(
		title = tr("help.shortcuts.group.tools"),
		shortcuts = listOf(
			tr("help.shortcuts.agentConnect") to tr("help.shortcuts.menuOnly"),
		),
	)

	// Everything else is generated from the shortcut registry, so no row here can disagree with the
	// keys the application actually listens for. All of it is rebindable in Settings -> Shortcuts.
	for (category in ShortcutCategory.entries) {
		DccShortcutGroup(
			title = tr(category.labelKey),
			shortcuts = ShortcutAction.entries
				.filter { it.category == category }
				.map { action ->
					// The nine tab-jump actions share one label pattern, keyed by their digit.
					val jump = action.jumpIndex
					val label = if (jump == null) tr(action.labelKey) else tr(action.labelKey, jump)
					val binding = keymap.labelsFor(action).joinToString(" / ")
						.ifEmpty { tr("help.shortcuts.unbound") }
					label to binding
				},
		)
	}
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
				text = "v1.0.0",
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
