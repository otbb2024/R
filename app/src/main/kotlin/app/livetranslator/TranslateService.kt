package app.livetranslator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/**
 * الصوت (ميكروفون أو صوت الجوال) ← Vosk يحوّله لنص ← ML Kit يترجمه للعربية
 * ← يظهر على التلفاز (وعلى الجوال إذا فعّلت ذلك).
 */
class TranslateService : Service() {

    companion object {
        const val ACTION_START = "app.livetranslator.START"
        const val ACTION_STOP = "app.livetranslator.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_LANG = "lang"
        const val EXTRA_SOURCE = "source" // 0 ميكروفون، 1 صوت الجوال

        @Volatile
        var running = false

        private const val CHANNEL = "live"
        private const val NOTIF_ID = 1
        private const val RATE = 16000
    }

    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlay: TextView? = null
    private var projection: MediaProjection? = null
    private var worker: Thread? = null

    @Volatile private var translator: Translator? = null
    @Volatile private var active = false

    private val seq = AtomicLong()
    private var shown = 0L // من الخيط الرئيسي فقط
    private val clearRunnable = Runnable {
        TvServer.setText("")
        overlay?.visibility = View.GONE
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i = intent ?: return START_NOT_STICKY
        if (i.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (i.action != ACTION_START || active) return START_NOT_STICKY

        val source = i.getIntExtra(EXTRA_SOURCE, 0)
        startInForeground(source)

        if (source == 1) {
            val data = projectionData(i)
            if (data == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val mp = mpm.getMediaProjection(i.getIntExtra(EXTRA_CODE, 0), data)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { stopSelf() }
                }
            }, main)
            projection = mp
        }

        val lang = LANGS[i.getIntExtra(EXTRA_LANG, 0).coerceIn(0, LANGS.lastIndex)]
        active = true
        running = true
        TvServer.start(this)
        if (Prefs(this).showOnPhone && Settings.canDrawOverlays(this)) showOverlay()
        worker = Thread({ work(lang, source) }, "live-translate").also { it.start() }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun projectionData(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else i.getParcelableExtra(EXTRA_DATA)

    private fun startInForeground(source: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "الترجمة الفورية", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, TranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("المترجم الفوري يعمل")
            .setContentText("يترجم الصوت إلى العربية")
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause), "إيقاف", stop
                ).build()
            )
            .setOngoing(true)
            .build()
        val type = if (source == 1) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTIF_ID, n, type)
    }

    // ---------- العرض ----------

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            textSize = 19f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_RTL
            visibility = View.GONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.BOTTOM
        lp.y = (72 * d).toInt()
        wm.addView(tv, lp)
        windowManager = wm
        overlay = tv
    }

    /** رسالة حالة تبقى ظاهرة (تحميل، أخطاء). */
    private fun status(text: String) {
        Hub.setStatus(text)
        main.post {
            val tv = overlay ?: return@post
            main.removeCallbacks(clearRunnable)
            tv.text = text
            tv.visibility = View.VISIBLE
        }
    }

    /** سطر ترجمة: على التلفاز وعلى الجوال، ويختفي بعد 6 ثواني من الصمت. */
    private fun emit(arabic: String) {
        TvServer.setText(arabic)
        main.removeCallbacks(clearRunnable)
        val tv = overlay
        if (tv != null) {
            tv.text = arabic
            tv.visibility = if (arabic.isBlank()) View.GONE else View.VISIBLE
        }
        if (arabic.isNotBlank()) main.postDelayed(clearRunnable, 6000)
    }

    // ---------- الصوت ← نص ← ترجمة ----------

    @SuppressLint("MissingPermission")
    private fun work(lang: SrcLang, source: Int) {
        var record: AudioRecord? = null
        var model: Model? = null
        var recognizer: Recognizer? = null
        try {
            status("جاري تجهيز الترجمة… (أول مرة فقط)")
            val tr = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(lang.ml)
                    .setTargetLanguage(TranslateLanguage.ARABIC)
                    .build()
            )
            translator = tr
            Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()))

            val dir = ensureVoskModel(lang)
            status("جاري تحميل نموذج الصوت…")
            val m = Model(dir.absolutePath)
            model = m
            val rec = Recognizer(m, RATE.toFloat())
            recognizer = rec

            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, RATE * 2)
            val ar = if (source == 0) {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(bufSize)
                    .build()
            } else {
                val cfg = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(bufSize)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build()
            }
            record = ar
            ar.startRecording()
            status(if (source == 0) "جاهز ✓ شغّل أي شي على التلفاز" else "جاهز ✓ شغّل أي مقطع أو بث")

            val buf = ShortArray(3200) // 200 ملي ثانية
            var lastPartial = ""
            var lastPartialTime = 0L
            while (active) {
                val n = ar.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                if (rec.acceptWaveForm(buf, n)) {
                    val text = JSONObject(rec.result).optString("text")
                    lastPartial = ""
                    if (text.isNotBlank()) translate(text)
                } else {
                    val p = JSONObject(rec.partialResult).optString("partial")
                    val now = SystemClock.elapsedRealtime()
                    if (p.isNotBlank() && p != lastPartial && now - lastPartialTime > 900) {
                        lastPartial = p
                        lastPartialTime = now
                        translate(p)
                    }
                }
            }
        } catch (e: Exception) {
            if (active) {
                status("تعذّر التشغيل: ${e.message}")
                main.postDelayed({ stopSelf() }, 8000)
            }
        } finally {
            try { record?.stop() } catch (_: Exception) {}
            record?.release()
            recognizer?.close()
            model?.close()
        }
    }

    private fun translate(text: String) {
        val tr = translator ?: return
        val id = seq.incrementAndGet()
        tr.translate(text).addOnSuccessListener { arabic ->
            // نتجاهل أي ترجمة وصلت متأخرة بعد ترجمة أحدث
            if (id > shown) {
                shown = id
                emit(arabic)
            }
        }
    }

    // ---------- تحميل نموذج Vosk ----------

    private fun ensureVoskModel(lang: SrcLang): File {
        val root = File(filesDir, "vosk").apply { mkdirs() }
        val dir = File(root, lang.vosk)
        if (File(dir, "am").exists()) return dir
        dir.deleteRecursively()

        val tmp = File(cacheDir, "${lang.vosk}.zip")
        val conn = URL("https://alphacephei.com/vosk/models/${lang.vosk}.zip")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val b = ByteArray(65536)
                var done = 0L
                var lastPct = -1
                while (active) {
                    val r = input.read(b)
                    if (r < 0) break
                    out.write(b, 0, r)
                    done += r
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct && pct % 5 == 0) {
                            lastPct = pct
                            status("جاري تحميل نموذج الصوت… $pct%")
                        }
                    }
                }
            }
        }
        if (!active) throw IllegalStateException("أُوقف التحميل")

        status("جاري فك ضغط النموذج…")
        val rootPath = root.canonicalPath + File.separator
        ZipInputStream(tmp.inputStream().buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val f = File(root, e.name)
                if (!f.canonicalPath.startsWith(rootPath)) throw SecurityException("ملف غير صالح")
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zin.copyTo(it) }
                }
                e = zin.nextEntry
            }
        }
        tmp.delete()
        if (!File(dir, "am").exists()) throw IllegalStateException("النموذج غير مكتمل")
        return dir
    }

    override fun onDestroy() {
        active = false
        running = false
        main.removeCallbacksAndMessages(null)
        TvServer.setText("")
        TvServer.stop()
        Hub.setStatus("متوقف")
        overlay?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        overlay = null
        translator?.close()
        try { projection?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }
}
