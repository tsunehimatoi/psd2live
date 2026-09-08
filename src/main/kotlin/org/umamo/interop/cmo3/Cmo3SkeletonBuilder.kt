package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.custom.CLabelColor
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.custom.CWritableImage
import org.umamo.format.cmo3.model.gen.CAffecterSourceSet
import org.umamo.format.cmo3.model.gen.CArtPathBrushSetting
import org.umamo.format.cmo3.model.gen.CDeformerSourceSet
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CEffectParameterGroups
import org.umamo.format.cmo3.model.gen.CGameMotionSet
import org.umamo.format.cmo3.model.gen.CGuidesSetting
import org.umamo.format.cmo3.model.gen.CImageCanvas
import org.umamo.format.cmo3.model.gen.CImageIcon
import org.umamo.format.cmo3.model.gen.CLabelColorType
import org.umamo.format.cmo3.model.gen.CModelInfo
import org.umamo.format.cmo3.model.gen.CParameterGroup
import org.umamo.format.cmo3.model.gen.CParameterGroupSet
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.gen.CPartForm
import org.umamo.format.cmo3.model.gen.CPartSource
import org.umamo.format.cmo3.model.gen.CPartSourceSet
import org.umamo.format.cmo3.model.gen.CPhysicsSettingsSourceSet
import org.umamo.format.cmo3.model.gen.CPoint
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.EditorEdition
import org.umamo.format.cmo3.model.gen.KeyformGridAccessKey
import org.umamo.format.cmo3.model.gen.KeyformGridSource
import org.umamo.format.cmo3.model.gen.KeyformOnGrid
import org.umamo.format.cmo3.model.gen.ModelViewerSetting
import org.umamo.format.cmo3.model.gen.TextureImageGroup
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CColor
import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.format.cmo3.type.CHashMap
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage

/**
 * Builds the blank CModelSource spine a fresh (never-read) CMO3 starts from, mirroring what the
 * official editor writes for New -> Save As (BareMinimum.cmo3; docs/format/CMO3.md §3 The
 * Blank-Model Skeleton).  Everything content-bearing - parameters, parts, deformers, drawables,
 * glues, the image chain - is deliberately absent: the reconcile exporter creates the model's own
 * entities against this skeleton, and the image-chain builder populates the texture manager.
 *
 * Fields the modern editor always writes as explicit defaults (useOffscreen=false,
 * invertClippingMask=false, an explicit null internalColor_direct_argb) emit on fresh objects
 * too - their DontSerializeIfDefault annotations are hand-removed in the generated model, because
 * the official reader's custom deserializers reject their absence.
 *
 * ERA NOTE, unresolved: seeding from BareMinimum means a fresh graph declares fileFormatVersion
 * 504000000 (Cubism 5.4) even though a MOC3-origin conversion populates nothing 5.x and its
 * targetVersionNo says SDK 4.0.  Both files that are known to RENDER for the same model - Cubism's
 * own golden (402030000) and a third-party converter's output (401030000) - are 4.x.  The standing
 * theory for the MOC3-origin render gap (see [Cmo3Conversion]) is that the 5.4 reader composites
 * through the model-image web, so a merely shape-correct synthetic web is not enough, while a 4.x
 * file draws straight from the atlas.  Emitting an era-matched graph (4.x version PIs, dropping
 * the 5.x-only fields) would test it; it was not pursued because the art-sourcing pipeline
 * supersedes the whole synthetic path.
 */
internal object Cmo3SkeletonBuilder {
	/**
	 * The root parameter group's well-known guid - identical across corpus files, never minted.
	 * CMO3: CParameterGroup rootParameterGroup guid (constant across eras; see CMO3.md §3).
	 */
	internal const val ROOT_PARAMETER_GROUP_UUID: String = "e9fe6eff-953b-4ce2-be7c-4a7c3913686b"

	/** The synthetic root part's id string.  CMO3: CPartSource rootPart, CPartId idstr. */
	internal const val ROOT_PART_ID_STR: String = "__RootPart__"

	/** The root parameter group's id string.  CMO3: CParameterGroupId idstr. */
	internal const val ROOT_PARAMETER_GROUP_ID_STR: String = "ParamGroupRoot"

