package de.localvoice.mistralhandsfree.speech

import kotlinx.coroutines.flow.StateFlow

/** Result of one listening round. */
sealed interface SttResult {
    /** Recognized text, guaranteed not to be empty. */
    data class Text(val text: String) : SttResult

    /** Nobody spoke, or nothing usable came out. */
    data object Silence : SttResult

    /**
     * Hard failure; [recoverable] false means it cannot continue without the user doing something.
     * [needsSignIn] says that Mistral rejected the API key, so the thing to do is to sign in again.
     */
    data class Failure(
        val message: String,
        val recoverable: Boolean,
        val needsSignIn: Boolean = false,
    ) : SttResult
}

/** Speech recognition that records one turn of speech and returns it as text. */
interface SpeechToText {

    /** Intermediate result while speaking, for the screen. Not every engine has one. */
    val partialText: StateFlow<String>

    /** Loudness 0..1, for the animation. */
    val level: StateFlow<Float>

    /**
     * true from the moment the speaker has fallen silent until the text is
     * there - the gap in which the app has heard you and is working on it.
     */
    val processing: StateFlow<Boolean>

    /** Listens once, until the speaker is done. Suspends until then. */
    suspend fun listenOnce(): SttResult

    /** Aborts a listening round in progress. */
    fun abort()

    fun destroy()
}
