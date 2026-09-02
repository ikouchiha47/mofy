package com.mofy.app.data.tmdb

import android.content.Context
import java.util.Locale
import java.util.TimeZone

/**
 * User-overridable TMDB request config (Settings), SharedPreferences-backed
 * so it survives restarts (unlike SignalingSettings' volatile-var pattern,
 * which is reapplied fresh from BuildConfig every launch - these need to
 * persist a user's own override across launches).
 *
 * - apiKey: override for BuildConfig.TMDB_API_KEY, blank = use the build's.
 * - timezone: IANA zone id (e.g. "Asia/Kolkata") passed to tv/airing_today
 *   - TMDB's "airing today" is timezone-relative (a show airing late night
 *     in Japan can be logged past midnight, i.e. "the next day" in UTC/other
 *     zones - see TMDB's own docs). Defaults to the device's local zone.
 */
object TmdbSettings {
    private const val PREFS = "mofy_tmdb_settings"
    private const val KEY_API_KEY_OVERRIDE = "api_key_override"
    private const val KEY_TIMEZONE_OVERRIDE = "timezone_override"
    private const val KEY_REGION_OVERRIDE = "region_override"

    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun apiKeyOverride(): String? = prefs.getString(KEY_API_KEY_OVERRIDE, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setApiKeyOverride(value: String?) {
        prefs.edit().putString(KEY_API_KEY_OVERRIDE, value?.trim()).apply()
    }

    /** IANA zone id - user override if set, else the device's current local zone. */
    fun timezone(): String = prefs.getString(KEY_TIMEZONE_OVERRIDE, null)?.trim()?.takeIf { it.isNotEmpty() }
        ?: TimeZone.getDefault().id

    fun timezoneOverride(): String? = prefs.getString(KEY_TIMEZONE_OVERRIDE, null)

    /** ISO 3166-1 region for movie feeds - user override if set, else the device's locale country. */
    fun region(): String = prefs.getString(KEY_REGION_OVERRIDE, null)?.trim()?.takeIf { it.isNotEmpty() }
        ?: Locale.getDefault().country.ifEmpty { "US" }

    /**
     * One user action, both values: Settings shows a single zone dropdown
     * (built from /configuration/timezones), not two separate zone+region
     * pickers - selecting a zone (e.g. "Asia/Pontianak") also sets region
     * to that entry's iso_3166_1 (e.g. "ID"), since a zone always implies
     * exactly one country in TMDB's own listing.
     */
    fun setZoneSelection(zone: String, isoRegion: String) {
        prefs.edit()
            .putString(KEY_TIMEZONE_OVERRIDE, zone.trim())
            .putString(KEY_REGION_OVERRIDE, isoRegion.trim())
            .apply()
    }

    fun clearZoneSelection() {
        prefs.edit().remove(KEY_TIMEZONE_OVERRIDE).remove(KEY_REGION_OVERRIDE).apply()
    }
}
