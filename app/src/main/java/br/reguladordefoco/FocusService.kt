package br.reguladordefoco

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * O "óculos invisível": a correção de foco por cima de qualquer app ([FocusLens]) e o filtro
 * de antes de dormir. Segue o que o app grava no [Store].
 */
class FocusService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var store: Store
    private var nightView: View? = null
    private var lens: FocusLens? = null
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    override fun onServiceConnected() {
        store = Store(this)
        store.prefs.registerOnSharedPreferenceChangeListener(this)
        applyFromStore()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key == null || key in Store.SERVICE_KEYS) applyFromStore()
    }

    private fun applyFromStore() {
        val active = store.profile != Profile.NORMAL && !store.appInFront
        setNight(if (active && store.profile == Profile.NIGHT) store.nightFilter else 0f)
        if (active && store.focusEnabled && FocusShader.available) {
            val l = lens ?: FocusLens(this, wm) { visible -> nightView?.alpha = if (visible) 1f else 0f }
            lens = l
            l.enable(store.focusRadius, store.focusContrast)
        } else {
            lens?.disable()
            lens = null
        }
    }

    private fun setNight(strength: Float) {
        val current = nightView
        if (strength > 0f && current != null) {
            current.setBackgroundColor(nightColor(strength))
        } else if (strength > 0f) {
            val view = View(this).apply {
                setBackgroundColor(nightColor(strength))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
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
        } else if (current != null) {
            wm.removeView(current)
            nightView = null
        }
    }

    /** A tela mudou: a lente refaz a correção quando parar. */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != packageName) lens?.onContentChanged()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (::store.isInitialized) store.prefs.unregisterOnSharedPreferenceChangeListener(this)
        lens?.disable()
        lens = null
        setNight(0f)
        super.onDestroy()
    }

    companion object {
        /** Âmbar escuro por cima de tudo: tela mais quente e mais fraca para antes de dormir. */
        fun nightColor(strength: Float): Int = (Math.round(strength * 255) shl 24) or 0x301800
    }
}