	/**
	 * The editor's root-deformer marker uuid.
	 *
	 * CMO3: CDeformerGuid uuid + note "ROOT" - the same fixed uuid in every corpus file, written
	 * as an affecter's or root-level deformer's targetDeformerGuid.
	 */
	internal const val ROOT_DEFORMER_SENTINEL_UUID: String = "71fae776-e218-4aee-873e-78e8ac0cb48a"

	/**
	 * A fresh root-deformer sentinel guid (see ROOT_DEFORMER_SENTINEL_UUID).
	 *
	 * @return Guid The sentinel CDeformerGuid.
	 */
	internal fun rootDeformerSentinel(): Guid =
		Guid("CDeformerGuid").apply {
			uuid = ROOT_DEFORMER_SENTINEL_UUID
			note = "ROOT"
		}

	/** One embedded PNG the skeleton's icons reference: the archive entry path plus its bytes. */
	internal class IconEntry(val path: String, val pngBytes: ByteArray)

	/** The built spine: the fresh root object plus the icon PNGs its CImageIcons reference. */
	internal class BlankSkeleton(val root: CModelSource, val iconEntries: List<IconEntry>)

	/**
	 * Builds the blank spine for a fresh document.
	 *
	 * @param String modelName       The model's display name (CModelSource field name).
	 * @param Int    canvasWidth     The canvas width in pixels (CImageCanvas field pixelWidth).
	 * @param Int    canvasHeight    The canvas height in pixels (CImageCanvas field pixelHeight).
	 * @param Int    targetVersionNo The persisted runtime target (CModelSource field targetVersionNo).
	 * @return BlankSkeleton The fresh root plus icon PNG entries.
	 */
	internal fun buildBlank(modelName: String, canvasWidth: Int, canvasHeight: Int, targetVersionNo: Int): BlankSkeleton {
		val rootPart = buildRootPart()
		val rootGroup = buildRootParameterGroup()
		val icons = listOf(IconEntry("image.png", blankPng(64)), IconEntry("image_0.png", blankPng(32)), IconEntry("image_1.png", blankPng(16)))
		val root =
			CModelSource().apply {
				guid = freshGuid("CModelGuid")
				name = modelName
				// CMO3: CModelSource field editorEdition (BareMinimum writes edition 11).
				editorEdition = EditorEdition().apply { edition = 11 }
				canvas =
					CImageCanvas().apply {
						// CMO3: CImageCanvas fields pixelWidth/pixelHeight + empty CColor background.
						pixelWidth = canvasWidth
						pixelHeight = canvasHeight
						background = CColor()
					}
				parameterSourceSet = CParameterSourceSet().apply { _sources = CArrayList<Any?>() }
				textureManager =
					CTextureManager().apply {
						// CMO3: CTextureManager - mandatory even when empty (BareMinimum).
						textureList = TextureImageGroup().apply { children = CArrayList<Any?>() }
						_rawImages = CArrayList<Any?>()
						_modelImageGroups = CArrayList<Any?>()
						_textureAtlases = CArrayList<Any?>()
						// CMO3: CTextureManager field isTextureInputModelImageMode - the blank 5.4 document's
						// own value.  A skeleton has no drawables, so the mode selects nothing; whoever
						// populates the image chain writes the document's real one over this.
						isTextureInputModelImageMode = true
						previewReductionRatio = 1
						artPathBrushUsingLayeredImageIds = CArrayList<Any?>()
					}
				useLegacyDrawOrder__testImpl = false
				drawableSourceSet = CDrawableSourceSet().apply { _sources = CArrayList<Any?>() }
				deformerSourceSet = CDeformerSourceSet().apply { _sources = CArrayList<Any?>() }
				affecterSourceSet = CAffecterSourceSet().apply { _sources = CArrayList<Any?>() }
				partSourceSet = CPartSourceSet().apply { _sources = CArrayList<Any?>(mutableListOf(rootPart)) }
				physicsSettingsSourceSet =
					CPhysicsSettingsSourceSet().apply {
						_sourceCubismPhysics = CArrayList<Any?>()
						// CMO3: CPhysicsSettingsSourceSet field settingFPS (BareMinimum writes 120).
						settingFPS = 120
					}
				this.rootPart = rootPart
				parameterGroupSet = CParameterGroupSet().apply { _groups = CArrayList<Any?>(mutableListOf(rootGroup)) }
				rootParameterGroup = rootGroup
				modelInfo =
					CModelInfo().apply {
						pixelsPerUnit = 1f
						originInPixels = CPoint()
						// CMO3: CEffectParameterGroups field _parameterGroups - a hash_map in every
						// corpus file (keyType="string" when empty); the editor's field is Map-typed
						// and rejects a list.
						_effectParameterGroups = CEffectParameterGroups().apply { _parameterGroups = CHashMap<Any?, Any?>() }
					}
				modelOptions = CHashMap<String, Any?>()
				_icon64 = iconOf(64, icons[0].path)
				_icon32 = iconOf(32, icons[1].path)
				_icon16 = iconOf(16, icons[2].path)
				gameMotionSet =
					CGameMotionSet().apply {
						gameMotions = CArrayList<Any?>()
						gameMotionGroups = CArrayList<Any?>()
					}
				modelViewerSetting = ModelViewerSetting().apply { trackCursorSettings = ArrayList<Any?>() }
				guides = CGuidesSetting().apply { guidesModeling = CArrayList<Any?>() }
				this.targetVersionNo = targetVersionNo
				// CMO3: CModelSource field latestVersionOfLastModelerNo (BareMinimum writes 5030000).
				latestVersionOfLastModelerNo = 5030000
				artPathBrushesSetting = CArtPathBrushSetting().apply { brushes = CArrayList<Any?>() }
				// CMO3: CModelSource fields randomPoseSetting / motionSyncSettingsSet /
				// modelStateSetSet - every 5.4 corpus file writes all three (empty when the
				// features are unused), modelStateSetSet as the root's last child; its presence
				// also drives the ModelStateSet:1 version PI.
				randomPoseSetting =
					org.umamo.format.cmo3.model.gen.CRandomPoseSettingManager().apply {
						// CMO3: CRandomPoseSettingManager field _settings - array_list, unlike the
						// carray_list sibling collections.
						_settings = ArrayList<Any?>()
					}
				motionSyncSettingsSet =
					org.umamo.format.cmo3.model.gen.CMotionSyncSettingSourceSet().apply {
						_settingSourceSetMotionSync = LinkedHashSet<Any?>()
					}
				modelStateSetSet =
					org.umamo.format.cmo3.model.gen.CModelStateSetSet().apply {
						_modelStateSets = CArrayList<Any?>()
					}
			}
		return BlankSkeleton(root, icons)
	}

