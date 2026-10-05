package de.localvoice.mistralhandsfree.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.localvoice.mistralhandsfree.HandsfreeApplication
import de.localvoice.mistralhandsfree.R
import de.localvoice.mistralhandsfree.auth.ApiKeys
import de.localvoice.mistralhandsfree.data.AppSettings
import de.localvoice.mistralhandsfree.mistral.MistralException
import de.localvoice.mistralhandsfree.mistral.ModelCatalog
import de.localvoice.mistralhandsfree.mistral.Voice
import de.localvoice.mistralhandsfree.mistral.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the sign-in form stands. */
sealed interface SignInState {
    data object Idle : SignInState
    data object Checking : SignInState

    /** The key was accepted and stored. */
    data object Done : SignInState
    data class Failed(val message: String) : SignInState
}

class MainViewModel(application: Application) : ViewModel() {

    private val app = application
    private val container = (application as HandsfreeApplication).container

    val session = container.liveSession
    val settings: StateFlow<AppSettings> = container.settings.settings

    /** true while a key is stored. */
    val signedIn: StateFlow<Boolean> = container.keyStore.signedIn

    val catalog: StateFlow<ModelCatalog?> = container.mistral.catalog
    val voices: StateFlow<List<Voice>?> = container.mistral.voices
    val loadingLists: StateFlow<Boolean> = container.mistral.loading

    private val _signIn = MutableStateFlow<SignInState>(SignInState.Idle)
    val signIn: StateFlow<SignInState> = _signIn.asStateFlow()

    private val _listError = MutableStateFlow<String?>(null)

    /** Why the model or voice list could not be loaded, if it could not. */
    val listError: StateFlow<String?> = _listError.asStateFlow()

    init {
        if (signedIn.value) refreshModels()
    }

    // --------------------------------------------------------------- sign-in

    /**
     * Checks the key with Mistral and, if it works, stores it.
     *
     * Whatever was pasted is cleaned up first ([ApiKeys.normalize]). The key is
     * only saved once Mistral has accepted it, so a typo never replaces a
     * working key.
     */
    fun submitKey(raw: String) {
        val key = ApiKeys.normalize(raw)
        if (!ApiKeys.looksPlausible(key)) {
            _signIn.value = SignInState.Failed(app.getString(R.string.signin_implausible))
            return
        }
        _signIn.value = SignInState.Checking
        viewModelScope.launch {
            container.mistral.refreshCatalog(keyOverride = key)
                .onSuccess {
                    try {
                        container.keyStore.save(key)
                        session.onSignedIn()
                        _signIn.value = SignInState.Done
                    } catch (e: Exception) {
                        _signIn.value = SignInState.Failed(app.getString(R.string.signin_store_failed))
                    }
                }
                .onFailure { failure ->
                    _signIn.value = SignInState.Failed(signInMessage(failure))
                }
        }
    }

    fun resetSignIn() {
        _signIn.value = SignInState.Idle
    }

    fun signOut() {
        session.stop()
        container.keyStore.clear()
        container.mistral.clear()
        _signIn.value = SignInState.Idle
    }

    /** The stored key with all but the last characters hidden, for the settings screen. */
    fun maskedKey(): String = container.keyStore.load()?.let(ApiKeys::mask).orEmpty()

    private fun signInMessage(failure: Throwable): String {
        val error = failure as? MistralException ?: return app.getString(R.string.err_network)
        return when (error.kind) {
            MistralException.Kind.UNAUTHORIZED -> app.getString(R.string.signin_rejected)
            else -> error.userMessage(app)
        }
    }

    // ---------------------------------------------------------- models/voices

    fun refreshModels() {
        viewModelScope.launch {
            _listError.value = null
            container.mistral.refreshCatalog()
                .onFailure { failure ->
                    val error = failure as? MistralException
                    _listError.value = error?.userMessage(app)
                    // A key that was revoked since last time: say so now, not on the first turn.
                    if (error?.kind == MistralException.Kind.UNAUTHORIZED) {
                        session.reportSignInNeeded(error.userMessage(app))
                    }
                }
        }
    }

    fun refreshVoices() {
        viewModelScope.launch {
            _listError.value = null
            container.mistral.refreshVoices()
                .onFailure { _listError.value = (it as? MistralException)?.userMessage(app) }
        }
    }

    // --------------------------------------------------------------- settings

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        container.settings.update(transform)
    }

    fun testSpeech() = session.testSpeech()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                MainViewModel(app)
            }
        }
    }
}
