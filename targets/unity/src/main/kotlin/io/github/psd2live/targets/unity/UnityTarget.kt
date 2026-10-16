package io.github.psd2live.targets.unity

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.*
import java.math.BigDecimal
import java.security.MessageDigest
import kotlin.math.abs

/**
 * A Unity folder: `.p2lunity`, texture pages, and a one-time importer setup. Drop the folder into
 * `Assets`. The setup installs a single shared importer and then removes itself, so another
 * character can be copied in the same way. Unity builds a prefab whose Animator plays Idle.
 *
 * Each parameter key that moves a mesh becomes a blend shape. Clips are weight curves sampled at
 * `clip_fps`. Shapes add up, so combined poses are measured and reported rather than reproduced.
 * Unity's texture origin is the bottom left and the rig's is the top left, so V is flipped.
 * One unit is `pixels_per_unit` canvas pixels (default 100), Y up, the canvas centre at the origin.
 */
public class UnityTarget(private val evaluator: GeometryEvaluator) : ExportTarget {
	override val id: String = "unity"
	override val family: TargetFamily = TargetFamily.RIG
	override val description: String = "Unity prefab with blend shapes (.p2lunity)"
	override val capabilities: CapabilityProfile = CapabilityProfile(parameterGrid = 1, timeline = true)
	override val settings: List<TargetSetting> = listOf(
		TargetSetting.CLIPS,
		TargetSetting.Number("clip_fps", 30.0, 1.0, 120.0, 1.0, 0),
		TargetSetting.Number("pixels_per_unit", 100.0, 1.0, 100000.0, 1.0, 0),
		TargetSetting.SAMPLE_PAIRS,
	)

	private class ShapeKey(val parameter: Int, val key: Int, val name: String, val delta: FloatArray)
	private class Track(val mesh: String, val shape: String, val times: FloatArray, val values: FloatArray)
	private class Motion(val name: String, val loop: Boolean, val length: Float, val fps: Float, val tracks: List<Track>)

	override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
		val scale = 1f / (options.setting("pixels_per_unit")?.toFloatOrNull()?.takeIf { it > 0f } ?: 100f)
		val fps = options.setting("clip_fps")?.toFloatOrNull()?.takeIf { it > 0f } ?: 30f
		val losses = ArrayList(CapabilityScan.scan(ir, capabilities, options, defaults = mapOf(
			Feature.WARP_LATTICE to Handling.BAKED, Feature.BLEND_SHAPES to Handling.BAKED, Feature.GLUE to Handling.BAKED)))
		val meshes = ir.meshes.filter { it.visible && it.geometry != null && (ir.textures.bindings[it.id] ?: it.page) >= 0 }
		require(meshes.isNotEmpty()) { "No textured meshes to export" }
		val pages = meshes.map { ir.textures.bindings[it.id] ?: it.page }.distinct().sorted()
		require(pages.all { it in ir.textures.pages.indices && ir.textures.pages[it].png.size > 0 }) { "Texture pages have no pixels" }
		for (mesh in meshes) if (mesh.screen != Rgb.Black)
			losses += LossEntry(mesh.id, Feature.BLEND_MODE, Handling.DROPPED, note = "Screen color is not written")

