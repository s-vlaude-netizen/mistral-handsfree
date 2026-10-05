package de.localvoice.mistralhandsfree.mistral

import android.content.Context
import de.localvoice.mistralhandsfree.R

/**
 * What to tell the user about a failure.
 *
 * The one file in this package that needs Android (for string resources); the
 * rest of it stays plain Kotlin so that it can be tested on the JVM.
 */
fun MistralException.userMessage(context: Context): String {
    val server = serverMessage?.takeIf { it.isNotBlank() }
    return when (kind) {
        MistralException.Kind.UNAUTHORIZED -> context.getString(R.string.err_unauthorized)
        MistralException.Kind.FORBIDDEN ->
            if (server != null) context.getString(R.string.err_forbidden_detail, server)
            else context.getString(R.string.err_forbidden)

        MistralException.Kind.RATE_LIMITED -> context.getString(R.string.err_rate_limited)
        MistralException.Kind.BAD_REQUEST ->
            if (server != null) context.getString(R.string.err_bad_request_detail, server)
            else context.getString(R.string.err_bad_request)

        MistralException.Kind.SERVER -> context.getString(R.string.err_server)
        MistralException.Kind.NETWORK -> context.getString(R.string.err_network)
        MistralException.Kind.PROTOCOL -> context.getString(R.string.err_protocol)
        MistralException.Kind.UNKNOWN ->
            context.getString(R.string.err_http, httpCode ?: 0, server ?: "")
    }
}
