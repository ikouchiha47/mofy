package com.mofy.app.data.library

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * Standalone FTS table (not Room's `contentEntity` linkage) - Room's
 * automatic content-table sync requires the source entity's primary key to
 * be Long/Int, but library_items.id is a synthetic UUID string (see ADR
 * 0005). Kept in sync manually instead: LibraryDao's write paths delete +
 * re-insert the matching row whenever a library_items row changes. Room
 * only ships @Fts3/@Fts4 (no @Fts5 annotation exists), so this generates
 * an FTS4 virtual table - functionally sufficient for plain keyword
 * search. See ADR 0002 - this is the keyword-search half of that plan;
 * the embedding/vector half is deliberately not built yet.
 */
@Fts4
@Entity(tableName = "library_search")
data class LibrarySearchEntity(
    @PrimaryKey @androidx.room.ColumnInfo(name = "rowid") val rowid: Int? = null,
    val itemId: String,
    val title: String,
    val overview: String,
    // Alternate/romanized names must be searchable too (e.g. "huo"/"zhe"
    // for 火遮眼/"Huo Zhe Yan") - stored "" rather than left out when null,
    // since FTS4 columns can't be NULL.
    val originalTitle: String,
    val romanizedOriginalTitle: String,
)
