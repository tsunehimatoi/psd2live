package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.agent.AgentOperationDoc
import io.github.psd2live.i18n.I18n
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.Manual
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.views.PageNavGroup
import io.github.psd2live.ui.state.Keymap
import io.github.psd2live.ui.views.PanelSearch
import io.github.psd2live.ui.views.PanelToolbar
import io.github.psd2live.ui.views.PanelExpandCollapseButtons
import io.github.psd2live.ui.views.texture.BarTooltip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.draw.clip
import io.github.psd2live.ui.state.HelpTab
import io.github.psd2live.ui.views.PageBody
import io.github.psd2live.ui.views.PageNavItem
import io.github.psd2live.ui.views.PageNavList
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.state.ShortcutCategory
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.tutorial.TutorialId
import io.github.psd2live.ui.tutorial.TutorialPath
import io.github.psd2live.ui.utils.DesktopUtils
import kotlinx.coroutines.delay

/**
 * The help page's topic panel: [tab] picks what it shows, and in the manual and the tutorials [page] picks the page.
 * A topic reads as a document in a [PageBody]; the shortcuts and links open with a toolbar for their filter and their
 * copy notice.
 */
@Composable
internal fun HelpPanel(
	tab: HelpTab,
	page: String,
	keymap: Keymap,
	onOpenPage: (String) -> Unit,
	onOpenUrl: (String) -> Unit = { DesktopUtils.openBrowser(it) },
	onStartInteractiveTutorial: ((TutorialId, TutorialPath) -> Unit)? = null,
	operationDocs: () -> List<AgentOperationDoc> = { emptyList() },
) {
	val operations = remember { operationDocs() }
	var query by remember(page) { mutableStateOf("") }
	var copied by remember(tab) { mutableStateOf(false) }
	LaunchedEffect(copied) {
		if (copied) {
			delay(2500)
			copied = false
		}
	}
	val manualPage = Manual.page(page)?.takeIf { tab == HelpTab.MANUAL || tab == HelpTab.TUTORIALS }
	Column(Modifier.fillMaxSize()) {
		when {
			manualPage?.embed == "shortcuts" -> PanelToolbar(search = PanelSearch(query, { query = it }, tr("help.shortcuts.filter"))) {
				Spacer(Modifier.weight(1f))
			}
			tab == HelpTab.COMMUNITY_LINKS && copied -> PanelToolbar {
				Text(tr("help.links.copied"), style = LocalToolTypography.current.caption.copy(fontSize = 10.5.sp),
					color = LocalToolColors.current.accent)
			}
		}
		PageBody {
			when (tab) {
				HelpTab.MANUAL, HelpTab.TUTORIALS -> if (manualPage != null) {
					ManualPageContent(
						page = manualPage,
						keymap = keymap,
						onOpenPage = onOpenPage,
						onOpenUrl = onOpenUrl,
						onStartTutorial = onStartInteractiveTutorial,
						operations = operations,
					) { embed ->
						when (embed) {
							"shortcuts" -> DccShortcutsContent(keymap, query)
							"psd_spec" -> DccPsdSpecContent(onOpenUrl)
						}
					}
				}
				HelpTab.COMMUNITY_LINKS -> DccCommunityLinksContent(
					onOpenUrl = onOpenUrl,
					onCopyLink = { url ->
						DesktopUtils.copyToClipboard(url)
						copied = true
					},
				)
				HelpTab.EXAMPLES -> ExamplesContent(onOpenUrl)
				HelpTab.LICENSES -> LicensesContent(onOpenUrl)
				HelpTab.ABOUT -> DccAboutContent()
			}
		}
	}
}

/**
 * The help page's contents, laid out like the Cubism manual's: the manual and the tutorials as folder trees whose pages
 * carry their comparison tag (a page Cubism has and PSD2Live does not is greyed and does not open), then the other
 * topics. The filter lists the pages whose titles match; the footer explains the tags.
 */
