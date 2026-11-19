package com.projetosuper.lab

// Build validation marker: automatic startup authorization is enabled on this branch.

import android.Manifest
import android.content.ComponentName
import android.provider.Settings.Secure
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

    private fun createAppLaunchIntent(packageName: String): Intent? {
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        val launcherActivity = packageManager
            .queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .firstOrNull()
            ?.activityInfo

        if (launcherActivity != null) {
            return Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(launcherActivity.packageName, launcherActivity.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        return packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    private fun openApp(packageName: String) {
        val launchIntent = createAppLaunchIntent(packageName) ?: return
        startActivity(launchIntent)
    }

    private suspend fun runAuthorizedAppCommand(context: Context, request: JSONObject) {
        val requestId = request.optString("id")
        val action = request.optString("action")
        require(requestId.isNotBlank()) { "Solicitação inválida." }
        when {
            action.startsWith("open_app:") -> {
                val packageName = action.substringAfter("open_app:")
                check(packageName != context.packageName && loadLaunchableApps().any { it.packageName == packageName }) {
                    "O aplicativo não está na lista identificada deste aparelho."
                }
                val launchIntent = createAppLaunchIntent(packageName)
                    ?: error("O Android não disponibilizou uma atividade de inicialização para este aplicativo.")
                ControlApi.decide(context, requestId, true)
                context.startActivity(launchIntent)
            }
            action.startsWith("type_text:") -> {
                val text = action.substringAfter("type_text:")
                check(text.isNotBlank() && text.length <= 2000) { "Texto vazio ou maior que 2.000 caracteres." }
                val service = RemoteControlAccessibilityService.activeInstance
                    ?: error("Ative a acessibilidade do Projeto Super nas configurações do Android.")
                ControlApi.decide(context, requestId, true)
                check(service.setFocusedText(text)) {
                    "Não foi encontrado um campo de texto ativo ou o aplicativo não permite preenchimento remoto."
                }
            }
            action.startsWith("tap:") -> {
                val parts = action.split(":")
                check(parts.size == 3) { "Coordenadas inválidas." }
                val x = parts[1].toIntOrNull() ?: error("Coordenada X inválida.")
                val y = parts[2].toIntOrNull() ?: error("Coordenada Y inválida.")
                check(x in 0..1000 && y in 0..1000) { "Toque fora dos limites." }
                val service = RemoteControlAccessibilityService.activeInstance
                    ?: error("Ative a acessibilidade do Projeto Super nas configurações do Android.")
                ControlApi.decide(context, requestId, true)
                check(service.tapNormalized(x, y)) { "O Android não aceitou o toque." }
            }
            action == "remote_back" || action == "remote_home" -> {
                val service = RemoteControlAccessibilityService.activeInstance
                    ?: error("Ative a acessibilidade do Projeto Super nas configurações do Android.")
                ControlApi.decide(context, requestId, true)
                val ok = if (action == "remote_back") service.goBack() else service.goHome()
                check(ok) { "O Android não aceitou o comando." }
            }
            else -> error("Comando de aplicativo não reconhecido.")
        }
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
            // A autorização geral é apresentada automaticamente na primeira abertura.
            val showGeneralAuthorizationDialog = remember { mutableStateOf(!appControlAuthorized.value) }
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
                        var createdSessionId: String? = null
                        try {
                            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                                val response = ControlApi.decide(context, requestId, true)
                                val sessionId = response.optString("session_id")
                                check(sessionId.isNotBlank()) { "O servidor não confirmou a sessão de tela." }
                                createdSessionId = sessionId
                                val start = Intent(context, ScreenCaptureService::class.java)
                                    .setAction(ScreenCaptureService.ACTION_START)
                                    .putExtra(ScreenCaptureService.EXTRA_SESSION_ID, sessionId)
                                    .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                                    .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                                ContextCompat.startForegroundService(context, start)
                            } else {
                                ControlApi.decide(context, requestId, false)
                                deviceError.value = "Captura de tela recusada no Android."
                            }
                        } catch (error: Exception) {
                            createdSessionId?.let { try { ControlApi.stopSession(context, it) } catch (_: Exception) {} }
                            deviceError.value = error.message ?: "Não foi possível iniciar a captura de tela."
                        } finally {
                            busy.value = false
                        }
                    }
                }
            }

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

            val photoPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri -> selectedMedia.value = uri }

            LaunchedEffect(Unit) {
                try {
                    val credentials = ControlApi.ensureRegistered(context)
                    deviceId.value = credentials.deviceId
                    deviceStatus.value = "Dispositivo conectado e vinculado automaticamente à conta administrativa."
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
                            val response = ControlApi.poll(context)
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
                            deviceStatus.value = if (devicePaired.value) {
                                "Dispositivo vinculado. Com autorização geral ativa, comandos de aplicativos seguem a permissão concedida neste aparelho."
                            } else {
                                "Conectando automaticamente à conta administrativa..."
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
                                            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
                                            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
                                            context.startService(Intent(context, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_STOP))
                                        } catch (error: Exception) {
                                            deviceError.value = error.message ?: "Não foi possível encerrar a sessão."
                                        }
                                        break
                                    }
                                    val requestedAction = candidate.optString("action")
                                    val isAppCommand = requestedAction.startsWith("open_app:") ||
                                        requestedAction.startsWith("tap:") ||
                                        requestedAction.startsWith("type_text:") ||
                                        requestedAction == "remote_back" || requestedAction == "remote_home"
                                    if (isAppCommand && appControlAuthorized.value &&
                                        RemoteControlAccessibilityService.activeInstance == null) {
                                        busy.value = true
                                        scope.launch {
                                            try {
                                                runAuthorizedAppCommand(context, candidate)
                                                deviceError.value = null
                                            } catch (error: Exception) {
                                                try { ControlApi.decide(context, candidate.optString("id"), false) } catch (_: Exception) {}
                                                deviceError.value = error.message ?: "Não foi possível executar o comando do aplicativo."
                                            } finally {
                                                busy.value = false
                                            }
                                        }
                                        break
                                    }
                                    val needsUiDecision = isAppCommand &&
                                        (!appControlAuthorized.value || RemoteControlAccessibilityService.activeInstance == null)
                                    if (requestedAction in listOf(
                                            "camera_front", "camera_back", "screen_share",
                                            "microphone_live", "microphone_record"
                                        ) || needsUiDecision) {
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
                                "A autorização geral habilita controle remoto e captura de tela. O Android mantém as confirmações próprias de acessibilidade e MediaProjection, além das permissões específicas de câmera e microfone."
                            )
                        }
                        item {
                            Text("Vincular ao endpoint", style = MaterialTheme.typography.titleLarge)
                            Text(deviceStatus.value)
                            Text(
                                if (devicePaired.value) "Este aparelho está vinculado à conta administrativa."
                                else "O vínculo automático está sendo concluído. Nenhum código ou aprovação manual é necessário."
                            )
                            Button(
                                onClick = {
                                    context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
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
                            PermissionRow("Acessibilidade para controle remoto", accessibilityEnabled)
                            Text(
                                "O controle de toques e navegação só funciona depois de ativar este serviço manualmente. " +
                                    "Ele não lê o conteúdo da tela; executa apenas gestos e comandos globais solicitados.",
                                style = MaterialTheme.typography.bodySmall
                            )
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
                            Text("A autorização geral vale para controle remoto e captura de tela. Acessibilidade e MediaProjection ainda dependem das confirmações nativas exigidas pelo Android. Câmera e microfone continuam sujeitos às permissões específicas do sistema.")
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
                                    TextButton(onClick = { openApp(app.packageName) }) { Text("Abrir") }
                                }
                            }
                        }
                    }
                }

                if (showGeneralAuthorizationDialog.value && !appControlAuthorized.value) {
                    AlertDialog(
                        onDismissRequest = { showGeneralAuthorizationDialog.value = false },
                        title = { Text("Autorização geral do Projeto Super") },
                        text = {
                            Text(
                                "Ao autorizar, você habilita conjuntamente o controle remoto dos aplicativos identificados e a captura de tela para exibição no dashboard. " +
                                    "O Android ainda poderá solicitar confirmações próprias, como ativar a acessibilidade e permitir cada sessão de captura de tela. " +
                                    "Você pode revogar esta autorização a qualquer momento."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                appControlAuthorized.value = true
                                showGeneralAuthorizationDialog.value = false
                                context.getSharedPreferences("projeto_super_device", Context.MODE_PRIVATE)
                                    .edit().putBoolean("app_control_authorized", true).apply()
                                appsSynced.value = false
                                scope.launch {
                                    try {
                                        if (devicePaired.value) {
                                            ControlApi.syncApps(context, loadLaunchableApps())
                                            appsSynced.value = true
                                        }
                                        deviceError.value = "Autorização geral concedida para controle remoto e captura de tela."
                                    } catch (error: Exception) {
                                        deviceError.value = error.message ?: "Autorização salva, mas houve falha ao sincronizar os aplicativos."
                                    }
                                }
                            }) { Text("Autorizar controle e captura") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showGeneralAuthorizationDialog.value = false }) {
                                Text("Agora não")
                            }
                        }
                    )
                }

                val request = pendingRequest.value
                if (request != null && !appControlAuthorized.value &&
                    (request.optString("action").startsWith("open_app:") ||
                        request.optString("action").startsWith("tap:") ||
                        request.optString("action") == "remote_back" ||
                        request.optString("action") == "remote_home")) {
                    AlertDialog(
                        onDismissRequest = { },
                        title = { Text("Autorizar controle geral dos aplicativos?") },
                        text = { Text("Uma única autorização habilita comandos remotos para todos os aplicativos identificados neste aparelho. O acesso pode ser revogado a qualquer momento. Toques e navegação também exigem que você ative manualmente a acessibilidade do Projeto Super nas configurações do Android.") },
                        confirmButton = {
                            TextButton(onClick = {
                                appControlAuthorized.value = true
                                context.getSharedPreferences("projeto_super_device", Context.MODE_PRIVATE)
                                    .edit().putBoolean("app_control_authorized", true).apply()
                                appsSynced.value = false
                                pendingRequest.value = null
                                scope.launch {
                                    try {
                                        ControlApi.syncApps(context, loadLaunchableApps())
                                        appsSynced.value = true
                                        runAuthorizedAppCommand(context, request)
                                        deviceError.value = "Autorização geral concedida e primeiro comando executado."
                                    } catch (error: Exception) {
                                        deviceError.value = error.message ?: "Não foi possível concluir a solicitação."
                                    }
                                }
                            }) { Text("Autorizar todos") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                pendingRequest.value = null
                                scope.launch {
                                    try { ControlApi.decide(context, request.optString("id"), false) } catch (_: Exception) {}
                                }
                                deviceError.value = "Autorização geral recusada. Nenhum comando foi executado."
                            }) { Text("Recusar") }
                        }
                    )
                }
                if (request != null && (appControlAuthorized.value ||
                    !(request.optString("action").startsWith("open_app:") ||
                        request.optString("action").startsWith("tap:") ||
                        request.optString("action") == "remote_back" ||
                        request.optString("action") == "remote_home"))) {
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
                        } else if (action.startsWith("tap:")) {
                            "O dashboard solicitou um toque na tela compartilhada. Confirme para executar."
                        } else if (action == "remote_back" || action == "remote_home") {
                            "O dashboard solicitou navegação remota. Confirme para executar."
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
                                                    pendingScreenRequestId.value = request.optString("id")
                                                    val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                                    screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
                                                }
                                                else -> {
                                                    if (action.startsWith("open_app:")) {
                                                        val packageName = action.substringAfter("open_app:")
                                                        val launchIntent = createAppLaunchIntent(packageName)
                                                        check(launchIntent != null) {
                                                            "O Android não encontrou uma tela inicial para este aplicativo. Atualize a lista de apps e tente novamente."
                                                        }
                                                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                                                        context.startActivity(launchIntent)
                                                        ControlApi.decide(context, request.optString("id"), true)
                                                        deviceError.value = null
                                                        return@launch
                                                    }
                                                    if (action.startsWith("tap:") || action == "remote_back" || action == "remote_home") {
                                                        val service = RemoteControlAccessibilityService.activeInstance
                                                        check(service != null) {
                                                            "Ative a acessibilidade do Projeto Super nas configurações do Android."
                                                        }
                                                        if (action.startsWith("tap:")) {
                                                            val parts = action.split(":")
                                                            check(parts.size == 3) { "Coordenadas inválidas." }
                                                            val x = parts[1].toIntOrNull() ?: error("Coordenada X inválida.")
                                                            val y = parts[2].toIntOrNull() ?: error("Coordenada Y inválida.")
                                                            check(x in 0..1000 && y in 0..1000) { "Toque fora dos limites." }
                                                            ControlApi.decide(context, request.optString("id"), true)
                                                            check(service.tapNormalized(x, y)) { "O Android não aceitou o toque." }
                                                        } else {
                                                            ControlApi.decide(context, request.optString("id"), true)
                                                            val ok = if (action == "remote_back") service.goBack() else service.goHome()
                                                            check(ok) { "O Android não aceitou o comando." }
                                                        }
                                                        return@launch
                                                    }
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
