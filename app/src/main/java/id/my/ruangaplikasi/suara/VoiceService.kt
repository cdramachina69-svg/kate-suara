package id.my.ruangaplikasi.suara

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** Status yang dibaca halaman web: state = off | idle | listening | command | speaking | paused */
object VoiceHub {
    @Volatile var activityVisible = false
    @Volatile var lastVisibleAt = 0L
    @Volatile var state = "off"
    @Volatile var text = ""
    @Volatile var listener: ((String, String) -> Unit)? = null
}

/**
 * Layanan latar depan: mendengarkan terus, menjawab dengan suara, membuka aplikasi,
 * dan menampilkan bola floating di atas aplikasi lain.
 */
class VoiceService : Service() {

    companion object {
        const val ACT_START = "id.my.ruangaplikasi.suara.START"
        const val ACT_STOP = "id.my.ruangaplikasi.suara.STOP"
        private const val CHANNEL = "kate_voice"
        private const val NOTIF_ID = 71
        private const val CHANNEL_OPEN = "kate_open"
        private const val NOTIF_OPEN_ID = 72
        private const val HINT = "Ucapkan \"Hallo Kate\""
        private const val TAP_HINT = "Ketuk bola, lalu ucapkan perintah"
        private const val CMD_HINT = "Silakan berikan perintah…"

        @Volatile
        var instance: VoiceService? = null

        fun start(ctx: Context, greet: Boolean) {
            val i = Intent(ctx, VoiceService::class.java)
            i.action = ACT_START
            i.putExtra("greet", greet)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var running = false
    private var greetPending = false

    private var rec: SpeechRecognizer? = null
    private var listening = false
    private var speaking = false
    private var paused = false
    private var commandUntil = 0L
    private var errCount = 0
    private var apps: List<AppEntry> = Brain.base

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var curUtt: String? = null
    private var curDone: (() -> Unit)? = null

    private var wm: WindowManager? = null
    private var orb: OrbView? = null

    private val restartRun = Runnable { startListening() }
    private val watchdog = Runnable {
        if (listening) {
            destroyRec()
            resumeListening()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACT_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (!running) {
            if (!goForeground()) {
                stopSelf()
                return START_NOT_STICKY
            }
            running = true
            instance = this
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            reloadApps()
            refreshOverlay()
            setState("idle", idleText())
            greetPending = intent.getBooleanExtra("greet", false)
            initTts()
            // cadangan bila mesin suara balasan (TTS) tidak merespons
            ui.postDelayed({
                if (running && !ttsReady && !listening && !speaking) {
                    greetPending = false
                    resumeListening()
                }
            }, 4000)
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ notifikasi

    private fun goForeground(): Boolean {
        return try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Kate Suara", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openIntent = Intent(this, MainActivity::class.java)
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, VoiceService::class.java)
        stopIntent.action = ACT_STOP
        val stop = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_stat)
        b.setContentTitle("Kate Suara aktif")
        b.setContentText("Ucapkan \"Hallo Kate\", lalu sebutkan perintah")
        b.setContentIntent(open)
        b.setOngoing(true)
        b.addAction(
            Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Berhenti", stop).build()
        )
        return b.build()
    }

    // ------------------------------------------------------------------ pengaturan

    fun reloadApps() {
        apps = Brain.base + Brain.parseCustom(Prefs.apps(this))
    }

    fun onSettingsChanged() {
        if (!running) return
        refreshOverlay()
        if (shouldListen()) {
            if (!listening && !speaking) resumeListening()
        } else {
            stopListening()
            if (!speaking) setState("idle", idleText())
        }
    }

    private fun idleText(): String = if (Prefs.loop(this)) HINT else TAP_HINT

    // ------------------------------------------------------------------ status

    private fun setState(s: String, t: String) {
        VoiceHub.state = s
        VoiceHub.text = t
        orb?.mode = orbMode()
        VoiceHub.listener?.invoke(s, t)
    }

    private fun orbMode(): Int = when (VoiceHub.state) {
        "paused", "off" -> 0
        "listening", "command" -> 2
        "speaking" -> 3
        else -> 1
    }

    private fun inCommand(): Boolean = System.currentTimeMillis() < commandUntil

    private fun shouldListen(): Boolean =
        running && !paused && !speaking && (Prefs.loop(this) || inCommand())

    // ------------------------------------------------------------------ suara balasan (TTS)

    private fun initTts() {
        tts = TextToSpeech(applicationContext) { status ->
            ui.post {
                if (!running) return@post
                if (status == TextToSpeech.SUCCESS) {
                    val t = tts
                    t?.setLanguage(Locale("id", "ID"))
                    t?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            val id = utteranceId ?: return
                            ui.post { finishSpeak(id) }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            val id = utteranceId ?: return
                            ui.post { finishSpeak(id) }
                        }
                    })
                    ttsReady = true
                }
                if (greetPending) {
                    greetPending = false
                    say("Selamat datang, saya Kate, siap melayani anda.")
                } else {
                    resumeListening()
                }
            }
        }
    }

