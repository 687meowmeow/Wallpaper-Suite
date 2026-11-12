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
import androidx.compose.ui.draw.clip
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
import androidx.compose.material3.IconButton
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
    // v0.8.11: "Manage" in the playlist picker jumps to the playlist manager.
    onOpenPlaylists: () -> Unit = {},
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
    // v0.8.9: the live GL scene rendered nothing but black (see
    // SceneWallpaperView.setBlackFrameCallback) — the static preview is
    // re-shown with a retry chip instead of leaving a black screen.
    var livePreviewBlack by remember { mutableStateOf(false) }

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

    // ----- v0.8.13: STATIC IMAGE support -----
    // "normal images should not be handled by the wallpaper engine
    // renderer": a local/imported image (type "Image") gets a plain
    // bitmap preview and a system-wallpaper apply (WallpaperManager
    // setBitmap underneath) — no SceneWallpaperView / VideoWallpaperView,
    // no GL surface, no engine.
    // `resolvedType` is computed off the main thread once (the native
    // getWallpaperType is a JNI metadata read; the MIME fallback needs a
    // resolver round-trip). While null we keep the (fast) synchronous
    // hint from the library enumeration, wallpaper.type.
    var resolvedType by remember(wallpaper.filePath) { mutableStateOf<String?>(null) }
    val isImageWallpaper = wallpaper.type == "Image" || resolvedType == "Image"
    var imageBitmap by remember(wallpaper.filePath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(wallpaper.filePath) {
        resolvedType = withContext(Dispatchers.IO) {
            runCatching {
                WeEnginePrefs.getWallpaperType(context, EngineLibrary.sceneLib(context), wallpaper)
            }.getOrNull()
        }
        WepWawetLogger.engine("Preview: media kind resolved",
            "type" to (resolvedType ?: "null"),
            "library_type" to wallpaper.type,
            "is_local" to wallpaper.isLocal)
    }
    LaunchedEffect(wallpaper.filePath, resolvedType) {
        if (resolvedType != null && resolvedType != "Image") return@LaunchedEffect
        if (wallpaper.type != "Image" && resolvedType != "Image") return@LaunchedEffect
        // Decode the still image for the plain preview (skip when the sync
        // hint already triggered a decode in a previous run of this effect).
        if (imageBitmap == null) {
            WepWawetLogger.engine("Preview: static image — plain renderer (no engine)")
            imageBitmap = withContext(Dispatchers.IO) {
                ImageFolderAssets.decodeBitmapScaled(context, wallpaper.filePath, 2048)
            }
            if (imageBitmap != null) {
                loaded = true
            } else {
                WepWawetLogger.engineWarn("Preview: image decode failed",
                    "file" to wallpaper.filePath.takeLast(80))
            }
        }
        // Details for images (the engine-backed details loader below is
        // keyed on `controls`, which stays null for images).
        if (details == null) {
            details = withContext(Dispatchers.IO) {
                val lib = EngineLibrary.sceneLib(context)
                val type = WeEnginePrefs.getWallpaperType(context, lib, wallpaper) ?: "Image"
                val resolution = WeEnginePrefs.getWallpaperResolution(context, lib, wallpaper)
                val sizeBytes = try {
                    context.contentResolver.openAssetFileDescriptor(
                        android.net.Uri.parse(wallpaper.filePath), "r")?.use { it.length } ?: 0L
                } catch (t: Throwable) { 0L }
                WallpaperDetails(
                    type = type,
                    resolution = resolution?.let { "${it.x} × ${it.y}" } ?: "—",
                    fps = 0,
                    fileSize = if (sizeBytes > 0) formatFileSize(sizeBytes) else "—",
                )
            }
        }
    }

    // The official drawer guideline: 0.98 = closed, 0.5 = open (tap),
    // clamped to [topInset/height .. 1 - bottomInset/height].
    val drawerAnim = remember { Animatable(0.98f) }

    // ----- The texture-pick bridge (official relinkPreferences) -----
    // Returning from the SAF picker replays the activity lifecycle
    // (onStop → onRestart → loadWallpaper), which REBUILDS the engine's
    // property map from the values stored at onPause — a pick made while
    // the picker was open would be lost. The official client bridges it:
    // the FilePickerPreference keeps the picked value and
    // relinkPreferences re-applies it to the fresh map after the reload.
    // This map is that bridge — it lives at SCREEN level so it survives
    // the drawer content unmounting during the reload.
    val pendingTexturePicks = remember {
        androidx.compose.runtime.mutableStateMapOf<String, Pair<String, String>>()
    }

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
                // v0.8.11: the active suite playlist drives the rotation;
                // fall back to the official single playlist when none is
                // active (legacy installs).
                val suiteActive = com.wepwawet.playlist.SuitePlaylistStore
                    .activePlaylist(context)
                val official = WeEnginePrefs.readPlaylist(context)
                val playlistLive = (suiteActive != null && suiteActive.entries.isNotEmpty()) ||
                    official.wallpapers.isNotEmpty()
                val service = WEWallpaperService.instance
                val live = service?.isLiveWallpaperActive == true
                playlistActive = playlistLive && live
                playlistActiveWithoutService = playlistLive && !live
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
            "force_picker" to forcePicker,
            "is_image" to isImageWallpaper)
        scope.launch {
            // v0.8.13: static images are set as the plain SYSTEM wallpaper
            // (decode → screen crop → setBitmap). They never go through
            // the Wallpaper Engine live-wallpaper machinery.
            if (isImageWallpaper) {
                val ok = withContext(Dispatchers.IO) {
                    val bmp = ImageFolderAssets.decodeBitmapScaled(context, wallpaper.filePath, 4096)
                        ?: return@withContext false
                    val crop = com.wepwawet.nyanpasu.NyanpasuApplier.cropForScreen(context, bmp)
                    com.wepwawet.nyanpasu.NyanpasuApplier.apply(
                        context, crop, homeOn = true, lockOn = true).ok
                }
                applied = ok
                WepWawetLogger.engine("Preview: image applied as system wallpaper",
                    "ok" to ok)
                android.widget.Toast.makeText(
                    context,
                    if (ok) "Wallpaper set" else "Could not set the wallpaper",
                    android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
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
        // v0.8.13: skip the first-apply lockscreen notice when our live
        // wallpaper is ALREADY the system's active one (the system-level
        // switch already happened — we are only swapping content) and for
        // plain images (system wallpaper, no live wallpaper involved).
        val alreadyAppliedLive = (service != null && service.isLiveWallpaperActive) ||
            WeEnginePrefs.isOurLiveWallpaperActive(context)
        if (!alreadyAppliedLive && !isImageWallpaper) {
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
        // v0.8.6: DON'T hide the static preview bitmap here — the GL
        // surface hasn't drawn its first frame yet (the load callback
        // fires at the end of loadScene, BEFORE the first onDrawFrame).
        // Hiding the bitmap here showed a black screen for ~100-500ms
        // until the first GL frame was drawn (the "preview is black"
        // bug). Instead, register a first-frame callback that hides the
        // bitmap once the GL surface has actually drawn something. For
        // video wallpapers (which don't have a first-frame callback), we
        // fall back to hiding the bitmap immediately — videos display
        // their first frame quickly via the media player.
        val sceneView = c as? io.wallpaperengine.weviews.SceneWallpaperView
        if (sceneView != null) {
            sceneView.setFirstFrameCallback(Runnable {
                WepWawetLogger.engine("Preview: first GL frame drawn — hiding static preview")
                showBackground = false
            })
            // v0.8.9: if the scene still renders only black ~3 s in (e.g.
            // Brown Dust x-ray's TEXI-flag-0x20 textures render fine as
            // the applied wallpaper but black in the preview surface),
            // fall back to the static preview image with a retry chip —
            // the wallpaper itself is unaffected.
            sceneView.setBlackFrameCallback(Runnable {
                if (backgroundPreview != null && !livePreviewBlack) {
                    WepWawetLogger.engine("Preview: live scene renders black — showing static preview")
                    livePreviewBlack = true
                    showBackground = true
                }
            })
        } else {
            // Video wallpaper — no first-frame callback. Hide the bitmap
            // immediately (the video player's first frame appears within
            // a frame or two of the load callback).
            showBackground = false
        }
        parallaxController.applyProperties(c.getWallpaperProperties())
        // The official relinkPreferences bridge: re-apply any texture picked
        // while the SAF picker was open to the freshly rebuilt property map
        // and push it to the engine (a plain reload would revert it).
        if (pendingTexturePicks.isNotEmpty()) {
            val fresh = c.getWallpaperProperties()
            var changed = false
            for ((pickKey, pick) in pendingTexturePicks) {
                val prop = fresh[pickKey] ?: continue
                if (prop.value != pick.first) {
                    prop.value = pick.first
                    prop.valueLabel = pick.second
                    changed = true
                }
            }
            pendingTexturePicks.clear()
            if (changed) {
                WepWawetLogger.engine("Preview: re-linked picked texture after reload")
                c.applyWallpaperProperties(Gson().toJson(fresh))
                parallaxController.applyProperties(fresh)
            }
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
        // Hoisted out of the nested Box — BoxWithConstraints' `constraints`
        // is an extension property that cannot be resolved through the
        // nested composable's content lambda.
        val screenAspect =
            if (constraints.maxHeight > 0)
                constraints.maxWidth.toFloat() / constraints.maxHeight.toFloat()
            else 1f

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
            val rendererModifier = if (matchPreview && screenAspect > 0f) {
                Modifier.fillMaxWidth().aspectRatio(screenAspect)
            } else {
                Modifier.fillMaxSize()
            }
            Box(modifier = rendererModifier) {
                // v0.8.13: STATIC IMAGE — a plain Compose Image, NOT the
                // engine. No SceneWallpaperView/VideoWallpaperView is
                // created at all, so no GL surface, no engine context and
                // no load-callback machinery runs for image wallpapers.
                if (isImageWallpaper) {
                    val bmp = imageBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = wallpaper.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Loading image…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
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
                // v0.8.9: black live-render fallback chip — tap to give the
                // GL scene another chance (it keeps running underneath).
                if (livePreviewBlack) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xCC1B1B20))
                            .clickable {
                                livePreviewBlack = false
                                showBackground = false
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Live preview shows nothing — tap to retry",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
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
        }

        // ===================== Power-save banner =====================
        // (drawn inside the top-bar surface so it never slides under it)

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
        // v0.8.13: hidden for static images — there are no engine
        // properties to edit on a plain bitmap.
        if (!isImageWallpaper) {
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
                                onPropertiesChanged = { key, _ , all ->
                                    // Official changeCallback (initializeWallpaper
                                    // Properties$lambda$21): serialize the LIVE map
                                    // (no JNI re-fetch per change) and apply.
                                    controlsNow.applyWallpaperProperties(Gson().toJson(all))
                                    parallaxController.applyProperties(all)
                                    // official changeCallback: the audio recorder
                                    // follows the audioprocessing switch live.
                                    if (key == WallpaperProperties.PROP_AUDIO_PROCESSING) {
                                        setAudioActive(
                                            WeEnginePrefs.anyToBoolean(
                                                all[WallpaperProperties.PROP_AUDIO_PROCESSING]?.value, false))
                                    }
                                },
                                onAudioRecorderActive = { active -> setAudioActive(active) },
                                onTexturePicked = { key, uri, name ->
                                    // Official FilePickerPreference bridge: keep the
                                    // pick across the reload that returning from the
                                    // SAF picker triggers, and persist immediately
                                    // (the reload reads the stored values; the
                                    // onLoaded relink covers the other ordering).
                                    pendingTexturePicks[key] = uri to name
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            val props = controls?.getWallpaperProperties()
                                            if (props != null && props.isNotEmpty()) {
                                                WeEnginePrefs.storeWallpaperProperties(
                                                    context, wallpaper.filePath,
                                                    Gson().toJson(props))
                                            }
                                        }
                                    }
                                },
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
            // v0.8.11: the shared suite-playlist picker (saved + editable
            // playlists) — replaces the old single-playlist confirm dialog.
            com.wepwawet.playlist.PlaylistPickerDialog(
                wallpaperTitle = wallpaper.title.ifEmpty {
                    wallpaper.filePath.substringAfterLast('/')
                },
                onDismiss = { showPlaylistDialog = false },
                onManagePlaylists = {
                    showPlaylistDialog = false
                    onOpenPlaylists()
                },
                onPicked = { playlistId ->
                    val path = wallpaper.filePath
                    val title = wallpaper.title.ifEmpty { path.substringAfterLast('/') }
                    val type = wallpaper.type
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            com.wepwawet.playlist.SuitePlaylistStore.addEntry(
                                context, playlistId,
                                com.wepwawet.playlist.SuitePlaylistStore.Entry(path, title, type))
                        }
                        showPlaylistDialog = false
                    }
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
            // v0.8.6: ensure the engine's log-to-file is on during preview
            // too (so shader compile errors / texture load failures during
            // preview are captured by EngineLogTailer and show up in the
            // in-app Logs screen). The wallpaper service sets this in
            // onCreate, but the preview runs in the activity — the engine
            // instance is shared (singleton), so this is a no-op if already
            // on, and a one-time enable if the user opens preview before
            // ever starting the wallpaper service.
            runCatching {
                // v0.8.8: create filesDir/logs/ before enabling the engine's
                // file logging — without the directory the engine's writes
                // silently fail (EngineLogTailer then has nothing to tail).
                EngineLibrary.ensureEngineLogDir(context)
                EngineLibrary.sceneLib(context).setLogToFileEnabled(true)
            }
            c.setLoadCallback { onLoaded() }
            // static preview bitmap for the loading fade
            backgroundPreview = withContext(Dispatchers.IO) {
                runCatching {
                    WeEnginePrefs.getWallpaperPreviewBitmap(
                        context, EngineLibrary.sceneLib(context), wallpaper)
                }.getOrNull()
            }
            // v0.8.9: reset the black-fallback state for this load.
            livePreviewBlack = false
            showBackground = true
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

        // AudioRecorder listener → engine (Kotlin interface — object
        // expression, not SAM syntax)
        DisposableEffect(Unit) {
            audioRecorder.listener = object : AudioRecorder.Listener {
                override fun onAudioData(fft: FloatArray) {
                    controls?.sendAudioData(fft)
                }
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