		val built = evaluator.open(ir).use { session ->
			val bake = ParameterBake(ir, session, linkedPairs = false)
			losses += bake.crossTermLosses(options.int("sample_pairs", 48))
			if (opacityVaries(meshes, bake)) losses += LossEntry("*", Feature.TIMELINE, Handling.DROPPED,
				note = "Opacity curves are not written; each mesh keeps its rest opacity")
			val clips = if (options.flag("clips", true)) ir.clips else emptyList()
			Document(options.baseName, ir, meshes, pages, bake, scale, fps, clips)
		}
		val script = UnityTarget::class.java.getResourceAsStream("/unity/Psd2LiveUnityImporter.cs")?.readBytes()
			?: error("Unity importer script is missing from resources")
		val json = built.json()
		val setup = setupToken(options.baseName, json)
		val files = linkedMapOf(
			"${options.baseName}.p2lunity" to json.encodeToByteArray(),
			*pages.map { page -> "Textures/page_%02d.png".format(page) to ir.textures.pages[page].png.toArray() }.toTypedArray(),
			"Editor/Psd2LiveUnityImporter.cs.txt" to script,
			"Editor/Psd2LiveUnitySetup_$setup.cs" to unitySetup(setup).encodeToByteArray(),
			"README.txt" to readme(options.baseName).encodeToByteArray(),
		)
		return object : LoweredExport {
			override val losses: List<LossEntry> = losses
			override fun write(sink: OutputSink) { files.forEach { (path, bytes) -> sink.write(path, bytes) } }
		}
	}

	private fun opacityVaries(meshes: List<Mesh>, bake: ParameterBake): Boolean = meshes.any { mesh ->
		val rest = bake.rest.opacity[mesh.id] ?: mesh.opacity
		bake.axes.any { axis -> bake.samples.getValue(axis).any { row -> row.any { pose ->
			abs((pose.opacity[mesh.id] ?: mesh.opacity) - rest) > 0.02f
		} } }
	}

	private class Document(
		val name: String, val ir: RigIR, val meshes: List<Mesh>, val pages: List<Int>, val bake: ParameterBake,
		val scale: Float, val fps: Float, val clips: List<Clip>,
	) {
		private val pageIndex = pages.withIndex().associate { it.value to it.index }
		private val axes = bake.axes
		private val rest = bake.rest
		private val stacked = meshes.withIndex().sortedWith(compareBy({ rest.drawOrder[it.value.id] ?: it.value.drawOrder }, { it.index })).map { it.value }
		private val shapes = stacked.map { shapesOf(it) }
		private val motions = motions()
		private val poses = poses()

		private fun shapesOf(mesh: Mesh): List<ShapeKey> {
			val used = HashSet<String>()
			return axes.flatMap { axis ->
				val parameter = axes.indexOf(axis)
				axis.xKeys.indices.filter { axis.xKeys[it] != axis.x.default }.mapNotNull { key ->
					val offsets = bake.offsets(axis, key, 0, mesh.id)
					if (offsets.all { abs(it) < 1e-3f }) return@mapNotNull null
					var name = unityShapeName(axis.x.id, axis.xKeys[key])
					var suffix = 2
					while (!used.add(name)) name = unityShapeName(axis.x.id, axis.xKeys[key]) + "_" + suffix++
					ShapeKey(parameter, key, name, delta(offsets))
				}
			}
		}

		private fun motions(): List<Motion> {
			val used = HashSet<String>()
			return clips.mapNotNull { clip ->
				val times = ClipSampler.frameTimes(clip, fps)
				val poses = times.map { ClipSampler.valuesAt(clip, it) }
				val tracks = tracks(poses, times, clip.duration)
				if (tracks.isEmpty() && clip.duration <= 0f) return@mapNotNull null
				var name = clipTitle(clip)
				var suffix = 2
				while (!used.add(name)) name = clipTitle(clip) + " " + suffix++
				Motion(name, clip.loop, if (clip.duration > 0f) clip.duration else times.lastOrNull() ?: 0f, clip.fps.takeIf { it > 0f } ?: fps, tracks)
			}
		}

		private fun poses(): List<Motion> = axes.mapNotNull { axis ->
			val span = axis.x.max - axis.x.min
			if (span <= 1e-6f) return@mapNotNull null
			val times = (axis.xKeys.map { ((it - axis.x.min) / span).coerceIn(0f, 1f) } + listOf(0f, 1f)).distinct().sorted()
			val values = times.map { mapOf(axis.x.id to axis.x.min + span * it) }
			val tracks = tracks(values, times, holdUntil = null)
			if (tracks.isEmpty()) return@mapNotNull null
			Motion("param_" + unityToken(axis.x.id), loop = false, length = 1f, fps = fps, tracks = tracks)
		}

		/** Weight curves, 0..100, for the shapes each sampled pose moves. A final hold keeps a clip's authored length. */
		private fun tracks(values: List<Map<String, Float>>, times: List<Float>, holdUntil: Float?): List<Track> {
			val result = ArrayList<Track>()
			for ((index, mesh) in stacked.withIndex()) {
				val keys = shapes[index]
				if (keys.isEmpty()) continue
				val driven = keys.filter { axes[it.parameter].x.id in values.flatMap { pose -> pose.keys } }
				if (driven.isEmpty()) continue
				val frames = values.map { pose -> FloatArray(driven.size) { weights(driven, pose)[it] * 100f } }
				val kept = KeyReduction.reduce(times, frames, 0.05f)
				var keptTimes = kept.map { times[it] }
				var keptFrames = kept.map { frames[it] }
				if (holdUntil != null && holdUntil > keptTimes.last() + 1e-4f) {
					keptTimes = keptTimes + holdUntil
					keptFrames = keptFrames + keptFrames.last()
				}
				driven.forEachIndexed { shape, key ->
					result += Track(mesh.id, key.name, keptTimes.toFloatArray(), FloatArray(keptFrames.size) { keptFrames[it][shape] })
				}
			}
			return result
		}

		private fun weights(keys: List<ShapeKey>, values: Map<String, Float>): FloatArray = FloatArray(keys.size) { index ->
			val key = keys[index]
			val axis = axes[key.parameter]
			val samples = axis.xKeys
			val value = (values[axis.x.id] ?: axis.x.default).coerceIn(samples.first(), samples.last())
			val at = key.key
			when {
				samples[at] == value -> 1f
				at > 0 && value > samples[at - 1] && value < samples[at] -> (value - samples[at - 1]) / (samples[at] - samples[at - 1])
				at < samples.size - 1 && value > samples[at] && value < samples[at + 1] -> (samples[at + 1] - value) / (samples[at + 1] - samples[at])
				else -> 0f
			}
		}

		private fun place(points: FloatArray, z: Float): FloatArray = FloatArray(points.size / 2 * 3) { index ->
			val vertex = index / 3
			when (index % 3) {
				0 -> (points[vertex * 2] - ir.canvas.width / 2f) * scale
				1 -> (ir.canvas.height - points[vertex * 2 + 1]) * scale
				else -> z
			}
		}

		private fun delta(offsets: FloatArray): FloatArray = FloatArray(offsets.size / 2 * 3) { index ->
			when (index % 3) {
				0 -> offsets[index / 3 * 2] * scale
				1 -> -offsets[index / 3 * 2 + 1] * scale
				else -> 0f
			}
		}

		fun json(): String = buildString {
			append("{\"version\":1,\"name\":").append(jsonString(name))
			append(",\"pixelsPerUnit\":").append(jsonFloat(1f / scale))
			append(",\"textures\":[")
			pages.forEachIndexed { index, page ->
				if (index > 0) append(',')
				append("{\"file\":").append(jsonString("Textures/page_%02d.png".format(page))).append('}')
			}
			append("],\"meshes\":[")
			stacked.forEachIndexed { index, mesh ->
				if (index > 0) append(',')
				val geometry = mesh.geometry!!
				val positions = rest.positions[mesh.id] ?: geometry.positions.shared()
				val opacity = rest.opacity[mesh.id] ?: mesh.opacity
				val color = floatArrayOf(mesh.multiply.red, mesh.multiply.green, mesh.multiply.blue, opacity)
				val uvs = geometry.uvs.shared()
				val flipped = FloatArray(uvs.size) { if (it % 2 == 0) uvs[it] else 1f - uvs[it] }
				append("{\"id\":").append(jsonString(mesh.id))
				append(",\"texture\":").append(pageIndex.getValue(ir.textures.bindings[mesh.id] ?: mesh.page))
				append(",\"sortingOrder\":").append(index)
				append(",\"tint\":{\"r\":").append(jsonFloat(color[0]))
				append(",\"g\":").append(jsonFloat(color[1]))
				append(",\"b\":").append(jsonFloat(color[2]))
				append(",\"a\":").append(jsonFloat(color[3])).append('}')
				append(",\"vertices\":").append(jsonFloats(place(positions, -index * 0.0001f)))
				append(",\"uvs\":").append(jsonFloats(flipped))
				append(",\"triangles\":").append(geometry.indices.shared().joinToString(",", "[", "]"))
				append(",\"blendShapes\":[")
				shapes[index].forEachIndexed { shape, key ->
					if (shape > 0) append(',')
					append("{\"name\":").append(jsonString(key.name)).append(",\"delta\":").append(jsonFloats(key.delta)).append('}')
				}
				append("]}")
			}
			append("],\"clips\":").append(jsonMotions(motions))
			append(",\"poses\":").append(jsonMotions(poses)).append('}')
		}

		private fun jsonMotions(items: List<Motion>): String = buildString {
			append('[')
			items.forEachIndexed { index, motion ->
				if (index > 0) append(',')
				append("{\"name\":").append(jsonString(motion.name))
				append(",\"loop\":").append(motion.loop)
				append(",\"length\":").append(jsonFloat(motion.length))
				append(",\"fps\":").append(jsonFloat(motion.fps))
				append(",\"tracks\":[")
				motion.tracks.forEachIndexed { trackIndex, track ->
					if (trackIndex > 0) append(',')
					append("{\"mesh\":").append(jsonString(track.mesh))
					append(",\"shape\":").append(jsonString(track.shape))
					append(",\"times\":").append(jsonFloats(track.times))
					append(",\"values\":").append(jsonFloats(track.values)).append('}')
				}
				append("]}")
			}
			append(']')
		}
	}

}