	/**
	 * Builds the synthetic root part with its one-cell keyform grid (a single CPartForm at draw
	 * order 500, opacity 1), exactly the shape BareMinimum's __RootPart__ carries.
	 *
	 * @return CPartSource The fresh root part.
	 */
	private fun buildRootPart(): CPartSource {
		val part = CPartSource()
		val formGuid = freshGuid("CFormGuid")
		val form =
			CPartForm().apply {
				guid = formGuid
				_source = part
				notes = ""
				// CMO3: CPartForm fields drawOrder/opacity/multiplyColor/screenColor.
				drawOrder = 500
				opacity = 1f
				multiplyColor = identityMultiplyColor()
				screenColor = identityScreenColor()
			}
		return part.apply {
			localName = "Root Part"
			isVisible = true
			keyformGridSource =
				KeyformGridSource().apply {
					keyformsOnGrid =
						ArrayList<Any?>(
							mutableListOf(
								KeyformOnGrid().apply {
									accessKey = KeyformGridAccessKey().apply { _keyOnParameterList = ArrayList<Any?>() }
									keyformGuid = formGuid
								},
							),
						)
					keyformBindings = ArrayList<Any?>()
				}
			keyformMorphTargetSet = emptyMorphTargetSet()
			_extensions = CArrayList<Any?>()
			labelColor = undefinedLabelColor()
			guid = freshGuid("CPartGuid")
			id = Id("CPartId").apply { idstr = ROOT_PART_ID_STR }
			keyforms = CArrayList<Any?>(mutableListOf(form))
			defaultOrder_forEditor = 500
			partsEditColor = CColor()
			_childGuids = CArrayList<Any?>()
			clipGuidList = CArrayList<Any?>()
			colorComposition = org.umamo.format.cmo3.model.gen.ColorComposition.NORMAL
			alphaComposition = org.umamo.format.cmo3.model.gen.AlphaComposition.OVER
		}
	}

