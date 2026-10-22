package br.reguladordefoco

import android.content.Context
import android.content.SharedPreferences

/** De qual olho vem o ajuste: a tela é uma só, então a pessoa escolhe. */
enum class EyeSource { BOTH, LEFT, RIGHT }

enum class Profile { NORMAL, NO_GLASSES, NIGHT }

/**
 * Resultados do teste e o modo em uso. O serviço escuta estas preferências e aplica
 * zoom e filtro na hora, venha a mudança do app ou do botão na barra de notificações.
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

    /** Ajuste fino por cima do teste: 1.0 = como o teste mandou. */
    var fineTune: Double
        get() = prefs.getFloat("fine_tune", 1f).toDouble()
        set(v) = prefs.edit().putFloat("fine_tune", v.toFloat()).apply()

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

    /** Zoom que o serviço deve aplicar (1 = sem zoom). */
    var targetZoom: Float
        get() = prefs.getFloat(KEY_ZOOM, 1f)
        set(v) = prefs.edit().putFloat(KEY_ZOOM, v).apply()

    /** Durante o teste o zoom e o filtro saem, senão o "E" aparece maior do que é. */
    var testing: Boolean
        get() = prefs.getBoolean(KEY_TESTING, false)
        set(v) = prefs.edit().putBoolean(KEY_TESTING, v).apply()

    companion object {
        const val KEY_PROFILE = "profile"
        const val KEY_ZOOM = "target_zoom"
        const val KEY_TESTING = "testing"
    }
}
