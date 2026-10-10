package io.github.psd2live.scenario

import io.github.psd2live.CliOptions
import io.github.psd2live.testing.MESSAGE_BUNDLES
import io.github.psd2live.testing.messageBundle
import io.github.psd2live.testing.missingMessages
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.state.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a user meets before any document: every language has every message, every tool and shortcut has a name,
 * no keymap preset binds one input twice, and the command line accepts every option its help lists.
 */
class CatalogContractsTest {
	@Test fun everyLanguageHasEveryMessageAndEveryToolAndShortcutIsNamed() {
		val bundles = MESSAGE_BUNDLES.associateWith(::messageBundle)
		val reference = bundles.getValue("Messages").stringPropertyNames()
		for ((name, props) in bundles) {
			val keys = props.stringPropertyNames()
			assertEquals(emptySet(), reference - keys, "$name is missing keys")
			assertEquals(emptySet(), keys - reference, "$name has keys the English bundle lacks")
			assertEquals(emptyList(), keys.filter { props.getProperty(it).isBlank() }, "$name has blank values")
		}
		assertEquals(emptyList(), missingMessages(CanvasTool.entries.map { "editor.tool.${it.name.lowercase()}" } + ShortcutAction.entries.map { it.labelKey }))
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
