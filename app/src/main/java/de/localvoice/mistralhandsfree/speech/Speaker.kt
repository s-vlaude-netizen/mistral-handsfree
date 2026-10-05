package de.localvoice.mistralhandsfree.speech

import kotlinx.coroutines.flow.StateFlow

/** Speech output with a queue. */
interface Speaker {

    /** true while something is queued or being spoken. */
    val busy: StateFlow<Boolean>

    /** A message if the output does not work as intended - otherwise null. */
    val warning: StateFlow<String?>

    /** What is in use, for troubleshooting in the settings. */
    val diagnostics: StateFlow<String>

    /** Starts the engine. Returns false if nothing can be spoken at all. */
    suspend fun prepare(): Boolean

    /** Appends text to the end of the queue. */
    fun enqueue(text: String)

    /** Empties the queue and says this sentence right away - for the test button. */
    fun speakNow(text: String)

    /** Waits until the queue is empty and the last sound has played out. */
    suspend fun awaitIdle()

    /** Stops immediately and empties the queue. */
    fun stop()

    fun shutdown()
}
