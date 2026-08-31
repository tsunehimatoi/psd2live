package io.github.autolive2d

import io.github.autolive2d.core.AutoLive2DPipeline
import io.github.autolive2d.core.PipelineConfig
import io.github.autolive2d.core.ProgressListener
import io.github.autolive2d.ui.AutoLive2DFrame
import java.nio.file.Path
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.io.path.absolutePathString

fun main(arguments: Array<String>) {
	if (arguments.isEmpty()) {
		UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
		SwingUtilities.invokeLater { AutoLive2DFrame().isVisible = true }
		return
	}
	if (arguments.any { it == "--help" || it == "-h" }) {
		printUsage()
		return
	}
	val options = CliOptions.parse(arguments)
	val config = PipelineConfig(
		atlasSize = options.int("--atlas", 4096),
		meshSpacing = options.int("--mesh-spacing", 64),
		headTurnStrength = options.float("--head-strength", 1f),
		bodyStrength = options.float("--body-strength", 1f),
		generatePhysics = !options.flags.contains("--no-physics"),
		exportCmo3 = !options.flags.contains("--no-cmo3"),
		exportMoc3 = !options.flags.contains("--no-moc3"),
	)
	require(config.exportCmo3 || config.exportMoc3) { "至少要启用一种导出格式" }
	val input = Path.of(options.required("--input"))
	val output = Path.of(options.value("--output") ?: input.toAbsolutePath().parent.resolve("autolive2d-output").toString())
	println("AutoLive2D：${input.toAbsolutePath()} -> ${output.toAbsolutePath()}")
	val result = AutoLive2DPipeline().run(input, output, config, ProgressListener { stage, fraction ->
		println("%3d%%  %s".format((fraction * 100).toInt(), stage))
	})
	println("完成：${result.exportedFiles.size} 个文件")
	result.exportedFiles.forEach { println("  ${it.path.absolutePathString()} (${it.bytes} bytes)") }
	result.warnings.forEach { System.err.println("警告：$it") }
}

private fun printUsage() = println(
	"""
	AutoLive2D 0.1.0
	无参数启动桌面 GUI；命令行模式：
	  autolive2d --input character.psd [--output out] [选项]

	选项：
	  --atlas 4096            贴图页边长（256..16384）
	  --mesh-spacing 64       网格采样间距（像素）
	  --head-strength 1.0     头部 3D 转动幅度
	  --body-strength 1.0     身体动作幅度
	  --no-physics            不生成头发物理
	  --no-cmo3               不导出可编辑 CMO3
	  --no-moc3               不导出运行时 MOC3 文件族
	""".trimIndent(),
)

private data class CliOptions(val values: Map<String, String>, val flags: Set<String>) {
	fun value(name: String): String? = values[name]
	fun required(name: String): String = value(name) ?: error("缺少必需参数 $name；使用 --help 查看帮助")
	fun int(name: String, default: Int): Int = value(name)?.toIntOrNull() ?: default
	fun float(name: String, default: Float): Float = value(name)?.toFloatOrNull() ?: default

	companion object {
		private val flagNames = setOf("--no-physics", "--no-cmo3", "--no-moc3")
		fun parse(arguments: Array<String>): CliOptions {
			val values = linkedMapOf<String, String>()
			val flags = linkedSetOf<String>()
			var index = 0
			while (index < arguments.size) {
				val name = arguments[index]
				require(name.startsWith("--")) { "无法识别的参数：$name" }
				if (name in flagNames) {
					flags += name
					index++
				} else {
					require(index + 1 < arguments.size) { "$name 缺少值" }
					values[name] = arguments[index + 1]
					index += 2
				}
			}
			return CliOptions(values, flags)
		}
	}
}
