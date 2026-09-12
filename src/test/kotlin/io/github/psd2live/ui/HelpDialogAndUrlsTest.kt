package io.github.psd2live.ui

import io.github.psd2live.ui.components.HelpTab
import io.github.psd2live.ui.components.TutorialScenario
import io.github.psd2live.ui.utils.DesktopUtils
import java.io.InputStreamReader
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HelpDialogAndUrlsTest {

	@Test
	fun testDesktopUtilsUrlConstants() {
		val urls = listOf(
			DesktopUtils.GITHUB_REPO_URL,
			DesktopUtils.GITHUB_ISSUES_URL,
			DesktopUtils.GITHUB_RELEASES_URL,
			DesktopUtils.GITHUB_DOCS_URL,
		)

		for (url in urls) {
			assertTrue(url.startsWith("https://github.com/tsunehimatoi/psd2live"), "URL should point to repo: $url")
			val uri = URI(url)
			assertEquals("https", uri.scheme)
			assertEquals("github.com", uri.host)
		}
	}

	@Test
	fun testHelpTabAndTutorialScenarioEnums() {
		val tabs = HelpTab.entries
		assertEquals(5, tabs.size)
		assertTrue(tabs.contains(HelpTab.QUICK_START))
		assertTrue(tabs.contains(HelpTab.PSD_SPEC))
		assertTrue(tabs.contains(HelpTab.SHORTCUTS))
		assertTrue(tabs.contains(HelpTab.COMMUNITY_LINKS))
		assertTrue(tabs.contains(HelpTab.ABOUT))

		val scenarios = TutorialScenario.entries
		assertEquals(6, scenarios.size)
		assertTrue(scenarios.contains(TutorialScenario.STANDARD))
		assertTrue(scenarios.contains(TutorialScenario.CUSTOM_LAYERS))
		assertTrue(scenarios.contains(TutorialScenario.PREVIEW_ADJUST))
		assertTrue(scenarios.contains(TutorialScenario.UPSCALE))
		assertTrue(scenarios.contains(TutorialScenario.VARIANTS))
		assertTrue(scenarios.contains(TutorialScenario.AGENT_MCP))
	}

	@Test
	fun testHelpI18nKeysCompleteAcrossLanguages() {
		val requiredKeys = listOf(
			"menu.help",
			"menu.help.tutorial",
			"menu.help.psd_spec",
			"menu.help.shortcuts",
			"menu.help.github",
			"menu.help.issues",
			"menu.help.releases",
			"menu.help.docs",
			"menu.about",
			"help.dialog.title",
			"help.tab.quickstart",
			"help.tab.psd_spec",
			"help.tab.shortcuts",
			"help.tab.links",
			"help.tab.about",
			// Interactive tutorial triage & scenarios
			"help.tutorial.triage.title",
			"help.tutorial.triage.back",
			"help.tutorial.triage.section.core",
			"help.tutorial.triage.section.advanced",
			"help.tutorial.triage.standard.title",
			"help.tutorial.triage.standard.desc",
			"help.tutorial.triage.custom.title",
			"help.tutorial.triage.custom.desc",
			"help.tutorial.triage.preview.title",
			"help.tutorial.triage.preview.desc",
			"help.tutorial.triage.upscale.title",
			"help.tutorial.triage.upscale.desc",
			"help.tutorial.triage.variants.title",
			"help.tutorial.triage.variants.desc",
			"help.tutorial.triage.agent.title",
			"help.tutorial.triage.agent.desc",
			"help.tutorial.scenario.standard",
			"help.tutorial.scenario.custom",
			"help.tutorial.scenario.preview",
			"help.tutorial.scenario.upscale",
			"help.tutorial.scenario.variants",
			"help.tutorial.scenario.agent",
			// Standard scenario (See-Through PSD: 2 steps)
			"help.tutorial.standard.intro",
			"help.tutorial.standard.step1.title",
			"help.tutorial.standard.step1.desc",
			"help.tutorial.standard.step2.title",
			"help.tutorial.standard.step2.desc",
			// Custom layers scenario (自命名 PSD: 3 steps)
			"help.tutorial.custom.intro",
			"help.tutorial.custom.step1.title",
			"help.tutorial.custom.step1.desc",
			"help.tutorial.custom.step2.title",
			"help.tutorial.custom.step2.desc",
			"help.tutorial.custom.step3.title",
			"help.tutorial.custom.step3.desc",
			// Optional workflows (Independent points with direct links)
			"help.tutorial.optional.title",
			"help.tutorial.optional.desc",
			"help.tutorial.optional.action",
			"help.tutorial.optional.opt1.title",
			"help.tutorial.optional.opt1.desc",
			"help.tutorial.optional.opt2.title",
			"help.tutorial.optional.opt2.desc",
			"help.tutorial.optional.opt3.title",
			"help.tutorial.optional.opt3.desc",
			"help.tutorial.optional.opt4.title",
			"help.tutorial.optional.opt4.desc",
			// Preview & adjustment scenario
			"help.tutorial.preview.intro",
			"help.tutorial.preview.step1.title",
			"help.tutorial.preview.step1.desc",
			"help.tutorial.preview.step2.title",
			"help.tutorial.preview.step2.desc",
			// Texture upscale scenario
			"help.tutorial.upscale.intro",
			"help.tutorial.upscale.step1.title",
			"help.tutorial.upscale.step1.desc",
			"help.tutorial.upscale.step2.title",
			"help.tutorial.upscale.step2.desc",
			// Variants scenario
			"help.tutorial.variants.intro",
			"help.tutorial.variants.step1.title",
			"help.tutorial.variants.step1.desc",
			"help.tutorial.variants.step2.title",
			"help.tutorial.variants.step2.desc",
			"help.tutorial.variants.step3.title",
			"help.tutorial.variants.step3.desc",
			// Agent MCP scenario
			"help.tutorial.agent.warning",
			"help.tutorial.agent.intro",
			"help.tutorial.agent.ready.title",
			"help.tutorial.agent.ready.desc",
			"help.tutorial.agent.beta.title",
			"help.tutorial.agent.beta.desc",
			"help.tutorial.agent.unsupported.title",
			"help.tutorial.agent.unsupported.desc",
			"help.tutorial.agent.tip",
			// Spec
			"help.spec.intro",
			"help.spec.group.head",
			"help.spec.head.face",
			"help.spec.head.hairFront",
			"help.spec.head.hairBack",
			"help.spec.head.eyelash",
			"help.spec.head.eyewhite",
			"help.spec.head.irides",
			"help.spec.head.eyebrow",
			"help.spec.head.nose",
			"help.spec.head.mouth",
			"help.spec.head.mouthInternals",
			"help.spec.group.body",
			"help.spec.body.neck",
			"help.spec.body.topwear",
			"help.spec.body.limbs",
			"help.spec.group.notes",
			"help.spec.note.eyelash",
			"help.spec.note.mouth",
			"help.spec.note.body",
			// Shortcuts
			"help.shortcuts.group.project",
			"help.shortcuts.group.view",
			"help.shortcuts.group.tools",
			"help.shortcuts.openPsd",
			"help.shortcuts.openProject",
			"help.shortcuts.saveProject",
			"help.shortcuts.saveProjectAs",
			"help.shortcuts.reexportPsd",
			"help.shortcuts.generate",
			"help.shortcuts.openOutput",
			"help.shortcuts.settings",
			"help.shortcuts.help",
			"help.shortcuts.zoomWheel",
			"help.shortcuts.panCanvas",
			"help.shortcuts.selectLayer",
			"help.shortcuts.fitCenter",
			"help.shortcuts.zoomReset",
			"help.shortcuts.zoomStep",
			"help.shortcuts.textureUpscale",
			"help.shortcuts.agentConnect",
			"help.shortcuts.historyTree",
			// Links
			"help.links.intro",
			"help.links.github.title",
			"help.links.github.desc",
			"help.links.issues.title",
			"help.links.issues.desc",
			"help.links.releases.title",
			"help.links.releases.desc",
			"help.links.docs.title",
			"help.links.docs.desc",
			"help.links.openInBrowser",
			"help.links.copyUrl",
			"help.links.copied",
			// About
			"help.about.version",
			"help.about.tagline",
			"help.about.license",
			"help.about.disclaimer",
			"help.about.sdkNotice",
			"help.about.runtimeInfo",
			// Layer types & variants
			"layers.type.preset",
			"layers.type.toggle",
			"layers.type.switch",
			"layers.type.preset.tip",
			"layers.type.toggle.tip",
			"layers.type.switch.tip",
		)

		val locales = listOf(
			"/i18n/Messages_zh_CN.properties",
			"/i18n/Messages.properties",
			"/i18n/Messages_ja.properties",
		)

		for (localePath in locales) {
			val props = Properties()
			val stream = javaClass.getResourceAsStream(localePath)
			assertNotNull(stream, "Resource not found: $localePath")
			InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
				props.load(reader)
			}

			for (key in requiredKeys) {
				val value = props.getProperty(key)
				assertNotNull(value, "Missing key '$key' in $localePath")
				assertFalse(value.isBlank(), "Blank value for key '$key' in $localePath")
			}
		}
	}

	@Test
	fun testTutorialConventions() {
		val zhProps = Properties()
		val stream = javaClass.getResourceAsStream("/i18n/Messages_zh_CN.properties")
		assertNotNull(stream, "Resource not found: Messages_zh_CN.properties")
		InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
			zhProps.load(reader)
		}

		// 1. Ensure zero occurrences of "用户" in tutorial keys
		for (entry in zhProps.entries) {
			val key = entry.key.toString()
			val value = entry.value.toString()
			if (key.startsWith("help.tutorial.")) {
				assertFalse(
					value.contains("用户"),
					"Key '$key' must NOT contain the word '用户': $value"
				)
			}
		}

		// 2. Ensure switch variants have >= 3 distinct examples (expression, pose, costume/hair)
		val switchDesc = zhProps.getProperty("help.tutorial.variants.step2.desc")
		assertNotNull(switchDesc)
		assertTrue(switchDesc.contains("表情切换") || switchDesc.contains("expression"), "Must contain expression example")
		assertTrue(switchDesc.contains("动作") || switchDesc.contains("手势") || switchDesc.contains("arm_pose"), "Must contain pose/action example")
		assertTrue(switchDesc.contains("服饰") || switchDesc.contains("发型") || switchDesc.contains("costume"), "Must contain costume/hair example")

		// 3. Ensure preview & adjustment purpose is explicitly about selecting needed features
		val previewIntro = zhProps.getProperty("help.tutorial.preview.intro")
		assertNotNull(previewIntro)
		assertTrue(previewIntro.contains("挑选") || previewIntro.contains("选择") || previewIntro.contains("需求"), "Preview intro must explain feature selection purpose")
	}
}
