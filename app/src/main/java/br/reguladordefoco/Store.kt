package br.reguladordefoco

import android.content.Context
import android.content.SharedPreferences

/** De qual olho vem o ajuste: a tela é uma só, então a pessoa escolhe. */
enum class EyeSource { BOTH, LEFT, RIGHT }

enum class Profile { NORMAL, NO_GLASSES, NIGHT }

/**
 * Resultados dos testes e o modo em uso. O serviço escuta estas preferências e aplica
 * a correção de foco e o filtro na hora, venha a mudança do app ou do botão na barra de notificações.
 */
class Store(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("regulador", Context.MODE_PRIVATE)

    fun threshold(eye: EyeSource): Double? =
        prefs.getFloat("threshold_${eye.name}", -1f).takeIf { it > 0f }?.toDouble()

    fun setThreshold(eye: EyeSource, mm: Double?) =
        prefs.edit().putFloat("threshold_${eye.name}", mm?.toFloat() ?: -1f).apply()

    var eyeSource: EyeSource
        get() = EyeSource.valueOf(prefs.getString("eye_source", EyeSource.BOTH.name)!!)
        set(v) = prefs.edit().putString("eye_source", v.name).apply()

    /** Limite usado no ajuste. Sem o teste com os dois olhos, vale o melhor olho. */
    fun chosenThreshold(): Double? {
        val left = threshold(EyeSource.LEFT)
        val right = threshold(EyeSource.RIGHT)
        return when (eyeSource) {
            EyeSource.LEFT -> left
            EyeSource.RIGHT -> right
            EyeSource.BOTH -> threshold(EyeSource.BOTH)
                ?: listOfNotNull(left, right).minOrNull()
        }
    }

    /** Correção de foco ligada nos modos Sem óculos e Antes de dormir. */
    var focusEnabled: Boolean
        get() = prefs.getBoolean(KEY_FOCUS_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_FOCUS_ENABLED, v).apply()

    /** Foco geral: raio do embaçado na tela, em px. */
    var focusRadius: Double
        get() = prefs.getFloat(KEY_FOCUS_RADIUS, 2f).toDouble()
        set(v) = prefs.edit().putFloat(KEY_FOCUS_RADIUS, v.toFloat()).apply()

    /** Foco fino: quanto do contraste fica na tela (menos contraste = correção mais forte). */
    var focusContrast: Double
        get() = prefs.getFloat(KEY_FOCUS_CONTRAST, VisionMath.FINE_K_DEFAULT.toFloat()).toDouble()
        set(v) = prefs.edit().putFloat(KEY_FOCUS_CONTRAST, v.toFloat()).apply()

    /** Também aumentar as letras do sistema (opcional; o foco é o principal). */
    var biggerText: Boolean
        get() = prefs.getBoolean("bigger_text", false)
        set(v) = prefs.edit().putBoolean("bigger_text", v).apply()

    /** Força do filtro âmbar do modo Antes de dormir (0 a 1). */
    var nightFilter: Float
        get() = prefs.getFloat(KEY_NIGHT_FILTER, 0.35f)
        set(v) = prefs.edit().putFloat(KEY_NIGHT_FILTER, v).apply()

    var profile: Profile
        get() = Profile.valueOf(prefs.getString(KEY_PROFILE, Profile.NORMAL.name)!!)
        set(v) = prefs.edit().putString(KEY_PROFILE, v.name).apply()

    /** Último modo ligado, para o botão da barra de notificações. */
    var lastActiveProfile: Profile
        get() = Profile.valueOf(prefs.getString("last_active", Profile.NO_GLASSES.name)!!)
        set(v) = prefs.edit().putString("last_active", v.name).apply()

    /** Tamanho das letras de antes do app mexer, para devolver no modo Normal. */
    var originalFontScale: Float?
        get() = prefs.getFloat("original_font_scale", -1f).takeIf { it > 0f }
        set(v) = prefs.edit().putFloat("original_font_scale", v ?: -1f).apply()

    /**
     * Com o nosso app na frente, a correção e o filtro saem: o teste mede a tela sem correção
     * e as barras de ajuste não ficam cobertas pela foto da tela.
     */
    var appInFront: Boolean
        get() = prefs.getBoolean(KEY_APP_IN_FRONT, false)
        set(v) = prefs.edit().putBoolean(KEY_APP_IN_FRONT, v).apply()

    companion object {
        const val KEY_PROFILE = "profile"
        const val KEY_APP_IN_FRONT = "app_in_front"
        const val KEY_NIGHT_FILTER = "night_filter"
        const val KEY_FOCUS_ENABLED = "focus_enabled"
        const val KEY_FOCUS_RADIUS = "focus_radius"
        const val KEY_FOCUS_CONTRAST = "focus_contrast"

        /** O que o serviço precisa reaplicar quando muda. */
        val SERVICE_KEYS = setOf(
            KEY_PROFILE, KEY_APP_IN_FRONT, KEY_NIGHT_FILTER, KEY_FOCUS_ENABLED, KEY_FOCUS_RADIUS, KEY_FOCUS_CONTRAST
        )
    }
}

/** Conta as telas do app abertas; a troca entre elas não deve religar a correção no meio. */
object InApp {
    private var started = 0

    fun start(store: Store) {
        if (started++ == 0) store.appInFront = true
    }

    fun stop(store: Store) {
        if (--started == 0) store.appInFront = false
    }
}
