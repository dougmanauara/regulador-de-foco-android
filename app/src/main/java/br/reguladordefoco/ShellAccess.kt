package br.reguladordefoco

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Fazer direto no celular, pelo Shizuku: ele dá ao app a mesma permissão de quem liga o celular
 * num computador pelo cabo (o "shell"), só que pela Depuração sem fio do próprio celular.
 * Com isso o app desativa apps de fábrica e muda as animações sem passar pelas telas do Android.
 */
object ShellAccess {
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 7

    enum class State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    fun state(context: Context): State {
        val installed = try {
            context.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        return when {
            !installed -> State.NOT_INSTALLED
            !Shizuku.pingBinder() || Shizuku.isPreV11() -> State.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NO_PERMISSION
            else -> State.READY
        }
    }

    /** Pede a permissão; o Shizuku mostra a pergunta e [onResult] recebe a resposta. */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQUEST_CODE)
    }

    data class Result(val ok: Boolean, val output: String)

    /**
     * Roda um comando como o shell. Bloqueia: chamar fora da linha principal.
     * A API pública do Shizuku esconde o newProcess; apps conhecidos o chamam assim.
     */
    fun run(command: List<String>): Result = try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(null, command.toTypedArray(), null, null) as Process
        val output = process.inputStream.bufferedReader().readText() + process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        Result(code == 0 && !output.contains("Exception") && !output.startsWith("Error"), output.trim())
    } catch (e: Exception) {
        Result(false, e.cause?.message ?: e.message ?: e.toString())
    }
}