/** Tokens Unity can store as blend-shape names and hierarchy paths. */
internal fun unityToken(text: String): String = buildString {
	for (character in text) append(if (character.isLetterOrDigit() || character == '_') character else '_')
}.ifEmpty { "param" }

internal fun unityShapeName(parameterId: String, key: Float): String = unityToken(parameterId) + "_" + keyToken(key)

private fun keyToken(key: Float): String {
	val number = if (abs(key - key.toInt().toFloat()) < 1e-4f && abs(key) < 1_000_000f) key.toInt().toString()
	else BigDecimal(key.toString()).stripTrailingZeros().toPlainString()
	return number.replace("-", "n").replace(".", "p")
}

private fun clipTitle(clip: Clip): String = buildString {
	val source = clip.name.ifBlank { clip.id }
	for (character in source) append(if (character == '/' || character == '\\' || character.isISOControl()) '_' else character)
}.ifBlank { "clip" }

private fun setupToken(baseName: String, json: String): String =
	MessageDigest.getInstance("SHA-256").digest("$baseName\n$json".toByteArray())
		.take(4).joinToString("") { "%02x".format(it) }

/** A uniquely named editor script. It installs one shared importer, then deletes itself. */
private fun unitySetup(id: String) = """
	// Generated by PSD2Live. Installs one shared importer, then removes itself.
	using System.Collections.Generic;
	using System.IO;
	using UnityEditor;
	using UnityEngine;

	[InitializeOnLoad]
	static class Psd2LiveUnitySetup_$id
	{
		const string Marker = "psd2live-importer ";

		static Psd2LiveUnitySetup_$id()
		{
			EditorApplication.update += Install;
		}

		static void Install()
		{
			EditorApplication.update -= Install;
			var setup = Find("Psd2LiveUnitySetup_$id.cs");
			if (setup == null) return;
			var folder = Path.GetDirectoryName(setup).Replace('\\', '/');
			var templateAsset = folder + "/Psd2LiveUnityImporter.cs.txt";
			var templateFull = Full(templateAsset);
			if (!File.Exists(templateFull)) return;
			var template = File.ReadAllText(templateFull);
			var version = VersionOf(template);
			var copies = Copies();
			if (copies.Count == 0)
			{
				const string dest = "Assets/PSD2Live/Editor/Psd2LiveUnityImporter.cs";
				Directory.CreateDirectory(Path.GetDirectoryName(Full(dest)));
				File.WriteAllText(Full(dest), template);
				AssetDatabase.ImportAsset(dest);
			}
			else if (VersionOf(File.ReadAllText(Full(copies[0]))) < version)
			{
				File.WriteAllText(Full(copies[0]), template);
				AssetDatabase.ImportAsset(copies[0]);
			}
			for (var i = 1; i < copies.Count; i++) Remove(copies[i]);
			Remove(templateAsset);
			Remove(setup);
			try
			{
				if (Directory.Exists(Full(folder)) && Directory.GetFileSystemEntries(Full(folder)).Length == 0)
					Remove(folder);
			}
			catch (System.Exception) { }
			AssetDatabase.Refresh();
		}

		static List<string> Copies()
		{
			var copies = new List<string>();
			foreach (var guid in AssetDatabase.FindAssets("Psd2LiveUnityImporter"))
			{
				var path = AssetDatabase.GUIDToAssetPath(guid);
				if (path.EndsWith("Psd2LiveUnityImporter.cs", System.StringComparison.Ordinal)) copies.Add(path);
			}
			return copies;
		}

		static string Find(string fileName)
		{
			foreach (var guid in AssetDatabase.FindAssets(Path.GetFileNameWithoutExtension(fileName)))
			{
				var path = AssetDatabase.GUIDToAssetPath(guid);
				if (path.EndsWith(fileName, System.StringComparison.Ordinal)) return path;
			}
			return null;
		}

		static string Full(string assetPath) => Path.Combine(Path.GetDirectoryName(Application.dataPath), assetPath);

		static int VersionOf(string text)
		{
			var at = text.IndexOf(Marker, System.StringComparison.Ordinal);
			if (at < 0) return 0;
			var value = 0;
			for (var i = at + Marker.Length; i < text.Length && text[i] >= '0' && text[i] <= '9'; i++)
				value = value * 10 + (text[i] - '0');
			return value;
		}

		static void Remove(string assetPath)
		{
			if (AssetDatabase.DeleteAsset(assetPath)) return;
			var full = Full(assetPath);
			if (File.Exists(full)) File.Delete(full);
			if (File.Exists(full + ".meta")) File.Delete(full + ".meta");
			if (Directory.Exists(full)) Directory.Delete(full, false);
		}
	}
	""".trimIndent()

