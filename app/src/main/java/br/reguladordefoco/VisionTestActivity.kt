package br.reguladordefoco

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.random.Random

/**
 * A bateria de testes, como numa consulta, na distância em que a pessoa usa o celular:
 * 1. Tamanho (o "E" que gira): olho esquerdo, olho direito e os dois juntos.
 * 2. Contraste: o "E" vai ficando cinza-claro.
 * 3. "Melhor o 1 ou o 2?": dois tamanhos de texto, para refinar o ajuste.
 * 4. Conforto da luz para a noite: a força do filtro âmbar.
 */
class VisionTestActivity : Activity() {
    private enum class Step { ACUITY_LEFT, ACUITY_RIGHT, ACUITY_BOTH, CONTRAST, COMFORT, NIGHT }

    private lateinit var store: Store
    private var stepIndex = 0
    private val step get() = Step.entries[stepIndex]
    private lateinit var stair: VisionMath.Staircase
    private lateinit var optotype: OptotypeView
    private lateinit var progress: TextView
    private var direction = Direction.RIGHT
    private val couldNotSeeEasiest = mutableSetOf<Step>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showIntro()
    }

    // Sem zoom nem filtro durante o teste: tudo tem que aparecer no tamanho e na cor reais.
    override fun onStart() {
        super.onStart()
        store.testing = true
    }

    override fun onStop() {
        store.testing = false
        super.onStop()
    }

    private fun next() {
        stepIndex++
        if (stepIndex < Step.entries.size) showIntro() else showSummary()
    }

    // ---- Apresentação de cada etapa ----

    private fun showIntro() {
        val col = Ui.column(this)
        col.addView(Ui.text(this, "Teste ${stepIndex + 1} de ${Step.entries.size}", 18f, Ui.MUTED))
        val (title, body) = when (step) {
            Step.ACUITY_LEFT -> "Tamanho · olho ESQUERDO" to
                "Tape o olho DIREITO com a mão, sem apertar.\n\n$HOLD\n\n$E_RULES"
            Step.ACUITY_RIGHT -> "Tamanho · olho DIREITO" to
                "Agora tape o olho ESQUERDO com a mão, sem apertar.\n\n$HOLD"
            Step.ACUITY_BOTH -> "Tamanho · os DOIS olhos" to
                "Agora destampe e use os dois olhos, como no dia a dia.\n\n$HOLD"
            Step.CONTRAST -> "Contraste" to
                "Com os dois olhos. Agora o E é grande, mas vai ficando cada vez mais claro, " +
                "quase sumindo no branco.\n\n$E_RULES"
            Step.COMFORT -> "Melhor o 1 ou o 2?" to
                "Como no oftalmologista. Vão aparecer dois tamanhos de texto: toque em 1 e em 2 " +
                "para comparar quantas vezes quiser.\n\nEscolha o MENOR que você lê sem nenhum " +
                "esforço. Se os dois estiverem bons, fique com o menor."
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

    private fun start() = when (step) {
        Step.ACUITY_LEFT, Step.ACUITY_RIGHT, Step.ACUITY_BOTH ->
            startE(VisionMath.Staircase(VisionMath.SIZES_MM.size, VisionMath.START_INDEX))
        Step.CONTRAST -> startE(VisionMath.Staircase(VisionMath.CONTRASTS.size, VisionMath.CONTRAST_START))
        Step.COMFORT -> startComfort()
        Step.NIGHT -> startNight()
    }

    // ---- Tamanho e contraste: o "E" que gira ----

    private fun startE(staircase: VisionMath.Staircase) {
        stair = staircase
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

    private fun mmToPx(mm: Double): Float = (mm / 25.4 * Regulator.reliableDpi(resources.displayMetrics)).toFloat()

    private fun nextTrial() {
        // Nunca repete o lado: assim fica claro que mudou de tentativa.
        direction = Direction.entries.filter { it != direction }.random(Random)
        val levels: Int
        if (step == Step.CONTRAST) {
            val acuity = store.threshold(EyeSource.BOTH) ?: store.chosenThreshold() ?: VisionMath.SIZES_MM[0]
            val gray = VisionMath.grayFor(VisionMath.CONTRASTS[stair.index])
            optotype.show(mmToPx(VisionMath.contrastTestSizeMm(acuity)), direction, Color.rgb(gray, gray, gray))
            levels = VisionMath.CONTRASTS.size
        } else {
            optotype.show(mmToPx(VisionMath.SIZES_MM[stair.index]), direction, Color.BLACK)
            levels = VisionMath.SIZES_MM.size
        }
        progress.text = "${stepName(step)} · nível ${levels - stair.index} de $levels"
    }

    private fun answer(chosen: Direction?) {
        stair.answer(chosen == direction)
        if (!stair.finished) {
            nextTrial()
            return
        }
        val passed = stair.smallestPassed
        if (passed == null) couldNotSeeEasiest += step
        when (step) {
            // Nem o maior "E" deu: conta como um passo além do maior, e o zoom compensa.
            Step.ACUITY_LEFT, Step.ACUITY_RIGHT, Step.ACUITY_BOTH -> store.setThreshold(
                eyeOf(step), passed?.let { VisionMath.SIZES_MM[it] } ?: (VisionMath.SIZES_MM.last() * 1.26)
            )
            else -> store.contrastThreshold = passed?.let { VisionMath.CONTRASTS[it] } ?: 2.0
        }
        next()
    }

    // ---- "Melhor o 1 ou o 2?" ----

    private fun startComfort() {
        val search = VisionMath.ComfortSearch(VisionMath.roundFine(store.fineTune))
        val col = Ui.column(this)
        val info = Ui.text(this, "", 18f, Ui.MUTED)
        col.addView(info)
        val sample = sampleText()
        col.addView(
            FrameLayout(this).apply { setBackgroundColor(Color.WHITE); addView(sample) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        val toggles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(toggles)
        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(choices)

        fun pxFor(fine: Double): Float {
            val s = VisionMath.recommend(
                store.chosenThreshold() ?: VisionMath.SIZES_MM[VisionMath.START_INDEX],
                Regulator.bodyCapMm(this), fine, store.contrastFactor()
            )
            return (14.0 * resources.displayMetrics.density * s.fontScale * s.zoom).toFloat()
        }

        lateinit var render: () -> Unit
        var showing = 1
        fun choose(c: VisionMath.ComfortSearch.Choice) {
            search.choose(c)
            if (search.finished) {
                store.fineTune = VisionMath.roundFine(search.value)
                next()
            } else {
                showing = 1
                render()
            }
        }
        render = {
            // Ordem sorteada: o 1 nem sempre é o menor, para não viciar a escolha.
            val smallerFirst = Random.nextBoolean()
            val one = if (smallerFirst) search.smaller else search.bigger
            val two = if (smallerFirst) search.bigger else search.smaller
            val pxOne = pxFor(one)
            val pxTwo = pxFor(two)
            if (pxOne == pxTwo) {
                // Mesmo tamanho (já no mínimo das letras): não há o que comparar nesta rodada.
                choose(VisionMath.ComfortSearch.Choice.SAME)
            } else {
                info.text = "Rodada ${search.round + 1} de ${search.rounds}"
                fun show(which: Int) {
                    showing = which
                    sample.setTextSize(TypedValue.COMPLEX_UNIT_PX, if (which == 1) pxOne else pxTwo)
                    toggles.removeAllViews()
                    for (n in 1..2) {
                        toggles.addView(
                            Ui.button(this, "$n", filled = n == showing) { show(n) }.apply { textSize = 26f },
                            LinearLayout.LayoutParams(0, Ui.dp(this, 64f), 1f).apply {
                                val m = Ui.dp(this@VisionTestActivity, 4f)
                                setMargins(m, m, m, m)
                            }
                        )
                    }
                }
                show(showing)
                fun pick(n: Int) = if ((n == 1) == smallerFirst) {
                    VisionMath.ComfortSearch.Choice.SMALLER
                } else {
                    VisionMath.ComfortSearch.Choice.BIGGER
                }
                choices.removeAllViews()
                choices.addView(Ui.button(this, "Fico com o 1") { choose(pick(1)) })
                choices.addView(Ui.button(this, "Fico com o 2") { choose(pick(2)) })
                choices.addView(Ui.button(this, "Tanto faz", filled = false) {
                    choose(VisionMath.ComfortSearch.Choice.SAME)
                })
            }
        }
        setContentView(col)
        render()
    }

    private fun sampleText() = TextView(this).apply {
        text = SAMPLE
        setTextColor(Color.BLACK)
        val pad = Ui.dp(context, 12f)
        setPadding(pad, pad, pad, pad)
    }

    // ---- Luz para antes de dormir ----

    private fun startNight() {
        val col = Ui.column(this)
        val sample = sampleText()
        val filter = View(this)
        val nightSetting = Regulator.setting(this, store, Profile.NIGHT)
        nightSetting?.let {
            sample.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                (14.0 * resources.displayMetrics.density * it.fontScale * it.zoom).toFloat()
            )
        }
        col.addView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.WHITE)
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
            val note = if (s in couldNotSeeEasiest) " (não viu nem o maior)" else ""
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
        store.contrastThreshold?.let {
            col.addView(Ui.text(this, "Contraste: ${contrastName(it)}", 21f))
        }
        col.addView(Ui.text(this, "Preferência de tamanho (1 ou 2): ${Ui.percent(store.fineTune)}", 21f))
        col.addView(Ui.text(this, "Filtro da noite: ${Ui.percent(store.nightFilter.toDouble())}", 21f))
        Regulator.setting(this, store, Profile.NO_GLASSES)?.let {
            col.addView(Ui.text(this, "Ajuste recomendado: ${describe(it)}", 22f, bold = true))
        }
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

    private fun stepName(s: Step) = if (s == Step.CONTRAST) "Contraste" else eyeName(eyeOf(s))

    companion object {
        private const val HOLD = "Segure o celular na distância de sempre, do jeito que você quer usar " +
            "o ajuste (por exemplo, sem óculos). Não aproxime nem afaste durante os testes."
        private const val E_RULES = "Vai aparecer a letra E virada para um lado. Toque na seta para onde " +
            "as \"pernas\" do E apontam. Se não der para ver, toque em \"Não consigo ver\". Na dúvida, " +
            "chute: o teste repete cada nível."
        private const val SAMPLE = "O sol entrava pela janela da cozinha enquanto o café passava. " +
            "Lá fora, as crianças corriam atrás da bola e o cachorro latia sem parar. Era um domingo " +
            "tranquilo, bom para ler as notícias com calma e responder as mensagens da família."

        fun eyeName(eye: EyeSource) = when (eye) {
            EyeSource.LEFT -> "Olho esquerdo"
            EyeSource.RIGHT -> "Olho direito"
            EyeSource.BOTH -> "Os dois olhos"
        }

        fun contrastName(threshold: Double) = when (VisionMath.contrastFactor(threshold)) {
            1.0 -> "normal"
            1.1 -> "um pouco baixo (letras 10% maiores)"
            else -> "baixo (letras 25% maiores; tela mais clara ajuda)"
        }

        fun describe(s: VisionMath.Setting): String {
            val zoom = if (s.zoom > 1.0) " · zoom ${Ui.format(s.zoom)}×" else " · sem zoom"
            return "letras em ${Ui.percent(s.fontScale)}$zoom"
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

/** O "E" em fundo branco: 5 traços de altura, como nas tabelas de consultório. */
class OptotypeView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var heightPx = 0f
    private var direction = Direction.RIGHT

    init {
        setBackgroundColor(Color.WHITE)
        contentDescription = "Letra E"
    }

    fun show(heightPx: Float, direction: Direction, color: Int) {
        this.heightPx = heightPx
        this.direction = direction
        paint.color = color
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
