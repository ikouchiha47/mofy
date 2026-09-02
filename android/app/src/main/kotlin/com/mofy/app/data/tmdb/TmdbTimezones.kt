package com.mofy.app.data.tmdb

import android.content.Context
import kotlinx.serialization.json.Json

/**
 * Bundled static asset (android/app/src/main/assets/tmdb_timezones.json),
 * not a live API call - TMDB's configuration/timezones list changes rarely,
 * so it's fetched once (manually, when preparing a release, mirroring the
 * ml/ pipeline's bundled-asset pattern for catalog.db) rather than hit at
 * Settings-open time. Update the asset file for a future release if TMDB's
 * list changes; no code changes needed to pick it up.
 */
object TmdbTimezones {
    private const val ASSET_NAME = "tmdb_timezones.json"
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cached: List<TmdbTimezoneEntry>? = null

    fun get(context: Context): List<TmdbTimezoneEntry> = cached ?: synchronized(this) {
        cached ?: context.assets.open(ASSET_NAME).use { input ->
            json.decodeFromString<List<TmdbTimezoneEntry>>(input.bufferedReader().readText())
        }.also { cached = it }
    }

    /** Flattened (zone, iso_3166_1) pairs for a single dropdown - see TmdbSettings.setZoneSelection. */
    fun flattenedZones(context: Context): List<Pair<String, String>> =
        get(context).flatMap { entry -> entry.zones.map { zone -> zone to entry.iso_3166_1 } }
}
