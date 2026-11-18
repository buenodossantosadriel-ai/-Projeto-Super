package com.projetosuper.lab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Recebe somente sinais de solicitação. Não inicia câmera, microfone,
 * captura de tela nem abre a Activity automaticamente.
 * O usuário toca na notificação e confirma a solicitação dentro do app.
 */
class ProjectSuperMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_FCM_TOKEN, token)
            .apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (message.data["type"] != "control_request") return

        val channelId = "projeto_super_requests"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(channelId) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Solicitações de autorização",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Avisos pontuais para revisar solicitações do painel."
                }
            )
        }

        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_PENDING_REQUEST, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Solicitação de autorização")
            .setContentText("Abra o Projeto Super para revisar uma solicitação.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val requestId = message.data["request_id"].orEmpty()
        val notificationId = requestId.hashCode().takeIf { it != 0 } ?: 9155
        try {
            NotificationManagerCompat.from(this).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Notificações desativadas: nenhuma tentativa de contornar a escolha do usuário.
        }
    }

    companion object {
        const val EXTRA_OPEN_PENDING_REQUEST = "open_pending_request"
        private const val PREFS = "projeto_super_fcm"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val REQUEST_CODE = 9155
    }
}
