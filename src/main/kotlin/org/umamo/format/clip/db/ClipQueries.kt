package org.umamo.format.clip.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.SqlDriver
import kotlin.Any
import kotlin.ByteArray
import kotlin.Double
import kotlin.Long
import kotlin.String

public class ClipQueries(
  driver: SqlDriver,
) : TransacterImpl(driver) {
  public fun <T : Any> selectCanvas(mapper: (
    CanvasWidth: Double?,
    CanvasHeight: Double?,
    CanvasRootFolder: Long?,
  ) -> T): Query<T> = Query(-1_014_999_238, arrayOf("Canvas"), driver, "Clip.sq", "selectCanvas", """
  |SELECT CanvasWidth, CanvasHeight, CanvasRootFolder
  |FROM Canvas
  |LIMIT 1
  """.trimMargin()) { cursor ->
    mapper(
      cursor.getDouble(0),
      cursor.getDouble(1),
      cursor.getLong(2)
    )
  }

  public fun selectCanvas(): Query<Canvas> = selectCanvas(::Canvas)

  public fun <T : Any> selectAllLayers(mapper: (
    MainId: Long?,
    LayerName: String?,
    LayerFolder: Long?,
    LayerType: Long?,
    LayerVisibility: Long?,
    LayerOpacity: Long?,
    LayerComposite: Long?,
    LayerClip: Long?,
    LayerOffsetX: Long?,
    LayerOffsetY: Long?,
    LayerFirstChildIndex: Long?,
    LayerNextIndex: Long?,
    LayerUuid: String?,
    LayerRenderOffscrOffsetX: Long?,
    LayerRenderOffscrOffsetY: Long?,
  ) -> T): Query<T> = Query(1_356_265_761, arrayOf("Layer"), driver, "Clip.sq", "selectAllLayers", """
  |SELECT MainId, LayerName, LayerFolder, LayerType, LayerVisibility, LayerOpacity, LayerComposite,
  |	LayerClip, LayerOffsetX, LayerOffsetY, LayerFirstChildIndex, LayerNextIndex, LayerUuid,
  |	LayerRenderOffscrOffsetX, LayerRenderOffscrOffsetY
  |FROM Layer
  """.trimMargin()) { cursor ->
    mapper(
      cursor.getLong(0),
      cursor.getString(1),
      cursor.getLong(2),
      cursor.getLong(3),
      cursor.getLong(4),
      cursor.getLong(5),
      cursor.getLong(6),
      cursor.getLong(7),
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getLong(10),
      cursor.getLong(11),
      cursor.getString(12),
      cursor.getLong(13),
      cursor.getLong(14)
    )
  }

  public fun selectAllLayers(): Query<SelectAllLayers> = selectAllLayers(::SelectAllLayers)

  public fun <T : Any> selectTextLayerIds(mapper: (MainId: Long?) -> T): Query<T> = Query(756_455_510, arrayOf("Layer"), driver, "Clip.sq", "selectTextLayerIds", """
  |SELECT MainId
  |FROM Layer
  |WHERE TextLayerType IS NOT NULL
  """.trimMargin()) { cursor ->
    mapper(
      cursor.getLong(0)
    )
  }

  public fun selectTextLayerIds(): Query<SelectTextLayerIds> = selectTextLayerIds(::SelectTextLayerIds)

  public fun <T : Any> selectExternalChunks(mapper: (ExternalID: ByteArray?, Offset: Long?) -> T): Query<T> = Query(309_860_147, arrayOf("ExternalChunk"), driver, "Clip.sq", "selectExternalChunks", """
  |SELECT ExternalID, Offset
  |FROM ExternalChunk
  """.trimMargin()) { cursor ->
    mapper(
      cursor.getBytes(0),
      cursor.getLong(1)
    )
  }

  public fun selectExternalChunks(): Query<ExternalChunk> = selectExternalChunks(::ExternalChunk)

  public fun <T : Any> selectLayerRasters(mapper: (
    MainId: Long?,
    Attribute: ByteArray?,
    BlockData: ByteArray,
  ) -> T): Query<T> = Query(167_710_823, arrayOf("Layer", "Offscreen", "Mipmap", "MipmapInfo"), driver, "Clip.sq", "selectLayerRasters", """
  |SELECT L.MainId, O.Attribute, O.BlockData
  |FROM Layer L
  |JOIN Mipmap M ON M.MainId = L.LayerRenderMipmap
  |JOIN MipmapInfo MI ON MI.MainId = M.BaseMipmapInfo
  |JOIN Offscreen O ON O.MainId = MI.Offscreen
  |WHERE L.LayerRenderMipmap IS NOT NULL AND O.BlockData IS NOT NULL
  """.trimMargin()) { cursor ->
    mapper(
      cursor.getLong(0),
      cursor.getBytes(1),
      cursor.getBytes(2)!!
    )
  }

  public fun selectLayerRasters(): Query<SelectLayerRasters> = selectLayerRasters(::SelectLayerRasters)

  public fun <T : Any> selectLayerMaskRasters(mapper: (
    MainId: Long?,
    Attribute: ByteArray?,
    BlockData: ByteArray,
  ) -> T): Query<T> = Query(1_079_897_947, arrayOf("Layer", "Offscreen", "Mipmap", "MipmapInfo"), driver, "Clip.sq", "selectLayerMaskRasters", """
  |SELECT L.MainId, O.Attribute, O.BlockData
  |FROM Layer L
  |JOIN Mipmap M ON M.MainId = L.LayerLayerMaskMipmap
  |JOIN MipmapInfo MI ON MI.MainId = M.BaseMipmapInfo
  |JOIN Offscreen O ON O.MainId = MI.Offscreen
  |WHERE L.LayerLayerMaskMipmap IS NOT NULL AND L.LayerLayerMaskMipmap <> 0 AND O.BlockData IS NOT NULL
  """.trimMargin()) { cursor ->
    mapper(
      cursor.getLong(0),
      cursor.getBytes(1),
      cursor.getBytes(2)!!
    )
  }

  public fun selectLayerMaskRasters(): Query<SelectLayerMaskRasters> = selectLayerMaskRasters(::SelectLayerMaskRasters)
}
