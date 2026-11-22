package app.livetranslator

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * يخدم صفحة الترجمة للتلفاز: http://الجوال:8791/tv?t=المفتاح
 * الصفحة شفافة وتسحب النص من /tv.json، والخطوط من /tv/font، وكلها تحتاج مفتاح التلفاز المربوط.
 */
object TvServer {
    const val PORT = 8791

    /** الخطوط المتاحة: الاسم ← (الوزن ← ملف الخط في assets/fonts) */
    val FACES: Map<String, List<Pair<String, String>>> = linkedMapOf(
        "Tajawal" to listOf("400" to "Tajawal-Regular.ttf", "700" to "Tajawal-Bold.ttf"),
        "Cairo" to listOf("200 900" to "Cairo.ttf"),
        "Changa" to listOf("200 800" to "Changa.ttf"),
        "Lalezar" to listOf("400" to "Lalezar-Regular.ttf"),
        "Noto Sans Arabic" to listOf("100 900" to "NotoSansArabic.ttf"),
    )

    private val pool = Executors.newCachedThreadPool()
    private val build = SecureRandom().nextLong().toString(16)
    private val changed = Object()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var appCtx: Context? = null
    @Volatile private var version = 1L
    @Volatile private var text = ""

    val isRunning: Boolean get() = server != null

