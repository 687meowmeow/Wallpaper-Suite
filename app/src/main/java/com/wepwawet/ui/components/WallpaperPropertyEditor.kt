package com.wepwawet.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.wepwawet.engine.EngineLibrary
import com.wepwawet.log.WepWawetLogger
import io.wallpaperengine.weclient.WallpaperProperties
import io.wallpaperengine.weclient.WeEnginePrefs
import io.wallpaperengine.wedata.Property
import io.wallpaperengine.wedata.PropertyList
import io.wallpaperengine.weviews.WallpaperControls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The wallpaper properties panel — a Compose rebuild of the official
 * client's `io.wallpaperengine.weclient.WallpaperProperties`
 * PreferenceFragment, shown in the preview's swipe-up drawer.
 *
 * Parity notes (all verified against the decompiled v2.8.8 client):
 *  - Titles are localized exactly like `localizeText`: the wallpaper's own
 *    user localizations (`lib.getUserLocalizations`) first, then the
 *    engine's locale table (`lib.getLocalization`), and plain (non-token)
 *    author titles pass through as-is; HTML tags are stripped like
 *    `Html.fromHtml(...).toString()`.
 *  - Properties with `"condition": "false"` are never shown (the internal
 *    alignment matrices, orientation, and the hidden motion-response
 *    default).
 *  - Conditions like `"wec_e.value"` / `"wec_e.value == false"` drive
 *    visibility with the official regex and inversion rules.
 *  - Without a gyroscope the parallax properties are dropped and parallax
 *    is forced to 0 (official `reloadPreferences` behavior).
 *  - Sliders honor `precision` and apply LIVE on every tick
 *    (`updatesContinuously`) — through a LOCAL slider position so the drag
 *    itself never rebuilds the list (the v0.8.4 "sliders don't move
 *    smoothly" fix).
 *  - Combos are a radio dialog with localized entries (ListPreference).
 *  - Colors open a full picker and round-trip through the official
 *    normalizedVec3 ↔ color24 conversions.
 *  - `scenetexture` opens the system picker with the official
 *    ChooseUserTexture contract (EXCLUDE_SELF + OPENABLE, the exact mime
 *    list), takes the persistable URI permission, and validates the stream
 *    — exactly `onTextureChooserResult`.
 *  - Every change fires [onPropertiesChanged] with the LIVE property map —
 *    the parent performs the official changeCallback duties:
 *    `applyWallpaperProperties(Gson().toJson(map))`, parallax re-apply,
 *    audio recorder start/stop.
 *  - "Reset Wallpaper Settings" asks for confirmation and restores the
 *    engine-provided defaults (the official reset flow).
 *
 * v0.8.4 race fix: every change handler resolves the property through the
 * CURRENT map by key (never a captured `Property` object) — returning from
 * the SAF picker or a dialog reloads the wallpaper (onStop → onStart flow),
 * which REPLACES the engine's property map; a captured object would write
 * into the dead map and the change would silently do nothing (the
 * "picture options doesn't work" bug).
 *
 * @param revision bump whenever the wallpaper (re)loads so the editor
 *   re-reads the live property map.
 */
@Composable
fun WallpaperPropertyEditor(
    controls: WallpaperControls,
    filePath: String,
    revision: Int,
    onPropertiesChanged: (key: String, property: Property, all: HashMap<String, Property>) -> Unit,
    onAudioRecorderActive: (active: Boolean) -> Unit,
    onTexturePicked: (key: String, uri: String, name: String) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }          // structural rebuild driver
    var properties by remember(revision) { mutableStateOf(controls.getWallpaperProperties()) }
    var customLocalizations by remember(revision) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var hasGyroscope by remember { mutableStateOf(true) }

    // Dialogs
    var comboFor by remember { mutableStateOf<String?>(null) }
    var textFor by remember { mutableStateOf<String?>(null) }
    var colorFor by remember { mutableStateOf<String?>(null) }
    var resetConfirm by remember { mutableStateOf(false) }
    var audioPrompt by remember { mutableStateOf(false) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onAudioRecorderActive(true)
        } else {
            // denied after an explicit request: switch the property off
            val props = properties
            props[WallpaperProperties.PROP_AUDIO_PROCESSING]?.let { prop ->
                prop.value = false
                version++
                onPropertiesChanged(WallpaperProperties.PROP_AUDIO_PROCESSING, prop, props)
            }
        }
    }

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    // Official ensurePermissionAllowed(shouldRequest): "" = silent check,
    // "prompt" = the audio-responsive info dialog, "force" = direct request.
    fun ensurePermissionAllowed(shouldRequest: String): Boolean {
        if (hasAudioPermission()) return true
        when (shouldRequest) {
            "prompt" -> audioPrompt = true
            "force" -> audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        return false
    }

    // The official onCreatePreferences: locale + gyro check + user
    // localizations for this wallpaper.
    LaunchedEffect(revision) {
        val lib = EngineLibrary.sceneLib(context)
        runCatching { lib.setLanguage(Locale.getDefault().toLanguageTag()) }
            .onFailure {
                WepWawetLogger.appWarn("WallpaperPropertyEditor: setLanguage failed",
                    "exception" to it.javaClass.simpleName,
                    "message" to (it.message ?: ""))
            }
        hasGyroscope = (context.getSystemService(android.content.Context.SENSOR_SERVICE)
                as? SensorManager)?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        if (!hasGyroscope) {
            // Official reloadPreferences: no gyro → parallax is forced to 0
            // and the change is propagated (mode 2 would never fire anyway).
            properties[WallpaperProperties.PROP_PARALLAX]?.let { prop ->
                prop.value = 0.0
                onPropertiesChanged(WallpaperProperties.PROP_PARALLAX, prop, properties)
            }
        }
        customLocalizations = withContext(Dispatchers.IO) {
            runCatching {
                val json = lib.getUserLocalizations(filePath)
                if (json.isNullOrEmpty()) {
                    emptyMap()
                } else {
                    val type = object : com.google.gson.reflect.TypeToken<
                        HashMap<String, String>>() {}.type
                    (com.google.gson.Gson().fromJson<HashMap<String, String>>(json, type)
                        ?: emptyMap())
                }
            }
                .onFailure {
                    WepWawetLogger.appWarn("WallpaperPropertyEditor: swallowed failure at line 187",
                        "exception" to it.javaClass.simpleName,
                        "message" to (it.message ?: ""))
                }
            .getOrDefault(emptyMap())
        }
    }

    // The official initializeWallpaperProperties audio block: when the
    // wallpaper supports audio processing, make sure the recorder can run;
    // otherwise force the property off.
    LaunchedEffect(revision, controls.isSupportingAudioProcessing()) {
        if (!controls.isSupportingAudioProcessing()) {
            onAudioRecorderActive(false)
            return@LaunchedEffect
        }
        val audioDefaults = Property.fromJson(PropertyList.audioRecordingProperties)
        val ignore = WeEnginePrefs.getStoredBoolean(
            context, WeEnginePrefs.PREF_KEY_IGNORE_AUDIO_RESPONSE, false)
        if (ensurePermissionAllowed(if (ignore) "" else "prompt")) {
            onAudioRecorderActive(true)
        } else {
            audioDefaults[WallpaperProperties.PROP_AUDIO_PROCESSING]?.value = false
            WeEnginePrefs.updatePropertyValues(properties, audioDefaults)
            version++
        }
    }

    fun localizeText(token: String): String {
        if (token.isEmpty()) return ""
        customLocalizations[token]?.let { return it }
        val fromEngine = runCatching {
            EngineLibrary.sceneLib(context).getLocalization(token)
        }
            .onFailure {
                WepWawetLogger.appWarn("WallpaperPropertyEditor: swallowed failure at line 216",
                    "exception" to it.javaClass.simpleName,
                    "message" to (it.message ?: ""))
            }
        .getOrNull()
        if (!fromEngine.isNullOrEmpty()) return fromEngine
        // Non-token author text passes through (the engine's lookup would
        // return it unchanged on the PC; plain titles are not tokens).
        return if (token.startsWith("ui_") || token.contains('.')) "" else token
    }

    fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()

    // Titles/option labels are resolved ONCE per properties/localizations
    // pair and cached — getLocalization is a JNI call and must not run on
    // every recomposition (the official sets each Preference title once
    // when building the screen).
    val localizationCache = remember(properties, customLocalizations) {
        val titles = HashMap<String, String>()
        val optionLabels = HashMap<String, List<String>>()
        for ((key, prop) in properties) {
            titles[key] = stripHtml(localizeText(prop.text))
            optionLabels[key] = prop.options.map { stripHtml(localizeText(it.label)) }
        }
        titles to optionLabels
    }

    // ===== Dependency visibility (official regex + inversion rules) =====

    data class Dependency(val sourceKey: String, val invert: Boolean)

    val dependencyOf: Map<String, Dependency> = remember(properties) {
        val regex = Regex("([\\w_]+)\\.value[\\s]*(==|!=)?[\\s]*(true|false)?")
        val map = HashMap<String, Dependency>()
        for ((key, prop) in properties) {
            val condition = prop.condition
            if (condition.isEmpty() || condition == "false") continue
            val match = regex.find(condition) ?: continue
            val groups = match.groupValues
            if (groups.size <= 1) continue
            val sourceKey = groups[1]
            if (sourceKey.isEmpty()) continue
            var invert = false
            if (groups.size > 3 && groups[3] == "false") invert = !invert
            if (groups.size > 2 && groups[2] == "!=") invert = !invert
            map[key] = Dependency(sourceKey, invert)
        }
        map
    }

    fun truthy(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Double -> value.toInt() != 0
        is Float -> value.toInt() != 0
        is Int -> value != 0
        is Long -> value != 0L
        else -> WeEnginePrefs.anyToBoolean(value, false)
    }

    fun isVisible(key: String, property: Property): Boolean {
        val dep = dependencyOf[key] ?: return true
        val source = properties[dep.sourceKey] ?: return true
        var visible = truthy(source.value)
        if (dep.invert) visible = !visible
        return visible
    }

    // ===== Change plumbing =====
    //
    // `structural` = the change can alter which rows are visible or their
    // labels (bool/combo/color/text/scenetexture) → bump [version] to
    // rebuild the list. Slider ticks are continuous and drive their row's
    // LOCAL state instead — no rebuild, no jank (v0.8.4).
    //
    // The property is ALWAYS resolved from the CURRENT map by key so a
    // wallpaper reload (picker round-trip) can never orphan the write.

    fun fireChange(key: String, structural: Boolean) {
        val all = properties
        val property = all[key] ?: return
        if (structural) version++
        onPropertiesChanged(key, property, all)
    }

    // ===== The property list (order-sorted, "false"-condition entries skipped) =====

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        // key(version): values mutate in place inside the engine's property
        // map, so STRUCTURAL changes bump [version] to re-render the list.
        androidx.compose.runtime.key(version) {
        Text(
            "General",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        val sorted = properties.entries
            .filter { it.value.condition != "false" }
            .filter { hasGyroscope || (it.key != WallpaperProperties.PROP_PARALLAX &&
                    it.key != WallpaperProperties.PROP_PARALLAX_STRENGTH) }
            .sortedWith { a, b -> compareValues(a.value.order, b.value.order) }

        for ((key, property) in sorted) {
            if (!isVisible(key, property)) continue
            val title = localizationCache.first[key] ?: ""
            when (property.type) {
                "bool" -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f))
                        Switch(
                            checked = WeEnginePrefs.anyToBoolean(property.value, false),
                            onCheckedChange = { checked ->
                                properties[key]?.let { prop ->
                                    prop.value = checked
                                    fireChange(key, structural = true)
                                }
                            },
                        )
                    }
                }
                "slider" -> {
                    PropertySliderRow(
                        key = key,
                        revision = revision,
                        title = title,
                        property = property,
                        onValueTick = { newValue ->
                            // updatesContinuously parity: the official
                            // SeekBarPreference applies live on every tick —
                            // the tick only touches the value (no rebuild).
                            properties[key]?.let { prop ->
                                prop.value = newValue.toDouble()
                                onPropertiesChanged(key, prop, properties)
                            }
                        },
                    )
                }
                "combo" -> {
                    val options = property.options
                    val labels = localizationCache.second[key] ?: emptyList()
                    val selectedLabel = options.indices.firstOrNull {
                        WeEnginePrefs.anyToInt(options[it].value) == WeEnginePrefs.anyToInt(property.value)
                    }?.let { labels.getOrNull(it) } ?: ""
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = options.isNotEmpty()) { comboFor = key }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.bodyLarge)
                            if (selectedLabel.isNotEmpty()) {
                                Text(
                                    selectedLabel,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                "color" -> {
                    val colorInt = WeEnginePrefs.normalizedVec3ToColor24(property.value, "0 0 0")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { colorFor = key }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "#%06X".format(0xFFFFFF and colorInt),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(colorInt)),
                        )
                    }
                }
                "textinput" -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { textFor = key }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                WeEnginePrefs.anyToString(property.value),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                        }
                        Text("✎", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                "scenetexture" -> {
                    SceneTextureRow(
                        title = title,
                        value = WeEnginePrefs.anyToString(property.value),
                        valueLabel = property.valueLabel,
                        onPicked = { uri, name ->
                            // Record the pick for the screen-level bridge
                            // FIRST (the official FilePickerPreference keeps
                            // it across the reload that follows), then apply.
                            onTexturePicked(key, uri, name)
                            properties[key]?.let { prop ->
                                prop.value = uri
                                prop.valueLabel = name
                                fireChange(key, structural = true)
                            }
                        },
                    )
                }
            }
        }

        // ===== Reset section (official properties_category_reset) =====
        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
        Text(
            "Reset Wallpaper Settings",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { resetConfirm = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Reset", style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(20.dp))
        }
    }

    // ===== Dialogs =====

    comboFor?.let { key ->
        val property = properties[key]
        if (property != null && property.options.isNotEmpty()) {
            val title = localizationCache.first[key] ?: ""
            val labels = localizationCache.second[key] ?: emptyList()
            AlertDialog(
                onDismissRequest = { comboFor = null },
                title = { if (title.isNotEmpty()) Text(title) },
                text = {
                    Column {
                        for (option in property.options) {
                            val selected =
                                WeEnginePrefs.anyToInt(option.value) == WeEnginePrefs.anyToInt(property.value)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        properties[key]?.value = option.value
                                        comboFor = null
                                        fireChange(key, structural = true)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = {
                                        properties[key]?.value = option.value
                                        comboFor = null
                                        fireChange(key, structural = true)
                                    },
                                )
                                Text(labels.getOrElse(property.options.indexOf(option)) { option.label })
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { comboFor = null }) { Text("Cancel") }
                },
            )
        }
    }

    textFor?.let { key ->
        val property = properties[key]
        if (property != null) {
            var draft by remember(key) { mutableStateOf(WeEnginePrefs.anyToString(property.value)) }
            AlertDialog(
                onDismissRequest = { textFor = null },
                title = { Text((localizationCache.first[key] ?: "").ifEmpty { "Value" }) },
                text = {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        properties[key]?.value = draft
                        textFor = null
                        fireChange(key, structural = true)
                    }) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { textFor = null }) { Text("Cancel") }
                },
            )
        }
    }

    colorFor?.let { key ->
        val property = properties[key]
        if (property != null) {
            val initial = WeEnginePrefs.normalizedVec3ToColor24(property.value, "0 0 0")
            ColorPickerDialog(
                initialColor = Color(initial),
                onDismiss = { colorFor = null },
                onPick = { picked ->
                    properties[key]?.value = WeEnginePrefs.color24ToNormalizedVec3(picked.toArgb())
                    colorFor = null
                    fireChange(key, structural = true)
                },
            )
        }
    }

    if (resetConfirm) {
        AlertDialog(
            onDismissRequest = { resetConfirm = false },
            title = { Text("Reset Properties") },
            text = { Text("Reset all properties of this wallpaper to their defaults?") },
            confirmButton = {
                TextButton(onClick = {
                    resetConfirm = false
                    val defaults = controls.getDefaultWallpaperProperties()
                    WeEnginePrefs.updatePropertyValues(properties, defaults)
                    version++
                    val json = com.google.gson.Gson().toJson(properties)
                    controls.applyWallpaperProperties(json)
                    // The official resets the visual editor and re-applies;
                    // propagate so parallax/audio follow the reset values.
                    properties[WallpaperProperties.PROP_PARALLAX]?.let {
                        onPropertiesChanged(WallpaperProperties.PROP_PARALLAX, it, properties)
                    }
                    WepWawetLogger.engine("Properties: reset to defaults",
                        "file" to filePath,
                        "count" to properties.size)
                }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirm = false }) { Text("No") }
            },
        )
    }

    if (audioPrompt) {
        AlertDialog(
            onDismissRequest = { audioPrompt = false },
            title = { Text("Audio responsive effects") },
            text = {
                Text(
                    "This wallpaper supports audio responsive effects. Please grant " +
                            "WallpaperSuite permission to record audio if you would like to use them.\n\n" +
                            "Would you like to enable audio responsive effects now?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    audioPrompt = false
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = {
                    audioPrompt = false
                    WeEnginePrefs.storeBoolean(
                        context, WeEnginePrefs.PREF_KEY_IGNORE_AUDIO_RESPONSE, true)
                    val prop = properties[WallpaperProperties.PROP_AUDIO_PROCESSING]
                    if (prop != null) {
                        prop.value = false
                        fireChange(WallpaperProperties.PROP_AUDIO_PROCESSING, structural = true)
                    }
                }) { Text("No") }
            },
        )
    }
}

