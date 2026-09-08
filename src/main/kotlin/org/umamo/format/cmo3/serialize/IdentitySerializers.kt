package org.umamo.format.cmo3.serialize

import org.jdom.Element
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id

/**
 * Custom serializer for all `*Guid` tags: `<Kind uuid="…" note="…" />` (uuid then note attributes).
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
internal object GuidSerializer : XmlSerializer {
	override fun createElement(name: String?, value: Any, ctx: WriteContext): Element {
		val guid = value as Guid
		val element = Element(guid.kind) // tag is the concrete *Guid kind
		element.setFieldName(name)
		element.setAttribute("uuid", guid.uuid)
		element.setAttribute("note", guid.note)
		return element
	}

	override fun createInstance(element: Element, ctx: ReadContext): Any =
		Guid(element.name).apply {
			uuid = element.getAttributeValue("uuid") ?: ""
			note = element.getAttributeValue("note") ?: ""
		}
}

/**
 * Custom serializer for all `*Id` tags: `<Kind idstr="…" />`.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
internal object IdSerializer : XmlSerializer {
	override fun createElement(name: String?, value: Any, ctx: WriteContext): Element {
		val id = value as Id
		val element = Element(id.kind)
		element.setFieldName(name)
		element.setAttribute("idstr", id.idstr)
		return element
	}

	override fun createInstance(element: Element, ctx: ReadContext): Any =
		Id(element.name).apply { idstr = element.getAttributeValue("idstr") ?: "" }
}

/**
 * The `*Guid` tags emitted by the editor. One [Guid] type, many tags.
 */
internal val GUID_TAGS: List<String> =
	listOf(
		"CAffecterGuid",
		"CArtPathBrushGuid",
		"CControllerCurveGuid",
		"CControllerPointGuid",
		"CDeformerGuid",
		"CDrawableGuid",
		"CExtensionGuid",
		"CFormGuid",
		"CGuideGuid",
		"CLayeredImageGuid",
		"CLayerGuid",
		"CModelGuid",
		"CModelImageGuid",
		"CMotionSyncSettingGuid",
		"CParameterGroupGuid",
		"CParameterGuid",
		"CPartGuid",
		"CPhysicsDataGuid",
		"CPhysicsSettingsGuid",
		"CTextureAtlasGuid",
		"FilterDefGuid",
		"GEditableMeshGuid",
		"GTextureGuid",
		"StaticFilterDefGuid",
		"SyncAudioParamGuid",
		"SyncCubismParamGuid",
	)

/** The `*Id` tags emitted by the editor. One [Id] type, many tags. */
internal val ID_TAGS: List<String> =
	listOf(
		"CAffecterId",
		"CArtPathBrushId",
		"CDeformerId",
		"CDrawableId",
		"CMotionSyncSettingId",
		"CParameterGroupId",
		"CParameterId",
		"CPartId",
		"CPhysicsSettingId",
		"FilterInstanceId",
		"FilterValueId",
	)

/**
 * Registers the identity subsystem (all `*Guid` and `*Id` tags) on a registry.
 *
 * @param SerializerRegistry registry The registry to populate.
 */
internal fun registerIdentitySubsystem(registry: SerializerRegistry) {
	for (tag in GUID_TAGS) registry.registerCustomTag(tag, Guid::class, GuidSerializer)
	for (tag in ID_TAGS) registry.registerCustomTag(tag, Id::class, IdSerializer)
}