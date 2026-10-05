package de.localvoice.mistralhandsfree.mistral

import android.content.Context
import de.localvoice.mistralhandsfree.R
import de.localvoice.mistralhandsfree.session.TextSource

/**
 * What to tell the user about a failure.
 *
 * The one file in this package that knows about string resources; the rest of it
 * stays plain Kotlin so that it can be tested on the JVM.
 */
fun MistralException.userMessage(text: TextSource): String {
    val server = serverMessage?.takeIf { it.isNotBlank() }
    return when (kind) {
        MistralException.Kind.UNAUTHORIZED -> text.get(R.string.err_unauthorized)
        MistralException.Kind.FORBIDDEN ->
            if (server != null) text.get(R.string.err_forbidden_detail, server)
            else text.get(R.string.err_forbidden)

        MistralException.Kind.RATE_LIMITED -> text.get(R.string.err_rate_limited)
        MistralException.Kind.BAD_REQUEST ->
            if (server != null) text.get(R.string.err_bad_request_detail, server)
            else text.get(R.string.err_bad_request)

        MistralException.Kind.SERVER -> text.get(R.string.err_server)
        MistralException.Kind.NETWORK -> text.get(R.string.err_network)
        MistralException.Kind.PROTOCOL -> text.get(R.string.err_protocol)
        MistralException.Kind.UNKNOWN -> text.get(R.string.err_http, httpCode ?: 0, server ?: "")
    }
}

fun MistralException.userMessage(context: Context): String = userMessage(TextSource.of(context))
