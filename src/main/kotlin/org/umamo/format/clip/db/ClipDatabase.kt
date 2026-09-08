package org.umamo.format.clip.db

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlin.Unit
import org.umamo.format.clip.db.format.newInstance
import org.umamo.format.clip.db.format.schema

public interface ClipDatabase : Transacter {
  public val clipQueries: ClipQueries

  public companion object {
    public val Schema: SqlSchema<QueryResult.Value<Unit>>
      get() = ClipDatabase::class.schema

    public operator fun invoke(driver: SqlDriver): ClipDatabase = ClipDatabase::class.newInstance(driver)
  }
}
