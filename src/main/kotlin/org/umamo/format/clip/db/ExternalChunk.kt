package org.umamo.format.clip.db

import kotlin.ByteArray
import kotlin.Long

public data class ExternalChunk(
  public val ExternalID: ByteArray?,
  public val Offset: Long?,
)
