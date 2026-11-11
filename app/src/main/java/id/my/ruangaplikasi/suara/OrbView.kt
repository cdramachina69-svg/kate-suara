package id.my.ruangaplikasi.suara

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Bola suara dengan gelombang berwarna yang bergerak (sama seperti ikon di halaman web).
 * mode: 0 = dijeda (redup), 1 = siaga (gelombang tenang), 2 = mendengarkan (aktif), 3 = berbicara
 */
class OrbView(ctx: Context) : View(ctx) {

    var mode: Int = 1
        set(value) {
            field = value
            invalidate()
        }

    private val density = ctx.resources.displayMetrics.density
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private var phase = 0f
    private var amp = 0.2f
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - 7f * density
        bodyPaint.shader = RadialGradient(
            cx, cy * 1.05f, radius,
            intArrayOf(0xF00B1B3F.toInt(), 0xEE0A2A5C.toInt(), 0xEE1585B5.toInt()),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        wavePaint.shader = LinearGradient(
            cx - radius, 0f, cx + radius, 0f,
            intArrayOf(0xFFA78BFA.toInt(), 0xFFF472B6.toInt(), 0xFF22D3EE.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        hiPaint.shader = RadialGradient(
            cx - radius * 0.35f, cy - radius * 0.5f, radius * 0.6f,
            intArrayOf(0x55FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val dim = if (mode == 0) 0.45f else 1f

        // cahaya di tepi bola
        for (i in 4 downTo 1) {
            glowPaint.strokeWidth = i * 2.2f * density
            glowPaint.color = ((0x16 * dim).toInt() shl 24) or 0x38BDF8
            canvas.drawCircle(cx, cy, radius + i * 0.8f * density, glowPaint)
        }
        // badan bola
        canvas.drawCircle(cx, cy, radius, bodyPaint)
        rimPaint.strokeWidth = 1.6f * density
        rimPaint.color = ((0xDD * dim).toInt() shl 24) or 0x38BDF8
        canvas.drawCircle(cx, cy, radius, rimPaint)

        // gelombang
        val save = canvas.save()
        val clip = Path()
        clip.addCircle(cx, cy, radius - 1.5f * density, Path.Direction.CW)
        canvas.clipPath(clip)
        wavePaint.alpha = (255 * dim).toInt()
        val left = cx - radius
        val width = radius * 2f
        val steps = 28
        val speeds = floatArrayOf(1.0f, 1.6f, 0.7f, 1.3f)
        val freqs = floatArrayOf(1.4f, 2.2f, 1.0f, 3.0f)
        val offs = floatArrayOf(0f, 1.1f, 2.3f, 3.6f)
        val widths = floatArrayOf(1.7f, 1.2f, 1.4f, 1.0f)
        for (k in 0..3) {
            path.reset()
            for (s in 0..steps) {
                val t = s / steps.toFloat()
                val env = sin(PI * t).toFloat()
                val y = cy + amp * radius * 0.55f * env *
                    sin(2.0 * PI * freqs[k] * t + phase * speeds[k] + offs[k]).toFloat()
                val x = left + t * width
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            wavePaint.strokeWidth = widths[k] * density
            canvas.drawPath(path, wavePaint)
        }
        canvas.restoreToCount(save)

        // kilau
        canvas.drawCircle(cx, cy, radius, hiPaint)

        // animasi
        val target = when (mode) {
            0 -> 0.08f
            1 -> 0.22f
            2 -> 0.9f
            else -> 0.65f
        }
        amp += (target - amp) * 0.12f
        phase += when (mode) {
            0 -> 0f
            1 -> 0.05f
            2 -> 0.16f
            else -> 0.12f
        }
        if (mode != 0 || kotlin.math.abs(target - amp) > 0.01f) {
            postInvalidateDelayed(33)
        }
    }
}
