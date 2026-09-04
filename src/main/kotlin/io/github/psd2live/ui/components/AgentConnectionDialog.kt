package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.agent.AgentMcpConnectionInfo
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private const val AGENT_INSTALLATION_PROMPT = """You are connected to the PSD2Live Live2D Rigging MCP server.
Follow these operational guidelines when working with the open Live2D workspace:

1. Inspection & Planning: Always inspect the current project state using `project_get_state`, `project_list_layers`, and `project_list_parameters` before modifying the model. For multi-step workflows, maintain checkpoints using `task_start` and `task_update`.
2. Direct Visual Grounding: Use `view_render_layer` (isolated layer), `view_render_context` (layer with surrounding context), and `view_render_model` (model composite under arbitrary parameter poses) to visually verify artwork and deformations. Never guess coordinates.
3. AI Asset Generation & Inpainting: When generating accessories, hairpieces, or inpainted layers, stage the transparent PNG bytes via `asset_import_png` with the referenced `spatial_reference_id`, then insert into the model via `layer_add_from_asset`. Soft-delete redundant layers using `layer_soft_delete`.
4. Cubism Parameters: Create and manage parameters with `parameter_create`, `parameter_update`, and `parameter_delete`.
5. Keyform & K-Rig Editing: Inspect drawables, deformers, parts, and glue with `object_get`. Author keyform geometry and visual channels (opacity, draw order, multiply/screen color, glue intensity) at exact N-D parameter coordinates using `keyform_set`, `keyform_copy`, `keyform_delete`, and capture poses with `rig_k_pose`.
6. Append-Only Non-Destructive History: All mutations require `expected_history_head_node_id`. Workspace history is immutable. Use `history_list` and `history_checkout` to branch or restore any historical snapshot."""

