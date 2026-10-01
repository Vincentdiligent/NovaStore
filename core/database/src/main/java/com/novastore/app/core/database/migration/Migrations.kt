package com.novastore.app.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 1 → 2: adds an index on the download queue state column,
 * which the active queue queries filter on.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_state ON downloads(state)")
    }
}

/**
 * Migration 3 → 4: Nova Resolver v8 — update confidence levels (EXACT vs
 * DISCOVERY), the Play Web Watch freshness table and the package trust
 * cache.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE updates ADD COLUMN confidence TEXT NOT NULL DEFAULT 'EXACT'")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS play_freshness (" +
                "packageName TEXT NOT NULL PRIMARY KEY, " +
                "playUpdatedMillis INTEGER, " +
                "checkedAt INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS package_trust (" +
                "packageName TEXT NOT NULL PRIMARY KEY, " +
                "certSha256 TEXT, " +
                "source TEXT, " +
                "firstSeenAt INTEGER NOT NULL, " +
                "lastSeenAt INTEGER NOT NULL, " +
                "installs INTEGER NOT NULL DEFAULT 0)",
        )
    }
}

val ALL_MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2, MIGRATION_3_4)
