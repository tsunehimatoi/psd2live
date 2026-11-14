package io.github.psd2live.project

// Compatibility names; storage codecs depend on document values, never the reverse.
typealias TextureFootprint = io.github.psd2live.document.TextureFootprint
typealias ArrangedTile = io.github.psd2live.document.ArrangedTile
typealias AtlasArrangement = io.github.psd2live.document.AtlasArrangement
typealias TexturePin = io.github.psd2live.document.TexturePin
typealias TextureOverride = io.github.psd2live.document.TextureOverride

fun normalizedRotation(degrees: Float): Float = io.github.psd2live.document.normalizedRotation(degrees)
