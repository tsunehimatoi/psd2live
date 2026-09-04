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
	val connectionInfo = AgentMcpConnectionInfo(endpoint, token)
	val codexToml = connectionInfo.configToml
	val geminiJson = connectionInfo.configGeminiJson

	return when (language) {
		AppLanguage.CHINESE -> """
请配置 PSD2Live MCP。PSD2Live 桌面应用必须保持运行。

优先使用宿主原生支持的 Streamable HTTP：

ChatGPT 桌面版 / Codex CLI / Codex IDE：将下列配置合并到 ~/.codex/config.toml（或受信任项目的 .codex/config.toml），保存后重启客户端并用 /mcp 检查连接：
$codexToml

Gemini / Antigravity：将下列 psd2live 条目合并到 ~/.gemini/config/mcp_config.json，刷新 MCP Servers 并检查工具列表：
$geminiJson

其他支持 Streamable HTTP 的宿主：使用端点 $endpoint，并发送请求头 Authorization: Bearer $token。不要改成旧的 /sse 端点。

技能安装：将以下目录复制到宿主官方的技能目录（例如 ~/.codex/skills/、~/.gemini/config/skills/ 或项目级技能目录）：
- $skillsPath/psd2live-rigging
- $skillsPath/hair-separation

只要调用上述 Skill 或 PSD2Live MCP，就必须先完整读取 psd2live-rigging；头发任务还要读取 hair-separation。差分、拆分、遮挡补全或重建像素时，每个新素材都必须实际调用 Nano Banana Pro/NBP 或 GPT Image 2（gpt-image-2）；Codex/ChatGPT 中先加载 imagegen 再调用 image_gen。不得用 Python、PIL/OpenCV、Matplotlib、SVG 或 Canvas 绘制替代品。该要求仅在 Skill/MCP 被调用后生效，不影响普通编程任务。

仅当宿主不支持 HTTP MCP 时，才使用 stdio 后备：python "$proxyPath"。代理会读取 PSD2LIVE_MCP_TOKEN，或在 Windows 上读取 PSD2Live 保存的令牌。

修改前备份并合并配置，不要覆盖其他服务器，也不要修改任何宿主的程序文件、内部 RPC 或会话数据库。连接后先列出工具并读取 project_get_state。超时或断线后不要盲目重试写操作；重新连接并用 project_get_state/history_list 核对是否已提交。
""".trimIndent()

		AppLanguage.JAPANESE -> """
PSD2Live MCP を設定してください。PSD2Live デスクトップアプリは起動したままにします。

ホストがネイティブ対応する Streamable HTTP を優先します。

ChatGPT デスクトップ / Codex CLI / Codex IDE：次の設定を ~/.codex/config.toml（または信頼済みプロジェクトの .codex/config.toml）へマージし、クライアントを再起動して /mcp で接続を確認します：
$codexToml

Gemini / Antigravity：次の psd2live エントリを ~/.gemini/config/mcp_config.json へマージし、MCP Servers を更新してツール一覧を確認します：
$geminiJson

その他の Streamable HTTP 対応ホスト：エンドポイント $endpoint と Authorization: Bearer $token ヘッダーを使用します。旧 /sse エンドポイントには変更しません。

スキル：次のディレクトリをホスト公式のスキルディレクトリ（例：~/.codex/skills/、~/.gemini/config/skills/、プロジェクト単位のディレクトリ）へコピーします：
- $skillsPath/psd2live-rigging
- $skillsPath/hair-separation

上記 Skill または PSD2Live MCP を使用するときは、最初に psd2live-rigging 全文を読み、髪の作業では hair-separation も読みます。差分、パーツ分割、オクルージョン補完、ピクセル再構築では、新しい各素材について Nano Banana Pro/NBP または GPT Image 2（gpt-image-2）を実際に呼び出します。Codex/ChatGPT では imagegen を読み込み image_gen を呼び出します。Python、PIL/OpenCV、Matplotlib、SVG、Canvas による代替描画は禁止です。この要件は Skill/MCP 使用時だけに適用し、通常のプログラミング作業には適用しません。

HTTP MCP 非対応のホストでのみ stdio フォールバック python "$proxyPath" を使用します。プロキシは PSD2LIVE_MCP_TOKEN、または Windows 上で PSD2Live が保存したトークンを読み込みます。

変更前に設定をバックアップして既存エントリへマージし、ホストのプログラム、内部 RPC、会話データベースは変更しません。接続後はツールを列挙して project_get_state を読みます。タイムアウトや切断後に書き込みを盲目的に再試行せず、再接続後に project_get_state/history_list でコミット状態を照合します。
""".trimIndent()

		AppLanguage.ENGLISH -> """
Configure PSD2Live MCP. Keep the PSD2Live desktop application running.

Prefer the host's native Streamable HTTP support.

ChatGPT desktop / Codex CLI / Codex IDE: merge this into ~/.codex/config.toml (or .codex/config.toml in a trusted project), restart the client, and verify the connection with /mcp:
$codexToml

Gemini / Antigravity: merge this psd2live entry into ~/.gemini/config/mcp_config.json, refresh MCP Servers, and verify that the tool list loads:
$geminiJson

Other Streamable HTTP hosts: use endpoint $endpoint with the header Authorization: Bearer $token. Do not change it to the legacy /sse endpoint.

Skills: copy these directories to the host's documented skill location, such as ~/.codex/skills/, ~/.gemini/config/skills/, or a project-scoped skill directory:
- $skillsPath/psd2live-rigging
- $skillsPath/hair-separation

Whenever either Skill or the PSD2Live MCP is invoked, first read psd2live-rigging in full; also read hair-separation for hair work. For differences, part separation, occlusion completion, or pixel reconstruction, make an actual Nano Banana Pro/NBP or GPT Image 2 (gpt-image-2) call for every new asset. In Codex/ChatGPT, load imagegen and call image_gen. Do not draw substitutes with Python, PIL/OpenCV, Matplotlib, SVG, or Canvas. This requirement starts only when a Skill/MCP is invoked and does not affect ordinary programming tasks.

Use the stdio fallback, python "$proxyPath", only for hosts without HTTP MCP support. The proxy reads PSD2LIVE_MCP_TOKEN or, on Windows, the token saved by PSD2Live.

Back up and merge configuration without replacing other servers. Do not modify any host's program files, internal RPCs, or conversation database. After connecting, list tools and read project_get_state. Never blindly retry a write after a timeout or disconnect; reconnect and reconcile project_get_state/history_list first.
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
	val endpoint = connection?.endpoint ?: "http://127.0.0.1:23871/mcp"
	val token = connection?.token ?: ""
	val geminiJson = remember(connection) { connection?.configGeminiJson.orEmpty() }

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

						// ChatGPT desktop / Codex HTTP Config
						Column {
							Row(
								modifier = Modifier.fillMaxWidth(),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.SpaceBetween,
							) {
								Text(tr("dialog.agent.configCodex"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
								CompactButton(
									text = tr("dialog.agent.copyToml"),
									onClick = { copyToClipboard(connection.configToml, "ChatGPT / Codex TOML") },
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

						// Gemini / Antigravity HTTP Config
						Column {
							Row(
								modifier = Modifier.fillMaxWidth(),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.SpaceBetween,
							) {
								Text(tr("dialog.agent.configGemini"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
								CompactButton(
									text = tr("dialog.agent.copyHttpJson"),
									onClick = { copyToClipboard(geminiJson, "Gemini / Antigravity JSON") },
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
									text = geminiJson,
									style = typography.mono.copy(fontSize = 10.sp, lineHeight = 14.sp),
									color = Color(0xFF9CDCFE),
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
								Text(tr("dialog.agent.configStdio"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
								CompactButton(
									text = tr("dialog.agent.copyStdioJson"),
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