/**
 * The official `PreviewActivity.ChooseUserTexture` contract, copied 1:1:
 * OpenMultipleDocuments + the exact mime list + CATEGORY_OPENABLE +
 * EXTRA_EXCLUDE_SELF (the raw library contract does NOT add
 * EXCLUDE_SELF — the official adds it on top of createIntent).
 */
private class ChooseUserTextureContract :
    ActivityResultContract<Unit, List<Uri>>() {

    private val openMultipleDocuments = ActivityResultContracts.OpenMultipleDocuments()

    override fun createIntent(context: Context, input: Unit): Intent =
        openMultipleDocuments.createIntent(
            context,
            arrayOf(
                "video/*", "image/jpg", "image/jpeg", "image/png",
                "image/webp", "image/gif",
            ),
        )
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra("android.provider.extra.EXCLUDE_SELF", true)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> =
        openMultipleDocuments.parseResult(resultCode, intent)
}

/**
 * The slider row with LOCAL drag state (v0.8.4 smoothness fix).
 *
 * The M3 Slider is driven by [position] (a Compose FloatState), so finger
 * tracking is frame-perfect and never waits on the engine. Every tick
 * writes the value into the live property map and fires the parent's
 * change callback (official `updatesContinuously` behavior); the list is
 * NOT rebuilt (no version bump) — that rebuild-on-every-tick was the
 * original jank.
 */
