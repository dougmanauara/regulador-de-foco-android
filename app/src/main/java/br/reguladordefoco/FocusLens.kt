package br.reguladordefoco

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * A correção de foco por cima de qualquer app (Android 13+).
 *
 * O Android não deixa um app processar a tela ao vivo, então a lente trabalha assim: quando a
 * tela para, ela tira uma foto da tela, aplica a correção de foco ([FocusPasses]) e mostra a
 * foto corrigida exatamente por cima. Os toques passam direto para o app embaixo. Ao tocar,
 * a lente some (você mexe na tela real) e volta corrigida assim que a tela para de novo.
 */
@TargetApi(33)
class FocusLens(
    private val service: AccessibilityService,
    private val wm: WindowManager,
    /** Esconde e mostra as outras camadas do app (o filtro da noite) na hora da foto. */
    private val setOthersVisible: (Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var view: CorrectedImageView? = null
    private var touchWatcher: View? = null
    private var capturing = false
    private var lastCaptureAt = 0L

    /** Muda a cada toque ou mudança na tela; foto tirada antes de uma mudança é descartada. */
    private var generation = 0

    fun enable(radiusPx: Double, contrast: Double) {
        val current = view ?: createViews()
        current.setFocus(radiusPx, contrast)
        scheduleRefresh(QUIET_MS)
    }

    fun disable() {
        handler.removeCallbacksAndMessages(null)
        view?.let { wm.removeView(it) }
        touchWatcher?.let { wm.removeView(it) }
        view = null
        touchWatcher = null
        capturing = false
        setOthersVisible(true)
    }

    /** O app embaixo mudou (rolou, abriu algo, chegou notificação): refaz quando parar. */
    fun onContentChanged() {
        generation++
        scheduleRefresh(QUIET_MS)
    }

    private fun onUserTouch() {
        generation++
        hide()
        scheduleRefresh(QUIET_MS)
    }

    private fun hide() {
        view?.visibility = View.INVISIBLE
    }

    private fun scheduleRefresh(delayMs: Long) {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, delayMs)
    }

    private val refresh = Runnable {
        val v = view ?: return@Runnable
        if (capturing) {
            scheduleRefresh(QUIET_MS)
            return@Runnable
        }
        val wait = lastCaptureAt + MIN_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) {
            scheduleRefresh(wait)
            return@Runnable
        }
        // Tudo o que é nosso sai da tela antes da foto, senão a correção seria aplicada sobre ela mesma.
        v.visibility = View.INVISIBLE
        setOthersVisible(false)
        capturing = true
        val startedAt = generation
        handler.postDelayed({ capture(startedAt) }, HIDE_SETTLE_MS)
    }

    private fun capture(startedAt: Int) {
        lastCaptureAt = SystemClock.uptimeMillis()
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY, service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    capturing = false
                    setOthersVisible(true)
                    val buffer = result.hardwareBuffer
                    // O bitmap guarda a própria referência ao buffer; a nossa pode ser liberada já.
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    buffer.close()
                    val v = view
                    if (v == null || bitmap == null || generation != startedAt) return
                    v.bitmap = bitmap
                    v.visibility = View.VISIBLE
                }

                // Tela protegida (banco, senha) ou pedido rápido demais: fica sem correção até a próxima.
                override fun onFailure(errorCode: Int) {
                    capturing = false
                    setOthersVisible(true)
                }
            }
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createViews(): CorrectedImageView {
        val frozen = CorrectedImageView(service, alignToScreen = true).apply {
            visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Não pega toque: o app embaixo continua respondendo normalmente.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.fitInsetsTypes = 0
        wm.addView(frozen, lp)

        // Janela de 1 px que só serve para saber quando a pessoa toca em qualquer lugar da tela.
        val watcher = View(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) onUserTouch()
                false
            }
        }
        val wlp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        wlp.gravity = Gravity.TOP or Gravity.START
        wm.addView(watcher, wlp)

        view = frozen
        touchWatcher = watcher
        return frozen
    }

    companion object {
        /** Tempo de tela parada antes de corrigir. */
        private const val QUIET_MS = 600L

        /** O Android limita a frequência de fotos da tela. */
        private const val MIN_INTERVAL_MS = 400L

        /** Espera o quadro sem as nossas camadas chegar à tela antes da foto. */
        private const val HIDE_SETTLE_MS = 64L
    }
}
