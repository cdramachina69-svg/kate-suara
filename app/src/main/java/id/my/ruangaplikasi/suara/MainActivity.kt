package id.my.ruangaplikasi.suara

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONArray

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var recognizer: SpeechRecognizer? = null
    private val ui = Handler(Looper.getMainLooper())

    // Alamat halaman suara Anda
    private val startUrl = "https://ruangaplikasi.my.id/voice/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        setContentView(web)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleUrl(url)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        web.loadUrl(startUrl)
    }

    // Tautan halaman sendiri dimuat di WebView; selain itu dibuka lewat Android (tanpa aturan sentuhan Chrome)
    private fun handleUrl(url: String): Boolean {
        val host = Uri.parse(url).host ?: ""
        val isWeb = url.startsWith("http://") || url.startsWith("https://")
        if (isWeb && host.endsWith("ruangaplikasi.my.id")) return false
        try {
            if (url.startsWith("intent:")) {
                val i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                i.addCategory(Intent.CATEGORY_BROWSABLE)
                i.component = null
                i.selector = null
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } else {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            }
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Aplikasi tidak ditemukan", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membuka aplikasi", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun js(code: String) {
        ui.post { web.evaluateJavascript(code, null) }
    }

    // ---------- Pengenalan suara (native Android) ----------

    private fun startSpeech() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("window.onNativeError&&window.onNativeError(5)")
            return
        }
        stopSpeech()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                js("window.onNativeState&&window.onNativeState(true)")
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                js("window.onNativeResult&&window.onNativeResult(" + JSONArray(list).toString() + ")")
                js("window.onNativeState&&window.onNativeState(false)")
            }
            override fun onError(error: Int) {
                js("window.onNativeError&&window.onNativeError($error)")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        r.startListening(i)
    }

    private fun stopSpeech() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        recognizer = null
    }

    // ---------- Jembatan ke halaman web (window.Android) ----------

    inner class Bridge {
        @JavascriptInterface
        fun startListening() {
            ui.post { startSpeech() }
        }

        @JavascriptInterface
        fun stopListening() {
            ui.post { stopSpeech() }
        }

        // Membuka layar utama aplikasi lewat nama paket
        @JavascriptInterface
        fun openApp(pkg: String): Boolean {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ui.post {
                try {
                    startActivity(i)
                } catch (e: Exception) {
                }
            }
            return true
        }

        // Membuka layar sistem (kamera, pengaturan) lewat action
        @JavascriptInterface
        fun openAction(action: String): Boolean {
            val i = Intent(action)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(packageManager) == null) return false
            ui.post {
                try {
                    startActivity(i)
                } catch (e: Exception) {
                }
            }
            return true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            js("window.onNativeResume&&window.onNativeResume()")
        } else {
            js("window.onNativeError&&window.onNativeError(9)")
        }
    }

    override fun onResume() {
        super.onResume()
        js("window.onNativeResume&&window.onNativeResume()")
    }

    override fun onPause() {
        super.onPause()
        stopSpeech()
        js("window.onNativePause&&window.onNativePause()")
    }

    override fun onDestroy() {
        stopSpeech()
        super.onDestroy()
    }
}