@Composable
private fun PropertySliderRow(
    key: String,
    revision: Int,
    title: String,
    property: Property,
    onValueTick: (Float) -> Unit,
) {
    val min = WeEnginePrefs.anyToFloat(property.min, 0f)
    val max = WeEnginePrefs.anyToFloat(property.max, 100f)
    val precision = property.precision ?: 1

    // Local drag state, re-seeded when the wallpaper (re)loads (revision) —
    // a reload rebuilds the engine's property map with the stored values.
    var position by remember(key, revision) {
        mutableFloatStateOf(WeEnginePrefs.anyToFloat(property.value, min).coerceIn(min, max))
    }

    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                formatSliderValue(position, precision),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Slider(
            value = position,
            onValueChange = { newValue ->
                position = newValue.coerceIn(min, max)
                onValueTick(position)
            },
            valueRange = min..max,
        )
    }
}

/**
 * The `scenetexture` row: opens the system document picker with the
 * official ChooseUserTexture contract and reports the picked URI +
 * display name.
 *
 * v0.8.4 "picture options doesn't work" fixes, all against the
 * decompiled `onTextureChooserResult`:
 *  - the launcher intent adds CATEGORY_OPENABLE + EXTRA_EXCLUDE_SELF and
 *    the official mime list (the raw OpenMultipleDocuments contract does
 *    not add EXCLUDE_SELF itself);
 *  - for image/video picks the persistable URI permission is TAKEN
 *    (FLAG_GRANT_READ) so the engine can still read the texture after a
 *    process restart;
 *  - the stream is opened once (validate) exactly like the official
 *    before the value is committed.
 */
