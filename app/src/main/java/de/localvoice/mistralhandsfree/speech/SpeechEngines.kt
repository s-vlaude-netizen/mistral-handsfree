package de.localvoice.mistralhandsfree.speech

import de.localvoice.mistralhandsfree.data.AppSettings

/** A speaker, plus a note for the user if it is not quite what they asked for. */
class SpeakerSetup(val speaker: Speaker, val notice: String? = null)

/**
 * Builds the speech engines the settings ask for.
 *
 * The live session only knows this interface, which keeps it independent of the
 * Android speech classes - and lets tests run the whole loop with scripted engines.
 */
interface SpeechEngines {

    /** A recognizer for these settings. Not started yet. */
    fun recognizer(settings: AppSettings): SpeechToText

    /** A speaker for these settings. Not prepared yet - the caller does that. */
    suspend fun speaker(settings: AppSettings): SpeakerSetup
}
