package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import io.github.psd2live.ui.utils.DesktopUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The MCP service the app runs, for the settings page; null where none was started. */
internal val LocalAgentMcp = staticCompositionLocalOf<AgentMcpController?> { null }

private val CLIENT_LABELS = mapOf(
	AgentMcpClient.CLAUDE_CODE to "Claude Code", AgentMcpClient.CODEX to "Codex",
	AgentMcpClient.JSON to "JSON", AgentMcpClient.STDIO to "Stdio",
)

private val TOKEN_MASK = "•".repeat(24)

/**
 * The MCP topic of the settings page: how to connect a host to the running endpoint, then the endpoint's own
 * settings. Unlike the other topics these are a draft until applied, since applying restarts the service.
 */
@Composable
internal fun SettingsMcpSection() {
	val controller = LocalAgentMcp.current
	SettingsSectionDescription(tr("settings.mcp.desc"))
	if (controller == null) {
		Notice(tr("settings.mcp.unavailable"), LocalToolColors.current.textMuted)
		return
	}
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
		// Packages ship it among the app's resources; a source checkout runs from tools/.
		val packaged = System.getProperty("compose.application.resources.dir")?.let { File(it, "mcp_proxy.py") }?.takeIf(File::isFile)
		runCatching { (packaged ?: File("tools/mcp_proxy.py")).canonicalPath }.getOrDefault("tools/mcp_proxy.py").replace('\\', '/')
	}

	fun copy(text: String, label: String) {
		DesktopUtils.copyToClipboard(text)
		copied = tr("dialog.agent.copied", label)
	}

	fun apply() {
		applying = true
		scope.launch {
			try { withContext(Dispatchers.IO) { controller.apply(draft) } } finally { applying = false }
		}
	}

	SettingsGroup(tr("dialog.agent.section.connect")) {
		SettingsRow(tr("settings.mcp.status")) { StatusChip(status) }
		when (val current = status) {
			is AgentMcpStatus.Running -> {
				val connection = current.connection
				SettingsRow(tr("dialog.agent.endpoint")) {
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						Text(connection.endpoint, style = typography.monoSmall.copy(fontSize = 11.sp), color = colors.textPrimary,
							modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
						CompactButton(tr("dialog.agent.copy"), onClick = { copy(connection.endpoint, tr("dialog.agent.endpoint")) }, height = 22.dp)
					}
				}
				SettingsRow(tr("settings.mcp.host")) {
					val clients = AgentMcpClient.entries
					CompactTabBar(clients.map(CLIENT_LABELS::getValue), clients.indexOf(client), { client = clients[it] }, Modifier.fillMaxWidth(), height = 24.dp)
				}
				val snippet = AgentMcpClientConfigs.snippet(client, connection, proxyPath)
				// The host's instructions, its snippet and the prompt sit under the host row's control, as its own.
				SettingsIndented {
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
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						CompactButton(tr("dialog.agent.copyPrompt"), height = 22.dp, onClick = {
							copy(tr("dialog.agent.prompt", connection.endpoint, connection.token), tr("dialog.agent.promptName"))
						})
						Copied(copied)
					}
				}
			}
			is AgentMcpStatus.Failed -> SettingsHint(tr("dialog.agent.failed", current.message), color = colors.error)
			AgentMcpStatus.Stopped -> SettingsHint(tr("dialog.agent.offline"))
		}
	}

	SettingsGroup(tr("dialog.agent.section.settings")) {
		SettingsRow(tr("settings.mcp.service")) {
			CompactCheckbox(enabled, { enabled = it }, label = tr("dialog.agent.enabled"), enabled = !applying)
		}
		SettingsRow(tr("dialog.agent.port")) {
			CompactTextField(portText, { text -> portText = text.filter(Char::isDigit).take(5) }, Modifier.width(96.dp), isMono = true, enabled = !applying)
		}
		SettingsRow(tr("dialog.agent.token")) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
		}
		SettingsRow(tr("dialog.agent.tools")) {
			Column(Modifier.padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				ProfileOption(profile == AgentToolProfile.CORE, tr("dialog.agent.profile.core"),
					tr("dialog.agent.profile.coreDesc", CORE_TOOL_COUNT.toString()), !applying) { profile = AgentToolProfile.CORE }
				ProfileOption(profile == AgentToolProfile.FULL, tr("dialog.agent.profile.full"),
					tr("dialog.agent.profile.fullDesc"), !applying) { profile = AgentToolProfile.FULL }
			}
		}
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
			modifier = Modifier.padding(start = SettingsLabelWidth, top = 4.dp)) {
			Box(Modifier.weight(1f)) {
				when (problem) {
					AgentMcpSettingsProblem.PORT -> Notice(tr("dialog.agent.invalid.port"), colors.error)
					AgentMcpSettingsProblem.TOKEN -> Notice(tr("dialog.agent.invalid.token"), colors.error)
					null -> if (draft.port != saved.port || draft.token != saved.token) Notice(tr("dialog.agent.applyNote"), colors.warning)
				}
			}
			CompactButton(tr("dialog.agent.revert"), onClick = {
				enabled = saved.enabled; portText = saved.port.toString(); token = saved.token; profile = saved.profile
			}, enabled = dirty && !applying, height = 22.dp)
			CompactButton(tr(if (applying) "dialog.agent.applying" else "dialog.agent.apply"), onClick = ::apply,
				enabled = problem == null && !applying && (dirty || status is AgentMcpStatus.Failed), isPrimary = true, height = 22.dp)
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
private fun Copied(text: String?) {
	if (text == null) return
	Text("✓ $text", style = LocalToolTypography.current.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
		color = LocalToolColors.current.highlight, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Notice(text: String, color: androidx.compose.ui.graphics.Color) {
	Text(text, style = LocalToolTypography.current.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp), color = color)
}

@Composable
private fun ProfileOption(selected: Boolean, title: String, description: String, enabled: Boolean, onSelect: () -> Unit) {
	Column {
		CompactRadioButton(selected, onSelect, enabled = enabled, label = title)
		Text(description, style = LocalToolTypography.current.caption.copy(fontSize = 10.sp, lineHeight = 13.sp),
			color = LocalToolColors.current.textMuted, modifier = Modifier.padding(start = 20.dp))
	}
}
