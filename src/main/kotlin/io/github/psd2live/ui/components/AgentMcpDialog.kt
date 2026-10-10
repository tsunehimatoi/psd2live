package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.agent.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

private val CLIENT_LABELS = mapOf(
	AgentMcpClient.CLAUDE_CODE to "Claude Code", AgentMcpClient.CODEX to "Codex",
	AgentMcpClient.JSON to "JSON", AgentMcpClient.STDIO to "Stdio",
)

/** The single MCP entry: how to connect a host to the running endpoint, and the endpoint's own settings. */
@Composable
fun AgentMcpDialog(
	controller: AgentMcpController,
	onDismiss: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val scope = rememberCoroutineScope()
	val saved by controller.settings.collectAsState()
	val status by controller.status.collectAsState()

	var enabled by remember(saved) { mutableStateOf(saved.enabled) }
	var portText by remember(saved) { mutableStateOf(saved.port.toString()) }
	var token by remember(saved) { mutableStateOf(saved.token) }
	var profile by remember(saved) { mutableStateOf(saved.profile) }
	var tokenVisible by remember { mutableStateOf(false) }
	var applying by remember { mutableStateOf(false) }
	var client by remember { mutableStateOf(AgentMcpClient.CLAUDE_CODE) }
	var copied by remember { mutableStateOf<String?>(null) }

	val draft = AgentMcpSettings(enabled, portText.trim().toIntOrNull() ?: -1, token.trim(), profile)
	val problem = draft.problem()
	val dirty = draft != saved
	val proxyPath = remember {
		// Packages ship it among the app's resources; a source checkout runs from the repository root.
		val packaged = System.getProperty("compose.application.resources.dir")?.let { File(it, "mcp_proxy.py") }?.takeIf(File::isFile)
		runCatching { (packaged ?: File("mcp_proxy.py")).canonicalPath }.getOrDefault("mcp_proxy.py").replace('\\', '/')
	}

	fun copy(text: String, label: String) {
		val selection = StringSelection(text)
		Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
		copied = tr("dialog.agent.copied", label)
	}

	fun apply() {
		applying = true
		scope.launch {
			try { withContext(Dispatchers.IO) { controller.apply(draft) } } finally { applying = false }
		}
	}

	ModalDialogFrame(
		title = tr("dialog.agent.title"),
		onDismiss = onDismiss,
		width = 560.dp,
		maxHeight = 660.dp,
		dismissible = !applying,
		titleTrailing = { StatusChip(status) },
		footerStart = {
			Text(copied?.let { "✓ $it" }.orEmpty(), style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
				color = colors.highlight)
		},
		footer = {
			CompactButton(tr("dialog.agent.revert"), onClick = {
				enabled = saved.enabled; portText = saved.port.toString(); token = saved.token; profile = saved.profile
			}, enabled = dirty && !applying)
			CompactButton(tr(if (applying) "dialog.agent.applying" else "dialog.agent.apply"), onClick = ::apply,
				enabled = problem == null && !applying && (dirty || status is AgentMcpStatus.Failed), isPrimary = true)
		},
	) {
		SectionTitle(tr("dialog.agent.section.connect"))
		when (val current = status) {
			is AgentMcpStatus.Running -> {
				val connection = current.connection
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					FieldLabel(tr("dialog.agent.endpoint"))
					Text(connection.endpoint, style = typography.monoSmall.copy(fontSize = 11.sp), color = colors.textPrimary, modifier = Modifier.weight(1f))
					CompactButton(tr("dialog.agent.copy"), onClick = { copy(connection.endpoint, tr("dialog.agent.endpoint")) }, height = 22.dp)
				}
				val clients = AgentMcpClient.entries
				CompactTabBar(clients.map(CLIENT_LABELS::getValue), clients.indexOf(client), { client = clients[it] }, Modifier.fillMaxWidth())
				val snippet = AgentMcpClientConfigs.snippet(client, connection, proxyPath)
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(tr(when (client) {
						AgentMcpClient.CLAUDE_CODE -> "dialog.agent.hint.claude"
						AgentMcpClient.CODEX -> "dialog.agent.hint.codex"
						AgentMcpClient.JSON -> "dialog.agent.hint.json"
						AgentMcpClient.STDIO -> "dialog.agent.hint.stdio"
					}), style = typography.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp), color = colors.textMuted, modifier = Modifier.weight(1f))
					Spacer(Modifier.width(8.dp))
					CompactButton(tr("dialog.agent.copy"), onClick = { copy(snippet, CLIENT_LABELS.getValue(client)) }, isPrimary = true, height = 22.dp)
				}
				// Copy still takes the real token; only what is on screen (and in a screenshot) is masked.
				val shownSnippet = if (tokenVisible || connection.token.isEmpty()) snippet
					else snippet.replace(connection.token, TOKEN_MASK)
				SelectionContainer {
					Text(
						shownSnippet,
						style = typography.mono.copy(fontSize = 10.5.sp, lineHeight = 15.sp),
						color = colors.codeString,
						modifier = Modifier.fillMaxWidth()
							.background(colors.codeBackground, RoundedCornerShape(4.dp))
							.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(4.dp))
							.padding(10.dp),
					)
				}
				CompactButton(tr("dialog.agent.copyPrompt"), height = 22.dp, onClick = {
					copy(tr("dialog.agent.prompt", connection.endpoint, connection.token), tr("dialog.agent.promptName"))
				})
			}
			is AgentMcpStatus.Failed -> Notice(tr("dialog.agent.failed", current.message), colors.error)
			AgentMcpStatus.Stopped -> Notice(tr("dialog.agent.offline"), colors.textMuted)
		}

		Spacer(Modifier.height(4.dp))
		SectionTitle(tr("dialog.agent.section.settings"))
		CompactCheckbox(enabled, { enabled = it }, label = tr("dialog.agent.enabled"), enabled = !applying)
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			FieldLabel(tr("dialog.agent.port"))
			CompactTextField(portText, { text -> portText = text.filter(Char::isDigit).take(5) }, Modifier.width(96.dp), isMono = true, enabled = !applying)
		}
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			FieldLabel(tr("dialog.agent.token"))
			if (tokenVisible) {
				CompactTextField(token, { token = it.trim() }, Modifier.weight(1f), isMono = true, enabled = !applying)
			} else {
				Text(TOKEN_MASK, style = typography.monoSmall.copy(fontSize = 11.sp), color = colors.textPrimary,
					modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Clip)
			}
			CompactButton(tr(if (tokenVisible) "dialog.agent.token.hide" else "dialog.agent.token.show"), { tokenVisible = !tokenVisible }, height = 22.dp)
			CompactButton(tr("dialog.agent.token.regenerate"), { token = AgentMcpCredentials.generateToken() }, height = 22.dp, enabled = !applying)
			CompactButton(tr("dialog.agent.copy"), { copy(token, tr("dialog.agent.token")) }, height = 22.dp)
		}
		Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			FieldLabel(tr("dialog.agent.tools"))
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				ProfileOption(profile == AgentToolProfile.CORE, tr("dialog.agent.profile.core"),
					tr("dialog.agent.profile.coreDesc", CORE_TOOL_COUNT.toString()), !applying) { profile = AgentToolProfile.CORE }
				ProfileOption(profile == AgentToolProfile.FULL, tr("dialog.agent.profile.full"),
					tr("dialog.agent.profile.fullDesc"), !applying) { profile = AgentToolProfile.FULL }
			}
		}
		when (problem) {
			AgentMcpSettingsProblem.PORT -> Notice(tr("dialog.agent.invalid.port"), colors.error)
			AgentMcpSettingsProblem.TOKEN -> Notice(tr("dialog.agent.invalid.token"), colors.error)
			null -> if (draft.port != saved.port || draft.token != saved.token) Notice(tr("dialog.agent.applyNote"), colors.warning)
		}
	}
}

