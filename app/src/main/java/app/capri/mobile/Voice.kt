package app.capri.mobile

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService

/**
 * Her voice. Engines (Prefs.voiceEngine):
 *  - "android":     the phone's own text-to-speech, offline
 *  - "openai":      any OpenAI-compatible POST {url}/audio/speech (OpenAI, Kokoro-FastAPI on your PC, ...)
 *  - "elevenlabs":  ElevenLabs text-to-speech
 * A cloud failure falls back to the Android voice so she is never mute.
 */
class Voice(
    private val ctx: Context,
    private val prefs: Prefs,
    private val main: Handler,
    private val io: ExecutorService
) {
    interface Listener {
        fun onStart(durationMs: Long)
        fun onRange(index: Int)
        fun onDone()
        fun onFail(message: String)
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    private var current: Listener? = null
    private var activeId = ""
    @Volatile private var ticket = 0

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        if (id != activeId) return
                        main.post { current?.onStart(estimate()) }
                    }
                    override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                        if (id != activeId) return
                        main.post { current?.onRange(start) }
                    }
                    override fun onDone(id: String?) {
                        if (id != activeId) return
                        main.post { finish() }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        if (id != activeId) return
                        main.post { finish() }
                    }
                })
                ttsReady = true
            }
        }
    }

    private var lastLen = 0
    private fun estimate() = (lastLen * 62L).coerceAtLeast(1200)

    private fun finish() {
        val l = current
        current = null
        l?.onDone()
    }

    fun speak(text: String, listener: Listener) {
        stop()
        current = listener
        lastLen = text.length
        val mine = ++ticket
        when (prefs.voiceEngine) {
            "openai", "elevenlabs" -> io.execute {
                try {
                    val bytes = fetch(text)
                    main.post { if (mine == ticket) play(bytes, listener) }
                } catch (e: Exception) {
                    main.post {
                        if (mine == ticket) {
                            listener.onFail(e.message ?: e.javaClass.simpleName)
                            speakLocal(text, mine)
                        }
                    }
                }
            }
            else -> speakLocal(text, mine)
        }
    }

    fun stop() {
        ticket++
        activeId = ""
        tts?.stop()
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
        current = null
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun speakLocal(text: String, mine: Int) {
        val t = tts
        if (!ttsReady || t == null) {
            val l = current
            l?.onStart(estimate())
            main.postDelayed({ if (mine == ticket) finish() }, estimate())
            return
        }
        activeId = "capri-$mine"
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, activeId)
    }

    private fun play(bytes: ByteArray, listener: Listener) {
        val file = File(ctx.cacheDir, "capri_voice.mp3")
        file.writeBytes(bytes)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.path)
            mp.setOnPreparedListener {
                it.start()
                listener.onStart(it.duration.toLong())
            }
            mp.setOnCompletionListener {
                it.release()
                if (player === it) player = null
                if (current === listener) finish()
            }
            mp.setOnErrorListener { m, _, _ ->
                m.release()
                if (player === m) player = null
                if (current === listener) { listener.onFail("audio playback failed"); finish() }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            mp.release(); player = null
            listener.onFail(e.message ?: "audio error")
            finish()
        }
    }

    // ---------------------------------------------------------------- network

    private fun fetch(text: String): ByteArray {
        val conn: HttpURLConnection
        val body: String
        if (prefs.voiceEngine == "elevenlabs") {
            if (prefs.elevenKey.isBlank() || prefs.elevenVoice.isBlank())
                throw IOException("ElevenLabs needs an API key and a voice id (Voice settings).")
            conn = URL("https://api.elevenlabs.io/v1/text-to-speech/${prefs.elevenVoice}?output_format=mp3_44100_128")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("xi-api-key", prefs.elevenKey)
            body = JSONObject().put("text", text).put("model_id", prefs.elevenModel).toString()
        } else {
            val base = prefs.ttsUrl.trim().trimEnd('/')
            if (base.isEmpty()) throw IOException("Set the voice server URL (Voice settings).")
            conn = URL("$base/audio/speech").openConnection() as HttpURLConnection
            val key = prefs.ttsKey.ifBlank { prefs.apiKey }
            if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
            body = JSONObject()
                .put("model", prefs.ttsModel)
                .put("input", text)
                .put("voice", prefs.ttsVoice)
                .put("response_format", "mp3")
                .toString()
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "audio/mpeg")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IOException("Voice HTTP $code: ${err.take(160)}")
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
