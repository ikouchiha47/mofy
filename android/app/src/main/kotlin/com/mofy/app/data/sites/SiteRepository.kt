package com.mofy.app.data.sites

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SiteRepository(private val dao: SiteDao) {

    suspend fun ensureSeeded() {
        if (dao.count() == 0) {
            SiteCatalog.defaultSites.forEach { dao.upsert(it.toEntity()) }
        }
    }

    fun observeAll(): Flow<List<TorrentSite>> =
        dao.observeAll().map { entities -> entities.map { it.toTorrentSite() } }

    suspend fun getByName(name: String): TorrentSite? = dao.getByName(name)?.toTorrentSite()

    suspend fun upsert(site: TorrentSite) = dao.upsert(site.toEntity())

    suspend fun delete(name: String) = dao.delete(name)
}
