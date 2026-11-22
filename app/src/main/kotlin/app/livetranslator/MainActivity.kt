package app.livetranslator

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var button: Button
    private lateinit var statusView: TextView
    private lateinit var tvView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val clearPreview = Runnable { if (!TranslateService.running) TvServer.setText("") }

    private companion object {
        const val REQ_PERM = 1
        const val REQ_OVERLAY = 2
        const val REQ_PROJECTION = 3
        const val SAMPLE = "هذه ترجمة تجريبية لاختيار شكل الخط"
        val COLOR_NAMES = listOf("أبيض", "أصفر", "أخضر", "سماوي")
        val COLOR_VALUES = listOf("#ffffff", "#ffe082", "#a5d6a7", "#80deea")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(32))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        fun label(t: String) {
            root.addView(TextView(this).apply {
                text = t
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(20), 0, dp(4))
            })
        }

        fun spinner(items: List<String>, selected: Int, onPick: (Int) -> Unit) {
            val s = Spinner(this)
            s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
            s.setSelection(selected.coerceIn(0, items.lastIndex))
            s.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    onPick(pos)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            root.addView(s)
        }

        fun seek(title: String, lo: Int, hi: Int, value: Int, save: (Int) -> Unit) {
            val t = TextView(this).apply {
                textSize = 14f
                setPadding(0, dp(10), 0, 0)
                text = "$title: $value%"
            }
            val s = SeekBar(this)
            s.max = hi - lo
            s.progress = value - lo
            s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    t.text = "$title: ${p + lo}%"
                    if (fromUser) {
                        save(p + lo)
                        TvServer.touch()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { previewTv() }
            })
            root.addView(t)
            root.addView(s)
        }

        fun check(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
            root.addView(CheckBox(this).apply {
                text = title
                isChecked = value
                setOnCheckedChangeListener { _, c -> onChange(c) }
            })
        }

        // ---- العنوان
        root.addView(TextView(this).apply {
            text = "المترجم الفوري"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "يسمع الصوت ويترجمه للعربية، ويعرض الترجمة على التلفاز."
            textSize = 15f
            setPadding(0, dp(6), 0, 0)
        })

        // ---- التلفاز
        label("1) التلفاز")
        tvView = TextView(this).apply { textSize = 15f }
        root.addView(tvView)
        root.addView(Button(this).apply {
            text = "ابحث عن التلفاز واربطه"
            setOnClickListener { findTv() }
        })
        root.addView(Button(this).apply {
            text = "نسيان التلفاز"
            setOnClickListener {
                prefs.saveTvs(emptyList())
                refreshTv()
            }
        })

        // ---- الصوت
        label("2) مصدر الصوت")
        spinner(
            listOf("الميكروفون (يسمع صوت التلفاز)", "صوت الجوال الداخلي (يوتيوب، بث...)"),
            prefs.source,
        ) { prefs.source = it }
        label("لغة الصوت")
        spinner(LANGS.map { it.label }, prefs.lang) { prefs.lang = it }

        // ---- شكل الترجمة
        label("3) شكل الترجمة على التلفاز")
        val fonts = TvServer.FACES.keys.toList()
        spinner(fonts, fonts.indexOf(prefs.font)) {
            prefs.font = fonts[it]
            TvServer.touch()
            previewTv()
        }
        seek("حجم الخط", 50, 200, prefs.sizePct) { prefs.sizePct = it }
        seek("وضوح الخلفية", 0, 100, prefs.bgPct) { prefs.bgPct = it }
        spinner(COLOR_NAMES.map { "لون الخط: $it" }, COLOR_VALUES.indexOf(prefs.color)) {
            prefs.color = COLOR_VALUES[it]
            TvServer.touch()
            previewTv()
        }
        spinner(listOf("مكان الترجمة: أسفل الشاشة", "مكان الترجمة: أعلى الشاشة"), if (prefs.top) 1 else 0) {
            prefs.top = it == 1
            TvServer.touch()
            previewTv()
        }
        check("خط عريض", prefs.bold) {
            prefs.bold = it
            TvServer.touch()
            previewTv()
        }
        check("اعرض الترجمة على الجوال أيضاً", prefs.showOnPhone) { prefs.showOnPhone = it }
        root.addView(Button(this).apply {
            text = "جرّب الشكل على التلفاز"
            setOnClickListener { previewTv() }
        })

        // ---- التشغيل
        button = Button(this).apply {
            textSize = 18f
            setOnClickListener { onButton() }
        }
        root.addView(button, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(64)
        ).apply { topMargin = dp(28) })

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(statusView)
        root.addView(TextView(this).apply {
            text = "أول تشغيل يحمّل نموذج الصوت (حوالي 40 ميجا) ونموذج الترجمة (حوالي 30 ميجا)، وبعدها يشتغل بدون إنترنت. " +
                "ولازم تطبيق VI Translator يكون مفتوح على التلفاز، والجوال والتلفاز على نفس الواي فاي."
            textSize = 13f
            setPadding(0, dp(12), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        Hub.onStatus = { statusView.text = it }
        statusView.text = Hub.lastStatus
        refreshTv()
        refreshButton()
    }

    override fun onPause() {
        Hub.onStatus = null
        super.onPause()
    }

    private fun refreshButton() {
        button.text = if (TranslateService.running) "إيقاف الترجمة" else "ابدأ الترجمة"
    }

    private fun refreshTv() {
        val tvs = prefs.tvs()
        tvView.text = if (tvs.isEmpty()) "غير مربوط بأي تلفاز" else "مربوط: " + tvs.joinToString("، ") { it.name }
    }

    /** يرسل نص تجريبي للتلفاز 5 ثواني عشان تشوف الخط. */
    private fun previewTv() {
        if (TranslateService.running || prefs.tvs().isEmpty()) return
        TvServer.start(applicationContext)
        TvServer.setText(SAMPLE)
        ui.removeCallbacks(clearPreview)
        ui.postDelayed(clearPreview, 5000)
    }

    // ---------- ربط التلفاز ----------

    private fun findTv() {
        TvServer.start(applicationContext)
        Toast.makeText(this, "جاري البحث عن التلفاز… (ثواني)", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        Thread {
            val tvs = TvLink.scan(app)
            runOnUiThread { chooseTv(tvs) }
        }.start()
    }

    private fun chooseTv(tvs: List<Tv>) {
        val names = tvs.map { "${it.name} (${it.address})" } + "إدخال عنوان IP يدوياً"
        AlertDialog.Builder(this)
            .setTitle(if (tvs.isEmpty()) "ما لقيت تلفاز. افتح التطبيق على التلفاز" else "اختر التلفاز")
            .setItems(names.toTypedArray()) { _, i ->
                if (i < tvs.size) askCode(tvs[i].address) else askAddress()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun askAddress() {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = "مثال: 192.168.1.30"
        }
        AlertDialog.Builder(this)
            .setTitle("عنوان التلفاز")
            .setView(et)
            .setPositiveButton("التالي") { _, _ -> askCode(et.text.toString()) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun askCode(address: String) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "6 أرقام"
            filters = arrayOf(InputFilter.LengthFilter(6))
        }
        AlertDialog.Builder(this)
            .setTitle("اكتب الكود الظاهر على التلفاز")
            .setView(et)
            .setPositiveButton("اتصال") { _, _ -> pair(address, et.text.toString()) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun pair(address: String, code: String) {
        val app = applicationContext
        Thread {
            val r = TvLink.pairWith(app, address, code)
            runOnUiThread {
                val msg = when (r) {
                    PairResult.OK -> "تم الربط ✓"
                    PairResult.WRONG_CODE -> "الكود غلط"
                    PairResult.NEW_CODE -> "محاولات كثيرة، التلفاز عرض كود جديد"
                    PairResult.UNREACHABLE -> "ما وصلت للتلفاز، تأكد من العنوان والشبكة"
                    PairResult.NOT_SHARING -> "الجوال مو متصل بالواي فاي"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refreshTv()
                if (r == PairResult.OK) previewTv()
            }
        }.start()
    }

    // ---------- التشغيل والأذونات ----------

    private fun onButton() {
        if (TranslateService.running) {
            startService(Intent(this, TranslateService::class.java).setAction(TranslateService.ACTION_STOP))
            button.text = "ابدأ الترجمة"
        } else {
            begin()
        }
    }

    /** الأذونات بالترتيب: الصوت والإشعارات ← النوافذ العائمة (إذا فعّلت العرض على الجوال) ← التقاط صوت الجوال (إذا اخترته). */
    private fun begin() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERM)
            return
        }
        if (prefs.showOnPhone && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "فعّل «العرض فوق التطبيقات الأخرى» للتطبيق", Toast.LENGTH_LONG).show()
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY,
            )
            return
        }
        if (prefs.source == 1) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
        } else {
            launch(RESULT_CANCELED, null)
        }
    }

    private fun launch(code: Int, data: Intent?) {
        val svc = Intent(this, TranslateService::class.java)
            .setAction(TranslateService.ACTION_START)
            .putExtra(TranslateService.EXTRA_SOURCE, prefs.source)
            .putExtra(TranslateService.EXTRA_LANG, prefs.lang)
        if (data != null) svc.putExtra(TranslateService.EXTRA_CODE, code).putExtra(TranslateService.EXTRA_DATA, data)
        startForegroundService(svc)
        button.text = "إيقاف الترجمة"
        if (prefs.source == 1) moveTaskToBack(true) // ارجع للمقطع اللي تبي تترجمه
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            begin()
        } else {
            Toast.makeText(this, "إذن الصوت مطلوب", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> if (Settings.canDrawOverlays(this)) begin()
            REQ_PROJECTION -> if (resultCode == RESULT_OK && data != null) launch(resultCode, data)
        }
    }
}
