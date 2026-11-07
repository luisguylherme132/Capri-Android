package app.capri.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CapriService : Service() {

    companion object {
        const val ACTION_STOP = "app.capri.mobile.STOP"
        private const val CHANNEL = "capri"
        private const val REMIND_CHANNEL = "capri_remind"
        private const val NOTIF_ID = 1
        private const val PINK = 0xFFFF2A6D.toInt()
        private const val CYAN = 0xFF05D9E8.toInt()
        private const val DARK = 0xE61B1D21.toInt()
        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val rng = Random()

    private lateinit var prefs: Prefs
    private lateinit var brain: Brain
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var root: LinearLayout
    private lateinit var sprite: SpriteView
    private lateinit var bubble: TextView
    private lateinit var card: LinearLayout
    private lateinit var face: FaceView
    private lateinit var voice: Voice
    private var reminderId = 100
    private lateinit var inputRow: LinearLayout
    private lateinit var input: EditText

    private var recognizer: SpeechRecognizer? = null

    private var chatOpen = false
    private var asleep = false
    private var speaking = false
    private var thinking = false
    private var dragging = false
    private var lastActive = SystemClock.uptimeMillis()
    private var savedY = 0

    private val hideBubble = Runnable { if (!chatOpen) { card.visibility = View.GONE; face.hush() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        // Must happen right away after startForegroundService(), even if we bail out below.
        startForeground(NOTIF_ID, buildNotification())
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"display over other apps\" for Capri first.", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        running = true
        prefs = Prefs(this)
        brain = Brain(prefs)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildOverlay()
        voice = Voice(this, prefs, main, io)

        sprite.play(Clips.ARRIVE, loop = false) { rest() }
        scheduleLife()
    }

    // ---------------------------------------------------------------- overlay UI

    private fun buildOverlay() {
        sprite = SpriteView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(110 * prefs.scale / 100), dp(176 * prefs.scale / 100))
        }

        face = FaceView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { grin(); markActive() }
        }

        bubble = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxWidth = dp(190)
            setPadding(dp(10), dp(4), dp(4), dp(4))
        }

        card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(DARK); cornerRadius = dp(14).toFloat(); setStroke(dp(2), PINK)
            }
            addView(face)
            addView(bubble)
            visibility = View.GONE
        }

        input = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
            hint = "Talk to Capri..."
            textSize = 14f
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { submitInput(); true } else false
            }
        }
        val mic = pill("MIC") { startListening() }
        val send = pill("SEND") { submitInput() }

        inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                setColor(DARK); cornerRadius = dp(18).toFloat(); setStroke(dp(2), CYAN)
            }
            addView(input, LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(mic)
            addView(send)
            visibility = View.GONE
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(sprite)
            addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) })
            addView(inputRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.posX
            y = prefs.posY
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        wm.addView(root, lp)
        attachTouch()
    }

    private fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 11f
        setTextColor(Color.WHITE)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = GradientDrawable().apply { setColor(PINK); cornerRadius = dp(12).toFloat() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun attachTouch() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0; var startY = 0
        var downX = 0f; var downY = 0f; var lastX = 0f
        var moved = false
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            sprite.play(Clips.DANCE, loop = false) { sprite.play(Clips.DANCE, loop = false) { rest() } }
        }

        sprite.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    downX = e.rawX; downY = e.rawY; lastX = e.rawX
                    moved = false; longPressed = false
                    markActive()
                    main.postDelayed(longPress, 650)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        dragging = true
                        main.removeCallbacks(longPress)
                        sprite.play(Clips.WALK)
                    }
                    if (moved) {
                        if (abs(e.rawX - lastX) > 2) sprite.flip = e.rawX < lastX
                        lastX = e.rawX
                        val dm = resources.displayMetrics
                        lp.x = max(0, min(dm.widthPixels - root.width, (startX + dx).toInt()))
                        lp.y = max(0, min(dm.heightPixels - root.height, (startY + dy).toInt()))
                        wm.updateViewLayout(root, lp)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (moved) {
                        dragging = false
                        sprite.flip = false
                        prefs.posX = lp.x; prefs.posY = lp.y
                        rest()
                    } else if (!longPressed && e.actionMasked == MotionEvent.ACTION_UP) {
                        toggleChat()
                    }
                }
            }
            true
        }
    }

    private fun setFocusable(on: Boolean) {
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        wm.updateViewLayout(root, lp)
    }

    private fun toggleChat() = if (chatOpen) closeChat() else openChat()

    private fun openChat() {
        chatOpen = true
        savedY = lp.y
        val h = resources.displayMetrics.heightPixels
        if (lp.y > h * 0.4) lp.y = (h * 0.12).toInt() // keep her above the keyboard
        inputRow.visibility = View.VISIBLE
        setFocusable(true)
        input.requestFocus()
        main.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun closeChat() {
        chatOpen = false
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        inputRow.visibility = View.GONE
        lp.y = savedY
        setFocusable(false)
        main.postDelayed(hideBubble, 3000)
    }

    // ---------------------------------------------------------------- behaviour

    private fun rest() {
        sprite.flip = false
        sprite.play(if (asleep) Clips.SLEEP else Clips.IDLE)
    }

    private fun markActive() {
        lastActive = SystemClock.uptimeMillis()
        if (asleep) {
            asleep = false
            sprite.play(Clips.WAKE, loop = false) { rest() }
        }
    }

    private fun scheduleLife() {
        main.postDelayed({
            if (!running) return@postDelayed
            val busy = speaking || thinking || dragging
            if (!busy) {
                val idleFor = SystemClock.uptimeMillis() - lastActive
                if (!asleep && !chatOpen && idleFor > 90_000) {
                    asleep = true
                    sprite.play(Clips.SLEEP)
                } else if (!asleep) {
                    val r = rng.nextInt(100)
                    if (r < 45) sprite.play(Clips.BLINK, loop = false) { rest() }
                    else if (r < 52) sprite.play(Clips.GLITCH, loop = false) { rest() }
                }
            }
            scheduleLife()
        }, 4000)
    }

    private fun showBubble(text: String) {
        main.removeCallbacks(hideBubble)
        bubble.text = text
        card.visibility = View.VISIBLE
        if (!chatOpen) main.postDelayed(hideBubble, max(5000L, text.length * 70L))
    }

    private fun submitInput() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        send(text)
    }

    private fun send(text: String) {
        markActive()
        thinking = true
        showBubble("...")
        io.execute {
            val result = runCatching { brain.ask(text) }
            main.post {
                thinking = false
                val reply = result.getOrNull()
                if (reply == null) {
                    val e = result.exceptionOrNull()
                    say("Brain error: ${e?.message ?: e?.javaClass?.simpleName ?: "unknown"}")
                } else {
                    val mins = reply.remindMinutes
                    if (mins != null && mins > 0) scheduleReminder(mins, reply.remindText ?: "something")
                    say(reply.text.ifBlank { "Okay, I'll remind you." })
                }
            }
        }
    }

    private fun say(text: String) {
        showBubble(text)
        speaking = true
        sprite.play(Clips.GRIN)
        if (prefs.speak) {
            voice.speak(text, object : Voice.Listener {
                override fun onStart(durationMs: Long) = face.talk(text, durationMs)
                override fun onRange(index: Int) = face.rangeAt(index)
                override fun onDone() { endSpeech() }
                override fun onFail(message: String) {
                    Toast.makeText(this@CapriService, "Voice: $message", Toast.LENGTH_LONG).show()
                }
            })
        } else {
            val ms = min(6000L, max(1500L, text.length * 55L))
            face.talk(text, ms)
            main.postDelayed({ endSpeech() }, ms)
        }
    }

    private fun endSpeech() {
        speaking = false
        face.hush()
        rest()
    }

    // ---------------------------------------------------------------- reminders

    private fun scheduleReminder(minutes: Int, what: String) {
        main.postDelayed({
            val nm = getSystemService(NotificationManager::class.java)
            val n = Notification.Builder(this, REMIND_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Capri")
                .setContentText(what)
                .setAutoCancel(true)
                .build()
            nm.notify(reminderId++, n)
            markActive()
            say("Hey. Time for this: $what")
        }, minutes * 60_000L)
    }

    // ---------------------------------------------------------------- voice

    private fun startListening() {
        markActive()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Give Capri the microphone permission in her app.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "No speech recognizer on this phone.", Toast.LENGTH_LONG).show()
            return
        }
        voice.stop()
        if (speaking) endSpeech()
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) = showBubble("Listening...")
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
                override fun onPartialResults(p: Bundle?) {
                    p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.let { if (chatOpen) input.setText(it) else showBubble(it) }
                }
                override fun onResults(p: Bundle?) {
                    val text = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    input.setText("")
                    if (!text.isNullOrBlank()) send(text) else showBubble("Didn't catch that.")
                }
                override fun onError(code: Int) = showBubble("Didn't catch that.")
            })
            recognizer = r
        }
        rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    // ---------------------------------------------------------------- notification / teardown

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Capri", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(REMIND_CHANNEL, "Capri reminders", NotificationManager.IMPORTANCE_HIGH))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CapriService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Capri is hanging out on your screen")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss her", stop).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        if (::sprite.isInitialized) sprite.stop()
        if (::root.isInitialized) try { wm.removeView(root) } catch (_: Exception) {}
        if (::voice.isInitialized) voice.shutdown()
        recognizer?.destroy()
        io.shutdownNow()
        super.onDestroy()
    }
}
