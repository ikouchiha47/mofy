package com.mofy.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mofy.app.data.catalog.toLibraryItem
import com.mofy.app.data.library.AppDatabase
import com.mofy.app.data.library.LibraryDownload
import com.mofy.app.data.library.LibraryLink
import com.mofy.app.data.library.LibrarySource
import com.mofy.app.data.library.ResourceType
import com.mofy.app.data.library.downloadDedupeKey
import com.mofy.app.data.library.magnetInfoHash
import com.mofy.app.data.library.toLibraryItem
import com.mofy.app.data.sites.SiteRepository
import com.mofy.app.data.sites.TorrentSite
import com.mofy.app.ui.browse.BrowseScreen
import com.mofy.app.ui.browse.BrowseSessionViewModel
import com.mofy.app.ui.browse.TorrentWebViewScreen
import com.mofy.app.ui.confirm.ConfirmMatchScreen
import com.mofy.app.ui.detail.DetailScreen
import com.mofy.app.ui.home.HomeScreen
import kotlinx.coroutines.launch
import com.mofy.app.ui.library.LibraryScreen
import com.mofy.app.ui.nav.PlaceholderScreen
import com.mofy.app.ui.nav.PushedRoute
import com.mofy.app.ui.nav.TopLevelDestination
import com.mofy.app.ui.settings.SettingsScreen
import com.mofy.app.ui.sites.EditSiteScreen
import com.mofy.app.ui.theme.MofyTheme
import com.mofy.app.playback.FakePlayerController
import com.mofy.app.ui.watchtogether.CreateRoomScreen
import com.mofy.app.ui.watchtogether.GuestLobbyScreen
import com.mofy.app.ui.watchtogether.JoinSessionSheet
import com.mofy.app.ui.watchtogether.LiveSessionBar
import com.mofy.app.ui.watchtogether.PlayerScreen
import com.mofy.app.ui.watchtogether.QrScanScreen
import com.mofy.app.ui.watchtogether.WatchTogetherSessionViewModel
import com.mofy.app.ui.watchtogether.shareWatchTogetherInvite
import com.mofy.app.watchtogether.ItemHash
import com.mofy.app.watchtogether.Role
import com.mofy.app.watchtogether.WatchTogetherSession
import com.mofy.app.watchtogether.signaling.SignalingSettings

class MainActivity : ComponentActivity() {
    // mofy://wt/{roomKey} - RoomCode.toDeepLink / QR payload. Held here
    // (not just read once) since onNewIntent fires for taps while the app
    // is already running (singleTask), separately from cold-start onCreate.
    private var pendingDeepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        pendingDeepLink = intent?.data?.toString()
        // App is forced dark regardless of system theme (see ADR 0003), so
        // status/nav bar icons must always be light, not auto-detected.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MofyTheme {
                MofyApp(
                    pendingDeepLink = pendingDeepLink,
                    onDeepLinkConsumed = { pendingDeepLink = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink = intent.data?.toString()
    }
}

private const val ROUTE_WEBVIEW = "webview/{siteName}"
private const val ROUTE_CONFIRM_MATCH = "confirm_match"
private const val ROUTE_IMPORT_CONFIRM = "import_confirm/{title}/{uri}"
private const val ROUTE_DETAIL = "detail/{id}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MofyApp(
    pendingDeepLink: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    // remember is required here - MofyApp recomposes on every navigation
    // (currentRoute changes), and without it this would silently reset the
    // selected category/extracted title on every screen transition.
    val browseSessionViewModel = remember { BrowseSessionViewModel() }
    val watchTogetherViewModel = remember { WatchTogetherSessionViewModel() }
    var deepLinkedRoomKey by remember { mutableStateOf<String?>(null) }
    var deepLinkedSignalingUrl by remember { mutableStateOf<String?>(null) }
    var showJoinSheet by remember { mutableStateOf(false) }
    var joinPickedItem by remember { mutableStateOf<com.mofy.app.data.library.LibraryItem?>(null) }

    androidx.compose.runtime.LaunchedEffect(pendingDeepLink) {
        val parsed = pendingDeepLink?.let { com.mofy.app.watchtogether.RoomCode.parseDeepLink(it) }
        if (parsed != null) {
            deepLinkedRoomKey = parsed.roomKey
            deepLinkedSignalingUrl = parsed.signalingUrl
            showJoinSheet = true
        }
        onDeepLinkConsumed()
    }

