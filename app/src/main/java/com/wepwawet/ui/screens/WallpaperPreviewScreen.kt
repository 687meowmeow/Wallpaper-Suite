package com.wepwawet.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Point
import android.opengl.GLSurfaceView
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.wepwawet.engine.EngineLibrary
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.components.SlimTopBar
import io.wallpaperengine.weclient.WeEnginePrefs
import io.wallpaperengine.weclient.WallpaperProperties
import io.wallpaperengine.wedata.Property
import io.wallpaperengine.weviews.SceneWallpaperView
import io.wallpaperengine.weviews.VideoWallpaperView
import io.wallpaperengine.weviews.WallpaperControls
import io.wallpaperengine.weutil.WallpaperInfoSparse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live-rendering preview of a library wallpaper with Apply, per-wallpaper
 * properties, file details and playlist management — the Compose
 * replacement for the official client's PreviewActivity.
 *
 * v0.8.1: every lifecycle step around the integrated engine logs through
 * [com.wepwawet.log.WepWawetLogger.engine] (the WEP-ENG domain) so blank/
 * black-render issues are diagnosable from the in-app Logs screen; chatty
 * entries are DEBUG-level and only appear while the verbose-logging toggle
 * is ON. When the wallpaper's project.json carries a workshopurl (every
 * wallpaper downloaded through the app does), a "Workshop page" button
 * opens the item's in-app workshop detail page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperPreviewScreen(
    wallpaper: WallpaperInfoSparse,
    onBack: () -> Unit,
    onOpenWorkshop: (itemId: String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var showProperties by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf(
        EngineLibrary.getSelectedWallpaper(context) == wallpaper.filePath
    ) }
    var details by remember { mutableStateOf<Triple<String, String, Int>?>(null) }
    var workshopId by remember { mutableStateOf<String?>(null) }

    // The live renderer: Scene through the native engine, everything else
    // (Video/Image/GIF) through the ported GL video pipeline.
    var controls by remember { mutableStateOf<WallpaperControls?>(null) }
    var view by remember { mutableStateOf<View?>(null) }
    var propertiesRevision by remember { mutableStateOf(0) }

    WepWawetLogger.engine("Preview: opened wallpaper",
        "title" to wallpaper.title,
        "file" to wallpaper.filePath,
        "type" to (wallpaper.type.ifEmpty { "(unknown)" }),
        "is_local" to wallpaper.isLocal,
        "is_official" to wallpaper.isOfficial)

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* the engine retries the Visualizer on next load */ }

    fun applyNow(forcePicker: Boolean) {
        scope.launch {
            WepWawetLogger.engine("Preview: apply requested",
                "file" to wallpaper.filePath,
                "force_picker" to forcePicker)
            val hotReloaded = WeEnginePrefs.callApplyWallpaper(context, wallpaper, forcePicker)
            applied = true
            if (!hotReloaded) {
                snackbar.showSnackbar("Finish choosing WallpaperSuite in the wallpaper picker")
            } else {
                WepWawetLogger.engine("Preview: wallpaper hot-reloaded in the running service")
            }
        }
    }

    fun onLoaded() {
        WepWawetLogger.engine("Preview: wallpaper finished loading (load callback fired)")
        val c = controls ?: return
        // Audio-responsive wallpapers need the microphone permission for the
        // system-mix Visualizer (Android 10+ requirement).
        val props = c.getWallpaperProperties()
        val wantsAudio = props[WallpaperProperties.PROP_AUDIO_PROCESSING]?.let {
            WeEnginePrefs.anyToBoolean(it.value, false)
        } ?: false
        if (c.isSupportingAudioProcessing() && wantsAudio) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        propertiesRevision++
    }

    Scaffold(
        topBar = {
            SlimTopBar(
                title = wallpaper.title.ifEmpty { wallpaper.filePath.substringAfterLast('/') },
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showPlaylistDialog = true }) {
                        Icon(
                            Icons.Filled.PlaylistAdd,
                            contentDescription = "Add to playlist",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { showProperties = true }) {
                        Text("⚙", style = MaterialTheme.typography.titleMedium)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
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
                                // v0.8.1 FIX (blank preview bug): the official
                                // PreviewActivity calls setLib(this.lib) — the
                                // package-video byte-range lookup needs it.
                                videoView.lib = EngineLibrary.sceneLib(ctx)
                                controls = videoView
                            }
                        }
                        view = created
                        created
                    },
                    update = { _ -> },
                    onRelease = { },
                )
            }

            LaunchedEffect(controls) {
                val c = controls ?: return@LaunchedEffect
                c.setGeneralSettings(WeEnginePrefs.readGeneralSettings(context))
                c.setLoadCallback { onLoaded() }
                c.loadWallpaper(wallpaper.filePath)
                // details
                details = withContext(Dispatchers.IO) {
                    val lib = EngineLibrary.sceneLib(context)
                    val type = WeEnginePrefs.getWallpaperType(context, lib, wallpaper) ?: "—"
                    val resolution = WeEnginePrefs.getWallpaperResolution(context, lib, wallpaper)
                    val fps = WeEnginePrefs.getWallpaperFPS(context, lib, wallpaper.filePath)
                    // v0.8.1: resolve the workshop page (only exists for
                    // wallpapers that carry a workshopurl in project.json —
                    // every workshop download through this app does).
                    val workshopUrl = runCatching {
                        if (!wallpaper.isLocal) {
                            lib.getWallpaperProjectString(wallpaper.filePath, "workshopurl")
                        } else null
                    }.getOrNull()
                    workshopId = workshopUrl?.let { url ->
                        Regex("[?&]id=(\\d+)").find(url)?.groupValues?.get(1)
                    }
                    if (workshopId == null) {
                        WepWawetLogger.engineDebug("Preview: no workshop url for this wallpaper")
                    } else {
                        WepWawetLogger.engine("Preview: workshop page available",
                            "item_id" to workshopId!!)
                    }
                    Triple(
                        type,
                        resolution?.let { "${it.x} × ${it.y}" } ?: "—",
                        fps,
                    )
                }
            }

            DisposableEffect(view) {
                onDispose {
                    WepWawetLogger.engine("Preview: leaving — stopping renderer")
                    controls?.onStopWallpaper()
                    controls?.onDestroyWallpaper()
                }
            }

            details?.let { (type, resolution, fps) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    DetailPill("Type", type)
                    DetailPill("Resolution", resolution)
                    if (fps > 0) {
                        DetailPill("FPS", fps.toString())
                    }
                }
            }

            // v0.8.1: jump to this wallpaper's workshop page inside the app
            // (the in-app workshop detail screen) when its package carries a
            // workshopurl — true for everything downloaded through the app.
            workshopId?.let { id ->
                OutlinedButton(
                    onClick = {
                        WepWawetLogger.engine("Preview: opening workshop page", "item_id" to id)
                        onOpenWorkshop(id)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.height(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Workshop page")
                }
            }

            Button(
                onClick = { applyNow(false) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(if (applied) "Re-apply as wallpaper" else "Set as wallpaper")
            }
        }
    }

    if (showProperties) {
        val controlsNow = controls
        if (controlsNow != null) {
            ModalBottomSheet(onDismissRequest = { showProperties = false }) {
                WallpaperPropertyEditor(
                    controls = controlsNow,
                    filePath = wallpaper.filePath,
                    revision = propertiesRevision,
                )
            }
        }
    }

    if (showPlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showPlaylistDialog = false },
            title = { Text("Playlist") },
            text = { Text("Add \"${wallpaper.title.ifEmpty { "wallpaper" }}\" to the wallpaper playlist?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val playlist = WeEnginePrefs.readPlaylist(context)
                                val entry = io.wallpaperengine.wedata.PlaylistDataWallpaper()
                                entry.filepath = wallpaper.filePath
                                entry.duration = 60
                                playlist.wallpapers.removeAll { it.filepath == wallpaper.filePath }
                                playlist.wallpapers.add(entry)
                                WeEnginePrefs.storePlaylist(context, playlist)
                            }
                            showPlaylistDialog = false
                            snackbar.showSnackbar("Added to playlist")
                        }
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showPlaylistDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DetailPill(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Per-wallpaper property editor driven by the same Property JSON the engine
 * uses (bool / slider / combo / color / textinput), including `condition`
 * visibility rules ("otherprop.value").
 */
@Composable
fun WallpaperPropertyEditor(
    controls: WallpaperControls,
    filePath: String,
    revision: Int,
) {
    val context = LocalContext.current
    var properties by remember(revision) {
        mutableStateOf(controls.getWallpaperProperties())
    }

    fun commit() {
        val json = Gson().toJson(properties)
        WeEnginePrefs.storeWallpaperProperties(context, filePath, json)
        controls.applyWallpaperProperties(json)
    }

    fun isVisible(prop: Property): Boolean {
        val condition = prop.condition
        if (condition.isEmpty() || condition == "false") return condition.isEmpty()
        // conditions look like "wec_e.value" — visible when that property is truthy
        val key = condition.substringBefore(".")
        val referenced = properties[key] ?: return true
        return WeEnginePrefs.anyToBoolean(referenced.value, false)
    }

    val visible = properties.values
        .filter { isVisible(it) }
        .sortedBy { it.order ?: 0 }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Properties", style = MaterialTheme.typography.titleMedium)
        for (property in visible) {
            when (property.type) {
                "bool" -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(property.displayLabel())
                        Switch(
                            checked = WeEnginePrefs.anyToBoolean(property.value, false),
                            onCheckedChange = { checked ->
                                property.value = checked
                                properties = HashMap(properties)
                                commit()
                            },
                        )
                    }
                }
                "slider" -> {
                    val min = WeEnginePrefs.anyToFloat(property.min, 0f)
                    val max = WeEnginePrefs.anyToFloat(property.max, 100f)
                    val value = WeEnginePrefs.anyToFloat(property.value, min).coerceIn(min, max)
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(property.displayLabel())
                            Text("%.0f".format(value), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(
                            value = value,
                            onValueChange = { newValue ->
                                property.value = newValue.toDouble()
                                properties = HashMap(properties)
                            },
                            onValueChangeFinished = { commit() },
                            valueRange = min..max,
                        )
                    }
                }
                "combo" -> {
                    Column {
                        Text(property.displayLabel())
                        Spacer(Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            for (option in property.options) {
                                FilterChip(
                                    selected = WeEnginePrefs.anyToInt(property.value) ==
                                            WeEnginePrefs.anyToInt(option.value),
                                    onClick = {
                                        property.value = option.value
                                        properties = HashMap(properties)
                                        commit()
                                    },
                                    label = { Text(option.label.substringAfterLast('_')) },
                                )
                            }
                        }
                    }
                }
                "color" -> {
                    val rgb = (property.value?.toString() ?: "0 0 0")
                        .trim().split(Regex("\\s+"))
                    Column {
                        Text(property.displayLabel())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = androidx.compose.ui.graphics.Color(
                                    red = rgb.getOrNull(0)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f,
                                    green = rgb.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f,
                                    blue = rgb.getOrNull(2)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f,
                                    alpha = 1f,
                                ),
                                modifier = Modifier
                                    .width(40.dp)
                                    .height(24.dp),
                            ) {}
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                // cycle the scheme color through a few presets
                                val presets = listOf("0 0 0", "1 1 1", "0.2 0.2 0.2", "0 0 0.2")
                                val idx = presets.indexOf(property.value?.toString() ?: "0 0 0")
                                property.value = presets[(idx + 1) % presets.size]
                                properties = HashMap(properties)
                                commit()
                            }) { Text("Change") }
                        }
                    }
                }
                "textinput" -> {
                    // internal alignment matrices — not user-editable here
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun Property.displayLabel(): String = when {
    text.isNotEmpty() && !text.startsWith("ui_") -> text
    text.contains("parallax_strength") -> "Parallax strength"
    text.contains("playback_rate") -> "Playback rate"
    text.contains("audio_recording") -> "Audio visualization"
    text.contains("brightness") -> "Brightness"
    text.contains("contrast") -> "Contrast"
    text.contains("saturation") -> "Saturation"
    text.contains("hue_shift") -> "Hue shift"
    text.contains("show_color_options") -> "Show color options"
    text.contains("background_color") -> "Background color"
    text.contains("pixel_art") -> "Pixel art optimization"
    text.contains("alignment_flip") -> "Flip horizontally"
    text.contains("alignment") -> "Alignment"
    text.contains("parallax") -> "Parallax"
    else -> text.substringAfterLast('_').replaceFirstChar { it.uppercase() }
}
