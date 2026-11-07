package app.capri.mobile

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("capri", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("baseUrl", "https://api.openai.com/v1") ?: ""
        set(v) = sp.edit().putString("baseUrl", v.trim()).apply()

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()

    var model: String
        get() = sp.getString("model", "gpt-4o-mini") ?: ""
        set(v) = sp.edit().putString("model", v.trim()).apply()

    var speak: Boolean
        get() = sp.getBoolean("speak", true)
        set(v) = sp.edit().putBoolean("speak", v).apply()

    var memory: String
        get() = sp.getString("memory", "[]") ?: "[]"
        set(v) = sp.edit().putString("memory", v).apply()

    var posX: Int
        get() = sp.getInt("posX", 24)
        set(v) = sp.edit().putInt("posX", v).apply()

    var posY: Int
        get() = sp.getInt("posY", 400)
        set(v) = sp.edit().putInt("posY", v).apply()

    var voiceEngine: String
        get() = sp.getString("voiceEngine", "android") ?: "android"
        set(v) = sp.edit().putString("voiceEngine", v).apply()

    var ttsUrl: String
        get() = sp.getString("ttsUrl", "https://api.openai.com/v1") ?: ""
        set(v) = sp.edit().putString("ttsUrl", v.trim()).apply()

    var ttsKey: String
        get() = sp.getString("ttsKey", "") ?: ""
        set(v) = sp.edit().putString("ttsKey", v.trim()).apply()

    var ttsModel: String
        get() = sp.getString("ttsModel", "gpt-4o-mini-tts") ?: ""
        set(v) = sp.edit().putString("ttsModel", v.trim()).apply()

    var ttsVoice: String
        get() = sp.getString("ttsVoice", "alloy") ?: ""
        set(v) = sp.edit().putString("ttsVoice", v.trim()).apply()

    var elevenKey: String
        get() = sp.getString("elevenKey", "") ?: ""
        set(v) = sp.edit().putString("elevenKey", v.trim()).apply()

    var elevenVoice: String
        get() = sp.getString("elevenVoice", "") ?: ""
        set(v) = sp.edit().putString("elevenVoice", v.trim()).apply()

    var elevenModel: String
        get() = sp.getString("elevenModel", "eleven_multilingual_v2") ?: ""
        set(v) = sp.edit().putString("elevenModel", v.trim()).apply()

    /** Blank means her default personality. */
    var personality: String
        get() = sp.getString("personality", "") ?: ""
        set(v) = sp.edit().putString("personality", v).apply()

    /** Sprite size in percent. */
    var scale: Int
        get() = sp.getInt("scale", 100)
        set(v) = sp.edit().putInt("scale", v).apply()
}
