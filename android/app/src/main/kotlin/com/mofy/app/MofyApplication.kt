package com.mofy.app

import android.app.Application
import com.mofy.app.data.library.AppDatabase
import com.mofy.app.data.sites.SiteRepository
import com.mofy.app.data.tmdb.GenreRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MofyApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.get(this)
        val genreRepository = GenreRepository(dao = database.genreDao())
        val siteRepository = SiteRepository(dao = database.siteDao())
        applicationScope.launch { genreRepository.ensureSynced() }
        applicationScope.launch { siteRepository.ensureSeeded() }
    }
}
