package br.reguladordefoco

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.random.Random

/**
 * A bateria de testes, como numa consulta, na distância em que a pessoa usa o celular:
 * 1. O "E" que gira: olho esquerdo, olho direito e os dois juntos. Mede o quanto cada um embaça.
 * 2. Com ou sem correção de foco: confere se a correção ajuda.
 * 3. Foco geral: o grau da correção, no "melhor o 1 ou o 2?".
 * 4. Foco fino: a intensidade, também no "1 ou 2".
 * 5. Conforto da luz para a noite: a força do filtro âmbar.
 */
class VisionTestActivity : Activity() {
    private enum class Step { ACUITY_LEFT, ACUITY_RIGHT, ACUITY_BOTH, FOCUS_CHECK, FOCUS_GENERAL, FOCUS_FINE, NIGHT }

    private lateinit var store: Store
    private val steps = Step.entries.filter { FocusShader.available || it !in FOCUS_STEPS }
    private var stepIndex = 0
    private val step get() = steps[stepIndex]
    private lateinit var stair: VisionMath.Staircase
    private lateinit var optotype: OptotypeView
    private lateinit var progress: TextView
    private var direction = Direction.RIGHT
    private val couldNotSeeLargest = mutableSetOf<Step>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showIntro()
    }

    // Sem correção nem filtro durante o teste: tudo tem que aparecer como a tela é de verdade.
    override fun onStart() {
        super.onStart()
        InApp.start(store)
    }

    override fun onStop() {
        InApp.stop(store)
        super.onStop()
    }

    private fun next() {
        stepIndex++
        // Quem preferiu sem correção não precisa ajustar o grau dela.
        while (stepIndex < steps.size && step in FOCUS_TUNING && !store.focusEnabled) stepIndex++
        if (stepIndex < steps.size) showIntro() else showSummary()
    }

    // ---- Apresentação de cada etapa ----

    private fun showIntro() {
        val col = Ui.column(this)
        col.addView(Ui.text(this, "Teste ${stepIndex + 1} de ${steps.size}", 18f, Ui.MUTED))
        val (title, body) = when (step) {
            Step.ACUITY_LEFT -> "Olho ESQUERDO" to
                "Tape o olho DIREITO com a mão, sem apertar.\n\n$HOLD\n\n$E_RULES"
            Step.ACUITY_RIGHT -> "Olho DIREITO" to
                "Agora tape o olho ESQUERDO com a mão, sem apertar.\n\n$HOLD"
            Step.ACUITY_BOTH -> "Os DOIS olhos" to
                "Agora destampe e use os dois olhos, como no dia a dia.\n\n$HOLD"
            Step.FOCUS_CHECK -> "Correção de foco" to
                "Agora é o foco, como as lentes do oftalmologista. Vai aparecer um texto de dois " +
                "jeitos: 1 e 2. Um deles está com a correção de foco calculada para o seu embaçado.\n\n" +
                "Toque em 1 e em 2 para comparar quantas vezes quiser, na mesma distância de antes, " +
                "e escolha o MAIS NÍTIDO."
            Step.FOCUS_GENERAL -> "Foco geral" to
                "Agora o grau da correção. Em cada rodada, compare 1 e 2 e escolha o MAIS NÍTIDO: " +
                "letras mais definidas, menos borradas."
            Step.FOCUS_FINE -> "Foco fino" to
                "Por último, a intensidade. Escolha o mais nítido E confortável: sem bordas " +
                "brilhando nem letras \"tremidas\"."
            Step.NIGHT -> "Luz para antes de dormir" to
                "Se puder, faça esta parte no lugar onde usa o celular antes de dormir, com a luz " +
                "apagada.\n\nEscolha o filtro em que a tela fica mais confortável, sem ofuscar."
        }
        col.addView(Ui.text(this, title, 28f, Ui.ACCENT, bold = true))
        col.addView(Ui.text(this, body, 20f))
        col.addView(Ui.button(this, "Começar") { start() })
        if (step == Step.NIGHT) col.addView(Ui.button(this, "Pular esta parte", filled = false) { next() })
        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(Ui.BG) })
    }

    private fun start() {
        when (step) {
            Step.ACUITY_LEFT, Step.ACUITY_RIGHT, Step.ACUITY_BOTH -> startE()
            Step.FOCUS_CHECK -> {
                val estimate = Regulator.mmToPx(this, VisionMath.focusRadiusEstimateMm(threshold()))
                startPair(VisionMath.OnOff(estimate.coerceIn(1.0, VisionMath.FOCUS_MAX_PX)), { sample, r ->
                    sample.setFocus(r, VisionMath.FINE_K_DEFAULT)
                }) { r ->
                    store.focusEnabled = r > 0.0
                    if (r > 0.0) store.focusRadius = r
                    store.focusContrast = VisionMath.FINE_K_DEFAULT
                }
            }
            Step.FOCUS_GENERAL -> startPair(VisionMath.focusSearch(store.focusRadius), { sample, r ->
                sample.setFocus(r, store.focusContrast)
            }) { r -> store.focusRadius = r }
            Step.FOCUS_FINE -> startPair(VisionMath.fineSearch(store.focusContrast), { sample, k ->
                sample.setFocus(store.focusRadius, k)
            }) { k -> store.focusContrast = k }
            Step.NIGHT -> startNight()
        }
    }

    private fun threshold(): Double = store.chosenThreshold() ?: VisionMath.SIZES_MM[VisionMath.START_INDEX]

    // ---- O "E" que gira ----

    private fun startE() {
        stair = VisionMath.Staircase()
        val col = Ui.column(this)
        progress = Ui.text(this, "", 18f, Ui.MUTED)
        col.addView(progress)
        optotype = OptotypeView(this)
        col.addView(optotype, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(arrowPad())
        col.addView(Ui.button(this, "Não consigo ver", filled = false) { answer(null) })
        setContentView(col)
        nextTrial()
    }

    private fun arrowPad(): View {
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun row(vararg cells: Direction?) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (d in cells) {
                val cell: View = if (d == null) View(context) else Ui.button(context, d.arrow) { answer(d) }.apply {
                    textSize = 30f
                    contentDescription = d.label
                }
                addView(cell, LinearLayout.LayoutParams(0, Ui.dp(context, 72f), 1f).apply {
                    val m = Ui.dp(context, 4f)
                    setMargins(m, m, m, m)
                })
            }
        }
        pad.addView(row(null, Direction.UP, null))
        pad.addView(row(Direction.LEFT, null, Direction.RIGHT))
        pad.addView(row(null, Direction.DOWN, null))
        return pad
    }

    private fun nextTrial() {
        // Nunca repete o lado: assim fica claro que mudou de tentativa.
        direction = Direction.entries.filter { it != direction }.random(Random)
        optotype.show(Regulator.mmToPx(this, VisionMath.SIZES_MM[stair.index]).toFloat(), direction)
        val levels = VisionMath.SIZES_MM.size
        progress.text = "${eyeName(eyeOf(step))} · tamanho ${levels - stair.index} de $levels"
    }

    private fun answer(chosen: Direction?) {
        stair.answer(chosen == direction)
        if (!stair.finished) {
            nextTrial()
            return
        }
        val passed = stair.smallestPassed
        if (passed == null) couldNotSeeLargest += step
        // Nem o maior "E" deu: conta como um passo além do maior.
        store.setThreshold(eyeOf(step), passed?.let { VisionMath.SIZES_MM[it] } ?: (VisionMath.SIZES_MM.last() * 1.26))
        next()
    }

    // ---- "Melhor o 1 ou o 2?" com o foco ----

    /** Texto de exemplo num tamanho em que o embaçado atrapalha: o dobro do limite da pessoa. */
    private fun sampleText() = CorrectedImageView(this).apply {
        setBackgroundColor(Color.WHITE)
        bitmap = sampleBitmap(this@VisionTestActivity, SAMPLE, threshold())
    }

    private fun startPair(
        search: VisionMath.PairChoice,
        render: (CorrectedImageView, Double) -> Unit,
        onDone: (Double) -> Unit,
    ) {
        val col = Ui.column(this)
        val info = Ui.text(this, "", 18f, Ui.MUTED)
        col.addView(info)
        val sample = sampleText()
        col.addView(sample, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val toggles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(toggles)
        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(choices)
        setContentView(col)

        fun round() {
            if (search.finished) {
                onDone(search.value)
                next()
                return
            }
            if (search.smaller == search.bigger) {
                // Já no limite: não há o que comparar nesta rodada.
                search.choose(VisionMath.Choice.SAME)
                round()
                return
            }
            // Ordem sorteada: o 1 nem sempre é o menor, para não viciar a escolha.
            val smallerFirst = Random.nextBoolean()
            val values = if (smallerFirst) listOf(search.smaller, search.bigger) else listOf(search.bigger, search.smaller)
            info.text = if (search.rounds > 1) "Rodada ${search.round + 1} de ${search.rounds}" else ""

            fun show(which: Int) {
                render(sample, values[which - 1])
                toggles.removeAllViews()
                for (n in 1..2) {
                    toggles.addView(
                        Ui.button(this, "$n", filled = n == which) { show(n) }.apply { textSize = 26f },
                        LinearLayout.LayoutParams(0, Ui.dp(this, 64f), 1f).apply {
                            val m = Ui.dp(this@VisionTestActivity, 4f)
                            setMargins(m, m, m, m)
                        }
                    )
                }
            }
            show(1)

            fun pick(n: Int) = if ((n == 1) == smallerFirst) VisionMath.Choice.SMALLER else VisionMath.Choice.BIGGER
            choices.removeAllViews()
            choices.addView(Ui.button(this, "O 1 está mais nítido") { search.choose(pick(1)); round() })
            choices.addView(Ui.button(this, "O 2 está mais nítido") { search.choose(pick(2)); round() })
            choices.addView(Ui.button(this, "Tanto faz", filled = false) {
                search.choose(VisionMath.Choice.SAME)
                round()
            })
        }
        round()
    }

    // ---- Luz para antes de dormir ----

    private fun startNight() {
        val col = Ui.column(this)
        val sample = sampleText()
        if (store.focusEnabled) sample.setFocus(store.focusRadius, store.focusContrast)
        val filter = View(this)
        col.addView(
            FrameLayout(this).apply {
                addView(sample)
                addView(filter, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(options)
        val names = listOf("Sem filtro", "Fraco", "Médio", "Forte")
        var chosen = VisionMath.NIGHT_FILTERS.indexOfFirst { it.toFloat() == store.nightFilter }.coerceAtLeast(0)
        fun render() {
            filter.setBackgroundColor(FocusService.nightColor(VisionMath.NIGHT_FILTERS[chosen].toFloat()))
            options.removeAllViews()
            for (i in names.indices) {
                options.addView(Ui.button(this, names[i], filled = i == chosen) { chosen = i; render() })
            }
            options.addView(Ui.button(this, "Pronto", filled = false) {
                store.nightFilter = VisionMath.NIGHT_FILTERS[chosen].toFloat()
                next()
            })
        }
        render()
        setContentView(col)
    }

    // ---- Resultado ----

    private fun showSummary() {
        // Se já havia um modo ligado, ele passa a usar o resultado novo.
        if (store.profile != Profile.NORMAL) Regulator.apply(this, store.profile)

        val col = Ui.column(this)
        col.addView(Ui.text(this, "Resultado", 30f, Ui.ACCENT, bold = true))
        for (s in listOf(Step.ACUITY_LEFT, Step.ACUITY_RIGHT, Step.ACUITY_BOTH)) {
            val mm = store.threshold(eyeOf(s)) ?: continue
            val note = if (s in couldNotSeeLargest) " (não viu nem o maior)" else ""
            col.addView(Ui.text(this, "${eyeName(eyeOf(s))}: vê letras a partir de ${Ui.format(mm)} mm$note", 21f))
        }
        val left = store.threshold(EyeSource.LEFT)
        val right = store.threshold(EyeSource.RIGHT)
        if (left != null && right != null && VisionMath.eyesDifferALot(left, right)) {
            col.addView(
                Ui.text(
                    this,
                    "Um olho está enxergando bem pior que o outro. Vale marcar uma consulta " +
                        "com um oftalmologista.",
                    20f, Ui.WARN
                )
            )
        }
        col.addView(Ui.text(this, focusSummary(store), 22f, bold = true))
        col.addView(Ui.text(this, "Filtro da noite: ${Ui.percent(store.nightFilter.toDouble())}", 21f))
        val warning = Ui.text(this, "", 20f, Ui.WARN)
        col.addView(Ui.button(this, "Ligar o modo \"Sem óculos\"") {
            val problems = Regulator.apply(this, Profile.NO_GLASSES)
            if (problems.isEmpty()) finish() else warning.text = problems.joinToString("\n")
        })
        col.addView(warning)
        col.addView(Ui.button(this, "Voltar", filled = false) { finish() })
        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(Ui.BG) })
    }

    private fun eyeOf(s: Step) = when (s) {
        Step.ACUITY_LEFT -> EyeSource.LEFT
        Step.ACUITY_RIGHT -> EyeSource.RIGHT
        else -> EyeSource.BOTH
    }

    companion object {
        private val FOCUS_TUNING = setOf(Step.FOCUS_GENERAL, Step.FOCUS_FINE)
        private val FOCUS_STEPS = FOCUS_TUNING + Step.FOCUS_CHECK

        private const val HOLD = "Segure o celular na distância de sempre, do jeito que você quer usar " +
            "o ajuste (por exemplo, sem óculos). Não aproxime nem afaste durante os testes."
        private const val E_RULES = "Vai aparecer a letra E virada para um lado. Toque na seta para onde " +
            "as \"pernas\" do E apontam. Se não der para ver, toque em \"Não consigo ver\". Na dúvida, " +
            "chute: o teste repete cada tamanho."
        private const val SAMPLE = "O sol entrava pela janela da cozinha enquanto o café passava. " +
            "Lá fora, as crianças corriam atrás da bola e o cachorro latia sem parar. Era um domingo " +
            "tranquilo, bom para ler as notícias com calma e responder as mensagens da família."

        fun eyeName(eye: EyeSource) = when (eye) {
            EyeSource.LEFT -> "Olho esquerdo"
            EyeSource.RIGHT -> "Olho direito"
            EyeSource.BOTH -> "Os dois olhos"
        }

        /** Grau e intensidade como níveis de 0 a 100, mais fáceis de ler que px. */
        fun focusLevel(radiusPx: Double) =
            VisionMath.logLevel(radiusPx, 100, VisionMath.FOCUS_MIN_PX, VisionMath.FOCUS_MAX_PX)


        /** O texto de exemplo como imagem, da largura da tela, para receber a correção. */
        fun sampleBitmap(context: Context, text: String, thresholdMm: Double) = FocusShader.textBitmap(
            text,
            Regulator.mmToPx(context, 2 * thresholdMm).toFloat(),
            context.resources.displayMetrics.widthPixels - 2 * Ui.dp(context, 20f),
            Ui.dp(context, 12f),
        )

        fun focusSummary(store: Store): String = when {
            !FocusShader.available -> "Correção de foco: precisa do Android 13 ou mais novo."
            !store.focusEnabled -> "Correção de foco: desligada (você enxergou melhor sem ela)."
            else -> "Correção de foco: foco geral ${focusLevel(store.focusRadius)}, " +
                "foco fino ${VisionMath.fineLevel(store.focusContrast)} (de 0 a 100)"
        }
    }
}

/** Para onde as "pernas" do E apontam, e quanto girar o desenho (sentido horário). */
enum class Direction(val degrees: Float, val arrow: String, val label: String) {
    RIGHT(0f, "→", "Direita"),
    DOWN(90f, "↓", "Baixo"),
    LEFT(180f, "←", "Esquerda"),
    UP(270f, "↑", "Cima"),
}

/** O "E" preto em fundo branco: 5 traços de altura, como nas tabelas de consultório. */
class OptotypeView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private var heightPx = 0f
    private var direction = Direction.RIGHT

    init {
        setBackgroundColor(Color.WHITE)
        contentDescription = "Letra E"
    }

    fun show(heightPx: Float, direction: Direction) {
        this.heightPx = heightPx
        this.direction = direction
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = heightPx / 5f
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.rotate(direction.degrees)
        val h = heightPx / 2f
        canvas.drawRect(-h, -h, -h + s, h, paint)
        for (k in 0..2) {
            val top = -h + 2 * k * s
            canvas.drawRect(-h, top, h, top + s, paint)
        }
        canvas.restore()
    }
}
