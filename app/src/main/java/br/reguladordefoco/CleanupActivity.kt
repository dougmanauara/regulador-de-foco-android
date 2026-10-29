package br.reguladordefoco

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.concurrent.Executors

/**
 * "Celular mais leve": acha apps de fábrica dispensáveis, apps parados e ajustes que pesam na
 * fluidez, e abre a tela do Android certa para cada um. O Android não deixa um app desativar
 * outro, então o toque final em "Desativar" ou "Desinstalar" é da pessoa.
 */
class CleanupActivity : Activity() {
    private lateinit var store: Store
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
    }

    // Com o app aberto, a correção de foco sai da frente, como nas outras telas.
    override fun onStart() {
        super.onStart()
        InApp.start(store)
    }

    override fun onStop() {
        InApp.stop(store)
        super.onStop()
    }

    // Reconstrói ao voltar das telas do Android: o app pode ter sido desativado lá.
    override fun onResume() {
        super.onResume()
        val col = Ui.column(this)
        col.addView(Ui.text(this, "Celular mais leve", 30f, Ui.ACCENT, bold = true))
        col.addView(Ui.text(this, "Procurando apps e ajustes…", 20f, Ui.MUTED))
        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(Ui.BG) })
        worker.execute {
            val apps = scanApps()
            main.post { if (!isDestroyed) build(apps) }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun build(apps: List<Cleanup.App>) {
        val col = Ui.column(this)
        col.addView(Ui.text(this, "Celular mais leve", 30f, Ui.ACCENT, bold = true))
        col.addView(
            Ui.text(
                this,
                "O Android não deixa um app desativar outro. Aqui você vê o que pesa e, com um toque, " +
                    "abre a tela certa do Android para tocar em \"Desativar\" ou \"Desinstalar\". " +
                    "Desativar dá para desfazer na mesma tela.",
                18f, Ui.MUTED
            )
        )
        addFluidity(col)
        addBloat(col, apps)
        addUnused(col, apps)
        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(Ui.BG) })
    }

    private fun section(col: LinearLayout, title: String) {
        col.addView(Ui.text(this, title, 24f, Ui.ACCENT, bold = true).apply {
            setPadding(0, Ui.dp(context, 24f), 0, Ui.dp(context, 4f))
        })
    }

    // ---- Ajustes de fluidez ----

    private fun addFluidity(col: LinearLayout) {
        section(col, "Fluidez")
        var allGood = true

        if (getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) {
            allGood = false
            col.addView(Ui.text(this, "✗ Economia de energia ligada: ela deixa o processador mais lento.", 20f))
            col.addView(Ui.button(this, "Abrir economia de energia") {
                open(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            })
        }

        val stat = StatFs(Environment.getDataDirectory().path)
        if (Cleanup.storageLow(stat.availableBytes, stat.totalBytes)) {
            allGood = false
            col.addView(
                Ui.text(
                    this,
                    "✗ Pouco espaço livre: ${Cleanup.sizeText(stat.availableBytes)} de " +
                        "${Cleanup.sizeText(stat.totalBytes)}. Com menos de 15% livre o celular fica lento.",
                    20f
                )
            )
            col.addView(Ui.button(this, "Abrir armazenamento") {
                open(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            })
        }

        val scales = listOf(
            Settings.Global.WINDOW_ANIMATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.ANIMATOR_DURATION_SCALE,
        ).map { Settings.Global.getFloat(contentResolver, it, 1f) }
        if (scales.any { Cleanup.animationsSlow(it) }) {
            allGood = false
            val devOn = Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
            col.addView(
                Ui.text(
                    this,
                    "✗ Animações em velocidade normal. Em 0,5× a troca de telas fica bem mais rápida.",
                    20f
                )
            )
            col.addView(
                Ui.text(
                    this,
                    (if (devOn) "" else "Primeiro libere as Opções do desenvolvedor: em Sobre o telefone → " +
                        "Informações do software, toque 7 vezes em \"Número de compilação\".\n") +
                        "Depois, em Opções do desenvolvedor, mude para 0,5×: \"Escala de animação da janela\", " +
                        "\"Escala de animação de transição\" e \"Escala de duração do Animator\".",
                    17f, Ui.MUTED
                )
            )
            col.addView(Ui.button(this, if (devOn) "Abrir Opções do desenvolvedor" else "Abrir Sobre o telefone") {
                open(Intent(if (devOn) Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS else Settings.ACTION_DEVICE_INFO_SETTINGS))
            })
        }

        // A taxa de atualização não dá para ler em todos os celulares: fica como dica.
        col.addView(
            Ui.text(
                this,
                "Tela: em Tela → \"Suavidade de movimento\", deixe \"Adaptável\" (120 Hz). Rolar fica mais " +
                    "fluido; a bateria dura um pouco menos.",
                17f, Ui.MUTED
            )
        )
        col.addView(Ui.button(this, "Abrir Tela", filled = false) { open(Intent(Settings.ACTION_DISPLAY_SETTINGS)) })
        if (allGood) col.addView(Ui.text(this, "✓ Economia de energia, espaço livre e animações estão bons.", 20f))
    }

    // ---- Apps de fábrica ----

    private fun addBloat(col: LinearLayout, apps: List<Cleanup.App>) {
        section(col, "Apps de fábrica dispensáveis")
        val found = Cleanup.bloatFound(apps)
        if (found.isEmpty()) {
            col.addView(Ui.text(this, "✓ Nenhum dos apps de fábrica da lista está ligado.", 20f))
            return
        }
        col.addView(
            Ui.text(
                this,
                "Seguros de desativar: o celular continua funcionando. Se o botão \"Desativar\" estiver " +
                    "cinza, o fabricante não deixa.",
                17f, Ui.MUTED
            )
        )
        for ((app, bloat) in found) {
            col.addView(Ui.text(this, "${bloat.name}: ${bloat.why}", 20f, bold = true))
            col.addView(Ui.button(this, "Abrir para desativar", filled = false) { openApp(app.pkg) })
        }
    }

    // ---- Apps parados ----

    private fun addUnused(col: LinearLayout, apps: List<Cleanup.App>) {
        section(col, "Apps parados há ${Cleanup.UNUSED_DAYS} dias ou mais")
        if (!hasUsageAccess()) {
            col.addView(
                Ui.text(
                    this,
                    "Para saber quais apps você não usa, o Android pede uma permissão: \"Acesso aos dados " +
                        "de uso\". Na tela que abrir, escolha \"Regulador de Foco\" e ligue.",
                    20f
                )
            )
            col.addView(Ui.button(this, "Permitir acesso aos dados de uso") {
                open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            })
            return
        }
        val unused = Cleanup.unused(apps, System.currentTimeMillis(), packageName)
        if (unused.isEmpty()) {
            col.addView(Ui.text(this, "✓ Você usa todos os apps que instalou.", 20f))
            return
        }
        val total = unused.sumOf { it.sizeBytes }
        col.addView(
            Ui.text(this, "${unused.size} apps, ocupando ${Cleanup.sizeText(total)} no total.", 17f, Ui.MUTED)
        )
        val now = System.currentTimeMillis()
        for (app in unused) {
            col.addView(
                Ui.text(
                    this,
                    "${app.label}: ${Cleanup.sizeText(app.sizeBytes)}, ${Cleanup.lastUsedText(app.lastUsedMs, now)}",
                    20f, bold = true
                )
            )
            col.addView(Ui.button(this, "Abrir para desinstalar", filled = false) { openApp(app.pkg) })
        }
    }

    // ---- Leitura dos apps (fora da linha principal) ----

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    private fun scanApps(): List<Cleanup.App> {
        val now = System.currentTimeMillis()
        val canReadUsage = hasUsageAccess()
        val lastUsed: Map<String, Long> = if (canReadUsage) {
            getSystemService(UsageStatsManager::class.java)
                ?.queryAndAggregateUsageStats(now - 365L * 24 * 60 * 60 * 1000, now)
                ?.mapValues { it.value.lastTimeUsed }
                .orEmpty()
        } else {
            emptyMap()
        }
        val storage = getSystemService(StorageStatsManager::class.java)
        val pm = packageManager
        return pm.getInstalledApplications(0).map { info ->
            val size = if (canReadUsage && storage != null) {
                try {
                    val s = storage.queryStatsForPackage(StorageManager.UUID_DEFAULT, info.packageName, Process.myUserHandle())
                    s.appBytes + s.dataBytes + s.cacheBytes
                } catch (e: Exception) {
                    0L
                }
            } else {
                0L
            }
            val installed = try {
                pm.getPackageInfo(info.packageName, 0).firstInstallTime
            } catch (e: Exception) {
                now
            }
            Cleanup.App(
                pkg = info.packageName,
                label = info.loadLabel(pm).toString(),
                preinstalled = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                enabled = info.enabled,
                installedMs = installed,
                lastUsedMs = lastUsed[info.packageName] ?: 0L,
                sizeBytes = size,
            )
        }
    }

    private fun openApp(pkg: String) =
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    /** Alguns fabricantes não têm todas as telas: cai nas Configurações gerais. */
    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