    val context = LocalContext.current
    val database = remember { AppDatabase.get(context) }
    val genreRepository = remember { com.mofy.app.data.tmdb.GenreRepository(dao = database.genreDao()) }
    val siteRepository = remember { SiteRepository(dao = database.siteDao()) }
    // CatalogDatabase.get() copies a 39MB asset out on first run - real file
    // I/O, so it can't run inline on the composition/main thread.
    val onDeviceEmbedder = remember { com.mofy.app.search.OnDeviceEmbedder(context) }
    val modelFacetDecoder = remember { com.mofy.app.search.ModelBasedFacetDecoder(context) }

    var catalogRepository by remember { androidx.compose.runtime.mutableStateOf<com.mofy.app.data.catalog.CatalogRepository?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val db = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.mofy.app.data.catalog.CatalogDatabase.get(context)
        }
        catalogRepository = com.mofy.app.data.catalog.CatalogRepository(db, database.libraryDao(), database.catalogPosterCacheDao())
        // fp16 ONNX model (~127MB) — launched as a child so catalog init
        // doesn't block. DiscoverScreen falls back to RuleBasedFacetDecoder until isReady().
        launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = modelFacetDecoder.init()
            if (ok) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Smart search ready", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        launch(kotlinx.coroutines.Dispatchers.IO) {
            onDeviceEmbedder.init()
        }
    }
    val coroutineScope = rememberCoroutineScope()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = TopLevelDestination.entries.any { it.route == currentRoute }

    // A single topBar slot for the whole app, keyed off the current route.
    // Screens never render their own TopAppBar - doing that inside a screen
    // AND relying on Scaffold's contentPadding double-counts the status bar
    // inset (that was the padding bug). One slot, one inset calculation.
    Scaffold(
        topBar = {
            when (currentRoute) {
                TopLevelDestination.HOME.route -> TopAppBar(
                    title = { Text("Mofy") },
                    actions = {
                        IconButton(onClick = { showJoinSheet = true }) {
                            Icon(Icons.Filled.Groups, contentDescription = "Join a Watch Together session")
                        }
                        IconButton(onClick = { navController.navigate(PushedRoute.SEARCH) }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                    },
                )
                TopLevelDestination.BROWSE.route -> TopAppBar(title = { Text("Browse") })
                ROUTE_WEBVIEW -> TopAppBar(
                    title = { Text(backStackEntry?.arguments?.getString("siteName") ?: "") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                PushedRoute.EDIT_SITE -> TopAppBar(
                    title = { Text("Edit Site") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                PushedRoute.EDIT_SITE_NEW -> TopAppBar(
                    title = { Text("Add Site") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                PushedRoute.LINK -> TopAppBar(
                    title = { Text("Link") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                PushedRoute.IMPORT_LINK -> TopAppBar(
                    title = { Text("Import") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                PushedRoute.MANUAL_ENTRY_FORM -> TopAppBar(
                    title = { Text("Add manually") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                PushedRoute.SEARCH -> TopAppBar(
                    title = { Text("Search") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                PushedRoute.DISCOVER -> TopAppBar(
                    title = { Text("Discover") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                TopLevelDestination.LIBRARY.route -> TopAppBar(title = { Text("Library") })
                TopLevelDestination.SETTINGS.route -> TopAppBar(title = { Text("Settings") })
                ROUTE_DETAIL -> TopAppBar(
                    title = { Text("Details") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                ROUTE_IMPORT_CONFIRM -> TopAppBar(
                    title = { Text("Import") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                ROUTE_CONFIRM_MATCH -> TopAppBar(
                    title = {
                        Column {
                            Text("Confirm Match")
                            Text(
                                "Magnet link captured",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (isTopLevel) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        val watchTogetherSession by watchTogetherViewModel.session.collectAsState()
        Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.HOME.route,
        ) {
            composable(TopLevelDestination.HOME.route) {
                HomeScreen(
                    contentPadding = contentPadding,
                    libraryDao = database.libraryDao(),
                    watchProgressDao = database.watchProgressDao(),
                    catalogRepository = catalogRepository,
                    onItemClick = { item -> navController.navigate("detail/${item.id}") },
                    onCatalogItemClick = { item ->
                        val mediaType = if (item.titleType == "tvSeries") "TV" else "MOVIE"
                        navController.navigate(PushedRoute.resolveMatch(item.title, mediaType))
                    },
                    onContinueWatching = { wp ->
                        navController.navigate("detail/${wp.libraryItemId}")
                    },
                )
            }
            composable(TopLevelDestination.BROWSE.route) {
                BrowseScreen(
                    contentPadding = contentPadding,
                    sessionViewModel = browseSessionViewModel,
                    siteRepository = siteRepository,
                    onSitePicked = { site: TorrentSite ->
                        navController.navigate("webview/${site.name}")
                    },
                    onEditSite = { site: TorrentSite? ->
                        val route = site?.let { PushedRoute.editSite(it.name) } ?: PushedRoute.EDIT_SITE_NEW
                        navController.navigate(route)
                    },
                    onDiscoverClick = { navController.navigate(PushedRoute.DISCOVER) },
                )
            }
            composable(PushedRoute.DISCOVER) {
                com.mofy.app.ui.discover.DiscoverScreen(
                    contentPadding = contentPadding,
                    catalogRepository = catalogRepository,
                    embedder = onDeviceEmbedder,
                    facetDecoder = modelFacetDecoder,
                    onAdd = { catalogItem ->
                        // Catalog items are IMDb-only, never have a tmdbId - always
                        // resolve via text search + user confirmation (radio-select,
                        // not a blind first-result guess), same screen Detail's Sync
                        // info falls back to. See RESOLVE_MATCH.
                        val mediaType = if (catalogItem.titleType == "tvSeries") "TV" else "MOVIE"
                        navController.navigate(PushedRoute.resolveMatch(catalogItem.title, mediaType))
                    },
                )
            }
            composable(PushedRoute.RESOLVE_MATCH) { backStack ->
                val title = java.net.URLDecoder.decode(backStack.arguments?.getString("title") ?: "", "UTF-8")
                val mediaTypeArg = backStack.arguments?.getString("mediaType") ?: "MOVIE"
                val existingItemId = backStack.arguments?.getString("existingItemId")?.takeIf { it != "none" }
                ConfirmMatchScreen(
                    contentPadding = contentPadding,
                    extractedTitle = title,
                    mediaType = com.mofy.app.data.tmdb.MediaType.valueOf(mediaTypeArg),
                    showDownloadAction = false,
                    allowMultiSelect = false,
                    onConfirm = {},
                    onSaveToLibrary = { results ->
                        val matched = results.firstOrNull()
                        if (matched != null) {
                            coroutineScope.launch {
                                val imdbId = runCatching {
                                    val mediaType = com.mofy.app.data.tmdb.MediaType.valueOf(mediaTypeArg)
                                    if (mediaType == com.mofy.app.data.tmdb.MediaType.TV)
                                        com.mofy.app.data.tmdb.TmdbClient.api.tvExternalIds(matched.id).imdb_id
                                    else
                                        com.mofy.app.data.tmdb.TmdbClient.api.movieExternalIds(matched.id).imdb_id
                                }.getOrNull()
                                if (existingItemId != null) {
                                    val existing = database.libraryDao().getById(existingItemId)
                                    if (existing != null) {
                                        val source = runCatching { LibrarySource.valueOf(existing.source) }.getOrDefault(LibrarySource.DISCOVERED)
                                        database.libraryDao().update(
                                            matched.toLibraryItem(source, imdbId).copy(
                                                id = existingItemId,
                                                addedAtEpochMillis = existing.addedAtEpochMillis,
                                            ),
                                        )
                                    }
                                } else {
                                    val item = matched.toLibraryItem(LibrarySource.DISCOVERED, imdbId)
                                    database.libraryDao().saveConfirmedMatch(item)
                                    // Background: generate embedding so this title is
                                    // findable via semantic search even if it's not in catalog.db.
                                    val ready = onDeviceEmbedder.init()
                                    if (ready) {
                                        val text = "${item.title} ${item.overview}".trim()
                                        val vec = onDeviceEmbedder.embed(text)
                                        if (vec != null) {
                                            val blob = with(onDeviceEmbedder) { vec.toEmbeddingBlob() }
                                            database.libraryDao().updateRaw(item.copy(embeddingBlob = blob))
                                        }
                                    }
                                }
                            }
                        }
                        android.widget.Toast.makeText(context, "Saved \"$title\"", android.widget.Toast.LENGTH_SHORT).show()
                        navController.popBackStack()
                    },
                )
            }
            composable(TopLevelDestination.LIBRARY.route) {
                LibraryScreen(
                    contentPadding = contentPadding,
                    libraryDao = database.libraryDao(),
                    genreRepository = genreRepository,
                    onImportClick = { navController.navigate(PushedRoute.IMPORT_LINK) },
                    onAddManuallyClick = { navController.navigate(PushedRoute.MANUAL_ENTRY_FORM) },
                    onItemClick = { item -> navController.navigate("detail/${item.id}") },
                )
            }
            composable(PushedRoute.MANUAL_ENTRY_FORM) {
                com.mofy.app.ui.library.ManualEntryScreen(
                    contentPadding = contentPadding,
                    onSave = { libraryItem, fileUrl ->
                        coroutineScope.launch {
                            database.libraryDao().upsert(libraryItem)
                            if (fileUrl != null) {
                                database.libraryDao().addAndActivateLink(
                                    LibraryLink(
                                        libraryItemKey = libraryItem.id,
                                        label = null,
                                        movieUri = fileUrl,
                                        subtitleUri = null,
                                        subtitle2Uri = null,
                                        isActive = false,
                                        linkedAtEpochMillis = System.currentTimeMillis(),
                                    ),
                                )
                            }
                        }
                        android.widget.Toast.makeText(context, "Saved to library", android.widget.Toast.LENGTH_SHORT).show()
                        navController.popBackStack(TopLevelDestination.LIBRARY.route, inclusive = false)
                    },
                )
            }
            composable(PushedRoute.SEARCH) {
                com.mofy.app.ui.search.SearchScreen(
                    contentPadding = contentPadding,
                    libraryDao = database.libraryDao(),
                    genreRepository = genreRepository,
                    onItemClick = { item -> navController.navigate("detail/${item.id}") },
                )
            }
            composable(PushedRoute.IMPORT_LINK) {
                fun goToImportConfirm(guessedTitle: String, uri: android.net.Uri) {
                    val encodedTitle = java.net.URLEncoder.encode(guessedTitle, "UTF-8")
                    val encodedUri = java.net.URLEncoder.encode(uri.toString(), "UTF-8")
                    navController.navigate("import_confirm/$encodedTitle/$encodedUri") {
                        popUpTo(PushedRoute.IMPORT_LINK) { inclusive = true }
                    }
                }
                com.mofy.app.ui.link.LinkScreen(
                    contentPadding = contentPadding,
                    onSaveSingleFile = { uri ->
                        goToImportConfirm(com.mofy.app.data.library.guessTitleFromUri(context, uri), uri)
                    },
                    onSaveFolderLink = { movie, _, _ ->
                        // Subtitles picked during import aren't carried
                        // through to the TMDB-confirm step yet - link the
                        // movie file now, add subtitles via Detail's Link
                        // screen afterward if needed.
                        goToImportConfirm(com.mofy.app.data.library.guessTitleFromUri(context, movie), movie)
                    },
                )
            }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(contentPadding = contentPadding)
            }
            composable(ROUTE_WEBVIEW) { backStack ->
                val siteName = backStack.arguments?.getString("siteName") ?: ""
                val siteState by androidx.compose.runtime.produceState<TorrentSite?>(null, siteName) {
                    value = siteRepository.getByName(siteName)
                }
                val site = siteState
                if (site != null) {
                    TorrentWebViewScreen(
                        contentPadding = contentPadding,
                        site = site,
                        sessionViewModel = browseSessionViewModel,
                        onMagnetCaptured = { navController.navigate(ROUTE_CONFIRM_MATCH) },
                    )
                } else {
                    PlaceholderScreen(contentPadding = contentPadding, note = "Unknown site \"$siteName\"")
                }
            }
            composable(ROUTE_CONFIRM_MATCH) {
                val extractedTitle by browseSessionViewModel.extractedTitle.collectAsState()
                val category by browseSessionViewModel.selectedCategory.collectAsState()
                val magnetUri by browseSessionViewModel.pendingMagnetUri.collectAsState()
                // Title is guaranteed by construction by the time a magnet tap
                // navigates here: BrowseSessionViewModel.onMagnetTapped sets it
                // from the magnet URI's dn= param synchronously, falling back
                // to whatever the CSS-selector page extraction already found.
                // This check is a type-safety formality, not a real fallback path.
                if (extractedTitle != null && category != null) {
                    ConfirmMatchScreen(
                        contentPadding = contentPadding,
                        extractedTitle = extractedTitle!!,
                        mediaType = category!!,
                        onConfirm = { confirmed ->
                            // No torrent engine (Phase 03 parked) - hand the
                            // magnet off to whatever's installed (uTorrent
                            // etc.) via the system share sheet instead. Always
                            // force the chooser rather than letting Android
                            // silently reuse a remembered default handler.
                            magnetUri?.let { uri ->
                                val viewIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                                context.startActivity(android.content.Intent.createChooser(viewIntent, "Open magnet link with"))
                                // Persist against the confirmed TMDB match, not
                                // discarded - upsert is idempotent on tmdbId, and
                                // the unique (item, infoHash) index means
                                // re-confirming the same torrent is a no-op while
                                // different releases of the same title both stay.
                                coroutineScope.launch {
                                    val extImdbId = runCatching {
                                        if (confirmed.mediaType == com.mofy.app.data.tmdb.MediaType.TV)
                                            com.mofy.app.data.tmdb.TmdbClient.api.tvExternalIds(confirmed.id).imdb_id
                                        else
                                            com.mofy.app.data.tmdb.TmdbClient.api.movieExternalIds(confirmed.id).imdb_id
                                    }.getOrNull()
                                    val libraryItem = confirmed.toLibraryItem(LibrarySource.SAVED, extImdbId)
                                    database.libraryDao().saveConfirmedMatch(libraryItem)
                                    val saved = database.libraryDao().getByTmdbMatch(
                                        libraryItem.tmdbId!!,
                                        libraryItem.mediaType!!,
                                    ) ?: libraryItem
                                    val infoHash = magnetInfoHash(uri)
                                    database.libraryDao().insertDownload(
                                        LibraryDownload(
                                            libraryItemKey = saved.id,
                                            resourceType = ResourceType.MAGNET.name,
                                            name = null,
                                            uri = uri,
                                            infoHash = infoHash,
                                            dedupeKey = downloadDedupeKey(infoHash, null, uri),
                                            addedAtEpochMillis = System.currentTimeMillis(),
                                        ),
                                    )
                                }
                            }
                            browseSessionViewModel.clearAfterConfirm()
                            navController.popBackStack(TopLevelDestination.BROWSE.route, inclusive = false)
                        },
                        onSaveToLibrary = { results ->
                            coroutineScope.launch {
                                results.forEach { result ->
                                    val extImdbId = runCatching {
                                        if (result.mediaType == com.mofy.app.data.tmdb.MediaType.TV)
                                            com.mofy.app.data.tmdb.TmdbClient.api.tvExternalIds(result.id).imdb_id
                                        else
                                            com.mofy.app.data.tmdb.TmdbClient.api.movieExternalIds(result.id).imdb_id
                                    }.getOrNull()
                                    database.libraryDao().saveConfirmedMatch(result.toLibraryItem(LibrarySource.SAVED, extImdbId))
                                }
                            }
                            android.widget.Toast.makeText(
                                context,
                                if (results.size == 1) "Saved to library" else "Saved ${results.size} to library",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            // Same as the back button: single pop back into the
                            // WebView, which restores the exact page you were on.
                            navController.popBackStack()
                        },
                    )
                } else {
                    PlaceholderScreen(contentPadding = contentPadding, note = "No magnet link captured yet")
                }
            }
            composable(ROUTE_IMPORT_CONFIRM) { backStack ->
                val encodedTitle = backStack.arguments?.getString("title") ?: ""
                val title = java.net.URLDecoder.decode(encodedTitle, "UTF-8")
                val encodedUri = backStack.arguments?.getString("uri") ?: ""
                val importUri = java.net.URLDecoder.decode(encodedUri, "UTF-8")
                var importMediaType by remember { mutableStateOf(com.mofy.app.data.tmdb.MediaType.MOVIE) }
                var showManualEntry by remember { mutableStateOf(false) }
                if (showManualEntry) {
                    com.mofy.app.ui.library.ManualEntryScreen(
                        contentPadding = contentPadding,
                        onSave = { libraryItem, fileUrl ->
                            coroutineScope.launch {
                                database.libraryDao().upsert(libraryItem)
                                if (fileUrl != null) {
                                    database.libraryDao().addAndActivateLink(
                                        LibraryLink(
                                            libraryItemKey = libraryItem.id,
                                            label = null,
                                            movieUri = fileUrl,
                                            subtitleUri = null,
                                            subtitle2Uri = null,
                                            isActive = false,
                                            linkedAtEpochMillis = System.currentTimeMillis(),
                                        ),
                                    )
                                }
                            }
                            android.widget.Toast.makeText(context, "Saved to library", android.widget.Toast.LENGTH_SHORT).show()
                            navController.popBackStack(TopLevelDestination.LIBRARY.route, inclusive = false)
                        },
                    )
                } else {
                    ConfirmMatchScreen(
                        contentPadding = contentPadding,
                        extractedTitle = title,
                        // No site/category context for a locally-picked file -
                        // user picks it via the segmented control (onMediaTypeChange).
                        mediaType = importMediaType,
                        onMediaTypeChange = { importMediaType = it },
                        showDownloadAction = false,
                        // Filename-derived guesses are shaky - let the user fix
                        // the title before spending a TMDB round-trip on it.
                        autoSearch = false,
                        // A picked file can only unambiguously link to one saved
                        // item - reuses the radio (magnetMatchId) as a plain
                        // single-select instead of the checkbox multi-select.
                        allowMultiSelect = false,
                        // Skips the TMDB round-trip when the title's already
                        // known not to be there - goes straight to manual
                        // entry with the guessed title + picked file's URI
                        // carried over, not retyped.
                        onConfirm = {},
                        onSaveToLibrary = { results ->
                            coroutineScope.launch {
                                results.forEach { result ->
                                    val libraryItem = result.toLibraryItem(LibrarySource.IMPORTED)
                                    database.libraryDao().saveConfirmedMatch(libraryItem)
                                    val saved = database.libraryDao().getByTmdbMatch(
                                        libraryItem.tmdbId!!,
                                        libraryItem.mediaType!!,
                                    ) ?: libraryItem
                                    database.libraryDao().addAndActivateLink(
                                        LibraryLink(
                                            libraryItemKey = saved.id,
                                            label = null,
                                            movieUri = importUri,
                                            subtitleUri = null,
                                            subtitle2Uri = null,
                                            isActive = false,
                                            linkedAtEpochMillis = System.currentTimeMillis(),
                                        ),
                                    )
                                }
                            }
                            android.widget.Toast.makeText(
                                context,
                                if (results.size == 1) "Saved to library" else "Saved ${results.size} to library",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            navController.popBackStack(TopLevelDestination.LIBRARY.route, inclusive = false)
                        },
                    )
                }
            }
            composable(ROUTE_DETAIL) { backStack ->
                val id = backStack.arguments?.getString("id") ?: ""
                val detailItem by database.libraryDao().observeById(id).collectAsState(initial = null)
                val liveSessionForDetail by watchTogetherViewModel.sessionState.collectAsState()
                val sessionMatchesDetail = detailItem != null &&
                    liveSessionForDetail != null &&
                    liveSessionForDetail?.itemHash == ItemHash.of(detailItem!!)
                DetailScreen(
                    contentPadding = contentPadding,
                    itemId = id,
                    libraryDao = database.libraryDao(),
                    genreRepository = genreRepository,
                    onSearchForTorrent = { item ->
                        browseSessionViewModel.startSearch(
                            item.title,
                            item.mediaType?.let { com.mofy.app.data.tmdb.MediaType.valueOf(it) }
                                ?: com.mofy.app.data.tmdb.MediaType.MOVIE,
                            alternateTitle = item.romanizedOriginalTitle,
                        )
                        navController.navigate(TopLevelDestination.BROWSE.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onLink = { item -> navController.navigate(PushedRoute.link(item.id)) },
                    onNeedsMatchResolution = { title, mediaType ->
                        navController.navigate(PushedRoute.resolveMatch(title, mediaType.name, existingItemId = id))
                    },
                    onWatchTogether = { item ->
                        navController.navigate(PushedRoute.watchTogetherCreate(item.id))
                    },
                    activeWatchTogetherSession = if (sessionMatchesDetail) liveSessionForDetail else null,
                    onReturnToWatchTogetherSession = { navController.navigate(PushedRoute.WT_SESSION) },
                )
            }
            composable(PushedRoute.LINK) { backStack ->
                val linkItemId = backStack.arguments?.getString("itemId") ?: ""
                val existingLinks by database.libraryDao().observeLinks(linkItemId).collectAsState(initial = emptyList())
                com.mofy.app.ui.link.LinkScreen(
                    contentPadding = contentPadding,
                    existingLinks = existingLinks,
                    onSetActive = { linkId ->
                        coroutineScope.launch { database.libraryDao().setActiveLink(linkItemId, linkId) }
                    },
                    onSaveSingleFile = { uri ->
                        coroutineScope.launch {
                            database.libraryDao().addAndActivateLink(
                                com.mofy.app.data.library.LibraryLink(
                                    libraryItemKey = linkItemId,
                                    label = null,
                                    movieUri = uri.toString(),
                                    subtitleUri = null,
                                    subtitle2Uri = null,
                                    isActive = false,
                                    linkedAtEpochMillis = System.currentTimeMillis(),
                                ),
                            )
                            navController.popBackStack()
                        }
                    },
                    onSaveFolderLink = { movie, subtitle, subtitle2 ->
                        coroutineScope.launch {
                            database.libraryDao().addAndActivateLink(
                                com.mofy.app.data.library.LibraryLink(
                                    libraryItemKey = linkItemId,
                                    label = null,
                                    movieUri = movie.toString(),
                                    subtitleUri = subtitle?.toString(),
                                    subtitle2Uri = subtitle2?.toString(),
                                    isActive = false,
                                    linkedAtEpochMillis = System.currentTimeMillis(),
                                ),
                            )
                            navController.popBackStack()
                        }
                    },
                )
            }
            composable(PushedRoute.EDIT_SITE) { backStack ->
                val siteName = backStack.arguments?.getString("siteName") ?: ""
                val existingState by androidx.compose.runtime.produceState<TorrentSite?>(null, siteName) {
                    value = siteRepository.getByName(siteName)
                }
                EditSiteScreen(
                    contentPadding = contentPadding,
                    existing = existingState,
                    onSave = { site ->
                        coroutineScope.launch {
                            siteRepository.upsert(site)
                            navController.popBackStack()
                        }
                    },
                    onDelete = {
                        coroutineScope.launch {
                            siteRepository.delete(siteName)
                            navController.popBackStack()
                        }
                    },
                )
            }
            composable(PushedRoute.EDIT_SITE_NEW) {
                EditSiteScreen(
                    contentPadding = contentPadding,
                    existing = null,
                    onSave = { site ->
                        coroutineScope.launch {
                            siteRepository.upsert(site)
                            navController.popBackStack()
                        }
                    },
                )
            }
            composable(PushedRoute.WT_CREATE) { backStack ->
                val libraryItemId = backStack.arguments?.getString("libraryItemId") ?: ""
                val createItem by database.libraryDao().observeById(libraryItemId).collectAsState(initial = null)
                val resolvedItem = createItem
                if (resolvedItem == null) {
                    PlaceholderScreen(contentPadding = contentPadding, note = "Loading…")
                } else {
                    // host() does a synchronous signaling connect (up to 5s,
                    // see OkHttpSignalingChannel) - a failure there must not
                    // crash the whole composition, same "error, not crash"
                    // rule as guest join.
                    var hostError by remember(resolvedItem.id) { mutableStateOf<String?>(null) }
                    val hostSession = remember(resolvedItem.id) {
                        runCatching {
                            WatchTogetherSession.host(
                                itemHash = ItemHash.of(resolvedItem),
                                displayName = "You",
                                player = FakePlayerController(),
                                appContext = context,
                            )
                        }.onFailure { hostError = it.message ?: "Could not start session" }.getOrNull()
                    }
                    if (hostSession == null) {
                        PlaceholderScreen(
                            contentPadding = contentPadding,
                            note = hostError?.let { "Couldn't start Watch Together: $it" } ?: "Couldn't start Watch Together",
                        )
                    } else {
                        androidx.compose.runtime.LaunchedEffect(hostSession) {
                            watchTogetherViewModel.setActive(hostSession, resolvedItem)
                        }
                        CreateRoomScreen(
                            contentPadding = contentPadding,
                            session = hostSession,
                            onStartWatching = { navController.navigate(PushedRoute.WT_SESSION) },
                        )
                    }
                }
            }
            composable(PushedRoute.WT_SESSION) {
                val activeSession = watchTogetherSession
                val activeItem by watchTogetherViewModel.activeItem.collectAsState()
                if (activeSession == null) {
                    PlaceholderScreen(contentPadding = contentPadding, note = "No active Watch Together session")
                } else {
                    val sessionUiState by activeSession.state.collectAsState()
                    if (activeSession.role == Role.GUEST && !sessionUiState.isPlaying) {
                        GuestLobbyScreen(
                            contentPadding = contentPadding,
                            session = activeSession,
                            onSessionStarted = { /* handled by the isPlaying check above on recomposition */ },
                            onLeave = {
                                watchTogetherViewModel.clear()
                                navController.popBackStack(TopLevelDestination.HOME.route, inclusive = false)
                            },
                        )
                    } else {
                        val activeLink by (activeItem?.let { database.libraryDao().observeActiveLink(it.id) } ?: kotlinx.coroutines.flow.emptyFlow())
                            .collectAsState(initial = null)
                        PlayerScreen(
                            contentPadding = contentPadding,
                            mediaUri = activeLink?.movieUri ?: "",
                            itemTitle = activeItem?.title ?: "",
                            createSession = { realPlayer ->
                                // Lobby sessions are created with a headless FakePlayerController
                                // (no media chosen yet); starting playback re-creates the session
                                // bound to the real VlcPlayerController, reusing the same roomKey.
                                // This is a known v1 gap: any guest connected during the lobby
                                // phase must reconnect, since a new signaling/transport is
                                // stood up under the hood - see docs/tasks/13-watch-together.md C1.
                                activeSession.end()
                                val fresh = if (activeSession.role == Role.HOST) {
                                    WatchTogetherSession.host(
                                        itemHash = activeSession.itemHash,
                                        displayName = "You",
                                        player = realPlayer,
                                        appContext = context,
                                        roomKey = activeSession.roomKey,
                                    )
                                } else {
                                    WatchTogetherSession.guest(
                                        roomKey = activeSession.roomKey,
                                        signalingUrl = activeSession.signalingUrl
                                            ?: SignalingSettings.urlForRoom(activeSession.roomKey)
                                            ?: error("no signaling url"),
                                        itemHash = activeSession.itemHash,
                                        displayName = "You",
                                        player = realPlayer,
                                        appContext = context,
                                    )
                                }
                                watchTogetherViewModel.setActive(fresh, activeItem)
                                fresh
                            },
                            onBack = { navController.popBackStack() },
                            onInvite = { watchTogetherViewModel.session.value?.let { shareWatchTogetherInvite(context, it) } },
                        )
                    }
                }
            }
            composable(PushedRoute.WT_SCAN) {
                QrScanScreen(
                    onScanned = { parsed ->
                        val pickedItem = joinPickedItem
                        if (pickedItem != null) {
                            val signalingUrl = parsed.signalingUrl ?: SignalingSettings.urlForRoom(parsed.roomKey)
                            if (signalingUrl != null) {
                                val guestSession = WatchTogetherSession.guest(
                                    roomKey = parsed.roomKey,
                                    signalingUrl = signalingUrl,
                                    itemHash = ItemHash.of(pickedItem),
                                    displayName = "You",
                                    player = FakePlayerController(),
                                    appContext = context,
                                )
                                watchTogetherViewModel.setActive(guestSession, pickedItem)
                                joinPickedItem = null
                                navController.popBackStack(TopLevelDestination.HOME.route, inclusive = false)
                                navController.navigate(PushedRoute.WT_SESSION)
                            }
                        } else {
                            showJoinSheet = true
                            navController.popBackStack()
                        }
                    },
                    onCancel = { navController.popBackStack() },
                )
            }
        }
        if (watchTogetherSession != null && currentRoute != ROUTE_DETAIL && currentRoute != PushedRoute.WT_SESSION) {
            val liveState by watchTogetherViewModel.sessionState.collectAsState()
            LiveSessionBar(
                session = liveState,
                onReturnToSession = { navController.navigate(PushedRoute.WT_SESSION) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(contentPadding)
                    .padding(12.dp),
            )
        }
        }
        if (showJoinSheet) {
            val allLibraryItems by database.libraryDao().observeAll().collectAsState(initial = emptyList())
            JoinSessionSheet(
                libraryItem = joinPickedItem,
                itemHash = null,
                player = FakePlayerController(),
                appContext = context,
                displayName = "You",
                libraryItems = allLibraryItems,
                onLibraryItemPicked = { joinPickedItem = it },
                onDismiss = {
                    showJoinSheet = false
                    deepLinkedRoomKey = null
                    deepLinkedSignalingUrl = null
                },
                onScanQr = {
                    showJoinSheet = false
                    navController.navigate(PushedRoute.WT_SCAN)
                },
                onJoined = { session ->
                    watchTogetherViewModel.setActive(session, joinPickedItem)
                    joinPickedItem = null
                    showJoinSheet = false
                    deepLinkedRoomKey = null
                    deepLinkedSignalingUrl = null
                    navController.navigate(PushedRoute.WT_SESSION)
                },
            )
        }
    }
}