@Composable
internal fun HelpNav(selected: HelpTab, page: String, onSelect: (HelpTab) -> Unit, onOpenPage: (String) -> Unit) {
	val language = I18n.currentLanguage
	val texts = remember(language) { Manual.texts(language) }
	var query by remember { mutableStateOf("") }
	val expanded = remember { mutableStateListOf<String>() }
	LaunchedEffect(page) { Manual.ancestors(page).forEach { if (it.id !in expanded) expanded += it.id } }
	val inTree = selected == HelpTab.MANUAL || selected == HelpTab.TUTORIALS
	val others = HelpTab.entries.filter { it != HelpTab.MANUAL && it != HelpTab.TUTORIALS }
	fun title(id: String) = texts[id]?.title ?: id
	val needle = query.trim()
	val matches = if (needle.isEmpty()) emptyList() else Manual.pages.filter { title(it.id).contains(needle, ignoreCase = true) }

	// What the arrow keys step through: the rows shown that open.
	fun visible(nodes: List<Manual.Node>): List<String> = nodes.flatMap { node ->
		when (node) {
			is Manual.Folder -> if (node.id in expanded) visible(node.children) else emptyList()
			is Manual.Page -> if (node.opens) listOf(node.id) else emptyList()
		}
	}
	val stops: List<Any> = if (needle.isNotEmpty()) matches.filter { it.opens }.map { it.id }
		else Manual.Root.entries.flatMap { visible(Manual.trees[it].orEmpty()) } + others
	val current: Any = if (inTree) page else selected

	@Composable
	fun PageRow(node: Manual.Page, depth: Int) = PageNavItem(
		label = title(node.id),
		selected = inTree && node.id == page,
		enabled = node.opens,
		onClick = { onOpenPage(node.id) },
		indent = 12.dp * depth,
		gutter = manualStatusColor(node.status),
		// An empty chevron slot: a page's title starts where its folder's children start, past the folder's own.
		icon = {},
	)

	@Composable
	fun Tree(nodes: List<Manual.Node>, depth: Int) {
		nodes.forEach { node ->
			when (node) {
				is Manual.Folder -> {
					val open = node.id in expanded
					PageNavItem(
						label = title(node.id),
						selected = false,
						bold = depth == 0,
						onClick = { if (open) expanded -= node.id else expanded += node.id },
						indent = 12.dp * depth,
						flush = true,
						icon = { tint -> IconChevron(open, Modifier.size(11.dp), tint) },
					)
					if (open) Tree(node.children, depth + 1)
				}
				is Manual.Page -> PageRow(node, depth)
			}
		}
	}

	Column(Modifier.fillMaxSize()) {
		val folders = remember { Manual.trees.values.flatMap { Manual.flatten(it) }.filterIsInstance<Manual.Folder>().map { it.id } }
		PanelToolbar(iconCount = 2, search = PanelSearch(query, { query = it }, tr("help.manual.filter"))) {
			Spacer(Modifier.weight(1f))
			PanelExpandCollapseButtons(
				onExpandAll = { expanded.clear(); expanded += folders },
				onCollapseAll = { expanded.clear(); expanded += Manual.ancestors(page).map { it.id }.filter { inTree } },
				enabled = needle.isEmpty(),
			)
		}
		PageNavList(
			onStep = { step ->
				if (stops.isNotEmpty()) {
					val next = stops[(stops.indexOf(current).coerceAtLeast(0) + step).mod(stops.size)]
					if (next is HelpTab) onSelect(next) else onOpenPage(next as String)
				}
			},
			footer = { ManualLegend() },
		) {
			if (needle.isNotEmpty()) {
				if (matches.isEmpty()) PageNavGroup(tr("help.manual.noMatch"))
				matches.forEach { PageRow(it, 0) }
			} else {
				Manual.Root.entries.forEach { root ->
					PageNavGroup(tr(if (root == Manual.Root.MANUAL) "help.tab.manual" else "help.tab.tutorials"))
					Tree(Manual.trees[root].orEmpty(), 0)
				}
				PageNavGroup(tr("help.nav.more"))
				others.forEach { tab -> PageNavItem(tr(tab.labelKey), tab == selected, onClick = { onSelect(tab) }, flush = true) }
			}
		}
	}
}

