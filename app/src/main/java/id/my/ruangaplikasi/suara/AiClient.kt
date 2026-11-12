package id.my.ruangaplikasi.suara

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Menanyakan pertanyaan ke ai.php di hosting (yang meneruskan ke Groq / Gemini). */
object AiClient {
    private const val URL_AI = "https://ruangaplikasi.my.id/voice/ai.php"
    private const val TOKEN = "67e6e34f7b28eee0436befb1376342cf"

    /** [cb] dipanggil dari thread latar dengan jawaban, atau null bila gagal. */
    fun ask(question: String, mode: String, cb: (String?) -> Unit) {
        Thread {
            var answer: String? = null
            try {
                val c = URL(URL_AI).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 6000
                c.readTimeout = 25000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setRequestProperty("X-Kate-Token", TOKEN)
                val body = JSONObject().put("q", question).put("mode", mode).toString()
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (c.responseCode == 200) {
                    val txt = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val j = JSONObject(txt)
                    if (j.optBoolean("ok")) answer = j.optString("text").ifBlank { null }
                }
                c.disconnect()
            } catch (e: Exception) {
                answer = null
            }
            cb(answer)
        }.start()
    }
}
