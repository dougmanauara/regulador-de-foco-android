package br.reguladordefoco

/**
 * Regras do "Celular mais leve", sem nada de Android, para testar no PC.
 *
 * O Android não deixa um app desativar outro: o app só encontra o que pesa e abre a tela do
 * sistema onde a pessoa toca em "Desativar" ou "Desinstalar".
 */
object Cleanup {
    /** Um app de fábrica que dá para desativar sem quebrar o celular, e o porquê. */
    data class Bloat(val pkg: String, val name: String, val why: String)

    /**
     * Lista fechada e conservadora: só o que é seguro desativar. Fica de fora tudo o que o
     * sistema usa (teclado, telefone, mensagens, loja, atualizações, contas).
     */
    val BLOAT: List<Bloat> = listOf(
        Bloat("com.samsung.android.bixby.agent", "Bixby Voice", "assistente de voz que fica escutando"),
        Bloat("com.samsung.android.app.spage", "Samsung Free", "tela de notícias à esquerda da inicial"),
        Bloat("com.samsung.android.visionintelligence", "Bixby Vision", "reconhecimento pela câmera"),
        Bloat("com.samsung.android.arzone", "AR Zone", "efeitos de realidade aumentada"),
        Bloat("com.samsung.android.aremoji", "AR Emoji", "figurinhas de avatar"),
        Bloat("com.samsung.sree", "Samsung Global Goals", "campanhas e anúncios"),
        Bloat("com.samsung.android.tvplus", "Samsung TV Plus", "canais de TV com anúncios"),
        Bloat("com.samsung.android.app.tips", "Dicas", "notificações de dicas do celular"),
        Bloat("com.samsung.android.kidsinstaller", "Samsung Kids", "modo infantil"),
        Bloat("com.samsung.android.game.gamehome", "Game Launcher", "só serve para quem joga no celular"),
        Bloat("com.facebook.appmanager", "Facebook App Manager", "atualiza o Facebook em segundo plano"),
        Bloat("com.facebook.services", "Facebook Services", "serviço de fundo do Facebook"),
        Bloat("com.facebook.system", "Facebook App Installer", "instala apps da Meta sozinho"),
        Bloat("com.netflix.partner.activation", "Netflix (ativação)", "serviço de fundo da parceria com a Netflix"),
        Bloat("com.microsoft.skydrive", "OneDrive", "nuvem da Microsoft que vem instalada"),
        Bloat("com.linkedin.android", "LinkedIn", "vem instalado de fábrica"),
    )

    private val BLOAT_BY_PKG = BLOAT.associateBy { it.pkg }

    /** O que se sabe de um app instalado. [lastUsedMs] = 0 quando nunca foi usado. */
    data class App(
        val pkg: String,
        val label: String,
        val preinstalled: Boolean,
        val enabled: Boolean,
        val installedMs: Long,
        val lastUsedMs: Long,
        val sizeBytes: Long,
    )

    /** Apps de fábrica da lista que estão instalados e ainda ligados. */
    fun bloatFound(apps: List<App>): List<Pair<App, Bloat>> =
        apps.filter { it.enabled }
            .mapNotNull { app -> BLOAT_BY_PKG[app.pkg]?.let { app to it } }
            .sortedBy { it.second.name }

    const val UNUSED_DAYS = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Apps ligados sem uso há [UNUSED_DAYS] dias ou mais (e instalados há pelo menos isso), do
     * maior para o menor. Os da lista de fábrica já aparecem lá; os de fábrica fora da lista
     * ficam de fora, porque podem ser do sistema.
     */
    fun unused(apps: List<App>, nowMs: Long, ownPkg: String): List<App> =
        apps.filter {
            it.enabled && !it.preinstalled && it.pkg != ownPkg && it.pkg !in BLOAT_BY_PKG &&
                nowMs - it.installedMs >= UNUSED_DAYS * DAY_MS &&
                nowMs - it.lastUsedMs >= UNUSED_DAYS * DAY_MS
        }.sortedByDescending { it.sizeBytes }

    /** "nunca usado", "há 45 dias". */
    fun lastUsedText(lastUsedMs: Long, nowMs: Long): String =
        if (lastUsedMs <= 0L) "nunca usado" else "usado há ${(nowMs - lastUsedMs) / DAY_MS} dias"

    /** 1,2 GB / 340 MB. */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f GB", bytes / 1e9)
        else -> "${bytes / 1_000_000L} MB"
    }

    /** Abaixo de 15% livre o armazenamento começa a deixar o celular mais lento. */
    fun storageLow(freeBytes: Long, totalBytes: Long): Boolean = freeBytes < totalBytes * 0.15

    /** Animações acima de 0,5× só deixam a troca de telas mais lenta. */
    fun animationsSlow(scale: Float): Boolean = scale > 0.5f + 1e-3f

    // ---- Comandos para fazer direto no celular (pelo Shizuku) ----
    // Cada comando é uma lista de argumentos, sem passar por um interpretador: nada é "colado"
    // num texto de comando, então um nome de app não tem como virar outro comando.

    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    private fun checkPkg(pkg: String): String {
        require(PKG.matches(pkg)) { "nome de app inválido: $pkg" }
        return pkg
    }

    /** Desativa para o usuário: o app some e para de rodar, mas continua no celular. */
    fun disableCommand(pkg: String) = listOf("pm", "disable-user", "--user", "0", checkPkg(pkg))

    /** Desfaz o [disableCommand]. */
    fun enableCommand(pkg: String) = listOf("pm", "enable", "--user", "0", checkPkg(pkg))

    val ANIMATION_KEYS = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
    const val FAST_ANIMATION = "0.5"
    const val NORMAL_ANIMATION = "1.0"

    fun animationCommands(scale: String) = ANIMATION_KEYS.map { listOf("settings", "put", "global", it, scale) }
}
