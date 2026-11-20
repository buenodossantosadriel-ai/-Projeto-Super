package com.projetosuper.lab

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build

/**
 * Encaminha apps autorizados exclusivamente para o display virtual da sessão.
 * Não redireciona silenciosamente para a tela física caso o Android bloqueie o display.
 */
object RemoteDisplayLauncher {
    const val PREF_DISPLAY_ID = "remote_display_id"
    const val PREF_DISPLAY_WIDTH = "remote_display_width"
    const val PREF_DISPLAY_HEIGHT = "remote_display_height"

    fun start(context: Context, intent: Intent) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            "Displays secundários exigem Android 8 ou superior."
        }
        check(context.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)) {
            "Este aparelho não oferece suporte a atividades em displays secundários."
        }
        val prefs = context.getSharedPreferences("projeto_super_device", Context.MODE_PRIVATE)
        check(prefs.getBoolean("app_control_authorized", false)) {
            "A autorização geral do Projeto Super não está ativa."
        }
        val displayId = prefs.getInt(PREF_DISPLAY_ID, android.view.Display.INVALID_DISPLAY)
        check(displayId != android.view.Display.INVALID_DISPLAY) {
            "O ambiente virtual ainda não está ativo. Inicie primeiro a sessão visual."
        }
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(displayId)
            ?: error("O display virtual não está mais disponível.")
        check(display.flags and android.view.Display.FLAG_PRIVATE == 0) {
            "O display virtual está privado e não aceita aplicativos de terceiros."
        }
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "O controle remoto isolado exige Android 11 ou superior: versões anteriores não permitem direcionar gestos e validar janelas no display secundário."
        }
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        check(activityManager.isActivityStartAllowedOnDisplay(context, displayId, intent)) {
            "O Android bloqueou a abertura deste aplicativo no display virtual."
        }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        context.startActivity(intent, options.toBundle())
    }
}
