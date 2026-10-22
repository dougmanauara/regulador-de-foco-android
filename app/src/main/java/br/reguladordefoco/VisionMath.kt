package br.reguladordefoco

import kotlin.math.pow

/**
 * Contas do teste do "E" e de quanto aumentar a tela. Sem nada de Android, para testar no PC.
 *
 * O teste mede, na distância em que a pessoa segura o celular, o menor "E" (em mm na tela)
 * em que ela ainda acerta para onde ele aponta. Ler com conforto pede letras com o dobro
 * desse tamanho, e daí sai o aumento: primeiro nas letras do sistema, o resto em zoom.
 */
object VisionMath {
    /** Alturas do "E" em mm, em passos de 0,1 logMAR (cada um ~26% maior): 0,4 a ~12,6 mm. */
    val SIZES_MM: List<Double> = List(16) { 0.4 * 10.0.pow(it / 10.0) }

    /** Começa em ~4 mm, que quase todo mundo enxerga, e vai diminuindo. */
    const val START_INDEX = 10

    /** Folga de conforto: ler sem esforço pede o dobro do menor tamanho que se distingue. */
    const val COMFORT_RESERVE = 2.0

    const val MIN_FONT_SCALE = 0.85
    const val MAX_FONT_SCALE = 2.0
    const val MAX_ZOOM = 5.0

    /** Abaixo disso o zoom não compensa: só as letras resolvem. */
    const val MIN_USEFUL_ZOOM = 1.1

    /** Texto comum do Android tem 14sp, e a maiúscula ocupa ~70% disso. */
    private const val BODY_TEXT_SP = 14.0
    private const val CAP_HEIGHT_RATIO = 0.7

    /** À noite os olhos cansados pedem um pouco mais. */
    const val NIGHT_EXTRA = 1.15

    /** Altura da maiúscula do texto comum, em mm, com as letras em 100%. */
    fun bodyCapMm(density: Double, dpi: Double): Double =
        BODY_TEXT_SP * CAP_HEIGHT_RATIO * density / dpi * 25.4

    data class Setting(val fontScale: Double, val zoom: Double)

    /**
     * Quanto aumentar para que o texto comum fique [COMFORT_RESERVE] vezes maior que o limite.
     * [fineTune] é o ajuste fino da pessoa (1.0 = como o teste mandou).
     */
    fun recommend(thresholdMm: Double, bodyCapMm: Double, fineTune: Double = 1.0, extra: Double = 1.0): Setting {
        val total = COMFORT_RESERVE * thresholdMm * fineTune * extra / bodyCapMm
        val font = total.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        val rest = total / font
        val zoom = if (rest < MIN_USEFUL_ZOOM) 1.0 else rest.coerceAtMost(MAX_ZOOM)
        return Setting(font, zoom)
    }

    /** Diferença de 0,3 logMAR (2x) ou mais entre os olhos merece consulta. */
    fun eyesDifferALot(leftMm: Double, rightMm: Double): Boolean =
        maxOf(leftMm, rightMm) / minOf(leftMm, rightMm) >= 2.0 - 1e-9

    /**
     * Escada de tamanhos: em cada tamanho, até 3 tentativas; 2 acertos passa, 2 erros reprova.
     * Passou: diminui. Reprovou: aumenta. Termina na fronteira entre um tamanho que passou
     * e o vizinho menor que reprovou.
     */
    class Staircase(start: Int = START_INDEX) {
        var index = start
            private set
        var finished = false
            private set

        /** Menor tamanho em que a pessoa acertou, ou null se não acertou nem o maior. */
        var smallestPassed: Int? = null
            private set
        private var largestFailed: Int? = null
        private var right = 0
        private var wrong = 0

        val thresholdMm: Double?
            get() = smallestPassed?.let { SIZES_MM[it] }

        fun answer(correct: Boolean) {
            check(!finished) { "teste já terminou" }
            if (correct) right++ else wrong++
            when {
                right >= 2 -> pass()
                wrong >= 2 -> fail()
            }
        }

        private fun pass() {
            smallestPassed = minOf(index, smallestPassed ?: index)
            val failed = largestFailed
            if (index == 0 || (failed != null && failed >= index - 1)) finished = true else index--
            newLevel()
        }

        private fun fail() {
            largestFailed = maxOf(index, largestFailed ?: index)
            val passed = smallestPassed
            if (index == SIZES_MM.lastIndex || (passed != null && passed <= index + 1)) finished = true else index++
            newLevel()
        }

        private fun newLevel() {
            right = 0
            wrong = 0
        }
    }
}
