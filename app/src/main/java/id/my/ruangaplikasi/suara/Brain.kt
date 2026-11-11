package id.my.ruangaplikasi.suara

import org.json.JSONArray
import java.util.Locale

/** Satu aplikasi/tujuan yang bisa dibuka dengan suara. */
data class AppEntry(
    val name: String,
    val aliases: List<String>,
    val pkgs: List<String> = emptyList(),
    val action: String? = null,
    val url: String? = null
)

/** Hasil membaca kata pemanggil "Hallo Kate". */
data class WakeResult(val found: Boolean, val rest: String)

object Brain {

    val base: List<AppEntry> = listOf(
        AppEntry("WhatsApp", listOf("wa", "whatsapp", "watsap", "wasap", "whatsap"), listOf("com.whatsapp")),
        AppEntry("WhatsApp Business", listOf("whatsapp bisnis", "wa bisnis", "wa business", "whatsapp business"), listOf("com.whatsapp.w4b")),
        AppEntry("Telegram", listOf("telegram", "telegrem", "tele"), listOf("org.telegram.messenger", "org.telegram.messenger.web")),
        AppEntry("Instagram", listOf("instagram", "ig", "insta"), listOf("com.instagram.android")),
        AppEntry("Facebook", listOf("facebook", "fb", "pesbuk", "feisbuk"), listOf("com.facebook.katana", "com.facebook.lite")),
        AppEntry("Messenger", listOf("messenger", "masenger", "mesenger"), listOf("com.facebook.orca")),
        AppEntry("TikTok", listOf("tiktok", "tik tok"), listOf("com.ss.android.ugc.trill", "com.zhiliaoapp.musically")),
        AppEntry("YouTube", listOf("youtube", "yutub", "you tube", "yutup"), listOf("com.google.android.youtube")),
        AppEntry("Chrome", listOf("chrome", "krom", "google chrome", "browser"), listOf("com.android.chrome")),
        AppEntry("Gmail", listOf("gmail", "jimel", "email google"), listOf("com.google.android.gm")),
        AppEntry("Google Maps", listOf("maps", "google maps", "peta", "gmaps"), listOf("com.google.android.apps.maps")),
        AppEntry("Spotify", listOf("spotify", "spotifai"), listOf("com.spotify.music")),
        AppEntry("X (Twitter)", listOf("x", "twitter", "twiter", "tweeter"), listOf("com.twitter.android")),
        AppEntry("Line", listOf("line", "lain"), listOf("jp.naver.line.android")),
        AppEntry("Tokopedia", listOf("tokopedia", "toped", "tokped"), listOf("com.tokopedia.tkpd")),
        AppEntry("Shopee", listOf("shopee", "sopi", "shopi"), listOf("com.shopee.id")),
        AppEntry("Gojek", listOf("gojek", "gojeg", "go jek"), listOf("com.gojek.app")),
        AppEntry("Grab", listOf("grab", "grep"), listOf("com.grabtaxi.passenger")),
        AppEntry("DANA", listOf("dana"), listOf("id.dana")),
        AppEntry("OVO", listOf("ovo", "opo"), listOf("ovo.id")),
        AppEntry("Kamera", listOf("kamera", "camera", "foto"), action = "android.media.action.STILL_IMAGE_CAMERA"),
        AppEntry("Pengaturan", listOf("pengaturan", "setelan", "setting", "settings"), action = "android.settings.SETTINGS"),
        AppEntry("Telepon", listOf("telepon", "telpon", "telfon", "phone", "dialer"), url = "tel:"),
        AppEntry("Pesan SMS", listOf("sms", "pesan", "pesan singkat"), url = "sms:"),
        AppEntry("Email", listOf("email", "surel", "e mail"), url = "mailto:")
    )

    private val fillerWords = Regex(
        "\\b(tolong|buka|bukakan|bukain|bukak|open|jalankan|jalanin|aplikasi|aplikasinya|app|launch|start|dong|ya|kak|saya|aku|ke|mulai|nyalakan)\\b"
    )
    private val nonAlnum = Regex("[^a-z0-9 ]")
    private val spaces = Regex("\\s+")

