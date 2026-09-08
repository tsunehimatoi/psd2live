package org.umamo.format.cmo3.model.custom

import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.cmo3.serialize.annotations.DontSerializeIfDefault
import org.umamo.format.cmo3.serialize.annotations.SerialAttribute
import org.umamo.format.cmo3.serialize.annotations.SerialName
import org.umamo.format.cmo3.serialize.annotations.SerialTag

/*
 * Value/leaf classes whose scalar fields are written as attributes, modelled as reflective classes with
 * @SerialAttribute for the fields the editor writes as attributes. Field order mirrors each class's
 * serialize() method (verified by the byte-identity gate). Object fields are Any? - they reference
 * already-typed or still-verbatim objects and round-trip by identity regardless.
 */

/** A float RGBA color: `red`/`green`/`blue`/`alpha` float attributes. */
@SerialTag("CFloatColor")
public class CFloatColor {
	@SerialAttribute
	public var red: Float = 0f

	@SerialAttribute
	public var green: Float = 0f

	@SerialAttribute
	public var blue: Float = 0f

	@SerialAttribute
	public var alpha: Float = 0f
}

/** A label color: `customizedColorInt` attribute + a `CLabelColorType` enum child. */
@SerialTag("CLabelColor")
public class CLabelColor {
	@SerialAttribute
	public var customizedColorInt: Int = 0
	public var labelType: Any? = null
}

/*
 * Deformer original-shape snapshots.  I believe that that official Cubism Editor's Java subclasses shadow
 * the same field names declared on ACDeformerOriginalShape, re-emitting them at this level too;
 * they are modelled as distinct Kotlin properties carrying the on-disk name via @SerialName so that
 * both levels round-trip independently.
 */

/** A warp-deformer original shape, extending the generated ACDeformerOriginalShape. */
@SerialTag("WarpDeformerOriginalShape")
public class WarpDeformerOriginalShape : org.umamo.format.cmo3.model.gen.ACDeformerOriginalShape() {
	@SerialName("lastUpdatedTimeString")
	public var ownLastUpdatedTimeString: String? = null

	@SerialName("autoRefreshIsAvailable")
	public var ownAutoRefreshIsAvailable: Boolean = false

	@SerialName("creationMethod")
	public var ownCreationMethod: Any? = null

	@SerialName("col")
	public var ownCol: Int = 0

	@SerialName("row")
	public var ownRow: Int = 0
}

/** A rotation-deformer original shape, extending the generated ACDeformerOriginalShape. */
@SerialTag("RotationDeformerOriginalShape")
public class RotationDeformerOriginalShape : org.umamo.format.cmo3.model.gen.ACDeformerOriginalShape() {
	@SerialName("lastUpdatedTimeString")
	public var ownLastUpdatedTimeString: String? = null

	@SerialName("autoRefreshIsAvailable")
	public var ownAutoRefreshIsAvailable: Boolean = false

	@SerialName("creationMethod")
	public var ownCreationMethod: Any? = null

	@SerialName("col")
	public var ownCol: Int = 0

	@SerialName("row")
	public var ownRow: Int = 0
}

/** A width/height size value: `width`, `height` attributes. */
@SerialTag("CSize")
public class CSize {
	@SerialAttribute
	public var width: Int = 0

	@SerialAttribute
	public var height: Int = 0
}

/**
 * A rotation-deformer keyform: 4 float + 2 bool attributes,
 * extending ACDeformerForm (the engine nests its `<super>` automatically).
 */
@SerialTag("CRotationDeformerForm")
public class CRotationDeformerForm : org.umamo.format.cmo3.model.gen.ACDeformerForm() {
	@SerialAttribute
	public var angle: Float = 0f

	@SerialAttribute
	public var originX: Float = 0f

	@SerialAttribute
	public var originY: Float = 0f

	@SerialAttribute
	public var scale: Float = 0f

	@SerialAttribute
	public var isReflectX: Boolean = false

	@SerialAttribute
	public var isReflectY: Boolean = false
}

/** A writable image: `width`/`height`/`type` attributes + an embedded image file. */
@SerialTag("CWritableImage")
public class CWritableImage {
	@SerialAttribute
	public var width: Int = 0

	@SerialAttribute
	public var height: Int = 0

	@SerialAttribute
	public var type: String = ""
	public var image: FileRef? = null
}

/** A layer pixel resource: attributes + imageFileBuf/preview file children. */
@SerialTag("CImageResource")
public class CImageResource {
	@SerialAttribute
	public var width: Int = 0

