package org.umamo.format.clip.db.format

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlin.Long
import kotlin.Unit
import kotlin.reflect.KClass
import org.umamo.format.clip.db.ClipDatabase
import org.umamo.format.clip.db.ClipQueries

internal val KClass<ClipDatabase>.schema: SqlSchema<QueryResult.Value<Unit>>
  get() = ClipDatabaseImpl.Schema

internal fun KClass<ClipDatabase>.newInstance(driver: SqlDriver): ClipDatabase = ClipDatabaseImpl(driver)

private class ClipDatabaseImpl(
  driver: SqlDriver,
) : TransacterImpl(driver),
    ClipDatabase {
  override val clipQueries: ClipQueries = ClipQueries(driver)

  public object Schema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long
      get() = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
      driver.execute(null, """
          |CREATE TABLE Canvas (
          |	CanvasWidth REAL,        -- CLIP: Canvas.CanvasWidth  (pixels, stored as REAL)
          |	CanvasHeight REAL,       -- CLIP: Canvas.CanvasHeight (pixels, stored as REAL)
          |	CanvasRootFolder INTEGER -- CLIP: Canvas.CanvasRootFolder (MainId of the root layer folder)
          |)
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE TABLE Layer (
          |	MainId INTEGER,               -- CLIP: Layer.MainId (stable internal id; tree node key)
          |	LayerName TEXT,               -- CLIP: Layer.LayerName
          |	LayerFolder INTEGER,          -- CLIP: Layer.LayerFolder (bit 0x10 set => folder/group)
          |	LayerType INTEGER,            -- CLIP: Layer.LayerType (raster/adjustment/text/...; not yet mapped)
          |	LayerVisibility INTEGER,      -- CLIP: Layer.LayerVisibility (0/1)
          |	LayerOpacity INTEGER,         -- CLIP: Layer.LayerOpacity (0..256)
          |	LayerComposite INTEGER,       -- CLIP: Layer.LayerComposite (blend-mode code)
          |	LayerClip INTEGER,            -- CLIP: Layer.LayerClip (non-zero => clip to layer below)
          |	LayerOffsetX INTEGER,         -- CLIP: Layer.LayerOffsetX (canvas X, may be negative)
          |	LayerOffsetY INTEGER,         -- CLIP: Layer.LayerOffsetY (canvas Y, may be negative)
          |	LayerFirstChildIndex INTEGER, -- CLIP: Layer.LayerFirstChildIndex (first child MainId, 0 => leaf)
          |	LayerNextIndex INTEGER,       -- CLIP: Layer.LayerNextIndex (next sibling MainId, 0 => end)
          |	LayerUuid TEXT,                  -- CLIP: Layer.LayerUuid (rename/reorder-stable; preferred id)
          |	LayerRenderMipmap INTEGER,       -- CLIP: Layer.LayerRenderMipmap (MainId into Mipmap -> raster)
          |	LayerRenderOffscrOffsetX INTEGER, -- CLIP: Layer.LayerRenderOffscrOffsetX (grid anchor = offset + this)
          |	LayerRenderOffscrOffsetY INTEGER, -- CLIP: Layer.LayerRenderOffscrOffsetY
          |	LayerLayerMaskMipmap INTEGER,    -- CLIP: Layer.LayerLayerMaskMipmap (MainId into Mipmap -> mask)
          |	TextLayerType INTEGER            -- CLIP: Layer.TextLayerType (non-null => a text object layer)
          |)
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE TABLE Mipmap (
          |	MainId INTEGER,         -- CLIP: Mipmap.MainId (== Layer.LayerRenderMipmap)
          |	BaseMipmapInfo INTEGER  -- CLIP: Mipmap.BaseMipmapInfo (MainId into MipmapInfo, full-res level)
          |)
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE TABLE MipmapInfo (
          |	MainId INTEGER,    -- CLIP: MipmapInfo.MainId
          |	Offscreen INTEGER  -- CLIP: MipmapInfo.Offscreen (MainId into Offscreen)
          |)
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE TABLE Offscreen (
          |	MainId INTEGER,   -- CLIP: Offscreen.MainId
          |	Attribute BLOB,   -- CLIP: Offscreen.Attribute (Parameter: width/height/cols/rows; BlockSize array)
          |	BlockData BLOB    -- CLIP: Offscreen.BlockData ("extrnlid"+GUID, keys ExternalChunk)
          |)
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE TABLE ExternalChunk (
          |	ExternalID BLOB,  -- CLIP: ExternalChunk.ExternalID ("extrnlid"+GUID; matches Offscreen.BlockData)
          |	Offset INTEGER    -- CLIP: ExternalChunk.Offset (byte offset of the CHNKExta chunk in the file)
          |)
          """.trimMargin(), 0)
      return QueryResult.Unit
    }

    override fun migrate(
      driver: SqlDriver,
      oldVersion: Long,
      newVersion: Long,
      vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
  }
}