    /**
     * Mengucapkan kalimat, menjalankan [done] setelah selesai, lalu kembali mendengarkan.
     * Mikrofon dijeda selama Kate berbicara.
     */
    private fun say(text: String, done: (() -> Unit)? = null) {
        if (!running) return
        if (!Prefs.talk(this) || !ttsReady || tts == null) {
            done?.invoke()
            if (!speaking) afterTurn()
            return
        }
        stopListening()
        speaking = true
        setState("speaking", text)
        val id = "u" + System.nanoTime()
        curUtt = id
        curDone = done
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        ui.postDelayed({ finishSpeak(id) }, 9000)
    }

    private fun finishSpeak(id: String) {
        if (curUtt != id) return
        curUtt = null
        speaking = false
        val d = curDone
        curDone = null
        d?.invoke()
        if (!speaking) afterTurn()
    }

    private fun afterTurn() {
        if (!running) return
        if (inCommand()) setState("command", CMD_HINT) else setState("idle", idleText())
        resumeListening()
    }

    // ------------------------------------------------------------------ pengenalan suara

    private fun resumeListening() {
        ui.removeCallbacks(restartRun)
        if (shouldListen()) ui.postDelayed(restartRun, 300)
    }

    private fun startListening() {
        if (!shouldListen() || listening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setState("off", "Izin mikrofon belum diberikan. Buka aplikasi Kate Suara lalu izinkan.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setState("idle", "Layanan suara Google tidak tersedia di HP ini.")
            return
        }
        destroyRec()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        rec = r
        r.setRecognitionListener(recListener)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        listening = true
        ui.removeCallbacks(watchdog)
        ui.postDelayed(watchdog, 20000)
        try {
            r.startListening(i)
        } catch (e: Exception) {
            listening = false
            ui.postDelayed(restartRun, 1500)
        }
    }

    private fun stopListening() {
        ui.removeCallbacks(restartRun)
        ui.removeCallbacks(watchdog)
        destroyRec()
    }

    private fun destroyRec() {
        listening = false
        try {
            rec?.cancel()
            rec?.destroy()
        } catch (e: Exception) {
        }
        rec = null
    }

    private val recListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (inCommand()) setState("command", CMD_HINT) else setState("listening", idleText())
        }

        override fun onResults(results: Bundle?) {
            ui.removeCallbacks(watchdog)
            listening = false
            errCount = 0
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            handleHeard(list)
            if (!speaking) afterTurn()
        }

