package com.example.screenmirror.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser
import kotlin.math.min

/** Tasarımdaki renkler (mirror/docs ve tasarım canvas'ı ile aynı). */
object C {
    val BG = Color.parseColor("#0E1014")
    val SURFACE = Color.parseColor("#171A20")
    val SURFACE2 = Color.parseColor("#1F232B")
    val LINE = Color.parseColor("#2B303A")
    val LINE2 = Color.parseColor("#3A404C")
    val TEXT = Color.parseColor("#F3F5F8")
    val MUTED = Color.parseColor("#A0A8B6")
    val DIM = Color.parseColor("#7C8493")
    val ACCENT = Color.parseColor("#7CF5C8")
    val ACCENT_DARK = Color.parseColor("#07130F")
    val ACCENT_TINT = Color.parseColor("#14231F")
    val LIVE = Color.parseColor("#FF7A59")
    val LIVE_TEXT = Color.parseColor("#FF9A80")
    val LIVE_TINT = Color.parseColor("#2A1814")
    val LIVE_DARK = Color.parseColor("#1C0A05")
    val GOLD = Color.parseColor("#F2C879")
    val GOLD_TINT = Color.parseColor("#2A2314")
    val GOLD_DARK = Color.parseColor("#1B1405")
}

/**
 * Tasarımdaki yazı tipleri: gövde Figtree, başlık Bricolage Grotesque.
 * TTF dosyaları `app/src/main/assets/fonts/Figtree.ttf` ve `BricolageGrotesque.ttf` olarak
 * konursa (değişken yazı tipi, wght ekseni) kullanılır; yoksa sistem yazı tipine düşer.
 */
object Fonts {
    private var appContext: Context? = null
    private val cache = HashMap<String, Typeface>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun load(file: String, weight: Int): Typeface? = try {
        val assets = appContext?.assets
        if (assets == null) null
        else Typeface.Builder(assets, "fonts/$file").setFontVariationSettings("'wght' $weight").build()
    } catch (_: Throwable) {
        null
    }

    fun body(weight: Int): Typeface =
        cache.getOrPut("b$weight") { load("Figtree.ttf", weight) ?: Typeface.create(Typeface.DEFAULT, weight, false) }

    fun display(weight: Int): Typeface =
        cache.getOrPut("d$weight") {
            load("BricolageGrotesque.ttf", weight) ?: Typeface.create(Typeface.DEFAULT, weight, false)
        }
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Int): Int = context.dp(v)

fun Context.rounded(fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }

fun Context.circle(fill: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }

/** Dokunma dalgalı yuvarlak dikdörtgen arka plan. */
fun Context.rippleRounded(fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): RippleDrawable =
    RippleDrawable(
        ColorStateList.valueOf(0x30FFFFFF),
        rounded(fill, radiusDp, strokeColor, strokeDp),
        rounded(Color.WHITE, radiusDp),
    )

fun Context.label(
    s: CharSequence,
    sp: Float = 16f,
    color: Int = C.TEXT,
    weight: Int = 500,
    display: Boolean = false,
    spacing: Float = 0f,
): TextView = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    typeface = if (display) Fonts.display(weight) else Fonts.body(weight)
    if (spacing != 0f) letterSpacing = spacing
}

fun Context.column(block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        block()
    }

fun Context.row(block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        block()
    }

/** Kenar boşlukları dp cinsindendir. */
fun LinearLayout.add(
    v: View,
    w: Int = MATCH,
    h: Int = WRAP,
    weight: Float = 0f,
    top: Int = 0,
    start: Int = 0,
    end: Int = 0,
    bottom: Int = 0,
    gravity: Int = -1,
): View {
    val p = LinearLayout.LayoutParams(w, h, weight)
    p.setMargins(context.dp(start), context.dp(top), context.dp(end), context.dp(bottom))
    if (gravity != -1) p.gravity = gravity
    addView(v, p)
    return v
}

fun LinearLayout.spacer(): View = add(View(context), w = 0, h = 0, weight = 1f)

