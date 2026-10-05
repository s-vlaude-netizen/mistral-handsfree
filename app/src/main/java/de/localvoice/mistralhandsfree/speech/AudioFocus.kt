package de.localvoice.mistralhandsfree.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Audio focus for speech output.
 *
 * Without it the system ducks or swallows the output when something else is
 * playing - for example right after speech recognition. Other apps' media
 * (music, a podcast) is lowered while the assistant speaks and comes back after.
 */
class AudioFocus(context: Context, private val attributes: AudioAttributes) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    @Synchronized
    fun acquire() {
        if (request != null) return
        val created = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        request = created
        runCatching { audioManager?.requestAudioFocus(created) }
    }

    @Synchronized
    fun release() {
        val held = request ?: return
        request = null
        runCatching { audioManager?.abandonAudioFocusRequest(held) }
    }

    companion object {
        /** How the assistant's voice is classified by the system, whichever engine speaks. */
        val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
