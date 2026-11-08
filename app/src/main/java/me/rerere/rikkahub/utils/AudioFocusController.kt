package me.rerere.rikkahub.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * 申请/释放语音识别用的瞬时独占音频焦点。
 *
 * [AudioFocusRequest] 及其 Builder 从 API 26 才开始提供，minSdk 24/25 的设备上直接引用会在类加载时抛
 * NoClassDefFoundError；低版本回退到已废弃的 requestAudioFocus(listener, streamType, durationHint)。
 */
class AudioFocusController(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val legacyListener = AudioManager.OnAudioFocusChangeListener { }
    private val api26Request: Api26Request? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Api26Request(audioManager) else null

    fun request(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            checkNotNull(api26Request).request()
        } else {
            audioManager.requestAudioFocus(
                legacyListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

    fun abandon() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            api26Request?.abandon()
        } else {
            audioManager.abandonAudioFocus(legacyListener)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private class Api26Request(private val audioManager: AudioManager) {
        private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .build()

        fun request(): Boolean =
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        fun abandon() {
            audioManager.abandonAudioFocusRequest(request)
        }
    }
}
