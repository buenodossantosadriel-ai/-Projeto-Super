package com.projetosuper.lab

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

data class LaunchableApp(val label: String, val packageName: String)

class MainActivity : ComponentActivity() {
    private fun loadLaunchableApps(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map {
                LaunchableApp(
                    label = it.loadLabel(packageManager).toString(),
                    packageName = it.activityInfo.packageName
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun openApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        startActivity(launchIntent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            val scope = rememberCoroutineScope()
            val apps = remember { mutableStateOf(loadLaunchableApps()) }
            val permissionRefresh = remember { mutableStateOf(0) }
            val selectedMedia = remember { mutableStateOf<Uri?>(null) }
            val deviceId = remember { mutableStateOf<String?>(null) }
            val devicePaired = remember { mutableStateOf(false) }
            val deviceStatus = remember { mutableStateOf("Registrando dispositivo seguro...") }
            val deviceError = remember { mutableStateOf<String?>(null) }
            val pendingRequest = remember { mutableStateOf<JSONObject?>(null) }
            val pendingScreenRequestId = remember { mutableStateOf<String?>(null) }
            val busy = remember { mutableStateOf(false) }

            val screenCaptureLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val requestId = pendingScreenRequestId.value
                pendingScreenRequestId.value = null
                if (requestId != null) {
                    scope.launch {
                        busy.value = true
                        try {
                            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                                val response = ControlApi.decide(context, requestId, true)
                                val sessionId = response.optString("session_id")
                                check(sessionId.isNotBlank()) { "O servidor não confirmou a sessão de tela." }
                                val start = Intent(context, ScreenCaptureService::class.java)
                                    .setAction(ScreenCaptureService.ACTION_START)
                                    .putExtra(ScreenCaptureService.EXTRA_SESSION_ID, sessionId)
                                    .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                                    .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                                ContextCompat.startForegroundService(context, start)
                            } else {
                                ControlApi.decide(context, requestId, false)
                                deviceError.value = "Compartilhamento de tela recusado no Android."
                            }
                        } catch (error: Exception) {
                            deviceError.value = error.message ?: "Não foi possível iniciar o compartilhamento."
                        } finally {
                            busy.value = false
                        }
                    }
                }
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) {
                context.getSharedPreferences("projeto_super_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("initial_permissions_requested", true).apply()
                permissionRefresh.value++
            }

            val photoPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri -> selectedMedia.value = uri }

            LaunchedEffect(Unit) {
                val prefs = context.getSharedPreferences("projeto_super_prefs", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("initial_permissions_requested", false)) {
                    val requested = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) requested.add(Manifest.permission.POST_NOTIFICATIONS)
                    val missing = requested.distinct().filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) {
                        prefs.edit().putBoolean("initial_permissions_requested", true).apply()
                    } else {
                        permissionLauncher.launch(missing.toTypedArray())
                    }
                }
            }

            LaunchedEffect(Unit) {
                try {
                    val credentials = ControlApi.ensureRegistered(context)
                    deviceId.value = credentials.deviceId
                    deviceStatus.value = "Dispositivo registrado. Aguardando aprovação no painel administrativo."
                } catch (error: Exception) {
                    deviceError.value = error.message ?: "Falha ao registrar o dispositivo."
                    deviceStatus.value = "Não foi possível conectar ao servidor."
                }
            }

