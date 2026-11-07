package app.capri.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to any OpenAI-compatible /chat/completions endpoint
 * (OpenAI, OpenRouter, Groq, Ollama, LM Studio...). Keeps a short memory on-device.
 */
class Brain(private val prefs: Prefs) {
    class Reply(val text: String, val remindMinutes: Int?, val remindText: String?)

    private val remindTag = Regex("\\[\\[remind:(\\d{1,4})\\|(.{1,160}?)\\]\\]")

    private val history = JSONArray()

    init {
        try {
            val saved = JSONArray(prefs.memory)
            for (i in 0 until saved.length()) history.put(saved.getJSONObject(i))
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun ask(user: String): Reply {
        val base = prefs.baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw IOException("Set the API URL in the Capri app first.")

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", Persona.build(prefs.personality)))
        for (i in 0 until history.length()) messages.put(history.getJSONObject(i))
        messages.put(JSONObject().put("role", "user").put("content", user))

        val body = JSONObject().put("model", prefs.model).put("messages", messages).toString()

        val conn = URL("$base/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (prefs.apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${prefs.apiKey}")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(180)}")

            val raw = JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()

            val tag = remindTag.find(raw)
            val clean = remindTag.replace(raw, "").trim()

            remember("user", user)
            remember("assistant", clean)
            return Reply(clean, tag?.groupValues?.get(1)?.toIntOrNull(), tag?.groupValues?.get(2)?.trim())
        } finally {
            conn.disconnect()
        }
    }

    private fun remember(role: String, content: String) {
        history.put(JSONObject().put("role", role).put("content", content))
        while (history.length() > 24) history.remove(0)
        prefs.memory = history.toString()
    }

    @Synchronized
    fun forget() {
        while (history.length() > 0) history.remove(0)
        prefs.memory = "[]"
    }
}