/** What the gutter colours mean, under the contents: one bar and word each, the sentence on hover. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualLegend() {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Manual.Status.entries.forEach { status ->
			BarTooltip(tr(status.labelKey + ".desc")) {
				// Each entry is a small piece of a row: the gutter bar, the row's pale wash, the word.
				val color = manualStatusColor(status)
				Row(Modifier.height(18.dp).background(color.copy(alpha = 0.08f)), verticalAlignment = Alignment.CenterVertically) {
					Box(Modifier.width(3.dp).fillMaxHeight().background(color))
					Text(tr(status.labelKey), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, maxLines = 1,
						modifier = Modifier.padding(start = 6.dp, end = 7.dp))
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
	CompactButton(tr("help.spec.fullTable"), onClick = { onOpenUrl(docsUrl("spec/PSD_LAYER_SPEC.md")) }, height = 22.dp)

	DccSpecTable(
		title = tr("help.spec.group.prepare"),
		rows = listOf(
			tr("help.spec.prepare.format"),
			tr("help.spec.prepare.raster"),
			tr("help.spec.prepare.pose"),
		),
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
			tr("help.spec.note.hair"),
			tr("help.spec.note.sides"),
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
private fun DccShortcutsContent(keymap: Keymap, query: String) {
	val needle = query.trim()
	// A filter keeps the rows whose action or keys match; a group left empty is not shown.
	@Composable
	fun DccShortcutGroup(title: String, shortcuts: List<Pair<String, String>>) {
		val shown = if (needle.isEmpty()) shortcuts
			else shortcuts.filter { (action, keys) -> action.contains(needle, ignoreCase = true) || keys.contains(needle, ignoreCase = true) }
		if (shown.isNotEmpty()) ShortcutGroupTable(title, shown)
	}
	// The pointer basics no binding can change stay literal; the rebindable mouse gestures (pan, zoom drag,
	// brush adjust) are listed with the registry below.
	DccShortcutGroup(
		title = tr("help.shortcuts.group.view"),
		shortcuts = listOf(
			tr("help.shortcuts.zoomWheel") to "Wheel",
			tr("help.shortcuts.panCanvas") to "Space + Left Drag",
			tr("help.shortcuts.selectLayer") to "Left Click",
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
					// The numbered families (tab jumps, option picks) share one label pattern, keyed by their digit.
					val jump = action.labelIndex
					val label = if (jump == null) tr(action.labelKey) else tr(action.labelKey, jump)
					val binding = keymap.labelsFor(action).joinToString(" / ")
						.ifEmpty { tr("help.shortcuts.unbound") }
					label to binding
				},
		)
	}
}

@Composable
private fun ShortcutGroupTable(title: String, shortcuts: List<Pair<String, String>>) {
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
		Triple(tr("help.links.examples.title"), DesktopUtils.EXAMPLES_REPO_URL, tr("help.links.examples.desc")),
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
				text = "v" + io.github.psd2live.AppVersion.value,
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
			text = tr("help.about.thirdParty"),
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

/** A page of the repository's documentation. */
private fun docsUrl(path: String): String {
	return "${DesktopUtils.GITHUB_REPO_URL}/blob/master/docs/zh/$path"
}

