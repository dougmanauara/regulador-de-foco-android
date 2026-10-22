package br.reguladordefoco

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar

/** Tela principal: permissões, teste da visão, foco geral e fino, e os modos. */
class MainActivity : Activity() {
    private lateinit var store: Store

    /** Último aviso do que não deu para fazer; sobrevive à reconstrução da tela. */
    private var problems: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
    }

    // Com o app aberto, a correção sai da frente para não cobrir os ajustes.
    override fun onStart() {
        super.onStart()
        InApp.start(store)
    }

    override fun onStop() {
        InApp.stop(store)
        super.onStop()
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
                "Um \"óculos\" para a tela: o teste mede o embaçado de cada olho e o app corrige o " +
                    "foco da tela inteira, em qualquer app, no grau certo para você.",
                20f, Ui.MUTED
            )
        )

        addPermissions(col)
        addTest(col)
        addFocus(col)
        addProfiles(col)
        addExtras(col)
        if (problems.isNotEmpty()) col.addView(Ui.text(this, problems.joinToString("\n"), 20f, Ui.WARN))
        col.addView(
            Ui.text(
                this,
                "O app não substitui a consulta nem os óculos. A correção é feita na imagem da tela, " +
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
        val needsWrite = store.biggerText && !Settings.System.canWrite(this)
        val serviceOn = Regulator.isServiceOn(this)
        if (!needsWrite && serviceOn) return
        section(col, "Liberar o app")
        if (!serviceOn) {
            col.addView(Ui.text(this, "✗ Correção de foco e filtro da noite na tela inteira", 20f))
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
        if (needsWrite) {
            col.addView(Ui.text(this, "✗ Mudar o tamanho das letras do celular", 20f))
            col.addView(Ui.button(this, "Permitir mudar as letras") {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                )
            })
        }
    }

    private fun addTest(col: LinearLayout) {
        section(col, "Teste da visão")
        val tested = EyeSource.entries.mapNotNull { eye -> store.threshold(eye)?.let { eye to it } }
        if (tested.isEmpty()) {
            col.addView(
                Ui.text(
                    this,
                    "Leva uns 5 minutos, como numa consulta: a letra E com cada olho, e depois o " +
                        "\"melhor o 1 ou o 2?\" com o foco, para achar o grau da correção.",
                    20f
                )
            )
            col.addView(Ui.button(this, "Fazer o teste") { openTest() })
            return
        }
        for ((eye, mm) in tested) {
            col.addView(
                Ui.text(this, "${VisionTestActivity.eyeName(eye)}: letras a partir de ${Ui.format(mm)} mm", 20f)
            )
        }
        col.addView(Ui.text(this, "A tela é uma só, então escolha por qual olho ajustar:", 20f))
        for (eye in EyeSource.entries) {
            val label = if (eye == EyeSource.BOTH) "Os dois olhos (recomendado)" else VisionTestActivity.eyeName(eye)
            col.addView(Ui.button(this, label, filled = store.eyeSource == eye) {
                store.eyeSource = eye
                reapply()
            })
        }
        col.addView(Ui.button(this, "Refazer o teste", filled = false) { openTest() })
    }

    /** Foco geral e foco fino, com um texto de exemplo que mostra a correção ao vivo. */
    private fun addFocus(col: LinearLayout) {
        val threshold = store.chosenThreshold() ?: return
        section(col, "Foco")
        if (!FocusShader.available) {
            col.addView(Ui.text(this, "A correção de foco precisa do Android 13 ou mais novo.", 20f, Ui.WARN))
            return
        }
        col.addView(Ui.button(this, if (store.focusEnabled) "Correção de foco: LIGADA" else "Correção de foco: desligada",
            filled = store.focusEnabled) {
            store.focusEnabled = !store.focusEnabled
            reapply()
        })
        if (!store.focusEnabled) return

        val preview = CorrectedImageView(this).apply {
            bitmap = VisionTestActivity.sampleBitmap(
                this@MainActivity,
                "O sol entrava pela janela da cozinha enquanto o café passava. Lá fora, as crianças corriam.",
                threshold
            )
        }
        col.addView(preview)
        var radius = store.focusRadius
        var contrast = store.focusContrast
        preview.setFocus(radius, contrast)

        val generalLabel = Ui.text(this, "", 20f)
        col.addView(generalLabel)
        col.addView(slider(VisionTestActivity.focusLevel(radius), { level ->
            radius = VisionMath.logScale(level, 100, VisionMath.FOCUS_MIN_PX, VisionMath.FOCUS_MAX_PX)
            generalLabel.text = "Foco geral (o grau): $level"
            preview.setFocus(radius, contrast)
        }) { store.focusRadius = radius })

        val fineLabel = Ui.text(this, "", 20f)
        col.addView(fineLabel)
        col.addView(slider(VisionMath.fineLevel(contrast), { level ->
            contrast = VisionMath.fineK(level)
            fineLabel.text = "Foco fino (a intensidade): $level"
            preview.setFocus(radius, contrast)
        }) { store.focusContrast = contrast })

        col.addView(
            Ui.text(
                this,
                "Segure o celular na distância de sempre e ajuste até o texto ficar o mais nítido " +
                    "possível. O foco fino troca contraste por nitidez: mais forte deixa o fundo mais " +
                    "cinza. Nos outros apps, a correção aparece quando a tela para: ao rolar você vê " +
                    "a tela normal e, meio segundo depois, a corrigida.",
                17f, Ui.MUTED
            )
        )
    }

    /** Barra de 0 a 100: [onChange] ao arrastar (prévia ao vivo), [onDone] ao soltar (salva). */
    private fun slider(level: Int, onChange: (Int) -> Unit, onDone: () -> Unit) = SeekBar(this).apply {
        max = 100
        progress = level.coerceIn(0, 100)
        minimumHeight = Ui.dp(context, 48f)
        onChange(progress)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) = onChange(p)

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) = onDone()
        })
    }

    private fun addProfiles(col: LinearLayout) {
        if (store.chosenThreshold() == null) return
        section(col, "Usar")
        val current = store.profile
        val profiles = listOf(
            Profile.NORMAL to "Normal (desligado)",
            Profile.NO_GLASSES to "Sem óculos",
            Profile.NIGHT to "Antes de dormir (com filtro)",
        )
        for ((profile, label) in profiles) {
            col.addView(Ui.button(this, label, filled = current == profile) {
                show(Regulator.apply(this, profile))
                build()
            })
        }
        col.addView(
            Ui.text(
                this,
                "Atalho: puxe a barra de notificações, toque no lápis e arraste \"Óculos de tela\" " +
                    "para os botões rápidos. Um toque liga, outro desliga.",
                17f, Ui.MUTED
            )
        )
    }

    private fun addExtras(col: LinearLayout) {
        if (store.chosenThreshold() == null) return
        section(col, "Extra")
        col.addView(Ui.button(this, if (store.biggerText) "Letras maiores também: SIM" else "Letras maiores também: não",
            filled = store.biggerText) {
            store.biggerText = !store.biggerText
            reapply()
        })
        Regulator.fontScale(this, store, Profile.NO_GLASSES)?.let {
            col.addView(Ui.text(this, "Letras do sistema em ${Ui.percent(it)} nos modos ligados.", 17f, Ui.MUTED))
        }
    }

    /** Mudou o olho ou uma opção: o modo ligado passa a usar o valor novo. */
    private fun reapply() {
        show(if (store.profile != Profile.NORMAL) Regulator.apply(this, store.profile) else emptyList())
        build()
    }

    private fun show(problems: List<String>) {
        this.problems = problems
    }

    private fun openTest() = startActivity(Intent(this, VisionTestActivity::class.java))
}
