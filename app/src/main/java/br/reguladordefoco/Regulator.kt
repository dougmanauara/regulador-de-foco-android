package br.reguladordefoco

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.DisplayMetrics

/** Liga e desliga os modos: letras (opcional) aqui mesmo, foco e filtro pelo [FocusService]. */
object Regulator {

    /** Alguns aparelhos informam o dpi real errado; nesse caso vale o dpi nominal. */
    fun reliableDpi(dm: DisplayMetrics): Double {
        val real = (dm.xdpi + dm.ydpi) / 2.0
        val nominal = dm.densityDpi.toDouble()
        return if (real / nominal in 0.7..1.4) real else nominal
    }

    fun mmToPx(context: Context, mm: Double): Double = mm / 25.4 * reliableDpi(context.resources.displayMetrics)

    fun bodyCapMm(context: Context): Double {
        val dm = context.resources.displayMetrics
        return VisionMath.bodyCapMm(dm.density.toDouble(), reliableDpi(dm))
    }

    /** Tamanho das letras que o modo pede, ou null se não for para mexer nelas. */
    fun fontScale(context: Context, store: Store, profile: Profile): Double? {
        if (profile == Profile.NORMAL || !store.biggerText) return null
        val threshold = store.chosenThreshold() ?: return null
        return VisionMath.recommendFontScale(threshold, bodyCapMm(context))
    }

    /** Liga o modo e devolve o que não deu para fazer, para avisar a pessoa. */
    fun apply(context: Context, profile: Profile): List<String> {
        val store = Store(context)
        if (profile != Profile.NORMAL && store.chosenThreshold() == null) {
            return listOf("Faça o teste da visão primeiro.")
        }

        val problems = mutableListOf<String>()
        val resolver = context.contentResolver
        val scale = fontScale(context, store, profile)
        if (Settings.System.canWrite(context)) {
            try {
                if (scale == null) {
                    store.originalFontScale?.let { Settings.System.putFloat(resolver, Settings.System.FONT_SCALE, it) }
                    store.originalFontScale = null
                } else {
                    if (store.originalFontScale == null) {
                        store.originalFontScale = Settings.System.getFloat(resolver, Settings.System.FONT_SCALE, 1f)
                    }
                    Settings.System.putFloat(resolver, Settings.System.FONT_SCALE, scale.toFloat())
                }
            } catch (e: RuntimeException) {
                problems += "O Android não deixou mudar o tamanho das letras."
            }
        } else if (scale != null) {
            problems += "Falta permitir que o app mude o tamanho das letras."
        }

        store.profile = profile
        if (profile != Profile.NORMAL) store.lastActiveProfile = profile
        val needsService = profile != Profile.NORMAL &&
            (store.focusEnabled || (profile == Profile.NIGHT && store.nightFilter > 0f))
        if (needsService && !isServiceOn(context)) {
            problems += "Falta ligar o serviço do Regulador (correção de foco e filtro da noite)."
        }
        if (profile != Profile.NORMAL && store.focusEnabled && !FocusShader.available) {
            problems += "A correção de foco precisa do Android 13 ou mais novo."
        }
        return problems
    }

    fun isServiceOn(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(context, FocusService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}
