package com.mofy.app.data.tmdb

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * TMDB's genre id -> name, persisted locally after first fetch so lookups
 * never hit the network. Movie and TV lists are merged (ids that appear in
 * both mean the same thing in both, verified against TMDB's published lists),
 * see GenreRepository.
 */
@Entity(tableName = "genres")
data class GenreEntity(
    @PrimaryKey val id: Int,
    val name: String,
)
