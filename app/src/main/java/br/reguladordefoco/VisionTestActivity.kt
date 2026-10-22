package br.reguladordefoco

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.random.Random

/**
 * Teste da visão de cada olho com o "E" que gira, na distância em que a pessoa usa o celular.
 * Ordem: olho esquerdo, olho direito e os dois juntos (que é como a tela é vista no dia a dia).
 */
class VisionTestActivity : Activity() {
    private lateinit var store: Store
    private val eyes = listOf(EyeSource.LEFT, EyeSource.RIGHT, EyeSource.BOTH)
    private var eyeIndex = 0
    private lateinit var stair: VisionMath.Staircase
    private lateinit var optotype: OptotypeView
    private lateinit var progress: android.widget.TextView
    private var direction = Direction.RIGHT
    private val couldNotSeeLargest = mutableSetOf<EyeSource>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showIntro()
    }

    // Sem zoom nem filtro durante o teste: o "E" tem que aparecer no tamanho real.
    override fun onStart() {
        super.onStart()
        store.testing = true
    }

    override fun onStop() {
        store.testing = false
        super.onStop()
    }

    private fun showIntro() {
        val eye = eyes[eyeIndex]
        val col = Ui.column(this)
        val (title, cover) = when (eye) {
            EyeSource.LEFT -> "Olho ESQUERDO" to "Tape o olho DIREITO com a mão, sem apertar."
            EyeSource.RIGHT -> "Olho DIREITO" to "Agora tape o olho ESQUERDO com a mão, sem apertar."
            EyeSource.BOTH -> "Os DOIS olhos" to "Agora destampe e use os dois olhos, como no dia a dia."
        }
        col.addView(Ui.text(this, "Teste ${eyeIndex + 1} de ${eyes.size}", 18f, Ui.MUTED))
        col.addView(Ui.text(this, title, 30f, Ui.ACCENT, bold = true))
        col.addView(Ui.text(this, cover, 22f))
        col.addView(
            Ui.text(
                this,
                "Segure o celular na distância de sempre, do jeito que você quer usar " +
                    "o ajuste (por exemplo, sem óculos). Não aproxime nem afaste durante o teste.\n\n" +
                    "Vai aparecer a letra E virada para um lado. Toque na seta para onde as " +
                    "\"pernas\" do E apontam. Se não der para ver, toque em \"Não consigo ver\". " +
                    "Na dúvida, chute: o teste repete cada tamanho.",
                20f
            )
        )
        col.addView(Ui.button(this, "Começar") { startTest() })
        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(Ui.BG) })
    }

    private fun startTest() {
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
        val heightPx = VisionMath.SIZES_MM[stair.index] / 25.4 * Regulator.reliableDpi(resources.displayMetrics)
        optotype.show(heightPx.toFloat(), direction)
        progress.text = "${eyeName(eyes[eyeIndex])} · tamanho ${VisionMath.SIZES_MM.size - stair.index} " +
            "de ${VisionMath.SIZES_MM.size}"
    }

    private fun answer(chosen: Direction?) {
        stair.answer(chosen == direction)
        if (!stair.finished) {
            nextTrial()
            return
        }
        val eye = eyes[eyeIndex]
        val threshold = stair.thresholdMm
        if (threshold == null) couldNotSeeLargest += eye
        // Nem o maior "E" deu: conta como um passo além do maior, e o zoom compensa.
        store.setThreshold(eye, threshold ?: VisionMath.SIZES_MM.last() * 1.26)
        eyeIndex++
        if (eyeIndex < eyes.size) showIntro() else showSummary()
    }

    private fun showSummary() {
        // Se já havia um modo ligado, ele passa a usar o resultado novo.
        if (store.profile != Profile.NORMAL) Regulator.apply(this, store.profile)

        val col = Ui.column(this)
        col.addView(Ui.text(this, "Resultado", 30f, Ui.ACCENT, bold = true))
        for (eye in eyes) {
            val mm = store.threshold(eye) ?: continue
            val note = if (eye in couldNotSeeLargest) " (não viu nem o maior)" else ""
            col.addView(Ui.text(this, "${eyeName(eye)}: vê letras a partir de ${Ui.format(mm)} mm$note", 22f))
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

    companion object {
        fun eyeName(eye: EyeSource) = when (eye) {
            EyeSource.LEFT -> "Olho esquerdo"
            EyeSource.RIGHT -> "Olho direito"
            EyeSource.BOTH -> "Os dois olhos"
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
