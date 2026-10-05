package de.localvoice.mistralhandsfree.mistral

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/** A voice that Voxtral TTS can speak with. */
data class Voice(
    val id: String,
    val name: String,
    val languages: List<String>,
    val gender: String?,
    /** Built in, as opposed to a voice the account cloned itself. */
    val preset: Boolean,
)

/** Voxtral speech output and transcription. */
class MistralAudio(private val http: MistralHttp) {

    /** All voices of the account: the built-in ones plus any it created. */
    suspend fun listVoices(): List<Voice> {
        val out = mutableListOf<Voice>()
        var offset = 0
        while (offset < MAX_VOICES) {
            val root = http.getJson("audio/voices?limit=$PAGE_SIZE&offset=$offset").obj()
                ?: throw MistralException(MistralException.Kind.PROTOCOL, "The voice list has an unexpected shape")
            val items = root["items"].arr() ?: break
            out += items.mapNotNull { parseVoice(it.obj()) }
            val total = root["total"].int() ?: items.size
            offset += PAGE_SIZE
            if (items.isEmpty() || offset >= total) break
        }
        return out
    }

    /**
     * Synthesizes [text] and streams the audio as it is generated: mono, 24 kHz,
     * 32-bit float samples in the range -1..1 ([SPEECH_SAMPLE_RATE]).
     *
     * Raw PCM is requested because it has the lowest time-to-first-audio; the
     * compressed formats need several times longer before the first sound.
     */
    fun streamSpeech(text: String, voiceId: String, modelId: String = DEFAULT_TTS_MODEL): Flow<FloatArray> = flow {
        val body = buildJsonObject {
            put("model", modelId)
            put("input", text)
            put("voice_id", voiceId)
            put("response_format", "pcm")
            put("stream", true)
        }
        val request = http.request("audio/speech")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON))
            .build()
        // One decoder per request: it carries over bytes when a sample is split across events.
        val decoder = Float32LeDecoder()
        emitAll(http.streamEvents(request) { event -> parseSpeechEvent(event, decoder) })
    }

    /**
     * Transcribes a complete recording.
     *
     * [language] is an ISO 639-1 code such as "de". Leave it null to let the model
     * detect the language - better for people who switch between languages than
     * a wrong fixed guess.
     */
    suspend fun transcribe(wav: ByteArray, language: String? = null, modelId: String = DEFAULT_STT_MODEL): String {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", modelId)
            .apply { if (!language.isNullOrBlank()) addFormDataPart("language", language) }
            .addFormDataPart("file", "speech.wav", wav.toRequestBody(WAV))
            .build()
        val request = http.request("audio/transcriptions")
            .header("Accept", "application/json")
            .post(form)
            .build()
        val root = http.open(request).use { http.readJson(it) }.obj()
            ?: throw MistralException(MistralException.Kind.PROTOCOL, "The transcription has an unexpected shape")
        return root["text"].str().orEmpty().trim()
    }

    companion object {
        /**
         * Fallbacks for when the model list could not be fetched; normally the ids
         * come from [ModelCatalog].
         */
        const val DEFAULT_TTS_MODEL = "voxtral-mini-tts-2603"
        const val DEFAULT_STT_MODEL = "voxtral-mini-latest"

        /**
         * Not stated in Mistral's own docs (they only say "raw float32 LE"), but what
         * the API produces and what integrations such as Pipecat decode it as.
         */
        const val SPEECH_SAMPLE_RATE = 24_000

        private const val PAGE_SIZE = 100
        private const val MAX_VOICES = 300
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val WAV = "audio/wav".toMediaType()
    }
}

internal fun parseVoice(card: JsonObject?): Voice? {
    card ?: return null
    val id = card["id"].str() ?: return null
    return Voice(
        id = id,
        name = card["name"].str()?.takeIf { it.isNotBlank() } ?: id,
        languages = card["languages"].arr()?.mapNotNull { it.str() }.orEmpty(),
        gender = card["gender"].str(),
        preset = card["type"].str() != "custom",
    )
}

/** One `data:` payload of a speech stream. */
internal fun parseSpeechEvent(event: SseEvent, decoder: Float32LeDecoder): Parsed<FloatArray> {
    val root = parseJsonOrNull(event.data).obj() ?: return Parsed() // ignore anything unreadable
    throwIfErrorObject(root)

    // The payload names its own type; the SSE `event:` line says the same thing.
    return when (root["type"].str() ?: event.event) {
        "speech.audio.delta" -> {
            val encoded = root["audio_data"].str() ?: return Parsed()
            val bytes = try {
                Base64.getDecoder().decode(encoded)
            } catch (e: IllegalArgumentException) {
                throw MistralException(MistralException.Kind.PROTOCOL, "Undecodable audio in the speech stream", cause = e)
            }
            val samples = decoder.feed(bytes)
            if (samples.isEmpty()) Parsed() else Parsed(listOf(samples))
        }

        "speech.audio.done" -> Parsed(done = true)
        else -> Parsed()
    }
}

/**
 * Turns the byte stream of little-endian 32-bit floats into samples.
 *
 * The audio arrives in arbitrary pieces, and nothing promises that a piece
 * ends on a sample boundary. A sample cut in two would otherwise decode as
 * noise - and shift every later sample by a few bytes.
 */
class Float32LeDecoder {
    private var carry = ByteArray(0)

    fun feed(bytes: ByteArray): FloatArray {
        val all = if (carry.isEmpty()) bytes else carry + bytes
        val count = all.size / 4
        val samples = FloatArray(count)
        val buffer = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val value = buffer.getFloat(i * 4)
            // A stray NaN would turn into a loud click on some audio drivers.
            samples[i] = if (value.isNaN()) 0f else value.coerceIn(-1f, 1f)
        }
        carry = all.copyOfRange(count * 4, all.size)
        return samples
    }
}
