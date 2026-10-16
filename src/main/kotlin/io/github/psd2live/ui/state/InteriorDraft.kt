package io.github.psd2live.ui.state

import androidx.compose.runtime.*
import io.github.psd2live.core.interior.*

/** Per-layer unsaved authoring state survives selection changes and rejected generation. */
internal class InteriorDraft(effect: NativeInteriorEffect?) {
    private var recipe=effect
    var dirty by mutableStateOf(false)
    var version by mutableStateOf(0L)
    private var shapeValue by mutableStateOf(effect?.settings?:InteriorSurfaceSettings())
    private var motionValue by mutableStateOf(effect?.motion?:InteriorMotionSettings())
    private var guideValue by mutableStateOf(effect?.guides.orEmpty())
    private var sourceValue by mutableStateOf(effect?.sources?.map { it.id.raw }?:listOf("",""))
    var shape: InteriorSurfaceSettings
        get()=shapeValue
        set(value) { shapeValue=value;changed() }
    var motion: InteriorMotionSettings
        get()=motionValue
        set(value) { motionValue=value;changed() }
    var guides: List<InteriorGuide>
        get()=guideValue
        set(value) { guideValue=value;changed() }
    var sources: List<String>
        get()=sourceValue
        set(value) { sourceValue=value;changed() }
    private fun changed() { dirty=true;version++ }
    fun sync(effect: NativeInteriorEffect?) {
        if(dirty || recipe===effect)return
        recipe=effect;shapeValue=effect?.settings?:InteriorSurfaceSettings();motionValue=effect?.motion?:InteriorMotionSettings()
        guideValue=effect?.guides.orEmpty();sourceValue=effect?.sources?.map { it.id.raw }?:listOf("","")
    }
}
