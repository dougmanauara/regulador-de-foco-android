package br.reguladordefoco

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.MagnificationConfig
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * O "óculos invisível": zoom na tela inteira, em qualquer app, e o filtro de antes de dormir.
 * Segue o que o app grava no [Store] e centraliza o zoom no campo em que a pessoa está.
 */
class FocusService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var store: Store
    private var zoomedByUs = false
    private var appliedZoom = 1f
    private var nightView: View? = null
    private val bounds = Rect()
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    override fun onServiceConnected() {
        store = Store(this)
        store.prefs.registerOnSharedPreferenceChangeListener(this)
        applyFromStore()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key == null || key == Store.KEY_PROFILE || key == Store.KEY_ZOOM || key == Store.KEY_TESTING) {
            applyFromStore()
        }
    }

    private fun applyFromStore() {
        val active = store.profile != Profile.NORMAL && !store.testing
        setZoom(if (active) store.targetZoom else 1f)
        setNight(active && store.profile == Profile.NIGHT)
    }

    private fun setZoom(scale: Float) {
        val controller = magnificationController
        if (scale <= 1.01f) {
            // Só desfaz o zoom que nós pusemos, não o que a pessoa fez com os gestos do Android.
            if (zoomedByUs) controller.reset(true)
            zoomedByUs = false
            appliedZoom = 1f
            return
        }
        if (zoomedByUs && scale == appliedZoom) return
        val screen = screenBounds()
        magnify(scale, screen.exactCenterX(), screen.exactCenterY())
        zoomedByUs = true
        appliedZoom = scale
    }

    private fun magnify(scale: Float, cx: Float, cy: Float) {
        val controller = magnificationController
        if (Build.VERSION.SDK_INT >= 33) {
            val config = MagnificationConfig.Builder()
                .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
                .setScale(scale)
                .setCenterX(cx)
                .setCenterY(cy)
                .build()
            controller.setMagnificationConfig(config, true)
        } else {
            @Suppress("DEPRECATION")
            controller.setScale(scale, true)
            @Suppress("DEPRECATION")
            controller.setCenter(cx, cy, true)
        }
    }

    private fun currentScale(): Float {
        val controller = magnificationController
        if (Build.VERSION.SDK_INT >= 33) return controller.magnificationConfig?.scale ?: appliedZoom
        @Suppress("DEPRECATION")
        return controller.scale
    }

    private fun setNight(on: Boolean) {
        val current = nightView
        if (on && current == null) {
            val view = View(this).apply { setBackgroundColor(NIGHT_COLOR) }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Não pega toque: a tela embaixo continua funcionando normalmente.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            wm.addView(view, lp)
            nightView = view
        } else if (!on && current != null) {
            wm.removeView(current)
            nightView = null
        }
    }

    /** Leva o zoom para onde a pessoa está digitando ou navegando. */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!zoomedByUs) return
        val node = event.source ?: return
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return
        // Uma área quase da tela toda (lista, página): centralizar nela não ajuda.
        val screen = screenBounds()
        if (bounds.width() > screen.width() * 0.9f && bounds.height() > screen.height() * 0.5f) return
        // Mantém o zoom atual, caso a pessoa tenha mudado com a pinça.
        magnify(currentScale(), bounds.exactCenterX(), bounds.exactCenterY())
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (::store.isInitialized) store.prefs.unregisterOnSharedPreferenceChangeListener(this)
        setNight(false)
        setZoom(1f)
        super.onDestroy()
    }

    private fun screenBounds(): Rect {
        if (Build.VERSION.SDK_INT >= 30) return wm.maximumWindowMetrics.bounds
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    companion object {
        /** Âmbar escuro a 35%: tela mais quente e mais fraca para antes de dormir. */
        private const val NIGHT_COLOR = 0x59301800
    }
}
