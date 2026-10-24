package io.github.psd2live.scenario

import io.github.psd2live.CliOptions
import io.github.psd2live.testing.MESSAGE_BUNDLES
import io.github.psd2live.testing.messageBundle
import io.github.psd2live.testing.missingMessages
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.MeshPointMode
import io.github.psd2live.ui.state.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a user meets before any document: every language has every message, every tool and shortcut has a name,
 * no keymap preset binds one input twice, and the command line accepts every option its help lists.
 */
class CatalogContractsTest {
	/** Every value the graphics settings page names. */
	private val graphicsChoices: List<io.github.psd2live.render.GraphicsChoice> =
		listOf(io.github.psd2live.render.RenderPath.entries, io.github.psd2live.render.VsyncMode.entries,
			io.github.psd2live.render.FrameRateLimit.entries, io.github.psd2live.render.BackgroundFrameRate.entries,
			io.github.psd2live.render.WindowAntialiasing.entries, io.github.psd2live.render.CanvasAntialiasing.entries,
			io.github.psd2live.render.TextureFiltering.entries, io.github.psd2live.render.GpuCacheSize.entries,
			io.github.psd2live.render.ThreadedGl.entries, io.github.psd2live.render.QualityPreset.entries).flatten()

	@Test fun everyLanguageHasEveryMessageAndEveryToolAndShortcutIsNamed() {
		val bundles = MESSAGE_BUNDLES.associateWith(::messageBundle)
		val reference = bundles.getValue("Messages").stringPropertyNames()
		for ((name, props) in bundles) {
			val keys = props.stringPropertyNames()
			assertEquals(emptySet(), reference - keys, "$name is missing keys")
			assertEquals(emptySet(), keys - reference, "$name has keys the English bundle lacks")
			assertEquals(emptyList(), keys.filter { props.getProperty(it).isBlank() }, "$name has blank values")
		}
		assertEquals(emptyList(), missingMessages(CanvasTool.entries.map { "editor.tool.${it.name.lowercase()}" } + ShortcutAction.entries.map { it.labelKey } +
			SettingsTopic.entries.map { it.labelKey } + graphicsChoices.flatMap { listOfNotNull(it.labelKey, it.descriptionKey) }))
	}

	/** A message taking arguments is a MessageFormat pattern: shortcut markup in it must be quoted ('{key:…}'). */
	@Test fun everyMessageTakingArgumentsFormats() {
		val argument = Regex("""\{\d""")
		for (name in MESSAGE_BUNDLES) {
			val props = messageBundle(name)
			val broken = props.stringPropertyNames().filter { argument.containsMatchIn(props.getProperty(it)) }
				.filter { runCatching { java.text.MessageFormat(props.getProperty(it)) }.isFailure }
			assertEquals(emptyList(), broken.sorted(), "$name has messages MessageFormat cannot parse")
		}
	}

	/** The point tool's two faces (the old point tool and knife) each keep a name, a hint and their old key in every preset that had it. */
	@Test fun everyFaceOfThePointToolIsNamedAndKeepsItsKey() {
		assertEquals(emptyList(), missingMessages(MeshPointMode.entries.flatMap { listOf(it.labelKey, it.hintKey) }))
		assertEquals(MeshPointMode.entries.size, MeshPointMode.entries.map { it.action }.toSet().size)
		val keymap = Keymap.of(KeymapPreset.entries.first())
		assertEquals("K", keymap.labelFor(MeshPointMode.LINE.action))
		assertEquals("Alt+M", keymap.labelFor(MeshPointMode.POINT.action))
	}

	@Test fun everyKeymapPresetIsFreeOfCollisions() {
		for (preset in KeymapPreset.entries) {
			val keymap = Keymap.of(preset)
			for (action in ShortcutAction.entries) for (binding in keymap.bindingsFor(action)) {
				assertEquals(emptyList(), keymap.conflictsFor(action, binding), "$preset $action ${binding.format()}")
				val mouse = binding.mouse
				if (action.kind == ShortcutKind.DRAG) assertTrue(mouse != null && !mouse.isWheel, "$preset $action ${binding.format()}")
				else assertTrue(mouse != MouseInput.LEFT && mouse != MouseInput.RIGHT, "$preset $action ${binding.format()}")
			}
		}
	}

	@Test fun theCommandLineAcceptsEveryOptionItsHelpLists() {
		val handledFirst = setOf("--clear-user-data", "--help")
		for (bundle in MESSAGE_BUNDLES) {
			val listed = Regex("--[a-z0-9-]+").findAll(messageBundle(bundle).getProperty("cli.usage")).map { it.value }.toSet() - handledFirst
			assertTrue(listed.isNotEmpty(), bundle)
			for (name in listed) assertTrue(name in CliOptions.flagNames || name in CliOptions.valueNames, "$bundle lists $name")
		}
		val options = CliOptions.parse(arrayOf("--input", "a.psd", "--mesh-pixels", "--mesh-spacing", "40"))
		assertEquals(setOf("--mesh-pixels"), options.flags)
		assertEquals(40, options.int("--mesh-spacing", 64))
	}
}
