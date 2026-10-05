package de.localvoice.mistralhandsfree.session

import android.content.Context
import androidx.annotation.StringRes

/**
 * Looks up a UI string by resource id.
 *
 * The session logic produces a good deal of text (status lines, error messages).
 * Going through this one-method interface instead of a [Context] keeps that
 * logic free of Android, so it can be driven by tests on the JVM.
 */
fun interface TextSource {
    fun get(@StringRes id: Int, vararg args: Any): String

    companion object {
        fun of(context: Context): TextSource = TextSource { id, args -> context.getString(id, *args) }
    }
}