/** Çubuk ikonlar: 24×24 görünüm kutusunda SVG yol verisi. */
object Icons {
    const val TV =
        "M5.5 5h13A2.5 2.5 0 0 1 21 7.5v7a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 14.5v-7A2.5 2.5 0 0 1 5.5 5z M8 20h8 M12 17v3"
    const val SLIDERS = "M4 7h10 M18 7h2 M4 17h2 M10 17h10 M14 7a2 2 0 1 0 4 0a2 2 0 1 0-4 0 M6 17a2 2 0 1 0 4 0a2 2 0 1 0-4 0"
    const val BACK = "M15 6l-6 6 6 6"
    const val CLOSE = "M6 6l12 12 M18 6L6 18"
    const val CHECK = "M5 12.5l4.5 4.5L19 7.5"
    const val CHEVRON = "M9 6l6 6-6 6"
    const val REFRESH = "M20 11a8 8 0 1 0-2.3 5.7 M20 5v6h-6"
    const val WIFI = "M3 9a14 14 0 0 1 18 0 M6 12.5a9.5 9.5 0 0 1 12 0 M9 16a5 5 0 0 1 6 0 M12 19h.01"
    const val BACKSPACE = "M9 5h10a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H9L3 12z M12 9.5l5 5 M17 9.5l-5 5"
    const val PLAY = "M8 5.5v13l11-6.5z"
    const val STOP =
        "M7.5 5h9A2.5 2.5 0 0 1 19 7.5v9a2.5 2.5 0 0 1-2.5 2.5h-9A2.5 2.5 0 0 1 5 16.5v-9A2.5 2.5 0 0 1 7.5 5z"
    const val VOLUME = "M4 10v4h4l5 4V6L8 10z M16.5 9a4 4 0 0 1 0 6 M19 7a7 7 0 0 1 0 10"
    const val HELP =
        "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0 M9.5 9.5a2.5 2.5 0 1 1 4 2c-.9.6-1.5 1-1.5 2 M12 17h.01"
}

class IconView(
    context: Context,
    pathData: String,
    private val tint: Int,
    private val strokeW: Float = 1.8f,
    private val filled: Boolean = false,
) : View(context) {
    private val path: Path? = PathParser.createPathFromPathData(pathData)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = tint
    }

    override fun onDraw(canvas: Canvas) {
        val p = path ?: return
        val s = min(width, height) / 24f
        paint.strokeWidth = strokeW
        canvas.save()
        canvas.scale(s, s)
        canvas.drawPath(p, paint)
        canvas.restore()
    }
}

fun Context.icon(d: String, tint: Int, stroke: Float = 1.8f, filled: Boolean = false): IconView =
    IconView(this, d, tint, stroke, filled)

/** Tasarımdaki 44 dp yuvarlak ikon düğmesi. */
fun Context.iconButton(d: String, desc: String, onClick: () -> Unit): FrameLayout =
    FrameLayout(this).apply {
        background = rippleRounded(C.SURFACE, 22, C.LINE, 1)
        contentDescription = desc
        isClickable = true
        isFocusable = true
        addView(icon(d, C.TEXT), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

/** 60 dp yüksekliğinde ana eylem düğmesi (ikon isteğe bağlı). */
fun Context.bigButton(
    text: String,
    fill: Int,
    textColor: Int,
    iconPath: String? = null,
    iconFilled: Boolean = true,
    onClick: () -> Unit,
): LinearLayout = row {
    gravity = Gravity.CENTER
    background = rippleRounded(fill, 20)
    isClickable = true
    isFocusable = true
    minimumHeight = dp(60)
    if (iconPath != null) {
        add(icon(iconPath, textColor, 2.2f, iconFilled), w = dp(22), h = dp(22), end = 10)
    }
    add(label(text, 18f, textColor, 700), WRAP, WRAP)
    setOnClickListener { onClick() }
}

/** Açma/kapama anahtarı (52×32 dp). */
class Toggle(context: Context, checked: Boolean, private val onChange: (Boolean) -> Unit) : View(context) {
    var isOn: Boolean = checked
        private set
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            isOn = !isOn
            invalidate()
            onChange(isOn)
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(context.dp(52), context.dp(32))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = context.dp(3).toFloat()
        track.color = if (isOn) C.ACCENT else C.LINE2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2, h / 2, track)
        val r = (h - 2 * pad) / 2
        val cx = if (isOn) w - pad - r else pad + r
        thumb.color = if (isOn) C.ACCENT_DARK else C.MUTED
        canvas.drawCircle(cx, h / 2, r, thumb)
    }
}
