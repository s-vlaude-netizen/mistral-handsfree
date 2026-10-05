package de.localvoice.mistralhandsfree.domain

/** Spoken commands that end live mode without touching the screen. */
object VoiceCommands {

    private val STOP_PHRASES = setOf(
        // English
        "stop", "stop it", "stop listening", "stop the conversation", "end conversation",
        "end the conversation", "end live mode", "goodbye", "bye", "bye bye", "that's all",
        "that is all",
        // German
        "stopp", "beende das gespräch", "beende das gespraech", "gespräch beenden",
        "gespraech beenden", "live modus beenden", "auf wiedersehen", "tschüss", "tschuess",
        "tschau", "das wars", "das war's", "ende",
        // French
        "arrête", "arrête-toi", "arrêtez", "arrete", "au revoir", "termine la conversation",
        "terminer la conversation", "c'est tout",
        // Spanish
        "adiós", "adios", "termina la conversación", "eso es todo", "hasta luego",
        // Italian
        "fermati", "basta", "arrivederci",
    )

    /**
     * Politeness and filler words that may surround a command ("okay, stop",
     * "thanks, bye"). Stripped from both ends before matching - but a lone
     * "thank you" is not a command, there must be a real phrase left.
     */
    private val FILLERS = setOf(
        "ok", "okay", "alright", "so", "well", "thanks", "thank", "you", "please",
        "danke", "bitte", "dann", "also", "gut", "merci", "gracias", "grazie",
    )

    private val PUNCTUATION = Regex("[.,!?;:…\"“”„]")
    private val WHITESPACE = Regex("\\s+")

    /** true if the utterance is nothing but a stop command. */
    fun isStopCommand(utterance: String): Boolean {
        val normalized = normalize(utterance)
        if (normalized.isEmpty()) return false
        return normalized in STOP_PHRASES
    }

    private fun normalize(utterance: String): String {
        val words = utterance.lowercase()
            .replace('’', '\'')
            .replace(PUNCTUATION, " ")
            .trim()
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .toMutableList()
        while (words.isNotEmpty() && words.first() in FILLERS) words.removeAt(0)
        while (words.isNotEmpty() && words.last() in FILLERS) words.removeAt(words.lastIndex)
        return words.joinToString(" ")
    }
}
