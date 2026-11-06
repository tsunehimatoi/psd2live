package io.github.psd2live.core.fit

import io.github.psd2live.core.ClassifiedLayer

typealias BrowFit = io.github.psd2live.algorithms.binding.BrowFit
typealias BrowOptions = io.github.psd2live.algorithms.binding.BrowOptions
typealias BrowRig = io.github.psd2live.algorithms.binding.BrowRig

/** Capture product layer metadata before invoking the independent fitting kernel. */
internal fun fitBrow(layer: ClassifiedLayer, level: Pair<Float, Float>, faceCenterX: Float,
                     toRig: (Float, Float) -> Pair<Float, Float>): BrowFit? =
    BrowFit.of(layer.source.raster.width, layer.source.raster.height, layer.source.raster.rgba,
        layer.source.bounds.left, layer.source.bounds.top, layer.semantic.side, level, faceCenterX, toRig)
