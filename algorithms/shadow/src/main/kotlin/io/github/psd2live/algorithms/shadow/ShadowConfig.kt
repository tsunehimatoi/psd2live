package io.github.psd2live.algorithms.shadow

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

enum class ShadowStyle(val blurPx: Float, val defaultOpacity: Float) {
    CEL(blurPx = 1.5f, defaultOpacity = 0.28f),
    SOFT(blurPx = 8f, defaultOpacity = 0.32f),
    PAINTERLY(blurPx = 16f, defaultOpacity = 0.36f),
}

enum class ShadowColorMode { AUTO_SKIN, CUSTOM }

enum class ShadowCompositeMode { AUTO, OFFSCREEN, COMPAT_MERGED }

enum class ShadowPlacement { ABOVE_FACE, ABOVE_EYES, BELOW_CASTER }

enum class FormProxyKind { FACE_ELLIPSOID, SPHERE, CYLINDER }

@Serializable
data class ShadowWorldConfig(
    val azimuth: Float = 25f,
    val elevation: Float = 45f,
    val style: ShadowStyle = ShadowStyle.SOFT,
    val colorMode: ShadowColorMode = ShadowColorMode.AUTO_SKIN,
    val customColor: String = "#5A4658",
    val intensity: Float = 1f,
    val compositeMode: ShadowCompositeMode = ShadowCompositeMode.AUTO,
    val previewEnabled: Boolean = true,
) {
    init {
        require(azimuth.isFinite() && azimuth in -180f..180f) { "azimuth out of range: $azimuth" }
        require(elevation.isFinite() && elevation in 0f..90f) { "elevation out of range: $elevation" }
        require(intensity.isFinite() && intensity in 0f..2f) { "intensity out of range: $intensity" }
    }

    fun toJson(): JsonObject = ShadowConfigJson.codec.encodeToJsonElement(this).jsonObject

    companion object {
        const val CONTEXT_KEY = "shadow.world"
        const val SOURCES_CONTEXT_KEY = "shadow.sources"
        val Default = ShadowWorldConfig()

        fun fromJson(json: JsonObject): ShadowWorldConfig =
            if (json.isEmpty()) Default else ShadowConfigJson.codec.decodeFromJsonElement(json)
    }
}

@Serializable
data class CastShadowConfig(
    val name: String = "Cast Shadow",
    val casters: List<String> = emptyList(),
    val receivers: List<String> = emptyList(),
    val distance: Float = 18f,
    val opacityOverride: Float? = null,
    val softnessOverride: Float? = null,
    val colorOverride: String? = null,
    val rootPinning: Float = 0.65f,
    val parallax: Float = 1f,
    val placement: ShadowPlacement = ShadowPlacement.ABOVE_FACE,
    val skipCoveredCasters: Boolean = true,
) {
    init {
        require(distance.isFinite() && distance in 0f..200f) { "distance out of range: $distance" }
        require(opacityOverride == null || (opacityOverride.isFinite() && opacityOverride in 0f..1f)) { "opacity out of range" }
        require(softnessOverride == null || (softnessOverride.isFinite() && softnessOverride in 0f..48f)) { "softness out of range" }
        require(rootPinning.isFinite() && rootPinning in 0f..1f) { "rootPinning out of range" }
        require(parallax.isFinite() && parallax in 0f..3f) { "parallax out of range" }
    }

    fun toJson(): JsonObject = ShadowConfigJson.codec.encodeToJsonElement(this).jsonObject

    companion object {
        fun fromJson(json: JsonObject): CastShadowConfig =
            if (json.isEmpty()) CastShadowConfig() else ShadowConfigJson.codec.decodeFromJsonElement(json)
    }
}

@Serializable
data class FormShadowConfig(
    val name: String = "Side Shadow",
    val targets: List<String> = emptyList(),
    val proxy: FormProxyKind = FormProxyKind.FACE_ELLIPSOID,
    val coverage: Float = 0.28f,
    val opacityOverride: Float? = null,
    val softnessOverride: Float? = null,
    val colorOverride: String? = null,
    val cutterExpandPx: Float = 3f,
) {
    init {
        require(coverage.isFinite() && coverage in 0.05f..0.85f) { "coverage out of range: $coverage" }
        require(opacityOverride == null || (opacityOverride.isFinite() && opacityOverride in 0f..1f)) { "opacity out of range" }
        require(softnessOverride == null || (softnessOverride.isFinite() && softnessOverride in 0f..48f)) { "softness out of range" }
        require(cutterExpandPx.isFinite() && cutterExpandPx in 0f..16f) { "cutterExpandPx out of range" }
    }

    fun toJson(): JsonObject = ShadowConfigJson.codec.encodeToJsonElement(this).jsonObject

    companion object {
        fun fromJson(json: JsonObject): FormShadowConfig =
            if (json.isEmpty()) FormShadowConfig() else ShadowConfigJson.codec.decodeFromJsonElement(json)
    }
}

@Serializable
data class ShadowSourceLayerInfo(
    val meshId: String,
    val layerKey: String,
    val width: Int,
    val height: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val rgbaDigest: String,
)

internal object ShadowConfigJson {
    val codec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
