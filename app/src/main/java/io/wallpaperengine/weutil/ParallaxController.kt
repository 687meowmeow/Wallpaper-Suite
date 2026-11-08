package io.wallpaperengine.weutil

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.PointF
import io.wallpaperengine.weclient.WallpaperProperties
import io.wallpaperengine.weviews.WallpaperControls
import io.wallpaperengine.wedata.Property
import io.wallpaperengine.weclient.WeEnginePrefs
import kotlin.math.abs

/**
 * Gyro / scroll parallax + motion-response input controller, ported 1:1
 * from the official client (`io.wallpaperengine.weutil.ParallaxController`).
 *
 *  - Parallax mode 1/3 (gyro / gyro-free): rotation-vector sensor drives
 *    sendNormalizedParallaxOffset; with motion response, linear-acceleration
 *    drives sendForceInput and the gravity sensor drives sendGravityInput.
 *  - Parallax mode 2 (scroll): home-screen scroll offsets drive parallax.
 */
class ParallaxController(private val context: Context) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var wallpaperControls: WallpaperControls? = null
    private var supportsForceInput: Boolean = false
    private var parallaxMode: Int = 0
    private var parallaxStrength: Float = 1.0f
    private var lastScrollOffset: Float = 0f

    fun linkWallpaper(wallpaperControls: WallpaperControls) {
        supportsForceInput = false
        parallaxMode = 0
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        this.wallpaperControls = wallpaperControls
    }

    fun applyProperties(properties: HashMap<String, Property>?) {
        if (properties == null) return
        val oldMode = parallaxMode
        var newMode: Int? = null
        if (properties.containsKey(WallpaperProperties.PROP_PARALLAX)) {
            newMode = WeEnginePrefs.anyToInt(properties[WallpaperProperties.PROP_PARALLAX]?.value, 0)
            parallaxMode = newMode
            if (newMode == 0 || newMode == 2) {
                wallpaperControls?.sendNormalizedParallaxOffset(null, 0.5f, 1.0f)
            }
        }
        if (properties.containsKey(WallpaperProperties.PROP_PARALLAX_STRENGTH)) {
            parallaxStrength = WeEnginePrefs.anyToFloat(
                properties[WallpaperProperties.PROP_PARALLAX_STRENGTH]?.value, 100f
            ) * 0.01f
        }
        if (properties.containsKey(WallpaperProperties.PROP_MOTION_RESPONSE)) {
            supportsForceInput = WeEnginePrefs.anyToBoolean(
                properties[WallpaperProperties.PROP_MOTION_RESPONSE]?.value, false
            )
        }
        val mode = newMode ?: oldMode
        if (mode == 1 || mode == 3) startSensors() else stopSensors()
        repeatLastInput()
    }

    fun pause() {
        stopSensors()
    }

    fun resume() {
        if (parallaxMode == 1 || parallaxMode == 3) startSensors()
    }

    fun onOffsetsChanged(offset: Float) {
        lastScrollOffset = offset
        if (parallaxMode != 2) return
        wallpaperControls?.sendNormalizedParallaxOffset(
            PointF((offset * -2f + 1f) * parallaxStrength, 0f), 0f, 1.0f
        )
    }

    fun repeatLastInput() {
        onOffsetsChanged(lastScrollOffset)
    }

    private fun startSensors() {
        val sm = sensorManager ?: return
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensor ->
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        if (supportsForceInput) startAccel()
    }

    private fun startAccel() {
        val sm = sensorManager ?: return
        sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sensor ->
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        sm.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sensor ->
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopSensors() {
        sensorManager?.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (parallaxMode != 1 && parallaxMode != 3) return
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                if (event.values.size < 2) return
                val factor = (parallaxStrength * 0.125f) + 0.1f
                val offset = PointF(event.values[1] * factor, -event.values[0] * factor)
                wallpaperControls?.sendNormalizedParallaxOffset(
                    offset, 0.5f, (parallaxStrength * 0.05f) + 0.01f
                )
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val values = event.values.map { if (abs(it) < 0.01f) 0f else it }
                wallpaperControls?.sendForceInput(PointF(values.getOrElse(0) { 0f }, values.getOrElse(1) { 0f }))
            }
            Sensor.TYPE_GRAVITY -> {
                wallpaperControls?.sendGravityInput(event.values[0], event.values[1], event.values[2])
            }
        }
    }
}
