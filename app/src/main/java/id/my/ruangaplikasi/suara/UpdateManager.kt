package id.my.ruangaplikasi.suara

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pembaruan aplikasi: membaca versi.json di hosting, mengunduh APK baru, lalu (di MainActivity)
 * menyerahkannya ke pemasang Android. Android sendiri yang meminta persetujuan pengguna.
 */
object UpdateManager {

    private const val URL_VERSI = "https://ruangaplikasi.my.id/voice/versi.json"

    class Info(val code: Long, val name: String, val url: String, val note: String)

    fun currentCode(c: Context): Long = try {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    } catch (e: Exception) {
        0L
    }

    fun currentName(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /**
     * Mengecek versi.json (thread latar). [cb] menerima (info, berhasil):
     * info != null bila ada versi lebih baru; berhasil = false bila gagal terhubung/membaca.
     */
    fun check(c: Context, cb: (Info?, Boolean) -> Unit) {
        val ctx = c.applicationContext
        Thread {
            var info: Info? = null
            var ok = false
            try {
                val con = URL(URL_VERSI + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
                con.connectTimeout = 8000
                con.readTimeout = 8000
                con.setRequestProperty("Cache-Control", "no-cache")
                if (con.responseCode == 200) {
                    val raw = con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    // Hosting gratis kadang menempelkan teks tambahan: ambil hanya objek JSON pertama.
                    val a = raw.indexOf('{')
                    val b = raw.indexOf('}', a + 1)
                    if (a >= 0 && b > a) {
                        val j = JSONObject(raw.substring(a, b + 1))
                        val code = j.optLong("code", 0L)
                        val url = j.optString("url", "")
                        if (url.startsWith("https://")) {
                            ok = true
                            if (code > currentCode(ctx)) {
                                info = Info(code, j.optString("name", ""), url, j.optString("note", ""))
                            }
                        }
                    }
                }
                con.disconnect()
            } catch (e: Exception) {
                ok = false
            }
            cb(info, ok)
        }.start()
    }

    /** Mengunduh APK ke cache aplikasi (thread latar). [onDone] menerima file, atau null bila gagal. */
    fun download(c: Context, info: Info, onProgress: (Int) -> Unit, onDone: (File?) -> Unit) {
        val ctx = c.applicationContext
        Thread {
            var result: File? = null
            try {
                val dir = File(ctx.cacheDir, "updates")
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "kate-update.apk")
                val con = URL(info.url).openConnection() as HttpURLConnection
                con.connectTimeout = 10000
                con.readTimeout = 20000
                if (con.responseCode == 200) {
                    val total = con.contentLength.toLong()
                    var got = 0L
                    var last = -1
                    con.inputStream.use { ins ->
                        FileOutputStream(out).use { fos ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                fos.write(buf, 0, n)
                                got += n
                                if (total > 0) {
                                    val p = (got * 100 / total).toInt()
                                    if (p != last) {
                                        last = p
                                        onProgress(p)
                                    }
                                }
                            }
                        }
                    }
                    // APK adalah berkas ZIP (diawali "PK") dan tidak mungkin sekecil ini bila utuh
                    val okHeader = out.length() > 500_000L && out.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
                    if (okHeader) result = out else out.delete()
                }
                con.disconnect()
            } catch (e: Exception) {
                result = null
            }
            onDone(result)
        }.start()
    }
}