private fun readme(name: String) = """
PSD2Live Unity export (experimental)

Requires Unity 6.0 (6000.0) or newer.
Copy this whole folder into a Unity project's Assets directory, including Editor.
Unity installs one importer for the project and then removes that Editor copy, so the
next character can be copied in the same way. $name.p2lunity becomes a prefab and
$name.controller appears beside it. The prefab's Animator uses that controller.
Drag the prefab into a scene and press Play. The controller's default state is Idle
when that motion exists. Edit transitions on $name.controller; the .p2lunity file
itself is the source document and cannot store Animator edits. Parts that share a
texture and are next to each other in draw order become one mesh. A part that sits
between them keeps its own draw order. Animation pauses while it is off screen.

Blend shapes are one per parameter key and they add up, so poses that combine
several parameters can differ from PSD2Live. Masks, non-normal blend modes and
physics are listed in the loss report rather than reproduced.

---

Unity 6.0(6000.0) 이상이 필요합니다.
이 폴더를 Editor까지 포함해 Unity 프로젝트의 Assets 안에 복사하세요.
임포터는 프로젝트에 하나만 설치되고, 캐릭터 폴더의 Editor 사본은 지워지므로
다음 캐릭터도 같은 방식으로 넣으면 됩니다. $name.p2lunity 가 프리팹으로 들어오고,
옆에 $name.controller 가 생깁니다. 프리팹의 Animator는 그 컨트롤러를 사용합니다.
프리팹을 씬에 넣고 Play 하면 Idle이 기본 상태입니다. 전이는 $name.controller 에서
편집하세요. .p2lunity 파일은 원본이라 Animator 수정이 저장되지 않습니다.
같은 텍스처를 쓰고 그리는 순서가 맞닿은 파츠만 메시 하나로 합칩니다. 그 사이에
끼는 파츠는 순서를 유지합니다. 화면 밖에서는 애니메이션이 쉬어 있습니다.

블렌드셰이프는 파라미터 키마다 하나씩이며 서로 더해집니다. 여러 파라미터가
겹친 포즈는 PSD2Live와 달라질 수 있습니다. 마스크, 일반이 아닌 블렌드 모드,
물리는 손실 보고서에 남고 재현되지 않습니다.
""".trimIndent()

private fun jsonString(text: String) = buildString {
	append('"')
	for (character in text) when (character) {
		'"' -> append("\\\"")
		'\\' -> append("\\\\")
		'\n' -> append("\\n")
		'\r' -> append("\\r")
		'\t' -> append("\\t")
		else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
	}
	append('"')
}

private fun jsonFloat(value: Float): String {
	require(value.isFinite()) { "Non-finite number in the Unity document" }
	val text = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
	// Unity's JsonUtility leaves a float field at 0 when the JSON number has no decimal point.
	return if ('.' in text) text else "$text.0"
}

private fun jsonFloats(values: FloatArray) = values.joinToString(",", "[", "]", transform = ::jsonFloat)