    private val verbs = setOf("buka", "bukakan", "bukain", "bukak", "open", "jalankan", "jalanin", "launch", "nyalakan")
    private val greetings = setOf("halo", "hallo", "helo", "hello", "hai", "hei", "hey", "hi", "alo", "ok", "oke", "okay")
    private val notKate = setOf("kita", "kota", "kali", "kata")

    fun clean(t: String): String =
        t.lowercase(Locale.ROOT).replace(nonAlnum, " ").replace(spaces, " ").trim()

    /** Buang kata pengisi (buka, tolong, aplikasi, ...) untuk pencocokan nama. */
    fun norm(t: String): String =
        clean(t).replace(fillerWords, " ").replace(spaces, " ").trim()

    fun hasVerb(t: String): Boolean = clean(t).split(" ").any { it in verbs }

    fun lev(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        var prev = IntArray(n + 1) { it }
        for (i in 1..m) {
            val cur = IntArray(n + 1)
            cur[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[n]
    }

    fun findApp(text: String, apps: List<AppEntry>): AppEntry? {
        val q = norm(text)
        if (q.isEmpty()) return null
        val qs = q.replace(" ", "")
        var best: AppEntry? = null
        var bestScore = 0
        for (app in apps) {
            val names = listOf(app.name) + app.aliases
            for (raw in names) {
                var nm = norm(raw)
                if (nm.isEmpty()) nm = raw.lowercase(Locale.ROOT)
                val ns = nm.replace(" ", "")
                var s = 0
                if (q == nm || qs == ns) s = 10
                else if (nm.length >= 3 && " $q ".contains(" $nm ")) s = 8
                else if (ns.length >= 4 && qs.contains(ns)) s = 7
                else if (qs.length >= 4 && ns.contains(qs)) s = 5
                else if (ns.length >= 4) {
                    val d = lev(qs, ns)
                    val lim = if (ns.length >= 7) 2 else 1
                    if (d <= lim) s = 6 - d
                }
                if (s > bestScore) {
                    bestScore = s
                    best = app
                }
            }
        }
        return if (bestScore >= 4) best else null
    }

    /**
     * Mengenali "Hallo Kate" (juga: halo kate, hai kate, hello kate, ok kate, atau hanya "kate").
     * rest = sisa kalimat tanpa kata pemanggil, mis. "hallo kate buka whatsapp" -> "buka whatsapp".
     */
    fun stripWake(text: String): WakeResult {
        val toks = clean(text).split(" ").filter { it.isNotEmpty() }
        for (i in toks.indices) {
            val t = toks[i]
            if (t in notKate) continue
            val prevGreet = i > 0 && toks[i - 1] in greetings
            val kateLike = t == "kate" || t == "kates" ||
                (prevGreet && t.length >= 3 && lev(t, "kate") <= 2)
            if (!kateLike) continue
            val rest = toks.filterIndexed { idx, _ -> idx != i && !(prevGreet && idx == i - 1) }
            return WakeResult(true, rest.joinToString(" "))
        }
        return WakeResult(false, text)
    }

    /** Daftar aplikasi buatan pengguna dari halaman web: [{n:"..",a:[".."],p:"..",s:".."}] */
    fun parseCustom(json: String?): List<AppEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<AppEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("n", "").trim()
                if (name.isEmpty()) continue
                val aliases = ArrayList<String>()
                val a = o.optJSONArray("a")
                if (a != null) for (k in 0 until a.length()) aliases.add(a.optString(k, ""))
                if (aliases.isEmpty()) aliases.add(name.lowercase(Locale.ROOT))
                val p = o.optString("p", "").trim()
                val s = o.optString("s", "").trim()
                out.add(
                    AppEntry(
                        name = name,
                        aliases = aliases,
                        pkgs = if (p.isNotEmpty()) listOf(p) else emptyList(),
                        url = if (s.isNotEmpty()) s else null
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
