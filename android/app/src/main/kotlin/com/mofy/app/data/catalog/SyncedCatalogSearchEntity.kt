package com.mofy.app.data.catalog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

@Fts4
@Entity(tableName = "synced_catalog_search")
data class SyncedCatalogSearchEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Int? = null,
    val itemId: Long,   // synced_catalog_items.id
    val title: String,
    val overview: String,
)