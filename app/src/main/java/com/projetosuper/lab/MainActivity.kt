package com.projetosuper.lab

import android.Manifest
import android.content.ComponentName
import android.provider.Settings.Secure
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val component = ComponentName(context, RemoteControlAccessibilityService::class.java)
    val enabledServices = Secure.getString(
        context.contentResolver,
        Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    return enabledServices.split(':').contains(component.flattenToString())
}

class MainActivity : ComponentActivity() {


    private fun loadLaunchableApps(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .map {
                LaunchableApp(
                    label = it.loadLabel(packageManager).toString(),
                    packageName = it.activityInfo.packageName
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
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
            val appsSynced = remember { mutableStateOf(false) }
            val appControlAuthorized = remember {
                mutableStateOf(context.getSharedPreferences("projeto_super_device", Context.MODE_PRIVATE)
                    .getBoolean("app_control_authorized", false))
            }
            val showInitialAuthorization = remember { mutableStateOf(!appControlAuthorized.value) }
            val deviceStatus = remember { mutableStateOf("Registrando dispositivo seguro...") }
            val deviceError = remember { mutableStateOf<String?>(null) }
            val pendingRequest = remember { mutableStateOf<JSONObject?>(null) }
            val busy = remember { mutableStateOf(false) }


            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                permissionRefresh.value++
                if (results.values.all { it }) {
                    deviceError.value = "Permissão concedida. Toque em Aceitar novamente para iniciar a operação solicitada."
                } else {
                    deviceError.value = "Permissão não concedida. A operação não foi iniciada."
                }
            }

            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                permissionRefresh.value++
                deviceError.value = if (granted) {
                    "Notificações autorizadas. As funções de câmera e microfone continuam dependendo das permissões específicas do Android."
                } else {
                    "Notificações não autorizadas. A tela e a câmera ainda podem ser compartilhadas conforme as regras do Android; sessões de microfone exigem essa permissão."
                }
            }
            val notificationPermissionRequested = remember { mutableStateOf(false) }

            val photoPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri -> selectedMedia.value = uri }

            LaunchedEffect(appControlAuthorized.value) {
                if (appControlAuthorized.value && Build.VERSION.SDK_INT >= 33 &&
                    !notificationPermissionRequested.value &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionRequested.value = true
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            LaunchedEffect(lifecycleOwner) {
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) {
                        permissionRefresh.value++
                        delay(1000)
                    }
                }
            }

            LaunchedEffect(Unit) {
                try {
                    val credentials = ControlApi.ensureRegistered(context)
                    deviceId.value = credentials.deviceId
                    deviceStatus.value = "Dispositivo registrado. Aguardando aprovação no dashboard administrativo."
                } catch (error: Exception) {
                    deviceError.value = error.message ?: "Falha ao registrar o dispositivo."
                    deviceStatus.value = "Não foi possível conectar ao servidor."
                }
            }

