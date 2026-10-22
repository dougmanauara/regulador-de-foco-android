package br.reguladordefoco

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Peças de tela grandes e de alto contraste, já que o app é para quem enxerga com dificuldade. */
object Ui {
    const val BG = 0xFF101418.toInt()
    const val FG = 0xFFF5F5F5.toInt()
    const val ACCENT = 0xFF5ED6C4.toInt()
    const val MUTED = 0xFFB0BEC5.toInt()
    const val WARN = 0xFFFFC857.toInt()

    val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

    fun dp(context: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    fun column(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(context, 20f)
        setPadding(pad, pad, pad, pad)
        setBackgroundColor(BG)
    }

    fun text(context: Context, value: CharSequence, sizeSp: Float = 20f, color: Int = FG, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(context, 6f), 0, dp(context, 6f))
        }

    fun button(context: Context, label: String, filled: Boolean = true, onClick: (View) -> Unit) =
        Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 20f
            setTextColor(if (filled) BG else ACCENT)
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 14f).toFloat()
                if (filled) setColor(ACCENT) else {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(context, 2f), ACCENT)
                }
            }
            minHeight = dp(context, 56f)
            gravity = Gravity.CENTER
            setOnClickListener(onClick)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 10f) }
        }

    fun format(value: Double, decimals: Int = 1): String = String.format(PT_BR, "%.${decimals}f", value)

    fun percent(value: Double): String = "${Math.round(value * 100)}%"
}
