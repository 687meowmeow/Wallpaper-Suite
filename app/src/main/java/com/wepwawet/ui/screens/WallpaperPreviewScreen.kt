package com.wepwawet.ui.screens

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.gson.Gson
import com.wepwawet.engine.EngineLibrary
import com.wepwawet.log.WeClientLog
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.components.WallpaperPropertyEditor
import io.wallpaperengine.weclient.WEWallpaperService
import io.wallpaperengine.weclient.WallpaperProperties
import io.wallpaperengine.weclient.WeEnginePrefs
import io.wallpaperengine.weutil.AudioRecorder
import io.wallpaperengine.weutil.ParallaxController
import io.wallpaperengine.weutil.WallpaperInfoSparse
import io.wallpaperengine.weviews.SceneWallpaperView
import io.wallpaperengine.weviews.VideoWallpaperView
import io.wallpaperengine.weviews.WallpaperControls
import io.wallpaperengine.wedata.PlaylistDataWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * The live-rendering wallpaper preview — the Compose replacement for the
 * official client's PreviewActivity, rebuilt in v0.8.3 to match it 1:1
 * (ground truth: decompiled v2.8.8 + activity_preview.xml):
 *
 *  - Almost-fullscreen renderer with a semi-clear top bar floating over the
 *    wallpaper (the official layout has no bottom bar at all — apply and the
 *    details moved to the top bar / dialogs).
 *  - The static preview bitmap behind the GL surface while loading, fading
 *    out when the scene finishes loading (official fadeIntoCurrentScene).
 *  - A swipe-up properties drawer anchored to a draggable guideline
 *    (closed at 98% like the official 0.98 guidePercent; tap toggles to
 *    half height; drag moves it freely; clamped to the system bars).
 *  - Parallax + motion input (ParallaxController linked to the renderer),
 *    audio-responsive support (AudioRecorder wired to sendAudioData),
 *    screen-rotation tracking, match-preview aspect and Surface frame
 *    rate — all previously missing, which is why the wallpaper could not
 *    be moved around like in the real app.
 *  - Apply lives in the top bar (check icon). Long-press opens the official
 *    Apply / Revert dialog; the first apply shows the official lockscreen
 *    notice.
 *  - Type/Resolution/FPS/file size live in a Details dialog (the official
 *    keeps them in FileDetailsActivity, off the preview surface).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WallpaperPreviewScreen(
    wallpaper: WallpaperInfoSparse,
    onBack: () -> Unit,
    onOpenWorkshop: (itemId: String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // v0.8.3: process-death / empty-entry guard — a blank WallpaperInfoSparse
    // (PreviewRequestHolder lost across process death) previously rendered a
    // dead screen with no explanation.
    if (wallpaper.filePath.isEmpty()) {
        WepWawetLogger.engineWarn("Preview: opened without a wallpaper — returning")
        LaunchedEffect(Unit) { onBack() }
        return
    }

    // ----- Renderer + engine state -----
    var controls by remember { mutableStateOf<WallpaperControls?>(null) }
    var glView by remember { mutableStateOf<View?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var propertiesRevision by remember { mutableStateOf(0) }
    var backgroundPreview by remember { mutableStateOf<Bitmap?>(null) }
    var showBackground by remember { mutableStateOf(true) }

    // ----- UI state -----
    var applied by remember {
        mutableStateOf(EngineLibrary.getSelectedWallpaper(context) == wallpaper.filePath)
    }
    var playlistActive by remember { mutableStateOf(false) }
    var playlistActiveWithoutService by remember { mutableStateOf(false) }
    var showApplyRevert by remember { mutableStateOf(false) }
    var showLockscreenNotice by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showPowerSaveDialog by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<WallpaperDetails?>(null) }
    var workshopId by remember { mutableStateOf<String?>(null) }
    var powerSavingActive by remember { mutableStateOf(false) }
    var alignOverlayVisible by remember { mutableStateOf(false) }

    // The official drawer guideline: 0.98 = closed, 0.5 = open (tap),
    // clamped to [topInset/height .. 1 - bottomInset/height].
    val drawerAnim = remember { Animatable(0.98f) }

    // ----- Input controllers (the missing "move the wallpaper" wiring) -----
    val parallaxController = remember { ParallaxController(context) }
    val audioRecorder = remember { AudioRecorder() }

    WepWawetLogger.engine("Preview: opened wallpaper",
        "title" to wallpaper.title,
        "file" to wallpaper.filePath,
        "type" to (wallpaper.type.ifEmpty { "(unknown)" }),
        "is_local" to wallpaper.isLocal,
        "is_official" to wallpaper.isOfficial)

    fun refreshAppliedState() {
        applied = EngineLibrary.getSelectedWallpaper(context) == wallpaper.filePath
        scope.launch {
            withContext(Dispatchers.IO) {
                val playlist = WeEnginePrefs.readPlaylist(context)
                val service = WEWallpaperService.instance
                val live = service?.isLiveWallpaperActive == true
                playlistActive = playlist.wallpapers.isNotEmpty() && live
                playlistActiveWithoutService = playlist.wallpapers.isNotEmpty() && !live
            }
        }
    }

    // ----- Apply flow (official performApplyWallpaper + menu behavior) -----
    var lastApplyRequest by remember { mutableStateOf(0L) }

    fun applyNow(forcePicker: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastApplyRequest < 1000L && !forcePicker) return  // official 1s rate limit
        lastApplyRequest = now
        WepWawetLogger.engine("Preview: apply requested",
            "file" to wallpaper.filePath,
            "force_picker" to forcePicker)
        scope.launch {
            if (!forcePicker && playlistActiveWithoutService) {
                // official: applying while a playlist exists but the service
                // is down silently converts the apply into an add
                playlistActiveWithoutService = false
                withContext(Dispatchers.IO) {
                    addWallpaperToPlaylist(context, wallpaper, false)
                }
            }
            val hotReloaded = WeEnginePrefs.callApplyWallpaper(context, wallpaper, forcePicker)
            applied = true
            if (!hotReloaded) {
                WepWawetLogger.engine("Preview: picker shown — user must finish choosing WallpaperSuite")
            } else {
                WepWawetLogger.engine("Preview: wallpaper hot-reloaded in the running service")
            }
        }
    }

    fun performApplyWallpaper() {
        val service = WEWallpaperService.instance
        if (service == null || !service.isLiveWallpaperActive) {
            if (!WeEnginePrefs.getStoredBoolean(
                    context, WeEnginePrefs.PREF_KEY_HAS_SHOWN_LOCKSCREEN_WARNING, false)
            ) {
                showLockscreenNotice = true
                return
            }
        }
        applyNow(false)
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            audioRecorder.startAudioListener()
        } else {
            val props = controls?.getWallpaperProperties()
            props?.get(WallpaperProperties.PROP_AUDIO_PROCESSING)?.let { prop ->
                prop.value = false
                controls?.applyWallpaperProperties(Gson().toJson(props))
            }
        }
    }

    fun setAudioActive(active: Boolean) {
        if (active) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                audioRecorder.startAudioListener()
            } else {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            audioRecorder.stopAudioListener()
        }
    }

    // ----- The load callback: fade in, wire everything (official
    // fadeIntoCurrentScene$lambda$25) -----
    fun onLoaded() {
        WepWawetLogger.engine("Preview: wallpaper finished loading (load callback fired)")
        val c = controls ?: return
        loaded = true
        propertiesRevision++
        showBackground = false
        parallaxController.applyProperties(c.getWallpaperProperties())
        if (c.isAlignmentAvailable() && !WeEnginePrefs.getStoredBoolean(
                context, WeEnginePrefs.PREF_KEY_HAS_SHOWN_ALIGN_OVERLAY, false)
        ) {
            alignOverlayVisible = true
        }
        // official: Surface.setFrameRate on API 31+ (scene: default
        // compatibility; video: fixed at the wallpaper's own FPS)
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                val general = WeEnginePrefs.readGeneralSettings(context)
                var maxFps = general.maxFps
                var compatibility = 0
                if (glView is VideoWallpaperView) {
                    maxFps = WeEnginePrefs.getWallpaperFPS(
                        context, EngineLibrary.sceneLib(context), wallpaper.filePath)
                    compatibility = 1
                }
                if (maxFps > 0) {
                    (glView as? GLSurfaceView)?.holder?.surface
                        ?.setFrameRate(maxFps.toFloat(), compatibility, 1)
                }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenHpx = constraints.maxHeight.toFloat()
        val statusBarPx = with(density) {
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx()
        }
        val navBarPx = with(density) {
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().toPx()
        }
        val clampedMin =
            if (screenHpx > 0f) (statusBarPx / screenHpx).coerceAtLeast(0.04f) else 0.04f
        val clampedMax =
            if (screenHpx > 0f) (1f - navBarPx / screenHpx).coerceAtMost(1f) else 1f

        // ===================== The renderer =====================
        val configuration = LocalConfiguration.current
        val matchPreview = WeEnginePrefs.getStoredBoolean(
            context, WallpaperProperties.GENERAL_PROP_MATCH_PREVIEW, true)
        // recompute the display rotation when the device turns
        val rotation = remember(configuration.orientation) {
            runCatching {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay?.rotation ?: 0
            }.getOrDefault(0)
        }
        LaunchedEffect(rotation, controls) {
            controls?.setScreenRotation(rotation)
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            // The renderer surface: aspect-matched to the physical screen
            // while "match preview" is on (official updateAspectRatio), or
            // free otherwise.
            val rendererModifier = if (matchPreview && constraints.maxHeight > 0) {
                val aspect = constraints.maxWidth.toFloat() / constraints.maxHeight.toFloat()
                Modifier.fillMaxWidth().aspectRatio(aspect)
            } else {
                Modifier.fillMaxSize()
            }
            Box(modifier = rendererModifier) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val type = try {
                            EngineLibrary.sceneLib(ctx).getWallpaperType(wallpaper.filePath)
                        } catch (t: Throwable) {
                            WepWawetLogger.engineWarn("Preview: getWallpaperType failed",
                                "exception" to (t.message ?: t.javaClass.name))
                            null
                        }
                        WepWawetLogger.engine("Preview: creating renderer view",
                            "type" to (type ?: "null → local/SAF player"))
                        val created: View = if (type == "Scene") {
                            SceneWallpaperView(ctx).also { sceneView ->
                                sceneView.onCreated(EngineLibrary.sceneLib(ctx))
                                controls = sceneView
                            }
                        } else {
                            VideoWallpaperView(ctx).also { videoView ->
                                videoView.setLocalPlayer(type == null)
                                videoView.lib = EngineLibrary.sceneLib(ctx)
                                controls = videoView
                            }
                        }
                        glView = created
                        // official onCreate: link parallax + rotation before load
                        if (created is WallpaperControls) {
                            parallaxController.linkWallpaper(created)
                            created.setScreenRotation(rotation)
                        }
                        created
                    },
                    update = { _ -> },
                    onRelease = { },
                )

                // Static preview bitmap behind the GL surface while loading.
                backgroundPreview?.let { bmp ->
                    AnimatedVisibility(
                        visible = showBackground,
                        exit = fadeOut(tween(300)),
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                if (!loaded) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }

        // ===================== Power-save banner =====================
        // (drawn inside the top-bar surface so it never slides under it)

        // ===================== Align overlay (first-run hint) =====================
        if (alignOverlayVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x661A1A20))
                    .clickable {
                        WeEnginePrefs.storeBoolean(
                            context, WeEnginePrefs.PREF_KEY_HAS_SHOWN_ALIGN_OVERLAY, true)
                        alignOverlayVisible = false
                    },
            ) {
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("‹", color = Color.White,
                        style = MaterialTheme.typography.displayMedium)
                    Text(
                        "Move wallpaper to align it",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Text("›", color = Color.White,
                        style = MaterialTheme.typography.displayMedium)
                }
            }
        }

        // ===================== Semi-clear top bar =====================
        Surface(
            color = Color(0x66000000),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                if (powerSavingActive) {
                    Text(
                        "Wallpaper Paused: Battery Saver Enabled",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showPowerSaveDialog = true }
                            .background(Color(0xCC1B1B20))
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                        start = 4.dp, end = 4.dp, bottom = 4.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    controls?.cancelLoadingWallpaper()
                    onBack()
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Text(
                    wallpaper.title.ifEmpty { wallpaper.filePath.substringAfterLast('/') },
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                workshopId?.let { id ->
                    IconButton(onClick = {
                        WepWawetLogger.engine("Preview: opening workshop page", "item_id" to id)
                        onOpenWorkshop(id)
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Workshop page",
                            tint = Color.White,
                        )
                    }
                }
                IconButton(onClick = { showDetails = true }) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = "Details",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { showPlaylistDialog = true }) {
                    Icon(
                        Icons.Filled.PlaylistAdd,
                        contentDescription = "Add to playlist",
                        tint = Color.White,
                    )
                }
                // Apply — long-press opens the official Apply/Revert dialog.
                // The check turns green once this wallpaper is the active one.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .combinedClickable(
                            onClick = { performApplyWallpaper() },
                            onLongClick = { showApplyRevert = true },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Apply wallpaper",
                        tint = if (applied) Color(0xFF7BD88F) else Color.White,
                    )
                }
            }
            }
        }

        // ===================== The swipe-up properties drawer =====================
        val fraction = drawerAnim.value
        val drawerHeightDp = with(density) {
            ((1f - fraction).coerceIn(0f, 1f) * screenHpx).toDp()
        }

        // The handle floats half above the drawer's top edge (official:
        // the CardView is centered on the guideline, bottom margin -10dp).
        val handleOverlap = 20.dp
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(drawerHeightDp + handleOverlap),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(drawerHeightDp),
            ) {
                // The 16dp accent strip (official wp_blue strip)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
                // The drawer body (official propertiesContainer: scroll
                // background at 0.9 alpha, bottom inset padding).
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    val controlsNow = controls
                    Box(
                        modifier = Modifier.padding(
                            bottom = WindowInsets.navigationBars.asPaddingValues()
                                .calculateBottomPadding()
                        )
                    ) {
                        if (controlsNow != null && loaded) {
                            WallpaperPropertyEditor(
                                controls = controlsNow,
                                filePath = wallpaper.filePath,
                                revision = propertiesRevision,
                                onPropertyChanged = { key, property ->
                                    val props = controlsNow.getWallpaperProperties()
                                    controlsNow.applyWallpaperProperties(Gson().toJson(props))
                                    parallaxController.applyProperties(props)
                                    // official changeCallback: the audio
                                    // recorder follows the audioprocessing
                                    // switch live (with the force permission
                                    // request when enabling).
                                    if (key == WallpaperProperties.PROP_AUDIO_PROCESSING) {
                                        setAudioActive(
                                            WeEnginePrefs.anyToBoolean(property.value, false))
                                    }
                                },
                                onAudioRecorderActive = { active -> setAudioActive(active) },
                            )
                        }
                    }
                }
            }
            // Drag handle: drag moves the guideline, tap toggles
            // (official 15px click threshold, 300ms toggle animation).
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .size(width = 72.dp, height = 40.dp)
                    .pointerInput(screenHpx, clampedMin, clampedMax) {
                        var totalDrag = 0f
                        detectVerticalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onDragEnd = {
                                if (abs(totalDrag) < 15f) {
                                    val target = if (drawerAnim.value < 0.9f) clampedMax else 0.5f
                                    scope.launch {
                                        drawerAnim.animateTo(target, tween(300))
                                    }
                                }
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            totalDrag += abs(dragAmount)
                            val newFraction = (drawerAnim.value + dragAmount / screenHpx)
                                .coerceIn(clampedMin, clampedMax)
                            scope.launch { drawerAnim.snapTo(newFraction) }
                        }
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.DragHandle,
                        contentDescription = "Properties",
                        tint = Color.White,
                    )
                }
            }
        }

        // ===================== Dialogs =====================

        if (showApplyRevert) {
            AlertDialog(
                onDismissRequest = { showApplyRevert = false },
                title = { Text("Change Wallpaper") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                showApplyRevert = false
                                applyNow(true)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Preview & Apply") }
                        Text(
                            "Apply options provided by device manufacturer",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                showApplyRevert = false
                                WeEnginePrefs.resetToSystemWallpaper(context)
                                WepWawetLogger.engine("Preview: reverted to system wallpaper")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Disable WallpaperSuite") }
                        Text(
                            "Revert to default Android wallpaper",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showApplyRevert = false }) { Text("Cancel") }
                },
            )
        }

        if (showLockscreenNotice) {
            AlertDialog(
                onDismissRequest = {
                    WeEnginePrefs.storeBoolean(
                        context, WeEnginePrefs.PREF_KEY_HAS_SHOWN_LOCKSCREEN_WARNING, true)
                    showLockscreenNotice = false
                    applyNow(false)
                },
                title = { Text("Lockscreen") },
                text = {
                    Text(
                        "Please read this:\nWhether or not you can set a live wallpaper on " +
                                "your lock screen is decided by your phone manufacturer, not " +
                                "WallpaperSuite.\n\nIf you do not get the option to set a " +
                                "lockscreen live wallpaper, then your phone does not support " +
                                "it.\n\nYou can long-press the apply button to bring up the " +
                                "upcoming selection screen again."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        WeEnginePrefs.storeBoolean(
                            context, WeEnginePrefs.PREF_KEY_HAS_SHOWN_LOCKSCREEN_WARNING, true)
                        showLockscreenNotice = false
                        applyNow(false)
                    }) { Text("OK") }
                },
            )
        }

        if (showDetails) {
            val d = details
            AlertDialog(
                onDismissRequest = { showDetails = false },
                title = { Text("Details") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DetailRow("Title", wallpaper.title.ifEmpty { "—" })
                        DetailRow("Filename", wallpaper.filePath.substringAfterLast('/'))
                        DetailRow("File size", d?.fileSize ?: "—")
                        workshopId?.let { DetailRow("Workshop ID", it) }
                        HorizontalRule()
                        DetailRow("Type", d?.type ?: "—")
                        DetailRow("Resolution", d?.resolution ?: "—")
                        if ((d?.fps ?: 0) > 0) DetailRow("FPS", d!!.fps.toString())
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDetails = false }) { Text("Close") }
                },
            )
        }

        if (showPlaylistDialog) {
            AlertDialog(
                onDismissRequest = { showPlaylistDialog = false },
                title = { Text("Playlist") },
                text = {
                    Text("Add \"${wallpaper.title.ifEmpty { "wallpaper" }}\" to the wallpaper playlist?")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    addWallpaperToPlaylist(context, wallpaper, true)
                                }
                                showPlaylistDialog = false
                            }
                        }
                    ) { Text("Add") }
                },
                dismissButton = {
                    TextButton(onClick = { showPlaylistDialog = false }) { Text("Cancel") }
                },
            )
        }

        if (showPowerSaveDialog) {
            AlertDialog(
                onDismissRequest = { showPowerSaveDialog = false },
                title = { Text("Battery Saver Enabled") },
                text = {
                    Text(
                        "Your phone is in power saving mode and WallpaperSuite will pause " +
                                "the live wallpaper to save battery power.\n\nYou can quit " +
                                "power saving mode on your phone or disable automatic pausing " +
                                "in the settings to resume the wallpaper."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showPowerSaveDialog = false
                        onOpenSettings()
                    }) { Text("Open Settings") }
                },
                dismissButton = {
                    TextButton(onClick = { showPowerSaveDialog = false }) { Text("OK") }
                },
            )
        }

        // ===================== Load + lifecycle =====================

        LaunchedEffect(controls, wallpaper.filePath) {
            val c = controls ?: return@LaunchedEffect
            refreshAppliedState()
            val general = withContext(Dispatchers.IO) {
                WeEnginePrefs.readGeneralSettings(context)
            }
            c.setGeneralSettings(general)
            // official: engine file logging follows the general setting
            runCatching {
                EngineLibrary.sceneLib(context).setLogToFileEnabled(
                    WeClientLog.isLogToFileEnabled())
            }
            c.setLoadCallback { onLoaded() }
            // static preview bitmap for the loading fade
            backgroundPreview = withContext(Dispatchers.IO) {
                runCatching {
                    WeEnginePrefs.getWallpaperPreviewBitmap(
                        context, EngineLibrary.sceneLib(context), wallpaper)
                }.getOrNull()
            }
            c.loadWallpaper(wallpaper.filePath)
            // details (resolved off the main thread)
            details = withContext(Dispatchers.IO) {
                val lib = EngineLibrary.sceneLib(context)
                val type = WeEnginePrefs.getWallpaperType(context, lib, wallpaper) ?: "—"
                val resolution = WeEnginePrefs.getWallpaperResolution(context, lib, wallpaper)
                val fps = WeEnginePrefs.getWallpaperFPS(context, lib, wallpaper.filePath)
                val workshopUrl = runCatching {
                    if (!wallpaper.isLocal) {
                        lib.getWallpaperProjectString(wallpaper.filePath, "workshopurl")
                    } else null
                }.getOrNull()
                val resolvedId = workshopUrl?.let { url ->
                    Regex("[?&]id=(\\d+)").find(url)?.groupValues?.get(1)
                } ?: Regex("^downloads/(\\d{6,}) - ").find(wallpaper.filePath)?.groupValues?.get(1)
                workshopId = resolvedId
                if (resolvedId == null) {
                    WepWawetLogger.engine(
                        "Preview: no workshop page for this wallpaper",
                        "file" to wallpaper.filePath,
                        "reason" to "no workshopurl in project.json and no item id in the filename")
                }
                val sizeBytes = if (!wallpaper.isLocal) {
                    runCatching {
                        File(context.filesDir, wallpaper.filePath).length()
                    }.getOrDefault(0L)
                } else 0L
                WallpaperDetails(
                    type = type,
                    resolution = resolution?.let { "${it.x} × ${it.y}" } ?: "—",
                    fps = fps,
                    fileSize = if (sizeBytes > 0) formatFileSize(sizeBytes) else "—",
                )
            }
        }

        // Renderer teardown: keyed on the wallpaper identity (the v0.8.2
        // blank-preview fix — see worklog; never key on the view itself).
        DisposableEffect(wallpaper.filePath) {
            onDispose {
                WepWawetLogger.engine("Preview: leaving — stopping renderer",
                    "file" to wallpaper.filePath)
                controls?.onStopWallpaper()
                controls?.onDestroyWallpaper()
            }
        }

        // Official lifecycle parity: pause/resume the renderer, stop the
        // scene in onStop, re-load on return, store properties on pause.
        var stoppedAfterLoad by remember { mutableStateOf(false) }
        DisposableEffect(lifecycleOwner) {
            val observer = object : DefaultLifecycleObserver {
                override fun onPause(owner: LifecycleOwner) {
                    controls?.onPauseWallpaper()
                    parallaxController.pause()
                    audioRecorder.stopAudioListener()
                    val props = controls?.getWallpaperProperties()
                    if (props != null && props.isNotEmpty()) {
                        val json = Gson().toJson(props)
                        WeEnginePrefs.storeWallpaperProperties(context, wallpaper.filePath, json)
                        val service = WEWallpaperService.instance
                        if (service != null &&
                            service.getCurrentWallpaperFile() == wallpaper.filePath
                        ) {
                            service.requestWallpaperApplyStoredProperties()
                        }
                    }
                }

                override fun onResume(owner: LifecycleOwner) {
                    controls?.onResumeWallpaper()
                    parallaxController.resume()
                    refreshAppliedState()
                }

                override fun onStop(owner: LifecycleOwner) {
                    if (controls != null) {
                        stoppedAfterLoad = true
                        WepWawetLogger.engine("Preview: stopping renderer (screen hidden)")
                        controls?.onStopWallpaper()
                        audioRecorder.stopAudioListener()
                    }
                }

                override fun onStart(owner: LifecycleOwner) {
                    if (stoppedAfterLoad && controls != null) {
                        stoppedAfterLoad = false
                        loaded = false
                        showBackground = true
                        WepWawetLogger.engine("Preview: returning — reloading renderer")
                        controls?.loadWallpaper(wallpaper.filePath)
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                parallaxController.pause()
                audioRecorder.stopAudioListener()
            }
        }

        // Official power-saving receiver registration
        DisposableEffect(Unit) {
            val receiver = WeEnginePrefs.makePowerSavingReceiver { active ->
                val general = runCatching {
                    WeEnginePrefs.readGeneralSettings(context)
                }.getOrNull()
                powerSavingActive = active && (general?.powerSavingEnabled ?: true)
            }
            val filter = IntentFilter().apply {
                addAction("android.os.action.POWER_SAVE_MODE_CHANGED")
            }
            runCatching {
                ContextCompat.registerReceiver(
                    context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            }
            onDispose {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }

        // AudioRecorder listener → engine
        DisposableEffect(Unit) {
            audioRecorder.listener = AudioRecorder.Listener { fft ->
                controls?.sendAudioData(fft)
            }
            onDispose {
                audioRecorder.stopAudioListener()
            }
        }
    }
}

/** Data holder for the details dialog. */
private data class WallpaperDetails(
    val type: String,
    val resolution: String,
    val fps: Int,
    val fileSize: String,
)

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun HorizontalRule() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/**
 * The official addWallpaperToPlaylist: enforces the day-of-week 7-item cap,
 * rebalances the last item's duration via computeNewPlaylistItemLength and
 * appends the new entry.
 */
private fun addWallpaperToPlaylist(
    context: Context,
    wallpaper: WallpaperInfoSparse,
    showError: Boolean,
) {
    val playlist = WeEnginePrefs.readPlaylist(context)
    if (playlist.mode == WallpaperProperties.PLAYLIST_MODE_DAY_OF_WEEK &&
        playlist.wallpapers.size >= 7
    ) {
        if (showError) {
            WepWawetLogger.engineWarn("Playlist: cannot add — day-of-week playlist is full (7)")
        }
        return
    }
    val wallpapers = playlist.wallpapers
    val allButLast = wallpapers.take(maxOf(0, wallpapers.size - 1))
        .sumOf { it.duration }
    val newLength = WeEnginePrefs.computeNewPlaylistItemLength(allButLast, 1)
    if (wallpapers.isNotEmpty()) {
        wallpapers[wallpapers.size - 1].duration = newLength
    }
    val entry = PlaylistDataWallpaper()
    entry.filepath = wallpaper.filePath
    entry.duration = newLength
    wallpapers.add(entry)
    WeEnginePrefs.storePlaylist(context, playlist)
    WepWawetLogger.engine("Playlist: wallpaper added",
        "file" to wallpaper.filePath,
        "playlist_size" to wallpapers.size)
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
