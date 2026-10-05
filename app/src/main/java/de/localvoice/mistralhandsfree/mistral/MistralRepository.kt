package de.localvoice.mistralhandsfree.mistral

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the account can use - models and voices - fetched once and shared by the
 * settings screen and the live session.
 *
 * Failures are returned, not stored: whoever asked decides what to show.
 */
class MistralRepository(
    val client: MistralClient,
    val audio: MistralAudio,
) {
    private val _catalog = MutableStateFlow<ModelCatalog?>(null)
    val catalog: StateFlow<ModelCatalog?> = _catalog.asStateFlow()

    private val _voices = MutableStateFlow<List<Voice>?>(null)

    /** null until loaded; an empty list means the account has no voices. */
    val voices: StateFlow<List<Voice>?> = _voices.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    suspend fun refreshCatalog(keyOverride: String? = null): Result<ModelCatalog> =
        guarded { client.listModels(keyOverride) }.onSuccess { _catalog.value = it }

    suspend fun refreshVoices(): Result<List<Voice>> =
        guarded { audio.listVoices() }.onSuccess { _voices.value = it }

    /** The voices, fetched on first use. */
    suspend fun voicesOrLoad(): List<Voice> =
        _voices.value ?: refreshVoices().getOrNull().orEmpty()

    /** Forget everything tied to the account - on sign-out. */
    fun clear() {
        _catalog.value = null
        _voices.value = null
    }

    private suspend fun <T> guarded(block: suspend () -> T): Result<T> {
        _loading.value = true
        return try {
            Result.success(block())
        } catch (e: MistralException) {
            Result.failure(e)
        } finally {
            _loading.value = false
        }
    }
}
