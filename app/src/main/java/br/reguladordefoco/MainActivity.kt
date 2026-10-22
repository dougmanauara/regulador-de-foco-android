package br.reguladordefoco

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar

/** Tela principal: permissões, teste da visão, modos e ajuste fino. */
class MainActivity : Activity() {
    private lateinit var store: Store
    /** Último aviso do que não deu para fazer; sobrevive à reconstrução da tela. */
    private var problems: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
    }

    // Reconstrói ao voltar das telas do Android, que é onde as permissões mudam.
    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        val col = Ui.column(this)
        col.addView(Ui.text(this, getString(R.string.app_name), 32f, Ui.ACCENT, bold = true))
        col.addView(
            Ui.text(
                this,
                "Um \"óculos\" para a tela: o teste mede como cada olho enxerga e o app aumenta " +
                    "letras e tela inteira, em qualquer app, na medida certa para você.",
                20f, Ui.MUTED
            )
        )
        addPermissions(col)
        addTest(col)
        addProfiles(col)
        if (problems.isNotEmpty()) col.addView(Ui.text(this, problems.joinToString("\n"), 20f, Ui.WARN))
        col.addView(
            Ui.text(
                this,
                "O app não corrige a visão nem substitui a consulta. Ele só ajusta a tela " +
                    "para você forçar menos a vista.",
                16f, Ui.MUTED
            )
        )
        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(Ui.BG) })
    }

    private fun section(col: LinearLayout, title: String) {
        col.addView(Ui.text(this, title, 24f, Ui.ACCENT, bold = true).apply {
            setPadding(0, Ui.dp(context, 24f), 0, Ui.dp(context, 4f))
        })
    }

    private fun addPermissions(col: LinearLayout) {
        val canWrite = Settings.System.canWrite(this)
        val serviceOn = Regulator.isServiceOn(this)
        if (canWrite && serviceOn) return
        section(col, "1. Liberar o app")
        if (!canWrite) {
            col.addView(Ui.text(this, "✗ Mudar o tamanho das letras do celular", 20f))
            col.addView(Ui.button(this, "Permitir mudar as letras") {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                )
            })
        }
        if (!serviceOn) {
            col.addView(Ui.text(this, "✗ Zoom na tela inteira e filtro da noite", 20f))
            col.addView(
                Ui.text(
                    this,
                    "Na tela que abrir, procure \"Apps instalados\" → \"Regulador de Foco\" e ligue.\n" +
                        "Se o Android disser que é uma configuração restrita: abra \"Informações do app\" " +
                        "abaixo, toque nos 3 pontinhos (⋮) no canto e em \"Permitir configurações " +
                        "restritas\". Depois tente de novo.",
                    18f, Ui.MUTED
                )
            )
            col.addView(Ui.button(this, "Abrir Acessibilidade") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            col.addView(Ui.button(this, "Informações do app", filled = false) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            })
        }
    }

    private fun addTest(col: LinearLayout) {
        section(col, "2. Teste da visão")
        val tested = EyeSource.entries.mapNotNull { eye -> store.threshold(eye)?.let { eye to it } }
        if (tested.isEmpty()) {
            col.addView(
                Ui.text(this, "Leva uns 5 minutos, como numa consulta: tamanho (um olho de cada vez e " +
                    "depois os dois), contraste, \"melhor o 1 ou o 2?\" e a luz para antes de dormir.", 20f)
            )
            col.addView(Ui.button(this, "Fazer o teste") { openTest() })
            return
        }
        for ((eye, mm) in tested) {
            col.addView(
                Ui.text(this, "${VisionTestActivity.eyeName(eye)}: letras a partir de ${Ui.format(mm)} mm", 20f)
            )
        }
        col.addView(Ui.text(this, "A tela é uma só, então escolha por qual ajustar:", 20f))
        for (eye in EyeSource.entries) {
            val label = if (eye == EyeSource.BOTH) "Os dois olhos (recomendado)" else VisionTestActivity.eyeName(eye)
            col.addView(Ui.button(this, label, filled = store.eyeSource == eye) {
                store.eyeSource = eye
                reapply()
            })
        }
        col.addView(Ui.button(this, "Refazer o teste", filled = false) { openTest() })
    }

    private fun addProfiles(col: LinearLayout) {
        if (store.chosenThreshold() == null) return
        section(col, "3. Usar")
        val current = store.profile
        val profiles = listOf(
            Profile.NORMAL to "Normal (desligado)",
            Profile.NO_GLASSES to "Sem óculos",
            Profile.NIGHT to "Antes de dormir",
        )
        for ((profile, label) in profiles) {
            col.addView(Ui.button(this, label, filled = current == profile) {
                show(Regulator.apply(this, profile))
                build()
            })
            Regulator.setting(this, store, profile)?.let {
                col.addView(Ui.text(this, VisionTestActivity.describe(it), 17f, Ui.MUTED))
            }
        }

        col.addView(Ui.text(this, "", 8f))
        val fineLabel = Ui.text(this, fineText(store.fineTune), 20f)
        col.addView(fineLabel)
        col.addView(SeekBar(this).apply {
            max = FINE_STEPS
            progress = Math.round(((store.fineTune - FINE_MIN) / FINE_STEP)).toInt().coerceIn(0, FINE_STEPS)
            minimumHeight = Ui.dp(context, 48f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                    fineLabel.text = fineText(FINE_MIN + p * FINE_STEP)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}

                override fun onStopTrackingTouch(bar: SeekBar) {
                    store.fineTune = FINE_MIN + bar.progress * FINE_STEP
                    reapply()
                }
            })
        })
        col.addView(
            Ui.text(
                this,
                "Atalho: puxe a barra de notificações, toque no lápis e arraste \"Óculos de tela\" " +
                    "para os botões rápidos. Um toque liga, outro desliga.\n\n" +
                    "Para mover a tela ampliada, arraste com dois dedos. Se não mover, ligue também o " +
                    "atalho de Ampliação do Android (Acessibilidade → Melhorias de visibilidade → Ampliação).",
                17f, Ui.MUTED
            )
        )
    }

    private fun fineText(v: Double) = "Ajuste fino: ${Ui.percent(v)} do recomendado"

    /** Mudou o olho ou o ajuste fino: o modo ligado passa a usar o valor novo. */
    private fun reapply() {
        show(if (store.profile != Profile.NORMAL) Regulator.apply(this, store.profile) else emptyList())
        build()
    }

    private fun show(problems: List<String>) {
        this.problems = problems
    }

    private fun openTest() = startActivity(Intent(this, VisionTestActivity::class.java))

    companion object {
        private const val FINE_MIN = VisionMath.FINE_MIN
        private const val FINE_STEP = VisionMath.FINE_STEP
        private val FINE_STEPS = Math.round((VisionMath.FINE_MAX - FINE_MIN) / FINE_STEP).toInt()
    }
}