@Composable
private fun SceneTextureRow(
    title: String,
    value: String,
    valueLabel: String,
    onPicked: (uri: String, name: String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ChooseUserTextureContract()
    ) { uris ->
        val first = uris.firstOrNull() ?: return@rememberLauncherForActivityResult
        // official onTextureChooserResult: mime lookup (default "image"),
        // display name query, persistable permission for image/video.
        val mime = runCatching {
            context.contentResolver.getType(first)?.lowercase(Locale.ROOT)
        }
            .onFailure {
                WepWawetLogger.appWarn("WallpaperPropertyEditor: swallowed failure at line 746",
                    "exception" to it.javaClass.simpleName,
                    "message" to (it.message ?: ""))
            }
        .getOrNull() ?: "image"
        val name = queryDisplayName(context, first) ?: first.lastPathSegment ?: "texture"
        if (mime.startsWith("image") || mime.startsWith("video")) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    first, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // validate readability like the official (openInputStream) —
            // a broken provider fails here instead of inside the engine
            runCatching {
                context.contentResolver.openInputStream(first)?.close()
            }.onFailure {
                WepWawetLogger.engineWarn("Properties: picked texture is not readable",
                    "uri" to first.toString(),
                    "exception" to (it.message ?: it.javaClass.name))
            }
            onPicked(first.toString(), name)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                runCatching {
                    picker.launch(Unit)
                }
            }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (valueLabel.isNotEmpty()) valueLabel else value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        }
        Text("📁", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun queryDisplayName(
    context: android.content.Context,
    uri: Uri,
): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            var idx = cursor.getColumnIndex("_display_name")
            if (idx < 0) idx = 0
            cursor.getString(idx)
        } else null
    }
} catch (t: Throwable) {
    null
}

private fun formatSliderValue(value: Float, precision: Int): String = when {
    precision <= 1 -> "%.0f".format(value)
    precision == 2 -> "%.1f".format(value)
    precision == 3 -> "%.2f".format(value)
    else -> "%.${(precision - 1).coerceIn(1, 6)}f".format(value)
}