	/**
	 * Builds the root parameter group with its well-known constant guid.
	 *
	 * @return CParameterGroup The fresh root group.
	 */
	private fun buildRootParameterGroup(): CParameterGroup =
		CParameterGroup().apply {
			name = "Root Parameter Group"
			description = ""
			folderIsOpened = false
			guid =
				Guid("CParameterGroupGuid").apply {
					uuid = ROOT_PARAMETER_GROUP_UUID
					note = "Root Parameter Group"
				}
			_childGuids = CArrayList<Any?>()
			id = Id("CParameterGroupId").apply { idstr = ROOT_PARAMETER_GROUP_ID_STR }
			labelColor = undefinedLabelColor()
		}

	/**
	 * Mints a fresh guid of the given kind.
	 *
	 * @param String kind The typed guid tag (e.g. "CPartGuid").
	 * @return Guid The fresh guid.
	 */
	internal fun freshGuid(kind: String): Guid =
		Guid(kind).apply {
			uuid = java.util.UUID.randomUUID().toString()
			note = "(no debug info)"
		}

	/**
	 * An empty morph-target set (empty targets + empty constraint set).
	 *
	 * @return Any The fresh KeyFormMorphTargetSet.
	 */
	internal fun emptyMorphTargetSet(): Any =
		org.umamo.format.cmo3.model.gen.KeyFormMorphTargetSet().apply {
			_morphTargets = CArrayList<Any?>()
			blendWeightConstraintSet =
				org.umamo.format.cmo3.model.gen.MorphTargetBlendWeightConstraintSet().apply { _constraints = CArrayList<Any?>() }
		}

	/**
	 * The identity multiply tint (white, opaque).
	 *
	 * @return CFloatColor The fresh color.
	 */
	internal fun identityMultiplyColor(): org.umamo.format.cmo3.model.custom.CFloatColor =
		org.umamo.format.cmo3.model.custom.CFloatColor().apply {
			red = 1f
			green = 1f
			blue = 1f
			alpha = 1f
		}

	/**
	 * The identity screen tint (black, opaque).
	 *
	 * @return CFloatColor The fresh color.
	 */
	internal fun identityScreenColor(): org.umamo.format.cmo3.model.custom.CFloatColor =
		org.umamo.format.cmo3.model.custom.CFloatColor().apply { alpha = 1f }

	/**
	 * The default label color (uncustomized, UNDEFINED type).
	 *
	 * @return CLabelColor The fresh label color.
	 */
	internal fun undefinedLabelColor(): CLabelColor =
		CLabelColor().apply {
			// CMO3: CLabelColor attr customizedColorInt (-1 = none) + CLabelColorType labelType.
			customizedColorInt = -1
			labelType = CLabelColorType.UNDEFINED
		}

	/**
	 * A fully transparent square PNG for the model icons.
	 *
	 * @param Int size The square dimension in pixels.
	 * @return ByteArray The encoded PNG.
	 */
	internal fun blankPng(size: Int): ByteArray = PngCodec.write(RasterImage(size, size, ByteArray(size * size * 4)))

	/**
	 * An icon wrapper referencing an embedded PNG entry.
	 *
	 * @param Int    size The square dimension in pixels.
	 * @param String path The archive entry path the icon references.
	 * @return CImageIcon The fresh icon.
	 */
	private fun iconOf(size: Int, path: String): CImageIcon =
		CImageIcon().apply {
			image =
				CWritableImage().apply {
					width = size
					height = size
					// CMO3: CWritableImage attrs width/height/type + file child (BareMinimum icons).
					type = "INT_ARGB"
					image = FileRef().apply { archivePath = path }
				}
		}
}