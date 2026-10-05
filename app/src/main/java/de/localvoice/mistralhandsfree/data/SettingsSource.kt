package de.localvoice.mistralhandsfree.data

import kotlinx.coroutines.flow.StateFlow

/** Where the live session reads the user's settings from. */
interface SettingsSource {
    val settings: StateFlow<AppSettings>
    val current: AppSettings
    fun update(transform: (AppSettings) -> AppSettings)
}
