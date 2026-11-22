package app.livetranslator

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

data class PairedTv(val id: String, val name: String, val address: String, val token: String)

/** الإعدادات المحفوظة: المصدر، اللغة، شكل الخط، والتلفازات المربوطة. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("live", Context.MODE_PRIVATE)

    /** 0 = الميكروفون (يسمع التلفاز)، 1 = صوت الجوال الداخلي */
    var source: Int
        get() = sp.getInt("source", 0)
        set(v) { sp.edit().putInt("source", v).apply() }

    var lang: Int
        get() = sp.getInt("lang", 0)
        set(v) { sp.edit().putInt("lang", v).apply() }

    var font: String
        get() = sp.getString("font", "Tajawal") ?: "Tajawal"
        set(v) { sp.edit().putString("font", v).apply() }

    var sizePct: Int
        get() = sp.getInt("size", 100)
        set(v) { sp.edit().putInt("size", v).apply() }

    var bgPct: Int
        get() = sp.getInt("bg", 70)
        set(v) { sp.edit().putInt("bg", v).apply() }

    var bold: Boolean
        get() = sp.getBoolean("bold", true)
        set(v) { sp.edit().putBoolean("bold", v).apply() }

    var color: String
        get() = sp.getString("color", "#ffffff") ?: "#ffffff"
        set(v) { sp.edit().putString("color", v).apply() }

    var top: Boolean
        get() = sp.getBoolean("top", false)
        set(v) { sp.edit().putBoolean("top", v).apply() }

    var showOnPhone: Boolean
        get() = sp.getBoolean("phone", false)
        set(v) { sp.edit().putBoolean("phone", v).apply() }

    fun tvs(): List<PairedTv> {
        val a = try { JSONArray(sp.getString("tvs", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            PairedTv(o.optString("id"), o.optString("name"), o.optString("address"), o.optString("token"))
        }
    }

    fun saveTvs(list: List<PairedTv>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("address", it.address).put("token", it.token))
        }
        sp.edit().putString("tvs", a.toString()).apply()
    }
}

/** رسائل الحالة من الخدمة إلى الشاشة. */
object Hub {
    @Volatile var lastStatus = ""
    @Volatile var onStatus: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun setStatus(s: String) {
        lastStatus = s
        main.post { onStatus?.invoke(s) }
    }
}
