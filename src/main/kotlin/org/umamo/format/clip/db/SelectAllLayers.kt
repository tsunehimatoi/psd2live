package org.umamo.format.clip.db

import kotlin.Long
import kotlin.String

public data class SelectAllLayers(
  public val MainId: Long?,
  public val LayerName: String?,
  public val LayerFolder: Long?,
  public val LayerType: Long?,
  public val LayerVisibility: Long?,
  public val LayerOpacity: Long?,
  public val LayerComposite: Long?,
  public val LayerClip: Long?,
  public val LayerOffsetX: Long?,
  public val LayerOffsetY: Long?,
  public val LayerFirstChildIndex: Long?,
  public val LayerNextIndex: Long?,
  public val LayerUuid: String?,
  public val LayerRenderOffscrOffsetX: Long?,
  public val LayerRenderOffscrOffsetY: Long?,
)
