package org.umamo.format.clip

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.umamo.format.clip.db.ClipDatabase
import java.nio.file.Files

/**
 * Opens a SQLDelight driver over the extracted .clip SQLite [databaseBytes], runs [block] against the
 * resulting [ClipDatabase], and tears the driver (and any temp file) down afterward.
 *
 * @param ByteArray databaseBytes The raw "SQLite format 3" bytes.
 * @param Function1 block         Runs against the open database.
 * @return T The block's result.
 */
internal fun <T> useClipDatabase(databaseBytes: ByteArray, block: (ClipDatabase) -> T): T {
	val tempDatabasePath = Files.createTempFile("umamo-clip", ".sqlite3")
	try {
		Files.write(tempDatabasePath, databaseBytes)
		val driver = JdbcSqliteDriver("jdbc:sqlite:$tempDatabasePath")
		try {
			return block(ClipDatabase(driver))
		} finally {
			driver.close()
		}
	} finally {
		Files.deleteIfExists(tempDatabasePath)
	}
}