// ---------------------------------------------------------------------------
// Examples: what the example repository holds and how to use it.
// ---------------------------------------------------------------------------
@Composable
private fun ExamplesContent(onOpenUrl: (String) -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Text(
		text = tr("help.examples.intro"),
		style = typography.caption.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
		color = colors.textPrimary,
	)
	Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		CompactButton(tr("help.examples.open"), onClick = { onOpenUrl(DesktopUtils.EXAMPLES_REPO_URL) }, isPrimary = true, height = 22.dp)
		CompactButton(tr("help.examples.submit"), onClick = { onOpenUrl(DesktopUtils.EXAMPLES_SUBMIT_URL) }, height = 22.dp)
	}
	DccSpecTable(
		title = tr("help.examples.group.contents"),
		rows = listOf(
			tr("help.examples.contents.psd"),
			tr("help.examples.contents.projects"),
			tr("help.examples.contents.cases"),
			tr("help.examples.contents.prompts"),
		),
	)
	DccSpecTable(
		title = tr("help.examples.group.use"),
		rows = listOf(
			tr("help.examples.use.psd"),
			tr("help.examples.use.project"),
			tr("help.examples.use.terms"),
		),
	)
}

// ---------------------------------------------------------------------------
// Licenses: the third-party components the app is built from or ships with.
// ---------------------------------------------------------------------------

/** One component: its name, its license as published, and what it does here (a message key). */
private class ThirdPartyComponent(val name: String, val license: String, val useKey: String)

private val ThirdPartyComponents = listOf(
	ThirdPartyComponent("Umamo", "GPL-3.0", "help.licenses.use.umamo"),
	ThirdPartyComponent("Compose Multiplatform · Skiko (Skia)", "Apache-2.0 · BSD-3-Clause", "help.licenses.use.compose"),
	ThirdPartyComponent("Kotlin · kotlinx.coroutines · kotlinx.serialization · kotlinx-datetime", "Apache-2.0", "help.licenses.use.kotlin"),
	ThirdPartyComponent("LWJGL", "BSD-3-Clause", "help.licenses.use.lwjgl"),
	ThirdPartyComponent("JNA", "Apache-2.0 / LGPL-2.1", "help.licenses.use.jna"),
	ThirdPartyComponent("Model Context Protocol Kotlin SDK", "MIT · Apache-2.0", "help.licenses.use.mcp"),
	ThirdPartyComponent("Ktor", "Apache-2.0", "help.licenses.use.ktor"),
	ThirdPartyComponent("Okio", "Apache-2.0", "help.licenses.use.okio"),
	ThirdPartyComponent("JDOM", "JDOM License", "help.licenses.use.jdom"),
	ThirdPartyComponent("aircompressor", "Apache-2.0", "help.licenses.use.aircompressor"),
	ThirdPartyComponent("miniz_oxide · ruzstd", "MIT / Zlib / Apache-2.0 · MIT", "help.licenses.use.runtimeCrates"),
	ThirdPartyComponent("MHR v1.0.1", "Apache-2.0", "help.licenses.use.mhr"),
	ThirdPartyComponent("Stretchy Studio", "MIT", "help.licenses.use.stretchy"),
	ThirdPartyComponent("FFmpeg", "GPL-3.0", "help.licenses.use.ffmpeg"),
	ThirdPartyComponent("waifu2x (nunif)", "MIT", "help.licenses.use.nunif"),
	ThirdPartyComponent("Live2D Cubism SDK", "Live2D Proprietary Software License", "help.licenses.use.cubism"),
)

@Composable
private fun LicensesContent(onOpenUrl: (String) -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Text(
		text = tr("help.licenses.intro"),
		style = typography.caption.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
		color = colors.textPrimary,
	)
	CompactButton(tr("help.licenses.full"), onClick = { onOpenUrl("${DesktopUtils.GITHUB_REPO_URL}/blob/master/THIRD_PARTY_NOTICES.md") }, height = 22.dp)

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.panelElevated)
			.border(BorderStroke(1.dp, colors.divider)),
	) {
		ThirdPartyComponents.forEachIndexed { index, component ->
			if (index > 0) Divider(color = colors.divider, thickness = 1.dp)
			Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(
						component.name,
						style = typography.caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
						color = colors.textPrimary,
						modifier = Modifier.weight(1f),
					)
					Text(component.license, style = typography.monoSmall.copy(fontSize = 10.5.sp), color = colors.accent)
				}
				Text(tr(component.useKey), style = typography.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp), color = colors.textMuted)
			}
		}
	}
}
