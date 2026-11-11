package id.my.ruangaplikasi.suara

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.os.Build
import org.json.JSONObject

/** Pengaturan aplikasi, disimpan di HP. Dibaca oleh halaman web dan layanan suara. */
object Prefs {

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("kate", Context.MODE_PRIVATE)

    fun auto(c: Context) = sp(c).getBoolean("auto", true)
    fun talk(c: Context) = sp(c).getBoolean("talk", true)
    fun loop(c: Context) = sp(c).getBoolean("loop", true)
    fun floating(c: Context) = sp(c).getBoolean("floating", false)

    fun setBool(c: Context, key: String, v: Boolean) {
        sp(c).edit().putBoolean(key, v).apply()
    }

    fun apps(c: Context): String = sp(c).getString("apps", "[]") ?: "[]"
    fun setApps(c: Context, json: String) {
        sp(c).edit().putString("apps", json).apply()
    }

    fun floatX(c: Context): Int = sp(c).getInt("fx", -1)
    fun floatY(c: Context): Int = sp(c).getInt("fy", -1)
    fun setFloatPos(c: Context, x: Int, y: Int) {
        sp(c).edit().putInt("fx", x).putInt("fy", y).apply()
    }

    fun canOverlay(c: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c)

    fun json(c: Context): String {
        val o = JSONObject()
        o.put("auto", auto(c))
        o.put("talk", talk(c))
        o.put("loop", loop(c))
        o.put("floating", floating(c) && canOverlay(c))
        o.put("overlay", canOverlay(c))
        return o.toString()
    }
}
