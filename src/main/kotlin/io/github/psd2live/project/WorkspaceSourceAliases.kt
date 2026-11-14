package io.github.psd2live.project

import org.umamo.format.art.SourceLayer
import io.github.psd2live.document.transform as documentTransform
import io.github.psd2live.document.displayedRect as documentDisplayedRect
import io.github.psd2live.document.storedCanvasRect as documentStoredCanvasRect
import io.github.psd2live.document.canvasRect as documentCanvasRect

// Source compatibility while consumers migrate to the stable document contracts.
typealias LayerCanvasRect = io.github.psd2live.document.LayerCanvasRect
typealias LayerTransform = io.github.psd2live.document.LayerTransform
internal typealias WorkspaceSourceMetadata = io.github.psd2live.document.WorkspaceSourceMetadata
internal typealias WorkspaceSourceArt = io.github.psd2live.document.WorkspaceSourceArt
internal typealias WorkspaceSourceLayer = io.github.psd2live.document.WorkspaceSourceLayer

internal val SourceLayer.transform: LayerTransform get() = documentTransform
internal val SourceLayer.storedCanvasRect: LayerCanvasRect? get() = documentStoredCanvasRect
internal fun SourceLayer.canvasRect(): LayerCanvasRect = documentCanvasRect()
internal fun SourceLayer.displayedRect(): LayerCanvasRect? = documentDisplayedRect()
