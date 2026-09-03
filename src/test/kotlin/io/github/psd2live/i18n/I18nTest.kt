package io.github.psd2live.i18n

import io.github.psd2live.core.StandardParameters
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class I18nTest {
	@Test
	fun `all supported languages load and format messages`() = withOriginalLanguage {
		val expectedTitles = mapOf(
			AppLanguage.CHINESE to "项目",
			AppLanguage.ENGLISH to "Project",
			AppLanguage.JAPANESE to "プロジェクト",
		)
		for ((language, expectedTitle) in expectedTitles) {
			I18n.setLanguage(language, persist = false)
			assertEquals(expectedTitle, tr("project.title"))
			val message = tr("status.completed", 3, 2)
			assertTrue("3" in message && "2" in message)
			assertFalse(message.startsWith("!"))
		}
	}

	@Test
	fun `generated display names follow the selected language`() = withOriginalLanguage {
		I18n.setLanguage(AppLanguage.ENGLISH, persist = false)
		assertEquals("Angle X", StandardParameters.all.first().name)
		I18n.setLanguage(AppLanguage.JAPANESE, persist = false)
		assertEquals("角度 X", StandardParameters.all.first().name)
	}

	@Test
	fun `translation files contain the same keys`() {
		val resources = listOf(
			"i18n/Messages.properties",
			"i18n/Messages_zh_CN.properties",
			"i18n/Messages_ja.properties",
		)
		val keysByResource = resources.associateWith(::loadProperties).mapValues { it.value.stringPropertyNames() }
		val expected = keysByResource.getValue(resources.first())
		for (resource in resources.drop(1)) {
			assertEquals(expected, keysByResource.getValue(resource), "Translation keys differ in $resource")
		}
	}

	private fun loadProperties(resource: String): Properties = Properties().apply {
		val stream = checkNotNull(I18nTest::class.java.classLoader.getResourceAsStream(resource)) { "Missing $resource" }
		InputStreamReader(stream, StandardCharsets.UTF_8).use(::load)
	}

	private inline fun withOriginalLanguage(block: () -> Unit) {
		val original = I18n.currentLanguage
		try {
			block()
		} finally {
			I18n.setLanguage(original, persist = false)
		}
	}
}
