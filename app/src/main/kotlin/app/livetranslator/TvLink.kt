package app.livetranslator

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Tv(val id: String, val name: String, val address: String)

enum class PairResult { OK, WRONG_CODE, NEW_CODE, UNREACHABLE, NOT_SHARING }

/**
 * ربط التلفاز بنفس بروتوكول تطبيق VI Translator: تطبيق التلفاز يجاوب على
 * GET /vit-tv (المنفذ 8792)، ونرسل له الكود مع عنوان الجوال ومفتاح عبر POST /vit-tv/pair.
 * الطلبان يحتاجان الترويسة X-VIT.
 */
object TvLink {
    const val TV_PORT = 8792

    fun ownAddress(ctx: Context): Inet4Address? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return null
        return lp.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress } as Inet4Address?
    }

    /** "192.168.1.30" ← "192.168.1.30:8792"؛ null إذا مو عنوان صحيح. */
    fun normalize(address: String): String? {
        val m = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?::(\\d{1,5}))?$")
            .matchEntire(address.trim()) ?: return null
        if ((1..4).any { m.groupValues[it].toInt() > 255 }) return null
        val port = m.groupValues[5].ifEmpty { TV_PORT.toString() }.toInt()
        if (port !in 1..65535) return null
        return "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}.${m.groupValues[4]}:$port"
    }

    fun ask(address: String, connectMs: Int = 400, readMs: Int = 1500): Tv? {
        val (status, body) = request("GET", "http://$address/vit-tv", null, connectMs, readMs) ?: return null
        if (status != 200) return null
        return try {
            val o = JSONObject(body)
            if (o.optString("app") != "vi-translator-tv") return null
            Tv(o.optString("id"), o.optString("name").ifBlank { address }, address)
        } catch (e: Exception) {
            null
        }
    }

    /** كل تطبيقات التلفاز المنتظرة على نفس الشبكة (حوالي ثانيتين). */
    fun scan(ctx: Context): List<Tv> {
        val mine = ownAddress(ctx) ?: return emptyList()
        val b = mine.address
        val prefix = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}."
        val me = b[3].toInt() and 255
        val found = Collections.synchronizedList(mutableListOf<Tv>())
        val pool = Executors.newFixedThreadPool(64)
        for (i in 1..254) {
            if (i == me) continue
            pool.execute { ask("$prefix$i:$TV_PORT", 300, 1200)?.let { found.add(it) } }
        }
        pool.shutdown()
        try {
            pool.awaitTermination(15, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        pool.shutdownNow()
        return found.sortedBy { it.name }
    }

    /** يربط التلفاز بالكود اللي يعرضه، ويحفظه إذا نجح. */
    fun pairWith(ctx: Context, address: String, code: String): PairResult {
        val to = normalize(address) ?: return PairResult.UNREACHABLE
        if (!TvServer.isRunning) return PairResult.NOT_SHARING
        val own = ownAddress(ctx)?.hostAddress ?: return PairResult.NOT_SHARING
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        val (result, tv) = pair(to, code.trim(), "$own:${TvServer.PORT}", token, deviceName(ctx))
        if (result == PairResult.OK && tv != null) {
            val p = Prefs(ctx)
            val all = p.tvs().filter { it.id != tv.id || tv.id.isEmpty() }.toMutableList()
            all.add(PairedTv(tv.id, tv.name, tv.address, token))
            p.saveTvs(all)
        }
        return result
    }

    private fun pair(address: String, code: String, pc: String, token: String, pcName: String): Pair<PairResult, Tv?> {
        val body = JSONObject().put("code", code).put("pc", pc).put("token", token).put("pc_name", pcName).toString()
        val (status, answer) = request("POST", "http://$address/vit-tv/pair", body, 1500, 4000)
            ?: return PairResult.UNREACHABLE to null
        return when (status) {
            200 -> {
                val o = try { JSONObject(answer) } catch (e: Exception) { JSONObject() }
                PairResult.OK to Tv(o.optString("id"), o.optString("name").ifBlank { address }, address)
            }
            429 -> PairResult.NEW_CODE to null
            403 -> PairResult.WRONG_CODE to null
            else -> PairResult.UNREACHABLE to null
        }
    }

    private fun deviceName(ctx: Context): String {
        val n = try {
            Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (e: RuntimeException) {
            null
        }
        return (n?.trim()?.takeIf { it.isNotEmpty() } ?: Build.MODEL).take(60)
    }

    private fun request(method: String, url: String, body: String?, connectMs: Int, readMs: Int): Pair<Int, String>? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.useCaches = false
            c.setRequestProperty("X-VIT", "1")
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            status to (stream?.let { read(it, 64 * 1024) } ?: "")
        } catch (e: IOException) {
            null
        } finally {
            c?.disconnect()
        }
    }

    private fun read(input: InputStream, max: Int): String = input.use { s ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = s.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("too big")
        }
        out.toString("UTF-8")
    }
}
