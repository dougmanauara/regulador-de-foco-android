package br.reguladordefoco

import kotlin.math.pow

/**
 * Contas dos testes de visão e de quanto aumentar a tela. Sem nada de Android, para testar no PC.
 *
 * O teste do "E" mede, na distância em que a pessoa segura o celular, o menor "E" (em mm na tela)
 * em que ela ainda acerta para onde ele aponta. Ler com conforto pede letras com o dobro
 * desse tamanho, e daí sai o aumento: primeiro nas letras do sistema, o resto em zoom.
 * O teste de contraste e o "1 ou 2" ajustam esse aumento para mais ou para menos.
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

    /** Faixa do ajuste fino (o "1 ou 2" e a barra), em passos de 5%. */
    const val FINE_MIN = 0.7
    const val FINE_MAX = 1.5
    const val FINE_STEP = 0.05

    fun roundFine(v: Double): Double =
        (Math.round(v / FINE_STEP) * FINE_STEP).coerceIn(FINE_MIN, FINE_MAX)

    /** Contrastes do "E" cinza (contraste de Weber), do mais fraco ao total: ~1,6% a 100%, dobrando. */
    val CONTRASTS: List<Double> = List(7) { 2.0.pow(it - 6) }

    /** Começa em 25%, que quase todo mundo enxerga. */
    const val CONTRAST_START = 4

    /** No teste de contraste o "E" é bem maior que o limite, para medir contraste e não nitidez. */
    fun contrastTestSizeMm(acuityMm: Double): Double = (3 * acuityMm).coerceIn(4.0, SIZES_MM.last())

    /** Cinza (0 a 255) do "E" sobre branco que dá o contraste [c], com a gama 2,2 das telas. */
    fun grayFor(c: Double): Int = Math.round(255 * (1 - c).coerceIn(0.0, 1.0).pow(1 / 2.2)).toInt()

    /**
     * Quem precisa de mais contraste lê melhor com letra maior: até 25% a mais.
     * Valores aproximados: enxergar ~3% com o "E" grande é o normal.
     */
    fun contrastFactor(thresholdContrast: Double): Double = when {
        thresholdContrast <= 0.04 -> 1.0
        thresholdContrast <= 0.07 -> 1.1
        else -> 1.25
    }

    /** Força do filtro âmbar da noite que a pessoa pode escolher. */
    val NIGHT_FILTERS: List<Double> = listOf(0.0, 0.2, 0.35, 0.5)

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
     * Escada de níveis, do mais difícil (índice 0) ao mais fácil: em cada nível, até 3 tentativas;
     * 2 acertos passa, 2 erros reprova. Passou: fica mais difícil. Reprovou: mais fácil.
     * Termina na fronteira entre um nível que passou e o vizinho mais difícil que reprovou.
     */
    class Staircase(private val levels: Int = SIZES_MM.size, start: Int = START_INDEX) {
        var index = start
            private set
        var finished = false
            private set

        /** Nível mais difícil em que a pessoa acertou, ou null se não acertou nem o mais fácil. */
        var smallestPassed: Int? = null
            private set
        private var largestFailed: Int? = null
        private var right = 0
        private var wrong = 0

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
            if (index == levels - 1 || (passed != null && passed <= index + 1)) finished = true else index++
            newLevel()
        }

        private fun newLevel() {
            right = 0
            wrong = 0
        }
    }

    /**
     * "Melhor o 1 ou o 2?", como no oftalmologista: mostra um texto um pouco menor e um pouco
     * maior que o ajuste atual, e vai para o escolhido em passos cada vez menores.
     */
    class ComfortSearch(start: Double = 1.0) {
        private val steps = listOf(1.3, 1.15, 1.07)
        var value = start
            private set
        var round = 0
            private set
        val rounds: Int get() = steps.size
        val finished: Boolean get() = round >= steps.size
        val smaller: Double get() = (value / steps[round]).coerceIn(FINE_MIN, FINE_MAX)
        val bigger: Double get() = (value * steps[round]).coerceIn(FINE_MIN, FINE_MAX)

        enum class Choice { SMALLER, SAME, BIGGER }

        fun choose(choice: Choice) {
            check(!finished) { "busca já terminou" }
            value = when (choice) {
                Choice.SMALLER -> smaller
                Choice.SAME -> value
                Choice.BIGGER -> bigger
            }
            round++
        }
    }
}
