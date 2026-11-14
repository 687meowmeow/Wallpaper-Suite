package io.wallpaperengine.weutil

import android.media.audiofx.Visualizer
import kotlin.math.exp
import kotlin.math.log10

/**
 * System-audio FFT capture for audio-responsive wallpapers, ported 1:1 from
 * the official client (`io.wallpaperengine.weutil.AudioRecorder`).
 *
 * Captures from the global output mix (session 0). Capturing from the output
 * mix STILL requires the android.permission.RECORD_AUDIO permission (per the
 * Android Visualizer documentation — v0.8.18/review B18: the old comment
 * wrongly claimed no permission was needed. The app declares it in the
 * manifest and requests it at runtime, so behavior is unchanged; this
 * comment must not invite anyone to remove the declaration). The captured
 * FFT is downsampled to the 64-band float array the engine's `sendAudioData`
 * expects.
 */
class AudioRecorder : Visualizer.OnDataCaptureListener {

    interface Listener {
        fun onAudioData(fft: FloatArray)
    }

    private var visualizer: Visualizer? = null
    private var mAudioRecordState: Boolean = false
    private var mWaveBuffer: ByteArray? = null
    private var mFftBuffer: ByteArray? = null
    var listener: Listener? = null
    private val result64 = FloatArray(64)

    fun startAudioListener() {
        if (mAudioRecordState) return
        try {
            val viz = Visualizer(0)
            viz.setEnabled(false)
            viz.setCaptureSize(512)
            try {
                viz.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED)
            } catch (e: NoSuchMethodError) {
            }
            viz.setMeasurementMode(Visualizer.MEASUREMENT_MODE_NONE)
            viz.setDataCaptureListener(
                this,
                Visualizer.getMaxCaptureRate().toInt(),
                false,
                true,
            )
            val captureSize = viz.getCaptureSize()
            mWaveBuffer = ByteArray(captureSize)
            mFftBuffer = ByteArray(captureSize)
            this.visualizer = viz
            viz.setEnabled(true)
            mAudioRecordState = true
        } catch (e: RuntimeException) {
        }
    }

    fun stopAudioListener() {
        if (mAudioRecordState) {
            mAudioRecordState = false
            visualizer?.setEnabled(false)
            visualizer?.release()
            visualizer = null
            mWaveBuffer = null
            mFftBuffer = null
        }
    }

    override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
        val buffer = mWaveBuffer ?: return
        if (waveform == null || waveform.size != buffer.size) return
        System.arraycopy(waveform, 0, buffer, 0, waveform.size)
    }

    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
        if (fft == null) return
        for (i in 0 until 64) {
            val i2 = (i + 2) * 2
            val re = fft[i2].toFloat()
            val im = fft[i2 + 1].toFloat()
            val mag = (re * re) + (im * im)
            var log = 0.0f
            if (mag > 0) {
                log = log10(mag.toDouble()).toFloat() * 0.35f
            }
            val exponent = (((1.0f - (i / 63.0f)) * 1.0f) - 0.5f).toDouble()
            val decay = (2.0f - exp(exponent).toFloat())
            result64[i] = minOf(1.0f, log * decay)
        }
        listener?.onAudioData(result64)
    }
}
