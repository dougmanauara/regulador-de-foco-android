package br.reguladordefoco

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
 * fluidez. Com o Shizuku conectado ([ShellAccess]), desativa e ajusta direto no celular; sem
 * ele, abre a tela do Android certa para a pessoa tocar em "Desativar".
 */
class CleanupActivity : Activity() {
    private lateinit var store: Store
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Resultado da última ação, mostrado no topo depois de recarregar. */
    private var message: String? = null

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

    // Recarrega ao voltar das telas do Android: algo pode ter mudado lá.
    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun reload() {
        val col = Ui.column(this)
        col.addView(Ui.text(this, "Celular mais leve", 30f, Ui.ACCENT, bold = true))
        col.addView(Ui.text(this, "Procurando apps e ajustes…", 20f, Ui.MUTED))
        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(Ui.BG) })
        worker.execute {
            val apps = scanApps()
            main.post { if (!isDestroyed) build(apps) }
        }
    }

    private fun build(apps: List<Cleanup.App>) {
        val direct = ShellAccess.state(this) == ShellAccess.State.READY
        val col = Ui.column(this)
        col.addView(Ui.text(this, "Celular mais leve", 30f, Ui.ACCENT, bold = true))
        message?.let { col.addView(Ui.text(this, it, 20f, Ui.WARN)) }
        message = null
        addDirect(col)
        addFluidity(col, direct)
        addBloat(col, apps, direct)
        addUnused(col, apps)
        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(Ui.BG) })
    }

    private fun section(col: LinearLayout, title: String) {
        col.addView(Ui.text(this, title, 24f, Ui.ACCENT, bold = true).apply {
            setPadding(0, Ui.dp(context, 24f), 0, Ui.dp(context, 4f))
        })
    }

    /** Roda os comandos fora da linha principal e recarrega a tela com o resultado. */
    private fun runCommands(commands: List<List<String>>, done: String, after: (Boolean) -> Unit = {}) {
        worker.execute {
            val failures = commands.map { ShellAccess.run(it) }.filter { !it.ok }
            main.post {
                if (isDestroyed) return@post
                after(failures.isEmpty())
                message = if (failures.isEmpty()) "✓ $done" else
                    "Não deu certo: ${failures.first().output.ifBlank { "o Shizuku não respondeu" }}"
                reload()
            }
        }
    }

    // ---- Fazer direto (Shizuku) ----

    private fun addDirect(col: LinearLayout) {
        section(col, "Fazer direto no celular")
        when (ShellAccess.state(this)) {
            ShellAccess.State.READY -> {
                col.addView(
                    Ui.text(this, "✓ Conectado. Os botões abaixo agem direto no celular, e tudo pode ser desfeito.", 20f)
                )
                return
            }
            ShellAccess.State.NO_PERMISSION -> {
                col.addView(Ui.text(this, "O Shizuku está rodando. Falta permitir o Regulador de Foco.", 20f))
                col.addView(Ui.button(this, "Conectar ao Shizuku") {
                    ShellAccess.requestPermission { main.post { if (!isDestroyed) reload() } }
                })
                return
            }
            ShellAccess.State.NOT_INSTALLED -> {
                col.addView(
                    Ui.text(
                        this,
                        "Para o app desativar e ajustar sem você passar pelas telas do Android, ele precisa " +
                            "do Shizuku, um app gratuito que dá a permissão de quem conecta o celular num " +
                            "computador, mas pelo próprio celular. Sem ele, os botões abrem a tela certa e " +
                            "você toca em \"Desativar\".",
                        18f, Ui.MUTED
                    )
                )
                col.addView(Ui.button(this, "Instalar o Shizuku") {
                    open(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShellAccess.SHIZUKU_PKG}")))
                })
            }
            ShellAccess.State.NOT_RUNNING -> {
                col.addView(Ui.text(this, "O Shizuku está instalado, mas não está rodando.", 20f))
            }
        }
        col.addView(
            Ui.text(
                this,
                "Como ligar o Shizuku (uma vez; depois de reiniciar o celular, só o passo 4):\n" +
                    "1. Esteja num Wi-Fi.\n" +
                    "2. Libere as Opções do desenvolvedor: Sobre o telefone → Informações do software → " +
                    "toque 7 vezes em \"Número de compilação\".\n" +
                    "3. Abra o Shizuku → \"Parear\" e siga: em Opções do desenvolvedor → \"Depuração sem fio\" → " +
                    "\"Parear dispositivo com código\", digite o código na notificação do Shizuku.\n" +
                    "4. No Shizuku, toque em \"Iniciar\". Volte aqui e toque em \"Conectar\".",
                17f, Ui.MUTED
            )
        )
        col.addView(Ui.button(this, "Abrir o Shizuku", filled = false) {
            packageManager.getLaunchIntentForPackage(ShellAccess.SHIZUKU_PKG)?.let { open(it) }
        })
    }

    // ---- Ajustes de fluidez ----

    private fun addFluidity(col: LinearLayout, direct: Boolean) {
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

        val slow = listOf(
            Settings.Global.WINDOW_ANIMATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.ANIMATOR_DURATION_SCALE,
        ).any { Cleanup.animationsSlow(Settings.Global.getFloat(contentResolver, it, 1f)) }
        if (slow) {
            allGood = false
            col.addView(
                Ui.text(this, "✗ Animações em velocidade normal. Em 0,5× a troca de telas fica bem mais rápida.", 20f)
            )
            if (direct) {
                col.addView(Ui.button(this, "Acelerar animações (0,5×)") {
                    runCommands(Cleanup.animationCommands(Cleanup.FAST_ANIMATION), "Animações em 0,5×.")
                })
            } else {
                val devOn = Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
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
        } else if (direct) {
            col.addView(Ui.button(this, "Voltar animações ao normal (1×)", filled = false) {
                runCommands(Cleanup.animationCommands(Cleanup.NORMAL_ANIMATION), "Animações de volta ao normal.")
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

    private fun addBloat(col: LinearLayout, apps: List<Cleanup.App>, direct: Boolean) {
        section(col, "Apps de fábrica dispensáveis")
        val found = Cleanup.bloatFound(apps)
        val undo = store.disabledByUs.filter { pkg -> apps.any { it.pkg == pkg && !it.enabled } }
        if (found.isEmpty()) {
            col.addView(Ui.text(this, "✓ Nenhum dos apps de fábrica da lista está ligado.", 20f))
        } else {
            col.addView(
                Ui.text(
                    this,
                    "Seguros de desativar: o celular continua funcionando e dá para reativar depois." +
                        if (direct) "" else " Se o botão \"Desativar\" estiver cinza, o fabricante não deixa.",
                    17f, Ui.MUTED
                )
            )
            if (direct && found.size > 1) {
                col.addView(Ui.button(this, "Desativar todos os ${found.size}") { confirmDisable(found.map { it.first.pkg }) })
            }
            for ((app, bloat) in found) {
                col.addView(Ui.text(this, "${bloat.name}: ${bloat.why}", 20f, bold = true))
                if (direct) {
                    col.addView(Ui.button(this, "Desativar", filled = false) { disable(listOf(app.pkg)) })
                } else {
                    col.addView(Ui.button(this, "Abrir para desativar", filled = false) { openApp(app.pkg) })
                }
            }
        }
        if (direct && undo.isNotEmpty()) {
            col.addView(Ui.button(this, "Reativar os ${undo.size} que o app desativou", filled = false) {
                runCommands(undo.map { Cleanup.enableCommand(it) }, "Apps reativados.") { ok ->
                    if (ok) store.disabledByUs = store.disabledByUs - undo.toSet()
                }
            })
        }
    }

    private fun confirmDisable(pkgs: List<String>) {
        val names = pkgs.mapNotNull { pkg -> Cleanup.BLOAT.firstOrNull { it.pkg == pkg }?.name }
        AlertDialog.Builder(this)
            .setTitle("Desativar ${pkgs.size} apps?")
            .setMessage(names.joinToString("\n") + "\n\nDá para reativar depois, aqui mesmo.")
            .setPositiveButton("Desativar") { _, _ -> disable(pkgs) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun disable(pkgs: List<String>) {
        // Anota antes: se algum der certo e outro não, o desfazer ainda conhece todos.
        store.disabledByUs = store.disabledByUs + pkgs
        runCommands(pkgs.map { Cleanup.disableCommand(it) }, "Desativado. Se sentir falta, toque em \"Reativar\".")
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
        val now = System.currentTimeMillis()
        val unused = Cleanup.unused(apps, now, packageName)
        if (unused.isEmpty()) {
            col.addView(Ui.text(this, "✓ Você usa todos os apps que instalou.", 20f))
            return
        }
        col.addView(
            Ui.text(
                this,
                "${unused.size} apps, ocupando ${Cleanup.sizeText(unused.sumOf { it.sizeBytes })} no total. " +
                    "Desinstalar apaga os dados do app; o Android pede confirmação.",
                17f, Ui.MUTED
            )
        )
        for (app in unused) {
            col.addView(
                Ui.text(
                    this,
                    "${app.label}: ${Cleanup.sizeText(app.sizeBytes)}, ${Cleanup.lastUsedText(app.lastUsedMs, now)}",
                    20f, bold = true
                )
            )
            col.addView(Ui.button(this, "Desinstalar", filled = false) {
                open(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
            })
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
        // Inclui os desativados, para o "Reativar" saber o estado deles.
        return pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS).map { info ->
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