        override fun onError(error: Int) {
            ui.removeCallbacks(watchdog)
            listening = false
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    errCount = 0
                    if (!inCommand()) setState("idle", idleText())
                    resumeListening()
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    setState("off", "Izin mikrofon ditolak. Aktifkan di pengaturan aplikasi.")
                }
                else -> {
                    errCount++
                    if (errCount >= 3) setState("idle", "Perlu koneksi internet dan layanan suara Google.")
                    ui.removeCallbacks(restartRun)
                    if (shouldListen()) ui.postDelayed(restartRun, min(6000L, 500L * errCount))
                }
            }
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ------------------------------------------------------------------ memahami perintah

    private fun handleHeard(list: List<String>) {
        if (list.isEmpty()) return
        val inCmd = inCommand()
        var wakeSeen = false
        var found: AppEntry? = null
        var rest = ""
        for (c in list) {
            val w = Brain.stripWake(c)
            if (w.found) wakeSeen = true
            val text = if (w.found) w.rest else c
            val app = Brain.findApp(text, apps)
            if (app != null && (w.found || inCmd || Brain.hasVerb(c))) {
                found = app
                break
            }
            if (w.found && rest.isEmpty()) rest = Brain.norm(w.rest)
        }
        when {
            found != null -> {
                commandUntil = 0L
                openByVoice(found)
            }
            wakeSeen -> {
                if (rest.isEmpty()) {
                    wakeReply()
                } else {
                    commandUntil = 0L
                    say("Maaf, saya belum mengenali aplikasi itu.")
                }
            }
            inCmd -> {
                commandUntil = 0L
                say("Maaf, saya belum mengenali aplikasi itu.")
            }
            else -> {
                // percakapan biasa di sekitar HP: diabaikan
            }
        }
    }

    private fun wakeReply() {
        say("Ya saya disini, silahkan berikan perintah.") {
            commandUntil = System.currentTimeMillis() + 12000L
        }
    }

    /** Dipanggil saat bola ditekan (halaman web atau floating): sama seperti mengucapkan "Hallo Kate". */
    fun wakeNow() {
        if (!running) return
        paused = false
        stopListening()
        wakeReply()
    }

    fun togglePause() {
        if (!running) return
        paused = !paused
        if (paused) {
            stopListening()
            setState("paused", "Kate dijeda. Tekan lama bola untuk mengaktifkan.")
            Toast.makeText(this, "Kate dijeda", Toast.LENGTH_SHORT).show()
        } else {
            setState("idle", idleText())
            Toast.makeText(this, "Kate mendengarkan", Toast.LENGTH_SHORT).show()
            resumeListening()
        }
    }

    private fun openByVoice(app: AppEntry) {
        if (!Prefs.auto(this)) {
            setState("idle", "Buka otomatis dimatikan di Setting.")
            return
        }
        val line = if (isBgBlocked()) {
            "Ketuk notifikasi untuk membuka " + app.name
        } else {
            "Baik, membuka " + app.name
        }
        say(line) {
            if (!openEntry(app)) say(app.name + " belum terpasang di HP ini.")
        }
    }

    /**
     * Android melarang aplikasi di latar belakang membuka aplikasi lain, kecuali ada jendela
     * aplikasi yang tampil, bola floating (izin "Tampil di atas aplikasi lain"), atau baru saja
     * dibuka (jeda sekitar 10 detik). Di luar itu pembukaan diganti notifikasi yang bisa diketuk.
     */
    private fun isBgBlocked(): Boolean =
        !VoiceHub.activityVisible && orb == null &&
            System.currentTimeMillis() - VoiceHub.lastVisibleAt > 8000L

    private fun buildIntent(app: AppEntry): Intent? {
        try {
            val act = app.action
            if (act != null) {
                val i = Intent(act)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return i
            }
            for (p in app.pkgs) {
                val i = packageManager.getLaunchIntentForPackage(p) ?: continue
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                return i
            }
            val u = app.url
            if (u != null) {
                val action = when {
                    u.startsWith("tel:") -> Intent.ACTION_DIAL
                    u.startsWith("sms:") || u.startsWith("mailto:") -> Intent.ACTION_SENDTO
                    else -> Intent.ACTION_VIEW
                }
                val i = Intent(action, Uri.parse(u))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return i
            }
        } catch (e: Exception) {
        }
        return null
    }

    private fun openEntry(app: AppEntry): Boolean {
        val i = buildIntent(app) ?: return false
        if (isBgBlocked()) {
            notifyOpen(app, i)
            return true
        }
        return try {
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun notifyOpen(app: AppEntry, i: Intent) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_OPEN, "Buka aplikasi", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val pi = PendingIntent.getActivity(
                this, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_OPEN) else Notification.Builder(this)
            b.setSmallIcon(R.drawable.ic_stat)
            b.setContentTitle("Buka " + app.name)
            b.setContentText("Ketuk untuk membuka " + app.name)
            b.setContentIntent(pi)
            b.setAutoCancel(true)
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(20000L)
            nm.notify(NOTIF_OPEN_ID, b.build())
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------------ bola floating

    fun refreshOverlay() {
        val want = running && Prefs.floating(this) && !VoiceHub.activityVisible && Prefs.canOverlay(this)
        if (want && orb == null) showOverlay() else if (!want && orb != null) hideOverlay()
    }

    private fun showOverlay() {
        val manager = wm ?: return
        val d = resources.displayMetrics
        val size = (72 * d.density).toInt()
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val sx = Prefs.floatX(this)
        val sy = Prefs.floatY(this)
        lp.x = if (sx >= 0) sx else d.widthPixels - size - (12 * d.density).toInt()
        lp.y = if (sy >= 0) sy else (d.heightPixels * 0.4f).toInt()
        val view = OrbView(this)
        view.mode = orbMode()
        view.setOnTouchListener(makeTouch(lp, manager))
        try {
            manager.addView(view, lp)
            orb = view
        } catch (e: Exception) {
            orb = null
        }
    }

    private fun hideOverlay() {
        val v = orb ?: return
        orb = null
        try {
            wm?.removeView(v)
        } catch (e: Exception) {
        }
    }

    private fun makeTouch(lp: WindowManager.LayoutParams, manager: WindowManager): View.OnTouchListener {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        return object : View.OnTouchListener {
            var startX = 0f
            var startY = 0f
            var origX = 0
            var origY = 0
            var moved = false
            var longDone = false
            val longRun = Runnable {
                longDone = true
                togglePause()
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = e.rawX
                        startY = e.rawY
                        origX = lp.x
                        origY = lp.y
                        moved = false
                        longDone = false
                        ui.postDelayed(longRun, 700)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - startX
                        val dy = e.rawY - startY
                        if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                            moved = true
                            ui.removeCallbacks(longRun)
                        }
                        if (moved) {
                            lp.x = (origX + dx).toInt()
                            lp.y = (origY + dy).toInt()
                            try {
                                manager.updateViewLayout(v, lp)
                            } catch (ex: Exception) {
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        ui.removeCallbacks(longRun)
                        if (moved) {
                            Prefs.setFloatPos(this@VoiceService, lp.x, lp.y)
                        } else if (!longDone) {
                            wakeNow()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(longRun)
                }
                return true
            }
        }
    }

    // ------------------------------------------------------------------ berhenti

    private fun shutdown() {
        running = false
        ui.removeCallbacksAndMessages(null)
        destroyRec()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
        ttsReady = false
        hideOverlay()
        setState("off", "Kate dimatikan. Ketuk bola untuk mengaktifkan lagi.")
        instance = null
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) {
            running = false
            ui.removeCallbacksAndMessages(null)
            destroyRec()
            try {
                tts?.shutdown()
            } catch (e: Exception) {
            }
            hideOverlay()
            setState("off", "Kate dimatikan. Ketuk bola untuk mengaktifkan lagi.")
        }
        instance = null
        super.onDestroy()
    }
}