            LaunchedEffect(deviceId.value) {
                if (deviceId.value == null) return@LaunchedEffect
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) {
                        try {
                            val response = ControlApi.poll(context)
                            devicePaired.value = response.optBoolean("paired")
                            deviceStatus.value = if (devicePaired.value) {
                                "Dispositivo vinculado. Cada pedido remoto exige sua confirmação neste aparelho."
                            } else {
                                "Dispositivo registrado. Aguardando aprovação no painel administrativo."
                            }
                            val requests = response.optJSONArray("requests")
                            if (pendingRequest.value == null && !busy.value && requests != null) {
                                var selected: JSONObject? = null
                                for (i in 0 until requests.length()) {
                                    val candidate = requests.getJSONObject(i)
                                    if (candidate.optString("action") == "stop_session") {
                                        // Encerrar uma sessão é sempre permitido e não inicia nova captura.
                                        try {
                                            ControlApi.decide(context, candidate.optString("id"), true)
                                            context.startService(Intent(context, CameraCaptureService::class.java).setAction(CameraCaptureService.ACTION_STOP))
                                            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
                                            context.startService(Intent(context, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_STOP))
                                        } catch (error: Exception) {
                                            deviceError.value = error.message ?: "Não foi possível encerrar a sessão."
                                        }
                                        break
                                    }
                                    val requestedAction = candidate.optString("action")
                                    if (requestedAction in listOf(
                                            "camera_front", "camera_back", "screen_share",
                                            "microphone_live", "microphone_record"
                                        ) || requestedAction.startsWith("open_app:")) {
                                        selected = candidate
                                        break
                                    }
                                }
                                if (selected != null) pendingRequest.value = selected
                            }
                            deviceError.value = null
                        } catch (error: Exception) {
                            deviceError.value = error.message ?: "Falha na conexão."
                        }
                        delay(2500)
                    }
                }
            }

            val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val microphoneGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text("Projeto Super", style = MaterialTheme.typography.headlineMedium)
                            Text("Laboratório Android — controle transparente")
                            Text(
                                "Nenhuma câmera, gravação de áudio ou tela é iniciada sem uma solicitação visível e sua confirmação. " +
                                    "O compartilhamento de tela também exige a autorização nativa do Android."
                            )
                        }
                        item {
                            Text("Vincular ao endpoint", style = MaterialTheme.typography.titleLarge)
                            Text(deviceStatus.value)
                            Text(
                                if (devicePaired.value) "Este aparelho está vinculado à conta administrativa."
                                else "O administrador precisa aprovar este aparelho no dashboard. Nenhum código de pareamento é necessário."
                            )
                            Button(
                                onClick = {
                                    context.startService(Intent(context, CameraCaptureService::class.java).setAction(CameraCaptureService.ACTION_STOP))
                                    context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
                                    context.startService(Intent(context, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_STOP))
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Encerrar qualquer sessão neste aparelho") }
                            deviceError.value?.let { Text("Aviso: $it", color = MaterialTheme.colorScheme.error) }
                        }
                        item {
                            Text("Permissões do aparelho", style = MaterialTheme.typography.titleLarge)
                            Text("Verificação: ${permissionRefresh.value}", style = MaterialTheme.typography.labelSmall)
                            PermissionRow("Câmera", cameraGranted)
                            PermissionRow("Microfone", microphoneGranted)
                            PermissionRow("Notificações", notificationsGranted)
                            Button(
                                onClick = {
                                    val settingsIntent = Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(settingsIntent)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Abrir configurações de permissões") }
                            Text(
                                "Se uma permissão for negada ou revogada, altere-a manualmente nas configurações do Android.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Button(
                                onClick = {
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Selecionar foto ou vídeo") }
                            Text(
                                selectedMedia.value?.let { "Mídia selecionada: $it" } ?: "Nenhuma mídia selecionada.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        item {
                            Text("Aplicativos disponíveis", style = MaterialTheme.typography.titleLarge)
                            Text("Lista limitada aos apps exibidos no launcher. Abrir exige um toque seu.")
                            Button(
                                onClick = { apps.value = loadLaunchableApps() },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Atualizar lista") }
                            Text("Aplicativos encontrados: ${apps.value.size}")
                        }
                        items(apps.value, key = { it.packageName }) { app ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { openApp(app.packageName) }) { Text("Abrir") }
                                }
                            }
                        }
                    }
                }

                val request = pendingRequest.value
                if (request != null) {
                    val action = request.optString("action")
                    val description = when (action) {
                        "camera_front" -> "O painel administrativo solicitou acesso à câmera frontal. A câmera ficará ativa enquanto a sessão estiver aberta."
                        "camera_back" -> "O painel administrativo solicitou acesso à câmera traseira. A câmera ficará ativa enquanto a sessão estiver aberta."
                        "screen_share" -> "O painel administrativo solicitou compartilhar a tela. Se aceitar aqui, o Android ainda mostrará sua confirmação nativa."
                        "microphone_live" -> "O painel administrativo solicitou áudio ao vivo pelo microfone. O microfone ficará ativo e haverá uma notificação persistente."
                        "microphone_record" -> "O painel administrativo solicitou uma gravação de áudio. A gravação só começará após sua confirmação e ficará indicada por notificação."
                        else -> if (action.startsWith("open_app:")) {
                            val packageName = action.substringAfter("open_app:")
                            val appName = apps.value.firstOrNull { it.packageName == packageName }?.label ?: packageName
                            "O painel administrativo solicitou abrir o aplicativo $appName ($packageName). Ele só será aberto se você aceitar."
                        } else "O painel administrativo solicitou iniciar uma sessão."
                    }
                    AlertDialog(
                        onDismissRequest = {
                            if (!busy.value) {
                                pendingRequest.value = null
                                scope.launch {
                                    try { ControlApi.decide(context, request.optString("id"), false) }
                                    catch (error: Exception) { deviceError.value = error.message ?: "Falha ao recusar solicitação." }
                                }
                            }
                        },
                        title = { Text("Autorizar solicitação?") },
                        text = { Text(description) },
                        confirmButton = {
                            TextButton(
                                enabled = !busy.value,
                                onClick = {
                                    pendingRequest.value = null
                                    scope.launch {
                                        busy.value = true
                                        try {
                                            when (action) {
                                                "screen_share" -> {
                                                    pendingScreenRequestId.value = request.optString("id")
                                                    val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                                    screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
                                                }
                                                else -> {
                                                    if (action.startsWith("open_app:")) {
                                                        val packageName = action.substringAfter("open_app:")
                                                        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                                                        check(launchIntent != null) { "Este aplicativo não está instalado ou não pode ser aberto." }
                                                        ControlApi.decide(context, request.optString("id"), true)
                                                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        context.startActivity(launchIntent)
                                                        return@launch
                                                    }
                                                    val response = ControlApi.decide(context, request.optString("id"), true)
                                                    val sessionId = response.optString("session_id")
                                                    check(sessionId.isNotBlank()) { "O servidor não confirmou a sessão." }
                                                    when (action) {
                                                        "camera_front", "camera_back" -> {
                                                            val start = Intent(context, CameraCaptureService::class.java)
                                                                .setAction(CameraCaptureService.ACTION_START)
                                                                .putExtra(CameraCaptureService.EXTRA_SESSION_ID, sessionId)
                                                                .putExtra(CameraCaptureService.EXTRA_LENS, if (action == "camera_front") "front" else "back")
                                                            ContextCompat.startForegroundService(context, start)
                                                        }
                                                        "microphone_live", "microphone_record" -> {
                                                            val start = Intent(context, AudioCaptureService::class.java)
                                                                .setAction(AudioCaptureService.ACTION_START)
                                                                .putExtra(AudioCaptureService.EXTRA_SESSION_ID, sessionId)
                                                                .putExtra(AudioCaptureService.EXTRA_MODE, if (action == "microphone_record") "recording" else "live")
                                                            ContextCompat.startForegroundService(context, start)
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (error: Exception) {
                                            deviceError.value = error.message ?: "Não foi possível iniciar a sessão."
                                        } finally {
                                            busy.value = false
                                        }
                                    }
                                }
                            ) { Text("Aceitar") }
                        },
                        dismissButton = {
                            TextButton(
                                enabled = !busy.value,
                                onClick = {
                                    pendingRequest.value = null
                                    scope.launch {
                                        try { ControlApi.decide(context, request.optString("id"), false) }
                                        catch (error: Exception) { deviceError.value = error.message ?: "Falha ao recusar solicitação." }
                                    }
                                }
                            ) { Text("Recusar") }
                        }
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Text(if (granted) "Permitida" else "Não permitida")
    }
}
