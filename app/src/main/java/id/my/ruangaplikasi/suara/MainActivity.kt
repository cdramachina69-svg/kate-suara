package id.my.ruangaplikasi.suara

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private var pendingFloating = false
    private var pendingApk: File? = null
    private var dlDialog: AlertDialog? = null

    // Alamat halaman suara Anda
    private val startUrl = "https://ruangaplikasi.my.id/voice/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

            override fun onPageFinished(view: WebView?, url: String?) {
                pushSettings()
                pushStatus(VoiceHub.state, VoiceHub.text)
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

        requestNeededPermissions()
        if (hasMic() && VoiceService.instance == null) {
            VoiceService.start(this, true)
        }
        web.loadUrl(startUrl)
        checkUpdate(false)
    }

    // ------------------------------------------------------------------ izin

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val need = ArrayList<String>()
        if (!hasMic()) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasMic()) {
            if (VoiceService.instance == null) VoiceService.start(this, true)
        } else {
            pushStatus("off", "Izin mikrofon ditolak. Aktifkan di Setelan > Aplikasi > Kate Suara > Izin.")
        }
    }

    // ------------------------------------------------------------------ siklus hidup

    override fun onStart() {
        super.onStart()
        VoiceHub.listener = { s, t -> ui.post { pushStatus(s, t) } }
    }

    override fun onResume() {
        super.onResume()
        VoiceHub.activityVisible = true
        VoiceService.instance?.refreshOverlay()
        if (pendingFloating && Prefs.canOverlay(this)) {
            pendingFloating = false
            Prefs.setBool(this, "floating", true)
            VoiceService.instance?.onSettingsChanged()
        } else if (pendingFloating) {
            pendingFloating = false
        }
        pushSettings()
        pushStatus(VoiceHub.state, VoiceHub.text)
        val apk = pendingApk
        if (apk != null && canInstallApk()) {
            pendingApk = null
            installApk(apk)
        }
    }

    // ------------------------------------------------------------------ pembaruan aplikasi

    private fun canInstallApk(): Boolean =
        Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    /** manual = true: dari tombol "Cek update", memberi tahu bila sudah terbaru atau gagal. */
    private fun checkUpdate(manual: Boolean) {
        UpdateManager.check(this) { info, ok ->
            ui.post {
                if (isFinishing) return@post
                if (info != null) {
                    showUpdateDialog(info)
                } else if (manual) {
                    Toast.makeText(
                        this,
                        if (ok) "Aplikasi sudah versi terbaru" else "Tidak bisa memeriksa pembaruan. Periksa internet.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showUpdateDialog(info: UpdateManager.Info) {
        val note = if (info.note.isNotBlank()) "\n\n" + info.note else ""
        AlertDialog.Builder(this)
            .setTitle("Versi baru tersedia")
            .setMessage("Kate Suara " + info.name + note + "\n\nPerbarui sekarang?")
            .setPositiveButton("Update") { _, _ -> startDownload(info) }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun startDownload(info: UpdateManager.Info) {
        dlDialog?.dismiss()
        dlDialog = AlertDialog.Builder(this)
            .setTitle("Memperbarui")
            .setMessage("Mengunduh… 0%")
            .setCancelable(false)
            .show()
        UpdateManager.download(this, info, { p ->
            ui.post { dlDialog?.setMessage("Mengunduh… $p%") }
        }) { file ->
            ui.post {
                dlDialog?.dismiss()
                dlDialog = null
                if (file == null) {
                    Toast.makeText(this, "Gagal mengunduh pembaruan. Coba lagi nanti.", Toast.LENGTH_LONG).show()
                } else if (!isFinishing) {
                    installApk(file)
                }
            }
        }
    }

    private fun installApk(file: File) {
        if (!canInstallApk()) {
            pendingApk = file
            AlertDialog.Builder(this)
                .setTitle("Izin pemasangan")
                .setMessage(
                    "Agar bisa memperbarui, izinkan Kate Suara memasang aplikasi. " +
                        "Ketuk Lanjut, nyalakan \"Izinkan dari sumber ini\", lalu kembali ke Kate Suara."
                )
                .setPositiveButton("Lanjut") { _, _ ->
                    try {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName))
                        )
                    } catch (e: Exception) {
                        pendingApk = null
                        Toast.makeText(this, "Buka Setelan > Aplikasi > Kate Suara > Pasang aplikasi tidak dikenal", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Batal") { _, _ -> pendingApk = null }
                .show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/vnd.android.package-archive")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membuka pemasang pembaruan", Toast.LENGTH_LONG).show()
        }
    }

    private fun showOverlayHelp() {
        AlertDialog.Builder(this)
            .setTitle("Izin Floating")
            .setMessage(
                "Ketuk Lanjut, lalu nyalakan \"Tampil di atas aplikasi lain\" untuk Kate Suara.\n\n" +
                    "Jika muncul pesan \"App was denied access\" (akses ditolak), Android membatasi aplikasi " +
                    "yang dipasang dari APK. Caranya:\n" +
                    "1. Ketuk Info Aplikasi.\n" +
                    "2. Ketuk titik tiga di kanan atas.\n" +
                    "3. Pilih \"Izinkan pengaturan terbatas\" (Allow restricted settings).\n" +
                    "4. Kembali ke Kate Suara, lalu nyalakan Floating lagi."
            )
            .setPositiveButton("Lanjut") { _, _ -> openOverlaySettings() }
            .setNeutralButton("Info Aplikasi") { _, _ -> openAppInfo() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun openOverlaySettings() {
        pendingFloating = true
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
            )
        } catch (e: Exception) {
            pendingFloating = false
            Toast.makeText(
                this,
                "Buka Setelan > Aplikasi > Kate Suara > Tampil di atas aplikasi lain",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openAppInfo() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName))
            )
        } catch (e: Exception) {
        }
    }

    override fun onPause() {
        VoiceHub.lastVisibleAt = System.currentTimeMillis()
        VoiceHub.activityVisible = false
        VoiceService.instance?.refreshOverlay()
        super.onPause()
    }

    override fun onStop() {
        VoiceHub.listener = null
        super.onStop()
    }

    // ------------------------------------------------------------------ halaman web

    private fun js(code: String) {
        ui.post { web.evaluateJavascript(code, null) }
    }

    private fun pushStatus(state: String, text: String) {
        js("window.onNativeStatus&&window.onNativeStatus(" + JSONObject.quote(state) + "," + JSONObject.quote(text) + ")")
    }

    private fun pushSettings() {
        js("window.onNativeSettings&&window.onNativeSettings(" + Prefs.json(this) + ")")
    }

    // Tautan halaman sendiri dimuat di WebView; selain itu dibuka lewat Android
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

    // ------------------------------------------------------------------ jembatan ke halaman web (window.Android)

    inner class Bridge {

        @JavascriptInterface
        fun getSettings(): String = Prefs.json(this@MainActivity)

        @JavascriptInterface
        fun getVersion(): String = UpdateManager.currentName(this@MainActivity)

        @JavascriptInterface
        fun checkUpdate() {
            ui.post { this@MainActivity.checkUpdate(true) }
        }

        @JavascriptInterface
        fun setSetting(key: String, value: Boolean) {
            if (key != "auto" && key != "talk" && key != "loop" && key != "ai" && key != "screenOnly") return
            Prefs.setBool(this@MainActivity, key, value)
            ui.post { VoiceService.instance?.onSettingsChanged() }
        }

        /** Pilihan AI: auto | groq | gemini */
        @JavascriptInterface
        fun setAiMode(mode: String) {
            if (mode != "auto" && mode != "groq" && mode != "gemini") return
            Prefs.setAiMode(this@MainActivity, mode)
        }

        /** Mengaktifkan/mematikan bola floating. Mengembalikan true bila langsung berlaku. */
        @JavascriptInterface
        fun setFloating(value: Boolean): Boolean {
            if (!value) {
                Prefs.setBool(this@MainActivity, "floating", false)
                ui.post { VoiceService.instance?.onSettingsChanged() }
                return true
            }
            if (Prefs.canOverlay(this@MainActivity)) {
                Prefs.setBool(this@MainActivity, "floating", true)
                ui.post { VoiceService.instance?.onSettingsChanged() }
                return true
            }
            ui.post { showOverlayHelp() }
            return false
        }

        /** Daftar aplikasi buatan pengguna (JSON) untuk dikenali layanan suara. */
        @JavascriptInterface
        fun setApps(json: String) {
            Prefs.setApps(this@MainActivity, json)
            ui.post { VoiceService.instance?.reloadApps() }
        }

        /** Dipanggil halaman saat dibuka: pastikan layanan suara berjalan. */
        @JavascriptInterface
        fun start() {
            ui.post {
                if (hasMic() && VoiceService.instance == null) VoiceService.start(this@MainActivity, false)
            }
        }

        /** Bola di halaman ditekan: sama seperti mengucapkan "Hallo Kate". */
        @JavascriptInterface
        fun wake() {
            ui.post {
                val s = VoiceService.instance
                if (s != null) {
                    s.wakeNow()
                } else if (hasMic()) {
                    VoiceService.start(this@MainActivity, true)
                } else {
                    requestNeededPermissions()
                }
            }
        }

        // Pembukaan manual dari daftar aplikasi
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

        @JavascriptInterface
        fun openAction(action: String): Boolean {
            val i = Intent(action)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ui.post {
                try {
                    startActivity(i)
                } catch (e: Exception) {
                }
            }
            return true
        }
    }

    override fun onDestroy() {
        VoiceHub.listener = null
        super.onDestroy()
    }
}
