package com.wepwawet.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wepwawet.data.SteamApiService
import com.wepwawet.data.WorkshopRepository
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.screens.DownloadManagerScreen
import com.wepwawet.ui.screens.ImageEditorScreen
import com.wepwawet.ui.screens.LibraryScreen
import com.wepwawet.ui.screens.FavoritesScreen
import com.wepwawet.ui.screens.LogsScreen
import com.wepwawet.ui.screens.PlaylistsScreen
import com.wepwawet.ui.screens.PlaylistEditorScreen
import com.wepwawet.ui.screens.PairingScreen
import com.wepwawet.ui.screens.SettingsScreen
import com.wepwawet.ui.screens.VideoTrimScreen
import com.wepwawet.ui.screens.WallpaperDetailScreen
import com.wepwawet.ui.screens.WallpaperPreviewScreen
import com.wepwawet.ui.screens.WorkshopBrowserScreen

/**
 * v0.8.9: Material 3's own guidance prescribes a bottom navigation bar
 * for "three to five destinations of equal importance" — exactly our
 * case — which frees the entire top of every screen for content.
 *
 * v0.8.13: FOUR tabs — Wallpapers / Workshop / Playlists / Discover
 * (user request: "add the playlists to the bottom bar"). The playlists
 * manager used to be reachable only through a top-bar icon; as a tab it
 * is one tap from anywhere and keeps its scroll state like the others.
 *
 * v0.8.11: THREE tabs — Wallpapers / Workshop / Discover. The Folders
 * tab is gone (folders are chips on the Wallpapers page now), and the
 * auto-wallpaper tab is "Discover" (renamed from "Anime", formerly
 * "Daily", per user request).
 *
 * v0.8.10: the M3 NavigationBar's stock 80dp height read as "buttons at
 * the bottom are too tall", and the outer Scaffold's default
 * contentWindowInsets stacked a SECOND status-bar gap on top of every
 * screen's own top bar. The bar is a compact custom 60dp Surface row and
 * the outer Scaffold no longer insets its content — each screen's own
 * top bar already handles the status bar exactly once.
 */
private data class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val BOTTOM_TABS = listOf(
    BottomTab("library", "Wallpapers", Icons.Filled.Wallpaper),
    BottomTab("workshop", "Workshop", Icons.Filled.Explore),
    BottomTab("playlists", "Playlists", Icons.Filled.QueueMusic),
    BottomTab("discover", "Discover", Icons.Filled.AutoAwesome),
)

/**
 * WepWawet navigation host — a Material 3 bottom bar with the three
 * top-level destinations plus the drill-down routes above it:
 *
 *   - library            (Wallpapers tab — the device's wallpaper
 *                         collection, tags + folder chips + playlists)
 *   - workshop           (Workshop tab — global Steam Workshop browse)
 *   - discover           (Discover tab — auto wallpaper from booru sources)
 *   - creator/{steamId}  (WorkshopBrowserScreen scoped to a single creator)
 *   - downloads          (DownloadManagerScreen showing active + completed)
 *   - detail/{itemId}    (WallpaperDetailScreen — workshop item detail)
 *   - preview            (live-rendering preview + apply + properties)
 *   - playlists          (the saved WallpaperSuite playlists)
 *   - playlist/{id}      (one playlist's editor)
 *   - pairing / settings / logs / favorites / videoTrim
 *
 * Tab switches use the canonical M3 pattern (popUpTo the start
 * destination with saveState + launchSingleTop + restoreState) so every
 * tab keeps its own scroll/state; drill-down routes hide the bar and get
 * a back arrow instead.
 * A single [WorkshopRepository] is created at the NavHost level (via [remember])
 * so that the WorkshopBrowserScreen and WallpaperDetailScreen share the same
 * in-memory cache, browse session, and search session. If they each created
 * their own instance, navigation between them would lose all loaded items +
 * force a fresh fetch.
 *
 * The `creator/{steamId}` route opens a new [WorkshopBrowserScreen] with
 * `creatorSteamId` set — the repo's `creatorSteamId` filter scopes the
 * fetch to that creator's /profiles/<steamId>/myworkshop/ page. A back arrow
 * is shown so the user can return to the global browse.
 */
