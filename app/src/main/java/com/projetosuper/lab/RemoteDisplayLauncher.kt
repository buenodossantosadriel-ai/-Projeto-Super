package com.projetosuper.lab

import android.content.Context
import android.content.Intent

/**
 * Abre o aplicativo selecionado na tela principal do aparelho.
 * A sessão visual é capturada em JPEG pelo Serviço de Acessibilidade.
 */
object RemoteDisplayLauncher {
    const val PREF_DISPLAY_ID = "remote_display_id" // Mantido para limpar instalações anteriores.
    const val PREF_DISPLAY_WIDTH = "remote_display_width"
    const val PREF_DISPLAY_HEIGHT = "remote_display_height"

    fun start(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("projeto_super_device", Context.MODE_PRIVATE)
        check(prefs.getBoolean("app_control_authorized", false)) {
            "A autorização geral do Projeto Super não está ativa."
        }
        check(intent.component != null) {
            "O aplicativo de destino não foi identificado."
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
    }
}