            LaunchedEffect(deviceId.value) {
                if (deviceId.value == null) return@LaunchedEffect
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    var lastAppsSyncAt = 0L
                    while (true) {
                        try {
                            val response = ControlApi.poll(context, "activity")
                            devicePaired.value = response.optBoolean("paired")
                            val now = System.currentTimeMillis()
                            val shouldSyncApps = devicePaired.value && appControlAuthorized.value &&
                                (!appsSynced.value || now - lastAppsSyncAt >= 60_000L)
                            if (shouldSyncApps) {
                                val discoveredApps = loadLaunchableApps()
                                apps.value = discoveredApps
                                ControlApi.syncApps(context, discoveredApps)
                                appsSynced.value = true
                                lastAppsSyncAt = now
                            }
                            deviceStatus.value = when {
                                !devicePaired.value ->
                                    "Telefone registrado. Aguardando aprovação no dashboard administrativo."
                                !appControlAuthorized.value ->
                                    "Telefone vinculado. Aguardando sua autorização geral para liberar os comandos remotos."
                                Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
                                    "Este aparelho pode instalar o Projeto Super, mas o controle isolado de aplicativos exige Android 11 ou superior."
                                !context.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS) ->
                                    "Este aparelho não declara suporte a atividades em displays secundários; o controle isolado não estará disponível."
                                !isAccessibilityServiceEnabled(context) ->
                                    "Autorização geral ativa. Ative o serviço de Acessibilidade nas configurações para abrir apps, tocar, digitar e navegar."
                                else ->
                                    "Requisitos básicos detectados. O fabricante ou o aplicativo pode bloquear atividades em display secundário; mantenha o Projeto Super aberto para iniciar novas sessões."
                            }
                            val requests = response.optJSONArray("requests")
                            if (pendingRequest.value == null && !busy.value && requests != null) {
                                var selected: JSONObject? = null
                                for (i in 0 until requests.length()) {
                                    val candidate = requests.getJSONObject(i)
                                    val requestedAction = candidate.optString("action")
                                    if (requestedAction == "screen_share") {
                                        if (!appControlAuthorized.value) {
                                            deviceError.value = "Aguardando a autorização geral inicial."
                                            continue
                                        }
                                        if (!busy.value) {
                                            busy.value = true
                                            var createdSessionId: String? = null
                                            try {
                                                val response = ControlApi.decide(context, candidate.optString("id"), true)
                                                createdSessionId = response.optString("session_id")
                                                check(!createdSessionId.isNullOrBlank()) { "O servidor não confirmou a sessão de display virtual." }
                                                val start = Intent(context, ScreenCaptureService::class.java)
                                                    .setAction(ScreenCaptureService.ACTION_START)
                                                    .putExtra(ScreenCaptureService.EXTRA_SESSION_ID, createdSessionId)
                                                ContextCompat.startForegroundService(context, start)
                                            } catch (error: Exception) {
                                                createdSessionId?.let { sessionId ->
                                                    try { ControlApi.stopSession(context, sessionId) }
                                                    catch (cleanupError: Exception) {
                                                        android.util.Log.e("ProjetoSuperSession", "Falha ao limpar sessão que não iniciou.", cleanupError)
                                                    }
                                                }
                                                deviceError.value = error.message ?: "Não foi possível iniciar o display virtual."
                                            } finally {
                                                busy.value = false
                                            }
                                        }
                                        break
                                    }
                                    if (requestedAction in listOf(
                                            "camera_front", "camera_back",
                                            "microphone_live", "microphone_record"
                                        )) {
                                        selected = candidate
                                        break
                                    }
                                }
                                if (selected != null) pendingRequest.value = selected
                            }
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

            val accessibilityComponent = ComponentName(context, RemoteControlAccessibilityService::class.java)
            val accessibilityEnabled = Secure.getString(
                context.contentResolver,
                Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.split(":")?.contains(accessibilityComponent.flattenToString()) == true

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
                                "O controle remoto dos aplicativos identificados só funciona após autorização geral neste aparelho. " +
                                    "A transmissão JPEG usa o display virtual independente. Câmera e microfone continuam exigindo suas permissões específicas do Android."
                            )
                        }
                        item {
                            Text("Vincular ao endpoint", style = MaterialTheme.typography.titleLarge)
                            Text(deviceStatus.value)
                            Text(
                                if (devicePaired.value) "Este aparelho está vinculado à conta administrativa."
                                else "Aguardando aprovação deste telefone no dashboard administrativo."
                            )
                            Button(
                                onClick = {
                                    context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
                                    context.startService(Intent(context, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_STOP))
                                    context.startService(Intent(context, CameraCaptureService::class.java).setAction(CameraCaptureService.ACTION_STOP))
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Encerrar qualquer sessão neste aparelho") }
                            deviceError.value?.let { Text("Aviso: $it", color = MaterialTheme.colorScheme.error) }
                        }
                        item {
                            Text("Permissões do aparelho", style = MaterialTheme.typography.titleLarge)
                            Text("Verificação: ${permissionRefresh.value}", style = MaterialTheme.typography.labelSmall)
                            PermissionRow("Notificações do serviço", notificationsGranted)
                            PermissionRow("Acessibilidade para controle remoto", accessibilityEnabled)
                            Text(
                                "Transmissão de imagens: usa um display virtual independente e quadros JPEG. " +
                                    "Não usa MediaProjection nem captura a tela física. A compatibilidade depende do Android e do fabricante.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Câmera e microfone não são necessários para transmitir imagens da tela. Eles só são usados nas funções específicas de câmera e áudio.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted) {
                                Button(
                                    onClick = {
                                        notificationPermissionRequested.value = true
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Solicitar permissão de notificações") }
                            }
                            Button(
                                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (accessibilityEnabled) "Revisar serviço de acessibilidade" else "Ativar controle remoto nas configurações") }
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
                                if (accessibilityEnabled) "Acessibilidade ativada: os comandos remotos podem ser recebidos."
                                else "Para abrir aplicativos e executar comandos remotos, ative manualmente o serviço Projeto Super nesta tela de configurações. Isso não é exigido apenas para enviar quadros JPEG.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "A visualização envia somente quadros JPEG do display virtual próprio; o Projeto Super não captura a tela física nem faz fallback intencional para ela. Ainda assim, alguns Androids ou aplicativos podem forçar atividades secundárias na tela física. Este APK comum não consegue garantir isolamento absoluto em todos os modelos.",
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
                            Text("Autorização geral dos aplicativos", style = MaterialTheme.typography.titleLarge)
                            Text(if (appControlAuthorized.value) "Autorização concedida: comandos remotos para os aplicativos identificados estão habilitados."
                                else "Ainda não autorizada. Nenhum comando remoto de aplicativo será executado.")
                            Button(
                                onClick = {
                                    appControlAuthorized.value = true
                                    context.getSharedPreferences("projeto_super_device", Context.MODE_PRIVATE)
                                        .edit().putBoolean("app_control_authorized", true).apply()
                                    appsSynced.value = false
                                    scope.launch {
                                        try {
                                            if (devicePaired.value) {
                                                ControlApi.syncApps(context, loadLaunchableApps())
                                                appsSynced.value = true
                                            }
                                            deviceError.value = "Autorização geral concedida para os aplicativos identificados."
                                        } catch (error: Exception) {
                                            deviceError.value = error.message ?: "Autorização salva, mas houve falha ao sincronizar os aplicativos."
                                        }
                                    }
                                },
                                enabled = !appControlAuthorized.value,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (appControlAuthorized.value) "Autorização geral ativa" else "Autorizar todos os aplicativos identificados") }
                            Button(
                                onClick = {
                                    appControlAuthorized.value = false
                                    context.getSharedPreferences("projeto_super_device", Context.MODE_PRIVATE)
                                        .edit().putBoolean("app_control_authorized", false).apply()
                                    appsSynced.value = false
                                    deviceError.value = "Autorização geral revogada. Novos comandos de aplicativos serão bloqueados."
                                },
                                enabled = appControlAuthorized.value,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Revogar autorização geral") }
                            Text("A autorização vale para todos os aplicativos identificados. A Acessibilidade precisa ser ativada manualmente nas configurações do Android. Câmera e microfone dependem de suas permissões específicas; a transmissão JPEG do display virtual não usa MediaProjection.")
                        }
                        item {
                            Text("Aplicativos disponíveis", style = MaterialTheme.typography.titleLarge)
                            Text("Lista dos aplicativos com atividade de inicialização identificada neste aparelho.")
                            Button(
                                onClick = {
                                    apps.value = loadLaunchableApps()
                                    scope.launch {
                                        try {
                                            if (devicePaired.value) {
                                                ControlApi.syncApps(context, apps.value)
                                                appsSynced.value = true
                                                deviceError.value = "Lista de aplicativos atualizada no dashboard."
                                            }
                                        } catch (error: Exception) {
                                            deviceError.value = error.message ?: "Falha ao sincronizar aplicativos."
                                        }
                                    }
                                },
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
                                }
                            }
                        }
                    }
                }

                if (showInitialAuthorization.value && !appControlAuthorized.value) {
                    AlertDialog(
                        onDismissRequest = { },
                        title = { Text("Autorização geral do Projeto Super") },
                        text = {
                            Text(
                                "Uma única autorização habilita o controle remoto dos aplicativos identificados e permite iniciar sessões solicitadas pelo dashboard. " +
                                    "A Acessibilidade ainda precisa ser ativada manualmente nas configurações do Android. Câmera e microfone continuarão exigindo suas permissões específicas. " +
                                    "A autorização pode ser revogada neste aplicativo."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                appControlAuthorized.value = true
                                context.getSharedPreferences("projeto_super_device", Context.MODE_PRIVATE)
                                    .edit().putBoolean("app_control_authorized", true).apply()
                                showInitialAuthorization.value = false
                                appsSynced.value = false
                                scope.launch {
                                    try {
                                        if (devicePaired.value) {
                                            ControlApi.syncApps(context, loadLaunchableApps())
                                            appsSynced.value = true
                                        }
                                        deviceError.value = "Autorização geral concedida."
                                    } catch (error: Exception) {
                                        deviceError.value = error.message ?: "Autorização salva; falha ao sincronizar aplicativos."
                                    }
                                }
                            }) { Text("Autorizar") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showInitialAuthorization.value = false }) {
                                Text("Agora não")
                            }
                        }
                    )
                }

                val request = pendingRequest.value
                if (request != null) {
                    val action = request.optString("action")
                    val description = when (action) {
                        "camera_front" -> "O painel administrativo solicitou acesso à câmera frontal. A câmera ficará ativa enquanto a sessão estiver aberta."
                        "camera_back" -> "O painel administrativo solicitou acesso à câmera traseira. A câmera ficará ativa enquanto a sessão estiver aberta."
                        "microphone_live" -> "O painel administrativo solicitou áudio ao vivo pelo microfone. O microfone ficará ativo e haverá uma notificação persistente."
                        "microphone_record" -> "O painel administrativo solicitou uma gravação de áudio. A gravação só começará após sua confirmação e ficará indicada por notificação."
                        else -> "O painel administrativo solicitou iniciar uma sessão."
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
                                    val requiredPermissions = when (action) {
                                        "camera_front", "camera_back" -> listOf(Manifest.permission.CAMERA)
                                        "microphone_live", "microphone_record" -> buildList {
                                            add(Manifest.permission.RECORD_AUDIO)
                                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                        else -> emptyList()
                                    }
                                    val missingPermissions = requiredPermissions.filter {
                                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                                    }
                                    if (missingPermissions.isNotEmpty()) {
                                        permissionLauncher.launch(missingPermissions.toTypedArray())
                                    } else {
                                    pendingRequest.value = null
                                    scope.launch {
                                        busy.value = true
                                        var createdSessionId: String? = null
                                        try {
                                            when (action) {
                                                "screen_share" -> {
                                                    val response = ControlApi.decide(context, request.optString("id"), true)
                                                    val sessionId = response.optString("session_id")
                                                    check(sessionId.isNotBlank()) { "O servidor não confirmou a sessão de display virtual." }
                                                    createdSessionId = sessionId
                                                    val start = Intent(context, ScreenCaptureService::class.java)
                                                        .setAction(ScreenCaptureService.ACTION_START)
                                                        .putExtra(ScreenCaptureService.EXTRA_SESSION_ID, sessionId)
                                                    ContextCompat.startForegroundService(context, start)
                                                }
                                                else -> {
                                                    if (action == "camera_front" || action == "camera_back") {
                                                        check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                                            "Permissão da câmera ausente. Habilite-a nas configurações do Android."
                                                        }
                                                    }
                                                    if (action == "microphone_live" || action == "microphone_record") {
                                                        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                                            "Permissão do microfone ausente. Habilite-a nas configurações do Android."
                                                        }
                                                        if (Build.VERSION.SDK_INT >= 33) {
                                                            check(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                                                                "Permita notificações para manter o indicador visível da sessão de áudio."
                                                            }
                                                        }
                                                    }
                                                    val response = ControlApi.decide(context, request.optString("id"), true)
                                                    val sessionId = response.optString("session_id")
                                                    check(sessionId.isNotBlank()) { "O servidor não confirmou a sessão." }
                                                    createdSessionId = sessionId
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
                                            createdSessionId?.let {
                                                try { ControlApi.stopSession(context, it) } catch (_: Exception) {}
                                            }
                                            deviceError.value = error.message ?: "Não foi possível iniciar a sessão."
                                        } finally {
                                            busy.value = false
                                        }
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
