package com.mofy.app.data.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A file (or folder-derived movie+subtitle set) linked to a library item -
 * see ADR 0004 (Link/Play) and ADR 0005 (multi-version support). Several
 * linked versions can coexist per item (e.g. a 1080p and a 4K rip); exactly
 * one is `isActive` at a time and that's what Play reads. No FK to
 * library_items - see ADR 0005's "no cascade deletes, ever" decision;
 * LibraryDao.deleteLibraryItem cleans these up explicitly instead.
 */
@Entity(
    tableName = "library_links",
    indices = [Index(value = ["libraryItemKey"])],
)
data class LibraryLink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val libraryItemKey: String,
    val label: String?,
    val movieUri: String,
    val subtitleUri: String?,
    val subtitle2Uri: String?,
    val isActive: Boolean,
    val linkedAtEpochMillis: Long,
)