@Composable
fun WepWawetNavHost() {
    val nav = rememberNavController()
    val context = LocalContext.current

    // Shared [SteamApiService] + [WorkshopRepository] instances — see class doc.
    //
    // v0.5.3 — we create the [SteamApiService] separately so we can load the
    // Steam Web API key from SharedPreferences (stored by the Settings screen
    // under the `wepwawet_settings` file via the `steam_api_key` key) and set
    // it on the service BEFORE the repository starts issuing browse requests.
    // The key is required for the IPublishedFileService/QueryFiles server-side
    // sort options (Most Up Votes, Most Subscriptions); without it, those sorts
    // fall back to HTML scraping (which doesn't support them).
    val steamApi = remember { SteamApiService(appContext = context.applicationContext) }
    val repo = remember { WorkshopRepository(steamApi) }

    // v0.7.21: Hoist the LazyGridState for the workshop + creator screens to
    // the NavHost level so scroll position survives navigation to detail and
    // back. The old rememberLazyGridState() (which uses rememberSaveable
    // internally) didn't reliably restore scroll position within NavHost
    // composables — the state was lost when the composable left composition
    // during navigation. By hoisting to the NavHost level (which never leaves
    // composition), the gridState object persists across the entire app
    // session.
    // v0.7.24 (H-U3): rememberSaveable + LazyGridState.Saver — plain remember
    // lost the scroll position on every configuration change (rotation,
    // dark-mode toggle, locale change) because the whole Activity including
    // the NavHost is recreated.
    val workshopGridState = androidx.compose.runtime.saveable.rememberSaveable(
        saver = LazyGridState.Saver) { LazyGridState() }
    val creatorGridState = androidx.compose.runtime.saveable.rememberSaveable(
        saver = LazyGridState.Saver) { LazyGridState() }

    // Reload the Steam Web API key every time the user returns to the NavHost
    // (e.g. after saving a new key in Settings). Uses a lifecycle observer so
    // the key is always current.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                val prefs = context.getSharedPreferences("wepwawet_settings", Context.MODE_PRIVATE)
                val savedKey = prefs.getString("steam_api_key", null)
                steamApi.apiKey = savedKey
                WepWawetLogger.steam(
                    "nav_host_steam_api_key_loaded",
                    "is_set" to (savedKey != null),
                    "length" to (savedKey?.length ?: 0),
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // v0.8.9: the bottom bar shows ONLY on the four top-level tabs —
    // drill-down routes (detail, preview, settings, …) are full-screen
    // with a back arrow instead.
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute != null && currentRoute in BOTTOM_TABS.map { it.route }

    fun navigateToTab(route: String) {
        if (route == currentRoute) return
        nav.navigate(route) {
            popUpTo(nav.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        // v0.8.10: NO content insets here — every screen owns a top bar
        // that pads for the status bar itself; the outer default stacked
        // a second status-bar-height gap above the content.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                // v0.8.10: compact 60dp bar (the stock M3 NavigationBar is
                // 80dp — reported as "too tall"). Same M3 visuals: pill
                // indicator, secondaryContainer on the selected item.
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .navigationBarsPadding(),
                    ) {
                        for (tab in BOTTOM_TABS) {
                            val selected = currentRoute == tab.route
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clickable(onClick = { navigateToTab(tab.route) }),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .height(26.dp)
                                        .fillMaxWidth(0.55f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) {
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .clip(RoundedCornerShape(13.dp))
                                                .background(MaterialTheme.colorScheme.secondaryContainer),
                                        )
                                    }
                                    Icon(
                                        tab.icon,
                                        contentDescription = tab.label,
                                        // v0.8.19 (user request): back to
                                        // height(20.dp) — the bottom bar was
                                        // made 60dp-compact on purpose (the
                                        // stock M3 bar is 80dp) to leave room
                                        // for content; keep it exactly as it
                                        // was before the B11 change.
                                        modifier = Modifier.height(20.dp),
                                        tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    tab.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
    // v0.9.0 redesign: THE UNIVERSAL Home/Lock toggles sit at the top of
    // the app on the main tabs ("The Lock and Home toggles should be
    // universal and sit at the top of the app"). Every apply path in the
    // app — image wallpapers, live wallpapers, rules, Discover — follows
    // them (RuleEngine.resolvedTarget + the Discover/Library appliers).
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        if (showBottomBar) {
            com.wepwawet.ui.components.UniversalScreenToggles()
        }
        NavHost(
            navController = nav,
            startDestination = "library",
            modifier = Modifier.weight(1f),
        ) {

        // v0.8.0: the library is the main page — the device's wallpaper
        // collection (engine-enumerated) + manual import. The Workshop /
        // Discover entry points live in the bottom navigation bar; folders,
        // groups and workshop-tag filters live ON this page as chips
        // (v0.8.12). The shared steamApi powers the workshop-tag back-fill.
        composable("library") {
            LibraryScreen(
                onOpenSettings = { nav.navigate("settings") },
                onOpenDownloads = { nav.navigate("downloads") },
                onOpenWorkshopItem = { itemId -> nav.navigate("detail/$itemId") },
                onOpenWallpaper = { wallpaper ->
                    com.wepwawet.engine.PreviewRequestHolder.set(wallpaper)
                    nav.navigate("preview")
                },
                onOpenPlaylists = { nav.navigate("playlists") },
                // v0.8.16: the image editor (crop + aspect presets).
                // v0.8.17: the request goes through ImageEditRequestHolder —
                // uri-in-route arguments mangled SAF document ids ("the
                // edit menu doesnt load anything").
                onEditImage = { entry ->
                    com.wepwawet.ui.screens.ImageEditRequestHolder.set(entry.uri, entry.displayName)
                    nav.navigate("imageEditor")
                },
                steamApi = steamApi,
            )
        }

        // v0.8.11: Discover — the auto-wallpaper screen (booru sources,
        // tag chips, schedule). A bottom-nav tab, so no back arrow; the
        // implementation package keeps its historical name but NO
        // user-visible string carries it.
        composable("discover") {
            com.wepwawet.discover.DiscoverScreen(onBack = null)
        }

        // v0.8.0: live-rendering preview + apply + properties + playlist.
        // v0.8.1: onOpenWorkshop — the preview's "Workshop page" button jumps
        // to the existing in-app workshop detail screen for the item the
        // wallpaper was downloaded from (resolved from the package's
        // workshopurl in project.json).
        composable("preview") {
            val wallpaper = com.wepwawet.engine.PreviewRequestHolder.peek()
                ?: io.wallpaperengine.weutil.WallpaperInfoSparse()
            WallpaperPreviewScreen(
                wallpaper = wallpaper,
                onBack = { nav.popBackStack() },
                onOpenWorkshop = { itemId -> nav.navigate("detail/$itemId") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenPlaylists = { nav.navigate("playlists") },
            )
        }

        // v0.8.11: the multi-playlist manager + per-playlist editor.
        // v0.8.13: this is a BOTTOM-NAV TAB now (no back arrow — same as
        // the other tabs); the per-playlist editor below it stays a
        // drill-down with the bar hidden and a back arrow.
        composable("playlists") {
            PlaylistsScreen(
                onBack = null,
                onOpenEditor = { id -> nav.navigate("playlist/$id") },
            )
        }

        // v0.8.16: the image editor (crop + aspect presets). v0.8.17: no
        // route arguments — the request rides in ImageEditRequestHolder.
        composable("imageEditor") {
            ImageEditorScreen(onBack = { nav.popBackStack() })
        }

        composable(
            route = "playlist/{playlistId}",
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
        ) { entry ->
            val playlistId = entry.arguments?.getString("playlistId").orEmpty()
            PlaylistEditorScreen(
                playlistId = playlistId,
                onBack = { nav.popBackStack() },
            )
        }

        composable("workshop") {
            WorkshopBrowserScreen(
                onItemClick = { id -> nav.navigate("detail/$id") },
                onOpenDownloads = { nav.navigate("downloads") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenFavorites = { nav.navigate("favorites") },
                onOpenPairing = { nav.navigate("pairing") },
                onCreatorClick = { steamId -> nav.navigate("creator/$steamId") },
                // v0.8.9: the Workshop is a bottom-nav tab — no back arrow.
                onBack = null,
                repo = repo,
                gridState = workshopGridState,
            )
        }

        composable(
            route = "creator/{steamId}",
            arguments = listOf(navArgument("steamId") { type = NavType.StringType }),
        ) { entry ->
            val steamId = entry.arguments?.getString("steamId").orEmpty()
            // v0.7.24 (H-U4): logged from a LaunchedEffect — logging
            // directly in the composable body fired on every recomposition.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                WepWawetLogger.lifecycle("NavHost navigate to creator",
                    "steam_id" to steamId)
            }
            WorkshopBrowserScreen(
                onItemClick = { id -> nav.navigate("detail/$id") },
                onOpenDownloads = { nav.navigate("downloads") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenFavorites = { nav.navigate("favorites") },
                onOpenPairing = { nav.navigate("pairing") },
                onCreatorClick = null, // no nested creator navigation — the
                // "by …" line renders as plain text on a creator's own page.
                onBack = { nav.popBackStack() },
                creatorSteamId = steamId,
                repo = repo,
                gridState = creatorGridState,
            )
        }

        composable("downloads") {
            // v0.7.24 (H-U4): logged from a LaunchedEffect — logging
            // directly in the composable body fired on every recomposition.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                WepWawetLogger.lifecycle("NavHost navigate to downloads")
            }
            DownloadManagerScreen(
                onBack = { nav.popBackStack() },
                onItemClick = { itemId -> nav.navigate("detail/$itemId") },
            )
        }

        composable(
            route = "detail/{itemId}",
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
        ) { entry ->
            val itemId = entry.arguments?.getString("itemId").orEmpty()
            // v0.7.24 (H-U4): logged from a LaunchedEffect — logging
            // directly in the composable body fired on every recomposition.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                WepWawetLogger.lifecycle("NavHost navigate to detail",
                    "item_id" to itemId)
            }
            WallpaperDetailScreen(
                itemId = itemId,
                onBack = { nav.popBackStack() },
                repo = repo,
                onCreatorClick = { steamId -> nav.navigate("creator/$steamId") },
                onVideoTrim = { _, title, id ->
                    val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
                    nav.navigate("videoTrim/$id/$encodedTitle")
                },
            )
        }

        composable("pairing") {
            PairingScreen(onBack = { nav.popBackStack() })
        }

        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                // Logs button in Settings' top-right corner (visible only
                // while verbose logging is enabled).
                onOpenLogs = { nav.navigate("logs") },
                // v0.8.15: a saved Steam key reaches the RUNNING service
                // immediately (it used to wait for the next Activity
                // onResume, so server-side sorts kept HTML-scraping after
                // saving until the app was backgrounded once).
                onApiKeySaved = { key -> steamApi.apiKey = key },
            )
        }

        composable("logs") {
            LogsScreen(onBack = { nav.popBackStack() })
        }

        // v0.7.1: Favorites route — full implementation.
        // The WorkshopBrowserScreen top bar's heart icon navigates here.
        // The screen renders a list of saved-creator Steam IDs, each with
        // a 40dp circular profile picture (loaded via Coil from the
        // avatar URL cached as a side-effect of getPlayerSummaries), the
        // creator's display name, the raw Steam ID below it, and a heart
        // IconButton to unfavorite. A LaunchedEffect bulk-resolves names
        // + caches avatar URLs the moment the screen composes; rows fall
        // back to showing the raw ID while loading.
        //
        // Saved-creator Steam IDs are persisted in the `wepwawet_settings`
        // SharedPreferences file under the `saved_creators` StringSet (see
        // WallpaperDetailScreen's favorite-heart toggle).
        composable("favorites") {
            // v0.7.24 (H-U4): logged from a LaunchedEffect — logging
            // directly in the composable body fired on every recomposition.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                WepWawetLogger.lifecycle("NavHost navigate to favorites")
            }
            FavoritesScreen(
                onBack = { nav.popBackStack() },
                onCreatorClick = { steamId -> nav.navigate("creator/$steamId") },
                steamApi = steamApi,
            )
        }

        // Video trim route — only passes itemId + title (URL-encoded).
        // The video file path is read from WorkshopDownloadJob.pendingVideoFor(itemId)
        // (not passed as a nav arg, because file paths contain slashes that
        // break Jetpack Navigation's path segment parsing).
        composable(
            route = "videoTrim/{itemId}/{itemTitle}",
            arguments = listOf(
                navArgument("itemId") { type = NavType.StringType },
                navArgument("itemTitle") { type = NavType.StringType },
            ),
        ) { entry ->
            val trimItemId = entry.arguments?.getString("itemId").orEmpty()
            // v0.7.24 (H-U5 BUG FIX): URLDecoder.decode THROWS
            // IllegalArgumentException on a malformed % sequence — called
            // during composition, it crashed the app straight out of the
            // NavHost. Fall back to the raw string on failure.
            val trimItemTitle = runCatching {
                java.net.URLDecoder.decode(
                    entry.arguments?.getString("itemTitle").orEmpty(), "UTF-8")
            }.onFailure { WepWawetLogger.appWarn("WepWawetNavHost: block — the runCatching block failed", "exception" to it.javaClass.simpleName, "message" to (it.message ?: "")) }.getOrElse { entry.arguments?.getString("itemTitle").orEmpty() }
            // v0.7.20: Read the video file path from the per-item state (was
            // WorkshopDownloadJob.state.value.pendingVideoFile which under
            // concurrent downloads could read the wrong item's pending video).
            val trimVideoPath = com.wepwawet.pairing.WorkshopDownloadJob
                .pendingVideoFor(trimItemId)?.first
            // v0.7.24 (H-U4): logged from a LaunchedEffect (see above).
            androidx.compose.runtime.LaunchedEffect(trimItemId) {
                WepWawetLogger.lifecycle("NavHost navigate to videoTrim",
                    "item_id" to trimItemId,
                    "video_path" to (trimVideoPath ?: "null"),
                    "item_title" to trimItemTitle)
            }
            // v0.7.21 BUG FIX: If pendingVideoFor returns null (e.g. the
            // pending state was cleared by a concurrent cancelVideoSend, or
            // the user deep-linked to this route without a pending video),
            // pop back immediately instead of showing a broken trim screen
            // with File("") that ExoPlayer can't load.
            //
            // v0.7.23 BUG NAV-1: popBackStack was called during composition
            // which can cause a blank frame or IllegalStateException. Now
            // wrapped in LaunchedEffect so it's deferred to after composition.
            if (trimVideoPath == null) {
                WepWawetLogger.lifecycle("NavHost videoTrim: no pending video — popping back")
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    nav.popBackStack()
                }
                return@composable
            }
            VideoTrimScreen(
                videoFile = java.io.File(trimVideoPath),
                itemTitle = trimItemTitle,
                itemId = trimItemId,
                onBack = { nav.popBackStack() },
                onSendComplete = { nav.popBackStack() },
                // v0.9.0: library mode when the Download button paused the
                // video for a clip decision; send mode for the classic
                // send-to-phone flow (default, back-compat).
                mode = com.wepwawet.pairing.WorkshopDownloadJob
                    .pendingVideoModeFor(trimItemId),
            )
        }
    }
    } // NavHost
    } // v0.9.0: the Column holding the toggles + NavHost
}