    @Synchronized
    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (server != null) return
        val s = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(PORT))
        }
        server = s
        pool.execute { serve(s) }
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        server = null
    }

    /** النص الحالي على التلفاز (فاضي = إخفاء). */
    fun setText(t: String) {
        if (t == text) return
        text = t
        touch()
    }

    /** نبّه الصفحات إن شي تغيّر (نص أو شكل الخط). */
    fun touch() {
        synchronized(changed) {
            version++
            changed.notifyAll()
        }
    }

    private fun serve(s: ServerSocket) {
        while (true) {
            val c = try { s.accept() } catch (e: IOException) { return }
            pool.execute {
                try {
                    c.use {
                        it.soTimeout = 5000
                        it.tcpNoDelay = true
                        handle(it)
                    }
                } catch (e: IOException) {
                    // التلفاز قطع الاتصال
                }
            }
        }
    }

    private fun handle(c: Socket) {
        val ctx = appCtx ?: return
        val input = BufferedInputStream(c.getInputStream())
        val request = line(input) ?: return
        while (true) {
            if (line(input).isNullOrEmpty()) break
        }
        val out = c.getOutputStream()
        val parts = request.split(" ")
        if (parts.size < 2 || parts[0] != "GET") return respond(out, 405, "text/plain", ByteArray(0))
        val path = parts[1].substringBefore('?')
        val query = parts[1].substringAfter('?', "")
        val prefs = Prefs(ctx)
        val token = param(query, "t")
        val allowed = token.isNotEmpty() && prefs.tvs().any { it.token == token }
        when (path) {
            "/tv", "/tv.json", "/tv/forget", "/tv/font" -> if (!allowed) return respond(out, 403, "text/plain", ByteArray(0))
            else -> return respond(out, 404, "text/plain", ByteArray(0))
        }
        when (path) {
            "/tv" -> respond(out, 200, "text/html; charset=utf-8", page(token).toByteArray(Charsets.UTF_8))
            "/tv/forget" -> {
                prefs.saveTvs(prefs.tvs().filter { it.token != token })
                respond(out, 200, "application/json", "{}".toByteArray())
            }
            "/tv/font" -> {
                val f = param(query, "f")
                if (FACES.values.none { faces -> faces.any { it.second == f } }) return respond(out, 404, "text/plain", ByteArray(0))
                val bytes = ctx.assets.open("fonts/$f").use { it.readBytes() }
                respond(out, 200, "font/ttf", bytes, "Cache-Control: max-age=86400\r\n")
            }
            else -> {
                // ننتظر ثانية كحد أقصى لأي تغيير (long-poll)
                val seen = param(query, "h").toLongOrNull()
                val until = SystemClock.elapsedRealtime() + 1000
                synchronized(changed) {
                    while (seen == version && SystemClock.elapsedRealtime() < until) {
                        changed.wait((until - SystemClock.elapsedRealtime()).coerceAtLeast(1))
                    }
                }
                if (seen == version) return respond(out, 204, "text/plain", ByteArray(0))
                val v = version
                respond(out, 200, "application/json; charset=utf-8", state(prefs).toString().toByteArray(Charsets.UTF_8), "X-State: $v\r\n")
            }
        }
    }

    private fun state(p: Prefs): JSONObject = JSONObject()
        .put("build", build)
        .put("overlays", true)
        .put("text", text)
        .put("rtl", true)
        .put("font", p.font)
        .put("size", p.sizePct)
        .put("bg", p.bgPct)
        .put("bold", p.bold)
        .put("color", p.color)
        .put("top", p.top)

    private fun fontCss(token: String): String = FACES.entries.joinToString("\n") { (family, faces) ->
        faces.joinToString("\n") { (weight, file) ->
            "@font-face{font-family:\"$family\";font-weight:$weight;src:url(\"tv/font?f=$file&t=$token\")}"
        }
    }

    private fun page(token: String): String = PAGE.replace("%%FONTS%%", fontCss(token))

    private fun param(query: String, name: String): String {
        for (p in query.split('&')) {
            if (p.substringBefore('=') == name) {
                return try {
                    URLDecoder.decode(p.substringAfter('=', ""), "UTF-8")
                } catch (e: IllegalArgumentException) {
                    ""
                }
            }
        }
        return ""
    }

    private fun line(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        var c: Int
        while (true) {
            c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
            if (b.size() > 8192) throw IOException("line too long")
        }
        if (c < 0 && b.size() == 0) return null
        return b.toString("ISO-8859-1")
    }

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray, extra: String = "") {
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            403 -> "Forbidden"
            405 -> "Method Not Allowed"
            else -> "Not Found"
        }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n$extra" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    /** صفحة التلفاز: شفافة، والترجمة العربية في شريط أسفل (أو أعلى) الشاشة. */
    private val PAGE = """
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>المترجم الفوري</title>
<style>
%%FONTS%%
html,body{margin:0;height:100%;background:transparent;overflow:hidden}
#w{position:fixed;left:0;right:0;display:flex;justify-content:center;padding:0 6vw;box-sizing:border-box}
#b{display:none;border-radius:.3em;padding:.2em .6em;text-align:center;line-height:1.45;max-width:88vw;text-shadow:0 0 .1em #000}
</style></head><body><div id="w"><div id="b" dir="rtl"></div></div>
<script>
const key=new URLSearchParams(location.search).get("t")||"";let v="",build=null;
const w=document.getElementById("w"),b=document.getElementById("b");
function alive(){if(window.VITTV)window.VITTV.alive();try{if(window.parent!==window)window.parent.postMessage({vit:"alive"},"*")}catch(e){}}
function draw(s){
 if(!s||s.overlays===false||!s.text){b.style.display="none";return}
 b.textContent=s.text;b.style.display="block";
 b.style.fontFamily='"'+s.font+'",sans-serif';
 b.style.fontWeight=s.bold?700:400;
 b.style.fontSize="calc(100vh/1080*"+(44*s.size/100)+")";
 b.style.color=s.color;
 b.style.background="rgba(0,0,0,"+(s.bg/100)+")";
 if(s.top){w.style.top="5vh";w.style.bottom="auto"}else{w.style.bottom="7vh";w.style.top="auto"}
}
const timed=(p,ms)=>Promise.race([p,new Promise((_,n)=>setTimeout(()=>n(new Error("no answer")),ms))]);
async function poll(){let wait=0;try{const r=await timed(fetch("tv.json?t="+encodeURIComponent(key)+"&h="+encodeURIComponent(v),{cache:"no-store"}),6000);alive();
 if(r.status===200){const s=await timed(r.json(),6000);v=r.headers.get("X-State")||"";const re=build!==null&&s.build!==build;build=s.build;draw(s);if(re)location.reload()}
 else if(r.status!==204){draw(null);v="";wait=2000}}catch(e){draw(null);v="";wait=1000}setTimeout(poll,wait)}
poll();
</script></body></html>
""".trimIndent()
}