	@SerialAttribute
	public var height: Int = 0

	@SerialAttribute
	public var type: String = ""
	public var imageFileBuf: FileRef? = null

	@SerialAttribute
	public var imageFileBuf_size: Int = 0

	@DontSerializeIfDefault
	public var preview: FileRef? = null

	@SerialAttribute
	public var previewFileBuf_size: Int = 0
}

/** A filter instance: `filterName` attribute + object children. */
@SerialTag("FilterInstance")
public class FilterInstance {
	@SerialAttribute
	public var filterName: String = ""
	public var filterDefGuid: Any? = null
	public var filterDef: Any? = null
	public var filterId: Any? = null
	public var inputConnectors: Any? = null
	public var outputConnectors: Any? = null
	public var ownerFilterSet: Any? = null
}

/** A model image: `modelImageVersion` attribute + object children. */
@SerialTag("CModelImage")
public class CModelImage {
	@SerialAttribute
	public var modelImageVersion: Int = 0
	public var guid: Any? = null
	public var name: Any? = null
	public var inputFilter: Any? = null
	public var inputFilterEnv: Any? = null
	public var _filteredImage: Any? = null
	public var icon16: Any? = null
	public var _materialLocalToCanvasTransform: Any? = null
	public var _group: Any? = null
	public var linkedRawImageGuids: Any? = null
	public var cachedImageManager: Any? = null
	public var memo: Any? = null
}

/** An editable mesh: `nextPointUid`/`useDelaunayTriangulation` attributes + object children. */
@SerialTag("GEditableMesh2")
public class GEditableMesh2 {
	@SerialAttribute
	public var nextPointUid: Int = 0

	@SerialAttribute
	public var useDelaunayTriangulation: Boolean = false
	public var point: Any? = null
	public var pointPriority: Any? = null
	public var edge: Any? = null
	public var edgePriority: Any? = null
	public var pointUid: Any? = null
	public var meshGuid: Any? = null
	public var coordType: Any? = null
}

/**
 * An image layer. Extends the generated ACImageLayer (whose
 * ancestor ACLayerEntry carries the IOption `_optionOfIOption`); we override it here so the same value
 * is written both in `<super>` and directly, matching the editor.
 */
@SerialTag("CLayer")
public class CLayer : org.umamo.format.cmo3.model.gen.ACImageLayer() {
	public var imageResource: Any? = null
	public var boundsOnImageDoc: Any? = null
	public var layerIdentifier: Any? = null
	public var icon16: Any? = null
	public var icon64: Any? = null
	public var layerInfo: Any? = null
	override var _optionOfIOption: Any? = null
}

/** The model root: `isDefaultKeyformLocked` attribute + object children. */
@SerialTag("CModelSource")
public class CModelSource {
	public var guid: Any? = null
	public var name: Any? = null
	public var editorEdition: Any? = null
	public var canvas: Any? = null
	public var parameterSourceSet: Any? = null
	public var textureManager: Any? = null
	public var useLegacyDrawOrder__testImpl: Any? = null
	public var drawableSourceSet: Any? = null
	public var deformerSourceSet: Any? = null
	public var affecterSourceSet: Any? = null
	public var partSourceSet: Any? = null
	public var physicsSettingsSourceSet: Any? = null
	public var rootPart: Any? = null
	public var parameterGroupSet: Any? = null
	public var rootParameterGroup: Any? = null
	public var modelInfo: Any? = null
	public var modelOptions: Any? = null
	public var _icon64: Any? = null
	public var _icon32: Any? = null
	public var _icon16: Any? = null
	public var gameMotionSet: Any? = null

	@SerialAttribute
	public var isDefaultKeyformLocked: Boolean = false
	public var modelViewerSetting: Any? = null
	public var guides: Any? = null
	public var targetVersionNo: Any? = null
	public var latestVersionOfLastModelerNo: Any? = null
	public var artPathBrushesSetting: Any? = null

	// Newer fields absent in older files: omit when null so pre-existing models round-trip.
	@DontSerializeIfDefault
	public var randomPoseSetting: Any? = null

	@DontSerializeIfDefault
	public var motionSyncSettingsSet: Any? = null

	// CMO3: CModelSource field modelStateSetSet - the 5.4 tail (BareMinimum writes an empty
	// CModelStateSetSet as the root's last child).
	@DontSerializeIfDefault
	public var modelStateSetSet: Any? = null
}