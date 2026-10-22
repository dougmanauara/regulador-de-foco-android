package br.reguladordefoco

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.View

/**
 * A correção de foco na placa de vídeo (Android 13+): as passadas de [FocusPasses] encadeadas.
 * Cada passada lê a anterior ("content") e a imagem original ("orig").
 */
object FocusShader {
    val available: Boolean get() = Build.VERSION.SDK_INT >= 33

    @TargetApi(33)
    fun effect(orig: Shader, width: Int, height: Int, radiusPx: Double, k: Double): RenderEffect {
        var chain: RenderEffect? = null
        for (source in FocusPasses.ORDER) {
            val shader = RuntimeShader(source)
            shader.setFloatUniform("size", width.toFloat(), height.toFloat())
            shader.setFloatUniform("radius", radiusPx.toFloat())
            shader.setFloatUniform("k", k.toFloat())
            shader.setInputShader("orig", orig)
            val pass = RenderEffect.createRuntimeShaderEffect(shader, "content")
            chain = if (chain == null) pass else RenderEffect.createChainEffect(pass, chain)
        }
        return chain!!
    }

    /** Desenha um texto preto em fundo branco numa imagem, para as prévias e os testes. */
    fun textBitmap(text: String, textPx: Float, widthPx: Int, padPx: Int): Bitmap {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = textPx
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx - 2 * padPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
        val bitmap = Bitmap.createBitmap(widthPx, layout.height + 2 * padPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.translate(padPx.toFloat(), padPx.toFloat())
        layout.draw(canvas)
        return bitmap
    }
}

/**
 * Mostra uma imagem com a correção de foco. Com [alignToScreen], a imagem é uma foto da tela
 * inteira e é desenhada no lugar exato, compensando onde a janela começa.
 */
class CorrectedImageView(context: Context, private val alignToScreen: Boolean = false) : View(context) {
    var bitmap: Bitmap? = null
        set(value) {
            field = value
            requestLayout()
            rebuild()
        }
    private var radius = 0.0
    private var k = 1.0
    private val location = IntArray(2)

    /** Raio 0 = sem correção. */
    fun setFocus(radiusPx: Double, contrast: Double) {
        radius = radiusPx
        k = contrast
        rebuild()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val b = bitmap
        if (alignToScreen || b == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        } else {
            setMeasuredDimension(resolveSize(b.width, widthMeasureSpec), resolveSize(b.height, heightMeasureSpec))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) rebuild()
    }

    private fun offset(): Pair<Float, Float> {
        if (!alignToScreen) return 0f to 0f
        getLocationOnScreen(location)
        return -location[0].toFloat() to -location[1].toFloat()
    }

    private fun rebuild() {
        if (!FocusShader.available) return
        val b = bitmap
        if (b == null || radius <= 0.0 || width == 0 || height == 0) {
            setRenderEffect(null)
            invalidate()
            return
        }
        val (dx, dy) = offset()
        val orig = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        orig.setLocalMatrix(Matrix().apply { setTranslate(dx, dy) })
        try {
            setRenderEffect(FocusShader.effect(orig, width, height, radius, k))
        } catch (e: IllegalArgumentException) {
            // Se o celular recusar o filtro, fica sem correção em vez de o app fechar.
            Log.e("FocusShader", "filtro de foco recusado", e)
            setRenderEffect(null)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        val (dx, dy) = offset()
        canvas.drawBitmap(b, dx, dy, null)
    }
}
