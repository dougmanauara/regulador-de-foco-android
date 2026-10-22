package br.reguladordefoco

import kotlin.math.ln
import kotlin.math.pow

/**
 * Contas dos testes de visão e da correção de foco. Sem nada de Android, para testar no PC.
 *
 * O teste do "E" mede, na distância em que a pessoa segura o celular, o menor "E" (em mm na tela)
 * em que ela ainda acerta para onde ele aponta. O quanto isso passa do normal dá o primeiro
 * palpite do embaçado; o "1 ou 2" com o foco refina o grau (foco geral) e a intensidade (foco fino).
 */
object VisionMath {
    /** Alturas do "E" em mm, em passos de 0,1 logMAR (cada um ~26% maior): 0,4 a ~12,6 mm. */
    val SIZES_MM: List<Double> = List(16) { 0.4 * 10.0.pow(it / 10.0) }

    /** Começa em ~4 mm, que quase todo mundo enxerga, e vai diminuindo. */
    const val START_INDEX = 10

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

    // ---- "Melhor o 1 ou o 2?" ----

    /** Resposta do "melhor o 1 ou o 2?": o valor menor, o maior, ou tanto faz. */
    enum class Choice { SMALLER, SAME, BIGGER }

    /** Uma comparação "1 ou 2" em rodadas: mostra dois valores e anda para o escolhido. */
    interface PairChoice {
        val value: Double
        val round: Int
        val rounds: Int
        val finished: Boolean get() = round >= rounds
        val smaller: Double
        val bigger: Double
        fun choose(choice: Choice)
    }

    /**
     * Como as lentes do oftalmologista: mostra um valor um pouco menor e um pouco maior que o
     * atual, e vai para o escolhido em passos cada vez menores.
     */
    class PairSearch(
        start: Double,
        private val steps: List<Double>,
        private val min: Double,
        private val max: Double,
    ) : PairChoice {
        override var value = start.coerceIn(min, max)
            private set
        override var round = 0
            private set
        override val rounds: Int get() = steps.size
        override val smaller: Double get() = (value / steps[round]).coerceIn(min, max)
        override val bigger: Double get() = (value * steps[round]).coerceIn(min, max)

        override fun choose(choice: Choice) {
            check(!finished) { "busca já terminou" }
            value = when (choice) {
                Choice.SMALLER -> smaller
                Choice.SAME -> value
                Choice.BIGGER -> bigger
            }
            round++
        }
    }

    /** Uma rodada só: sem correção (0) ou com a correção [onValue]. "Tanto faz" fica sem. */
    class OnOff(private val onValue: Double) : PairChoice {
        override var value = 0.0
            private set
        override var round = 0
            private set
        override val rounds = 1
        override val smaller = 0.0
        override val bigger: Double get() = onValue

        override fun choose(choice: Choice) {
            check(!finished) { "escolha já feita" }
            value = if (choice == Choice.BIGGER) onValue else 0.0
            round++
        }
    }

    // ---- Correção de foco (pré-compensação do embaçado) ----

    /**
     * Foco geral: o raio do embaçado na tela, em px. A tela é pré-afiada com esse raio,
     * para que o embaçado do olho devolva a imagem mais perto da original.
     */
    const val FOCUS_MIN_PX = 0.3
    const val FOCUS_MAX_PX = 12.0

    /**
     * Foco fino: quanto do contraste fica na tela (k). A correção precisa de espaço além do preto
     * e do branco; com menos contraste sobra mais espaço e a correção fica mais forte.
     */
    const val FINE_K_MIN = 0.3
    const val FINE_K_MAX = 0.9
    const val FINE_K_DEFAULT = 0.55

    /** Foco fino como intensidade de 0 a 100 (100 = correção mais forte, contraste mais baixo). */
    fun fineLevel(k: Double): Int =
        Math.round((FINE_K_MAX - k.coerceIn(FINE_K_MIN, FINE_K_MAX)) / (FINE_K_MAX - FINE_K_MIN) * 100).toInt()

    fun fineK(level: Int): Double = FINE_K_MAX - level.coerceIn(0, 100) / 100.0 * (FINE_K_MAX - FINE_K_MIN)

    /**
     * Primeiro palpite do raio do embaçado, em mm na tela, a partir do teste do "E":
     * o que passa de ~0,5 mm (visão normal na distância do celular) vira embaçado.
     */
    fun focusRadiusEstimateMm(thresholdMm: Double): Double = 0.2 * maxOf(thresholdMm - 0.5, 0.25)

    /** Foco geral: começa no palpite e refina em 3 rodadas. */
    fun focusSearch(startPx: Double) = PairSearch(startPx, listOf(1.8, 1.35, 1.15), FOCUS_MIN_PX, FOCUS_MAX_PX)

    /** Foco fino: o contraste, em 2 rodadas. */
    fun fineSearch(start: Double = FINE_K_DEFAULT) = PairSearch(start, listOf(1.3, 1.14), FINE_K_MIN, FINE_K_MAX)

    /** Barras de ajuste em escala logarítmica: [level] de 0 a [levels] vai de [min] a [max]. */
    fun logScale(level: Int, levels: Int, min: Double, max: Double): Double =
        min * (max / min).pow(level.toDouble() / levels)

    fun logLevel(value: Double, levels: Int, min: Double, max: Double): Int =
        Math.round(ln(value.coerceIn(min, max) / min) / ln(max / min) * levels).toInt()

    // ---- Letras maiores (opcional) e noite ----

    /** Folga de conforto: ler sem esforço pede o dobro do menor tamanho que se distingue. */
    const val COMFORT_RESERVE = 2.0
    const val MIN_FONT_SCALE = 0.85
    const val MAX_FONT_SCALE = 2.0

    /** Texto comum do Android tem 14sp, e a maiúscula ocupa ~70% disso. */
    private const val BODY_TEXT_SP = 14.0
    private const val CAP_HEIGHT_RATIO = 0.7

    /** Altura da maiúscula do texto comum, em mm, com as letras em 100%. */
    fun bodyCapMm(density: Double, dpi: Double): Double =
        BODY_TEXT_SP * CAP_HEIGHT_RATIO * density / dpi * 25.4

    /** Tamanho das letras para que o texto comum fique [COMFORT_RESERVE] vezes maior que o limite. */
    fun recommendFontScale(thresholdMm: Double, bodyCapMm: Double): Double =
        (COMFORT_RESERVE * thresholdMm / bodyCapMm).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)

    /** Força do filtro âmbar da noite que a pessoa pode escolher. */
    val NIGHT_FILTERS: List<Double> = listOf(0.0, 0.2, 0.35, 0.5)
}
