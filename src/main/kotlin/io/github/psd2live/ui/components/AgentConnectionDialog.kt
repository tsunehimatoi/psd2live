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
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

fun buildInstallationPrompt(
	endpoint: String,
	token: String,
	projectDir: String,
	language: AppLanguage,
): String {
	val proxyPath = "$projectDir/mcp_proxy.py"
	val skillsPath = "$projectDir/.agent/skills"

	return when (language) {
		AppLanguage.CHINESE -> """
请将 PSD2Live 的 MCP 服务和 Live2D 绑骨技能 (Skill) 安装并配置到你自身环境中（系统级全局或当前应用/工作区）：

1. 配置 MCP 服务 (系统级或应用级)：
   根据你的运行环境（如 Claude Desktop、Cursor、Antigravity、Windsurf、Cline / Roo Code 等），将 PSD2Live 添加到对应的 MCP 配置文件中：
   - 方式一：SSE / HTTP 直连 (推荐)
     - 名称: psd2live
     - URL: $endpoint
     - Headers: {"Authorization": "Bearer $token"}
   - 方式二：Stdio 代理 (若环境仅支持 stdio 命令方式)
     - 名称: psd2live
     - 命令: python
     - 参数: ["$proxyPath"]

2. 安装工作区技能 (Skill)：
   将本项目自带的 Live2D 自动化技能复制或链接至你的技能目录（如系统级 ~/.gemini/antigravity/skills/ 或 Cursor Rules / 项目技能库），以便自主调用绑骨与分层工具：
   - $skillsPath/psd2live-rigging
   - $skillsPath/hair-separation

请检测你当前所在的 Agent 宿主环境与配置文件位置，自动完成上述 MCP 与 Skill 的安装与配置。
""".trimIndent()

		AppLanguage.JAPANESE -> """
PSD2Live の MCP サーバーおよび Live2D リギングスキル (Skill) を自身（システム全体または現在のアプリ／ワークスペース）にインストール・設定してください：

1. MCP サーバーの追加（システムまたはアプリレベル）：
   ご利用のクライアント環境（Claude Desktop、Cursor、Antigravity、Windsurf、Cline / Roo Code など）の MCP 設定ファイルに追加してください：
   - 方式 1: SSE / HTTP 直結（推奨）
     - 名前: psd2live
     - URL: $endpoint
     - ヘッダー: {"Authorization": "Bearer $token"}
   - 方式 2: Stdio プロキシ（stdio コマンドのみ対応の場合）
     - 名前: psd2live
     - コマンド: python
     - 引数: ["$proxyPath"]

2. スキル (Skill) のインストール：
   本プロジェクト付属の Live2D 自動リギングスキルを、ご自身のスキルディレクトリ（システム全体 ~/.gemini/antigravity/skills/ や Cursor / Claude ルール等）にコピーまたはリンクして有効化してください：
   - $skillsPath/psd2live-rigging
   - $skillsPath/hair-separation

現在のクライアント種別と設定ファイルのパスを判別し、上記 MCP とスキルのインストールを自動実行してください。
""".trimIndent()

		AppLanguage.ENGLISH -> """
Please install and configure the PSD2Live MCP server and Live2D rigging skills into yourself (system-level global or application/workspace-level):

1. Install MCP Server (System or Application Level):
   Based on your host environment (e.g., Claude Desktop, Cursor, Antigravity, Windsurf, Cline / Roo Code), add PSD2Live to your MCP configuration:
   - Method 1: SSE / HTTP Direct (Recommended)
     - Name: psd2live
     - URL: $endpoint
     - Headers: {"Authorization": "Bearer $token"}
   - Method 2: Stdio Proxy (If only command/stdio is supported)
     - Name: psd2live
     - Command: python
     - Args: ["$proxyPath"]

2. Install Skills:
   Copy or link the Live2D automation skills from this project into your skill directory (e.g., system-level ~/.gemini/antigravity/skills/, Cursor Rules, or workspace rules) so they can be invoked automatically:
   - $skillsPath/psd2live-rigging
   - $skillsPath/hair-separation

Please inspect your agent host environment and configuration file paths, then automatically complete the installation and setup for both the MCP server and skills.
""".trimIndent()
	}
}

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

	val projectDir = remember {
		runCatching { File(".").canonicalPath.replace('\\', '/') }
			.getOrDefault(System.getProperty("user.dir")?.replace('\\', '/') ?: ".")
	}
	val proxyPath = "$projectDir/mcp_proxy.py"
	val endpoint = connection?.endpoint ?: "http://127.0.0.1:23871/sse"
	val token = connection?.token ?: ""

	val stdioJson = remember(connection, proxyPath) {
		"""
		{
		  "mcpServers": {
		    "psd2live": {
		      "command": "python",
		      "args": ["$proxyPath"]
		    }
		  }
		}
		""".trimIndent()
	}

	val installationPrompt = remember(connection, projectDir, I18n.currentLanguage) {
		buildInstallationPrompt(
			endpoint = endpoint,
			token = token,
			projectDir = projectDir,
			language = I18n.currentLanguage,
		)
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
					text = tr("dialog.agent.message"),
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
								onClick = { copyToClipboard(installationPrompt, "Prompt") },
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
								text = installationPrompt,
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