@Composable
fun AgentConnectionDialog(
	connection: AgentMcpConnectionInfo?,
	startupError: String?,
	onDismiss: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	var selectedTab by remember { mutableStateOf(0) }
	var copyNotification by remember { mutableStateOf<String?>(null) }

	fun copyToClipboard(text: String, label: String) {
		val selection = StringSelection(text)
		Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
		copyNotification = "$label ${tr("dialog.agent.copied")}"
	}

	val stdioJson = remember(connection) {
		val endpoint = connection?.endpoint ?: "http://127.0.0.1:23871/mcp"
		"""
		{
		  "mcpServers": {
		    "psd2live": {
		      "command": "python",
		      "args": ["mcp_proxy.py"]
		    }
		  }
		}
		""".trimIndent()
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(Color(0x99000000))
			.clickable(onClick = onDismiss),
		contentAlignment = Alignment.Center,
	) {
		Column(
			modifier = Modifier
				.width(680.dp)
				.heightIn(min = 400.dp, max = 640.dp)
				.background(colors.panelBackground, RoundedCornerShape(8.dp))
				.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(8.dp))
				.clickable(enabled = false) {}
				.padding(18.dp),
		) {
			// Dialog Header
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
					Text(
						text = tr("dialog.agent.title"),
						style = typography.title.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
						color = colors.textPrimary,
					)

					if (connection != null) {
						Box(
							modifier = Modifier
								.clip(RoundedCornerShape(4.dp))
								.background(Color(0xFF1B4D3E))
								.border(BorderStroke(1.dp, Color(0xFF4EC9B0)), RoundedCornerShape(4.dp))
								.padding(horizontal = 6.dp, vertical = 2.dp),
						) {
							Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
								Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF4EC9B0)))
								Text(
									text = "ONLINE :23871",
									style = typography.monoSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
									color = Color(0xFF4EC9B0),
								)
							}
						}
					} else {
						Box(
							modifier = Modifier
								.clip(RoundedCornerShape(4.dp))
								.background(Color(0xFF4D1B1B))
								.border(BorderStroke(1.dp, colors.error), RoundedCornerShape(4.dp))
								.padding(horizontal = 6.dp, vertical = 2.dp),
						) {
							Text(
								text = "OFFLINE",
								style = typography.monoSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
								color = colors.error,
							)
						}
					}
				}

				CompactIconButton(
					onClick = onDismiss,
					size = 22.dp,
				) {
					IconClose(tint = colors.textMuted)
				}
			}

			Spacer(Modifier.height(10.dp))

			// Overview / Capability summary banner
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.clip(RoundedCornerShape(4.dp))
					.background(colors.panelElevated)
					.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(4.dp))
					.padding(10.dp),
			) {
				Text(
					text = tr("dialog.agent.message", connection?.endpoint ?: "-", connection?.token?.take(16) ?: "-", ""),
					style = typography.body.copy(fontSize = 11.sp, lineHeight = 15.sp),
					color = colors.textPrimary,
				)
			}

			Spacer(Modifier.height(12.dp))

			// Sub-tabs: Connection Config vs Installation Prompt
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.height(28.dp)
					.background(colors.inputBackground, RoundedCornerShape(4.dp))
					.padding(2.dp),
			) {
				Box(
					modifier = Modifier
						.weight(1f)
						.fillMaxHeight()
						.clip(RoundedCornerShape(3.dp))
						.background(if (selectedTab == 0) colors.panelElevated else Color.Transparent)
						.clickable { selectedTab = 0 },
					contentAlignment = Alignment.Center,
				) {
					Text(
						text = tr("dialog.agent.tab.config"),
						style = typography.caption.copy(fontSize = 11.sp, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal),
						color = if (selectedTab == 0) colors.accent else colors.textMuted,
					)
				}
				Box(
					modifier = Modifier
						.weight(1f)
						.fillMaxHeight()
						.clip(RoundedCornerShape(3.dp))
						.background(if (selectedTab == 1) colors.panelElevated else Color.Transparent)
						.clickable { selectedTab = 1 },
					contentAlignment = Alignment.Center,
				) {
					Text(
						text = tr("dialog.agent.tab.prompt"),
						style = typography.caption.copy(fontSize = 11.sp, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal),
						color = if (selectedTab == 1) colors.accent else colors.textMuted,
					)
				}
			}

			Spacer(Modifier.height(10.dp))

			// Tab Content Area
			Box(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth(),
			) {
				if (connection == null) {
					Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
						Text(
							text = tr("dialog.agent.unavailable", startupError ?: tr("dialog.agent.unknownError")),
							style = typography.body.copy(fontSize = 12.sp),
							color = colors.error,
						)
					}
				} else if (selectedTab == 0) {
					// Tab 0: Connection Config (Endpoint, Token, TOML, JSON)
					Column(
						modifier = Modifier
							.fillMaxSize()
							.verticalScroll(rememberScrollState()),
						verticalArrangement = Arrangement.spacedBy(10.dp),
					) {
						// Endpoint & Token row
						Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
							Column(modifier = Modifier.weight(1f)) {
								Text(tr("dialog.agent.endpoint"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
								Spacer(Modifier.height(3.dp))
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.background(colors.inputBackground, RoundedCornerShape(3.dp))
										.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(3.dp))
										.padding(horizontal = 8.dp, vertical = 5.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.SpaceBetween,
								) {
									Text(connection.endpoint, style = typography.monoSmall.copy(fontSize = 10.5.sp), color = colors.textPrimary)
									CompactButton(
										text = "Copy",
										onClick = { copyToClipboard(connection.endpoint, "Endpoint") },
										height = 18.dp,
									)
								}
							}

							Column(modifier = Modifier.weight(1f)) {
								Text(tr("dialog.agent.token"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
								Spacer(Modifier.height(3.dp))
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.background(colors.inputBackground, RoundedCornerShape(3.dp))
										.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(3.dp))
										.padding(horizontal = 8.dp, vertical = 5.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.SpaceBetween,
								) {
									Text("${connection.token.take(18)}…", style = typography.monoSmall.copy(fontSize = 10.5.sp), color = colors.textPrimary)
									CompactButton(
										text = "Copy",
										onClick = { copyToClipboard(connection.token, "Token") },
										height = 18.dp,
									)
								}
							}
						}

						// TOML Config
						Column {
							Row(
								modifier = Modifier.fillMaxWidth(),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.SpaceBetween,
							) {
								Text("Codex / Antigravity Config (TOML):", style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
								CompactButton(
									text = tr("dialog.agent.copyToml"),
									onClick = { copyToClipboard(connection.configToml, "TOML") },
									height = 20.dp,
									isPrimary = true,
								)
							}
							Spacer(Modifier.height(3.dp))
							Box(
								modifier = Modifier
									.fillMaxWidth()
									.background(Color(0xFF141416), RoundedCornerShape(3.dp))
									.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(3.dp))
									.padding(8.dp),
							) {
								Text(
									text = connection.configToml,
									style = typography.mono.copy(fontSize = 10.sp, lineHeight = 14.sp),
									color = Color(0xFFDCDCAA),
								)
							}
						}

						// Stdio Proxy JSON Config
						Column {
							Row(
								modifier = Modifier.fillMaxWidth(),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.SpaceBetween,
							) {
								Text("Claude Desktop / Cursor Config (Stdio Proxy JSON):", style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
								CompactButton(
									text = tr("dialog.agent.copyJson"),
									onClick = { copyToClipboard(stdioJson, "JSON") },
									height = 20.dp,
								)
							}
							Spacer(Modifier.height(3.dp))
							Box(
								modifier = Modifier
									.fillMaxWidth()
									.background(Color(0xFF141416), RoundedCornerShape(3.dp))
									.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(3.dp))
									.padding(8.dp),
							) {
								Text(
									text = stdioJson,
									style = typography.mono.copy(fontSize = 10.sp, lineHeight = 14.sp),
									color = Color(0xFF9CDCFE),
								)
							}
						}
					}
				} else {
					// Tab 1: Installation Prompt
					Column(
						modifier = Modifier
							.fillMaxSize(),
						verticalArrangement = Arrangement.spacedBy(8.dp),
					) {
						Row(
							modifier = Modifier.fillMaxWidth(),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.SpaceBetween,
						) {
							Text(
								text = tr("dialog.agent.promptDesc"),
								style = typography.caption.copy(fontSize = 10.5.sp),
								color = colors.textPrimary,
								modifier = Modifier.weight(1f),
							)
							CompactButton(
								text = tr("dialog.agent.copyPrompt"),
								onClick = { copyToClipboard(AGENT_INSTALLATION_PROMPT, "Prompt") },
								isPrimary = true,
								height = 24.dp,
							)
						}

						Box(
							modifier = Modifier
								.weight(1f)
								.fillMaxWidth()
								.background(Color(0xFF141416), RoundedCornerShape(4.dp))
								.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(4.dp))
								.padding(10.dp)
								.verticalScroll(rememberScrollState()),
						) {
							Text(
								text = AGENT_INSTALLATION_PROMPT,
								style = typography.mono.copy(fontSize = 10.5.sp, lineHeight = 15.sp),
								color = Color(0xFFCE9178),
							)
						}
					}
				}
			}

			Spacer(Modifier.height(10.dp))

			// Dialog Footer
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				if (copyNotification != null) {
					Text(
						text = "✓ $copyNotification",
						style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
						color = Color(0xFF4EC9B0),
					)
				} else {
					Spacer(Modifier.width(1.dp))
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

