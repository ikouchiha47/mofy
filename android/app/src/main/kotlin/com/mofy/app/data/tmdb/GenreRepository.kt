package com.mofy.app.data.tmdb

/**
 * Genre id -> name is fetched from TMDB once (app launch, see MofyApplication)
 * and cached in the `genres` table - see GenreEntity. Lookups never hit the
 * network directly; resolveNames only re-syncs when it hits an id it doesn't
 * recognize locally (e.g. TMDB added a new genre since our last fetch), and
 * only when the caller confirms the id actually came from TMDB.
 */
class GenreRepository(
    private val api: TmdbApi = TmdbClient.api,
    private val dao: GenreDao,
) {
    suspend fun ensureSynced() {
        if (dao.count() == 0) sync()
    }

    /** id -> name for all cached genres - used to build genre filter chips (see Library). */
    suspend fun getAllAsMap(): Map<Int, String> = dao.getAll().associate { it.id to it.name }

    /**
     * Resolve TMDB genre ids to names. If any id isn't found in the local
     * cache, re-syncs from TMDB once (covers newly added genres) before
     * giving up on that id.
     */
    suspend fun resolveNames(ids: List<Int>, resourceIsTmdb: Boolean = true): List<String> {
        var local = dao.getAll().associateBy { it.id }
        val missing = ids.filter { it !in local }
        if (missing.isNotEmpty() && resourceIsTmdb) {
            sync()
            local = dao.getAll().associateBy { it.id }
        }
        return ids.mapNotNull { local[it]?.name }
    }

    private suspend fun sync() {
        val movieGenres = runCatching { api.genreListMovie().genres }.getOrDefault(emptyList())
        val tvGenres = runCatching { api.genreListTv().genres }.getOrDefault(emptyList())
        val merged = (movieGenres + tvGenres)
            .distinctBy { it.id }
            .map { GenreEntity(id = it.id, name = it.name) }
        if (merged.isNotEmpty()) dao.upsertAll(merged)
    }
}
