package app.capri.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var face: FaceView
    private lateinit var url: EditText
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var speak: CheckBox
    private lateinit var engine: Spinner
    private lateinit var ttsUrl: EditText
    private lateinit var ttsKey: EditText
    private lateinit var ttsModel: EditText
    private lateinit var ttsVoice: EditText
    private lateinit var elKey: EditText
    private lateinit var elVoice: EditText
    private lateinit var elModel: EditText
    private lateinit var personality: EditText
    private lateinit var sizeLabel: TextView
    private lateinit var size: SeekBar
    private lateinit var openaiBox: LinearLayout
    private lateinit var elBox: LinearLayout

    private val io = Executors.newSingleThreadExecutor()
    private var testVoice: Voice? = null
    private val engineIds = listOf("android", "openai", "elevenlabs")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(32))
            setBackgroundColor(0xFF111317.toInt())
        }

        // header: her face and name
        face = FaceView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { grin() }
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(face)
            addView(TextView(this@MainActivity).apply {
                text = "Capri"
                textSize = 32f
                setTextColor(0xFFFF2A6D.toInt())
                setPadding(dp(16), 0, 0, 0)
            })
        })

        // brain
        col.addView(label("Brain (any OpenAI-compatible API)"))
        url = field("API base URL", prefs.baseUrl); col.addView(url)
        key = field("API key (empty for local servers)", prefs.apiKey).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }; col.addView(key)
        model = field("Model", prefs.model); col.addView(model)

        // voice
        col.addView(label("Voice"))
        speak = CheckBox(this).apply {
            text = "Speak her replies out loud"
            setTextColor(Color.WHITE)
            isChecked = prefs.speak
        }
        col.addView(speak)

        engine = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Android voice (offline)", "OpenAI-compatible or my own server", "ElevenLabs")
            )
            setSelection(engineIds.indexOf(prefs.voiceEngine).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = showEngineFields(pos)
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        col.addView(engine)

        openaiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        openaiBox.addView(note("POST {url}/audio/speech. Works with OpenAI and with Kokoro-FastAPI on your PC " +
            "(for example http://192.168.0.10:8880/v1, model kokoro, voice af_bella). Empty key reuses the brain key."))
        ttsUrl = field("Voice server URL", prefs.ttsUrl); openaiBox.addView(ttsUrl)
        ttsKey = field("Voice key (optional)", prefs.ttsKey).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }; openaiBox.addView(ttsKey)
        ttsModel = field("Voice model", prefs.ttsModel); openaiBox.addView(ttsModel)
        ttsVoice = field("Voice name", prefs.ttsVoice); openaiBox.addView(ttsVoice)
        col.addView(openaiBox)

        elBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        elBox.addView(note("Make a key with text-to-speech access, then paste the voice id of the voice you made or picked."))
        elKey = field("ElevenLabs API key", prefs.elevenKey).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }; elBox.addView(elKey)
        elVoice = field("Voice id", prefs.elevenVoice); elBox.addView(elVoice)
        elModel = field("Model id", prefs.elevenModel); elBox.addView(elModel)
        col.addView(elBox)
        showEngineFields(engine.selectedItemPosition)

        col.addView(button("Test voice") { testVoiceNow() })

        // looks and personality
        col.addView(label("Size"))
        sizeLabel = TextView(this).apply { setTextColor(Color.WHITE) }
        col.addView(sizeLabel)
        size = SeekBar(this).apply {
            max = 100
            progress = (prefs.scale - 60).coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { sizeLabel.text = "${p + 60}%" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        sizeLabel.text = "${prefs.scale}%"
        col.addView(size)

        col.addView(label("Her personality (like personality.md)"))
        personality = EditText(this).apply {
            setText(prefs.personality.ifBlank { Persona.DEFAULT })
            setTextColor(Color.WHITE)
            textSize = 12f
            minLines = 5
            maxLines = 10
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        col.addView(personality)
        col.addView(button("Reset to her default") { personality.setText(Persona.DEFAULT) })

        // permissions and controls
        col.addView(label("Setup"))
        col.addView(button("1. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(button("2. Allow microphone and notifications") {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(perms.toTypedArray(), 1)
        })
        col.addView(button("Call Capri") { callCapri() })
        col.addView(button("Dismiss Capri") { stopService(Intent(this, CapriService::class.java)) })
        col.addView(button("Wipe her memory") {
            Brain(prefs).forget()
            Toast.makeText(this, "She forgot everything.", Toast.LENGTH_SHORT).show()
        })

        col.addView(TextView(this).apply {
            text = "Tap her to chat. Drag to move her. Hold to make her dance. Tap her face to make her grin. " +
                "Ask her to remind you of something and she will (while she is on screen). " +
                "She naps after a minute and a half of nothing.\n\n" +
                "Unofficial fan project, ported from lachydotmcg/capri-desktop (PolyForm Noncommercial 1.0.0). " +
                "Art and animations by Capri's artist. Don't use voices you have no right to use."
            setTextColor(0x99FFFFFF.toInt())
            textSize = 12f
            setPadding(0, dp(20), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun showEngineFields(pos: Int) {
        openaiBox.visibility = if (pos == 1) View.VISIBLE else View.GONE
        elBox.visibility = if (pos == 2) View.VISIBLE else View.GONE
    }

    private fun save() {
        prefs.baseUrl = url.text.toString()
        prefs.apiKey = key.text.toString()
        prefs.model = model.text.toString()
        prefs.speak = speak.isChecked
        prefs.voiceEngine = engineIds[engine.selectedItemPosition]
        prefs.ttsUrl = ttsUrl.text.toString()
        prefs.ttsKey = ttsKey.text.toString()
        prefs.ttsModel = ttsModel.text.toString()
        prefs.ttsVoice = ttsVoice.text.toString()
        prefs.elevenKey = elKey.text.toString()
        prefs.elevenVoice = elVoice.text.toString()
        prefs.elevenModel = elModel.text.toString()
        prefs.scale = size.progress + 60
        val p = personality.text.toString().trim()
        prefs.personality = if (p == Persona.DEFAULT.trim()) "" else p
    }

    private fun testVoiceNow() {
        save()
        val v = testVoice ?: Voice(this, prefs, Handler(Looper.getMainLooper()), io).also { testVoice = it }
        val line = "Hey, it's me. Do I sound right?"
        v.speak(line, object : Voice.Listener {
            override fun onStart(durationMs: Long) = face.talk(line, durationMs)
            override fun onRange(index: Int) = face.rangeAt(index)
            override fun onDone() = face.hush()
            override fun onFail(message: String) {
                Toast.makeText(this@MainActivity, "Voice: $message", Toast.LENGTH_LONG).show()
            }
        })
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onDestroy() {
        testVoice?.shutdown()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun callCapri() {
        save()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Do step 1 first.", Toast.LENGTH_LONG).show()
            return
        }
        if (CapriService.running) stopService(Intent(this, CapriService::class.java))
        ContextCompat.startForegroundService(this, Intent(this, CapriService::class.java))
        moveTaskToBack(true) // go home so she's visible on top of everything
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        setTextColor(0xFF05D9E8.toInt())
        textSize = 13f
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        setTextColor(0x99FFFFFF.toInt())
        textSize = 11f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun field(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(Color.WHITE)
        setHintTextColor(0x66FFFFFF)
        setSingleLine(true)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }
}