@Composable
private fun StatusChip(status: AgentMcpStatus) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val (text, tint, container) = when (status) {
		is AgentMcpStatus.Running -> Triple(tr("dialog.agent.status.running", status.connection.port.toString()), colors.highlight, colors.highlightContainer)
		is AgentMcpStatus.Failed -> Triple(tr("dialog.agent.status.failed"), colors.error, colors.errorContainer)
		AgentMcpStatus.Stopped -> Triple(tr("dialog.agent.status.stopped"), colors.textMuted, colors.panelElevated)
	}
	Row(
		modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(container).padding(horizontal = 8.dp, vertical = 3.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(5.dp),
	) {
		Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
		Text(text, style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), color = tint)
	}
}

@Composable
private fun SectionTitle(text: String) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Text(text, style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold), color = colors.textPrimary)
}

@Composable
private fun FieldLabel(text: String) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Text(text, style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textMuted, modifier = Modifier.width(72.dp))
}

@Composable
private fun Notice(text: String, color: androidx.compose.ui.graphics.Color) {
	val typography = LocalToolTypography.current
	Text(text, style = typography.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp), color = color)
}

@Composable
private fun ProfileOption(selected: Boolean, title: String, description: String, enabled: Boolean, onSelect: () -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column {
		CompactRadioButton(selected, onSelect, enabled = enabled, label = title)
		Text(description, style = typography.caption.copy(fontSize = 10.sp, lineHeight = 13.sp), color = colors.textMuted,
			modifier = Modifier.padding(start = 20.dp))
	}
}

private val TOKEN_MASK = "•".repeat(24)
