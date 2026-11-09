package com.wepwawet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * The color picker dialog for `color`-type wallpaper properties — the
 * Compose equivalent of the official client's ColorPreferenceCompat
 * (jaredrummler colorpicker): a saturation/brightness field with a hue
 * slider, a hex entry, and material preset swatches. Returns the picked
 * color through [onPick]; the caller converts it to the engine's "r g b"
 * normalized vec3 via
 * [io.wallpaperengine.weclient.WeEnginePrefs.color24ToNormalizedVec3]
 * (the exact official round-trip).
 */
@Composable
fun ColorPickerDialog(
    initialColor: Color,
    onDismiss: () -> Unit,
    onPick: (Color) -> Unit,
) {
    // Compose's Color has no saturation/brightness accessors (and `.value`
    // is its ULong packing!) — resolve the initial HSV through the
    // framework converter once.
    val initialHsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialColor.toArgb(), it) }
    }
    var hue by remember { mutableStateOf(initialHsv[0]) }
    var sat by remember { mutableStateOf(initialHsv[1]) }
    var brightness by remember { mutableStateOf(initialHsv[2]) }
    var hexDraft by remember { mutableStateOf<String?>(null) }

    // The hex field overrides the HSV state only while it parses.
    val current = (hexDraft?.let { parseHex(it) }) ?: hsvColor(hue, sat, brightness)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a color") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Saturation/value field: horizontal = saturation,
                // vertical = value (bright at the top).
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                applySv(offset.x, offset.y, size.width, size.height) { s, v ->
                                    sat = s; brightness = v; hexDraft = null
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                applySv(change.position.x, change.position.y, size.width, size.height) { s, v ->
                                    sat = s; brightness = v; hexDraft = null
                                }
                            }
                        },
                ) {
                    drawSvField(hue)
                    val cx = sat * size.width
                    val cy = (1f - brightness) * size.height
                    drawCircle(Color.White, radius = 10.dp.toPx(), center = Offset(cx, cy))
                    drawCircle(
                        Color.Black,
                        radius = 8.dp.toPx(),
                        center = Offset(cx, cy),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }

                // Hue slider
                Column {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    hue = (offset.x / size.width * 360f).coerceIn(0f, 360f)
                                    hexDraft = null
                                }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    hue = (change.position.x / size.width * 360f).coerceIn(0f, 360f)
                                    hexDraft = null
                                }
                            },
                    ) {
                        drawHueBar()
                    }
                    Slider(
                        value = hue,
                        onValueChange = {
                            hue = it
                            hexDraft = null
                        },
                        valueRange = 0f..360f,
                    )
                }

                // Current color + hex entry
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(current)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hexDraft ?: colorHexOf(current),
                        onValueChange = { hexDraft = it },
                        label = { Text("Hex") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Preset swatches (the jaredrummler picker's material set)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val presets = listOf(
                        0xFF000000, 0xFFFFFFFF, 0xFFFF0000, 0xFFFF8000, 0xFFFFFF00,
                        0xFF00FF00, 0xFF00FF80, 0xFF00FFFF, 0xFF0000FF, 0xFF8000FF,
                        0xFFFF00FF, 0xFFFF0080, 0xFF808080, 0xFFC0C0C0, 0xFF804000,
                    )
                    for (preset in presets) {
                        val c = Color(preset.toInt())
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable {
                                    val hsv = FloatArray(3).also {
                                        android.graphics.Color.colorToHSV(c.toArgb(), it)
                                    }
                                    hue = hsv[0]
                                    sat = hsv[1]
                                    brightness = hsv[2]
                                    hexDraft = null
                                },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(current) }) { Text("Select") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun applySv(
    x: Float,
    y: Float,
    width: Int,
    height: Int,
    set: (Float, Float) -> Unit,
) {
    if (width <= 0 || height <= 0) return
    val s = (x / width).coerceIn(0f, 1f)
    val v = (1f - (y / height)).coerceIn(0f, 1f)
    set(s, v)
}

private fun hsvColor(h: Float, s: Float, v: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))

private fun colorHexOf(c: Color): String {
    val r = (c.red * 255).toInt().coerceIn(0, 255)
    val g = (c.green * 255).toInt().coerceIn(0, 255)
    val b = (c.blue * 255).toInt().coerceIn(0, 255)
    return "#%06X".format((r shl 16) or (g shl 8) or b)
}

private fun parseHex(hex: String): Color? {
    val cleaned = hex.trim().removePrefix("#")
    if (cleaned.length != 6) return null
    val rgb = cleaned.toLongOrNull(16) ?: return null
    val r = ((rgb shr 16) and 0xFF).toInt()
    val g = ((rgb shr 8) and 0xFF).toInt()
    val b = (rgb and 0xFF).toInt()
    return Color(r, g, b)
}

private fun DrawScope.drawSvField(hue: Float) {
    // Base hue
    drawRect(hsvColor(hue, 1f, 1f))
    // White → transparent, left-to-right (saturation)
    drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
    // Transparent → black, top-to-bottom (value)
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
}

private fun DrawScope.drawHueBar() {
    val hues = List(13) { hsvColor(it * 30f, 1f, 1f) }
    drawRect(Brush.horizontalGradient(hues))
}
