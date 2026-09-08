package org.umamo.format.clip.db

import kotlin.ByteArray
import kotlin.Long

public data class SelectLayerMaskRasters(
  public val MainId: Long?,
  public val Attribute: ByteArray?,
  public val BlockData: ByteArray,
)
