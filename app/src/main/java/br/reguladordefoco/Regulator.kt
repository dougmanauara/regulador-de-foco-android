package br.reguladordefoco

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.DisplayMetrics

/** Liga e desliga os modos: letras aqui mesmo, zoom e filtro pelo [FocusService]. */
object Regulator {

    /** Alguns aparelhos informam o dpi real errado; nesse caso vale o dpi nominal. */
    fun reliableDpi(dm: DisplayMetrics): Double {
        val real = (dm.xdpi + dm.ydpi) / 2.0
        val nominal = dm.densityDpi.toDouble()
        return if (real / nominal in 0.7..1.4) real else nominal
    }

    fun bodyCapMm(context: Context): Double {
        val dm = context.resources.displayMetrics
        return VisionMath.bodyCapMm(dm.density.toDouble(), reliableDpi(dm))
    }

    /** O que o modo pede nesta tela, ou null no Normal ou sem teste feito. */
    fun setting(context: Context, store: Store, profile: Profile): VisionMath.Setting? {
        if (profile == Profile.NORMAL) return null
        val threshold = store.chosenThreshold() ?: return null
        val extra = if (profile == Profile.NIGHT) VisionMath.NIGHT_EXTRA else 1.0
        return VisionMath.recommend(threshold, bodyCapMm(context), store.fineTune, extra)
    }

    /** Liga o modo e devolve o que não deu para fazer, para avisar a pessoa. */
    fun apply(context: Context, profile: Profile): List<String> {
        val store = Store(context)
        val setting = setting(context, store, profile)
        if (profile != Profile.NORMAL && setting == null) return listOf("Faça o teste da visão primeiro.")

        val problems = mutableListOf<String>()
        val resolver = context.contentResolver
        if (Settings.System.canWrite(context)) {
            try {
                if (setting == null) {
                    store.originalFontScale?.let { Settings.System.putFloat(resolver, Settings.System.FONT_SCALE, it) }
                    store.originalFontScale = null
                } else {
                    if (store.originalFontScale == null) {
                        store.originalFontScale = Settings.System.getFloat(resolver, Settings.System.FONT_SCALE, 1f)
                    }
                    Settings.System.putFloat(resolver, Settings.System.FONT_SCALE, setting.fontScale.toFloat())
                }
            } catch (e: RuntimeException) {
                problems += "O Android não deixou mudar o tamanho das letras."
            }
        } else if (setting != null) {
            problems += "Falta permitir que o app mude o tamanho das letras."
        }

        store.targetZoom = setting?.zoom?.toFloat() ?: 1f
        store.profile = profile
        if (profile != Profile.NORMAL) store.lastActiveProfile = profile
        val needsService = setting != null && (setting.zoom > 1.0 || profile == Profile.NIGHT)
        if (needsService && !isServiceOn(context)) {
            problems += "Falta ligar o serviço do Regulador (zoom e filtro da noite)."
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
