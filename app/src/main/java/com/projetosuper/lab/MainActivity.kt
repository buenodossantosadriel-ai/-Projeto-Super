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
            val startupPermissionRequestPending = remember { mutableStateOf(false) }
            val startupPermissionFlowStarted = remember { mutableStateOf(false) }
            val startupPermissionFlowComplete = remember { mutableStateOf(false) }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                permissionRefresh.value++
                if (startupPermissionRequestPending.value) {
                    startupPermissionRequestPending.value = false
                    val denied = results.filterValues { !it }.keys
                    deviceError.value = if (denied.isEmpty()) {
                        "Permissões iniciais concedidas."
                    } else {
                        "Algumas permissões opcionais foram recusadas: " + denied.joinToString() + ". A captura JPEG e o controle dependem da Acessibilidade."
                    }
                    startupPermissionFlowComplete.value = true
                    if (!isAccessibilityServiceEnabled(context)) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                } else if (results.values.all { it }) {
                    deviceError.value = "Permissão concedida. A operação solicitada pode continuar."
                } else {
                    deviceError.value = "Permissão não concedida. A operação solicitada não foi iniciada."
                }
            }

            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                permissionRefresh.value++
                deviceError.value = if (granted) {
                    "Notificações autorizadas. O Android pode exibir os indicadores das sessões em primeiro plano na área de notificações."
                } else {
                    "Notificações não autorizadas. Isso não impede a captura de tela, a câmera ou o microfone; o Android pode ocultar a notificação do serviço na bandeja."
                }
            }
            val notificationPermissionRequested = remember { mutableStateOf(false) }

            val photoPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri -> selectedMedia.value = uri }

            LaunchedEffect(deviceId.value) {
                // Primeiro registra e conecta o telefone; só depois inicia a sequência de permissões.
                if (deviceId.value == null || startupPermissionFlowStarted.value) return@LaunchedEffect
                startupPermissionFlowStarted.value = true
                val startupPermissions = buildList {
                    add(Manifest.permission.CAMERA)
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                val missingPermissions = startupPermissions.filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missingPermissions.isNotEmpty()) {
                    startupPermissionRequestPending.value = true
                    notificationPermissionRequested.value = Build.VERSION.SDK_INT >= 33 &&
                        missingPermissions.contains(Manifest.permission.POST_NOTIFICATIONS)
                    permissionLauncher.launch(missingPermissions.toTypedArray())
                } else {
                    startupPermissionFlowComplete.value = true
                    if (!isAccessibilityServiceEnabled(context)) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
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
                                    "Telefone conectado e vinculado automaticamente à conta administrativa."
                                !appControlAuthorized.value ->
                                    "Telefone vinculado. Aguardando sua autorização geral para liberar os comandos remotos."
                                Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
                                    "Este aparelho pode instalar o Projeto Super, mas o controle isolado de aplicativos exige Android 11 ou superior."
                                !isAccessibilityServiceEnabled(context) ->
                                    "Autorização geral ativa. Ative o serviço de Acessibilidade nas configurações para abrir apps, tocar, digitar e navegar."
                                else ->
                                    "Acessibilidade ativa. A captura JPEG e os comandos remotos estão prontos para iniciar uma sessão."
                            }
                            val requests = response.optJSONArray("requests")
                            if (pendingRequest.value == null && !busy.value && requests != null) {
                                var selected: JSONObject? = null
                                for (i in 0 until requests.length()) {
                                    val candidate = requests.getJSONObject(i)
                                    val requestedAction = candidate.optString("action")
                                    if (!appControlAuthorized.value) {
                                        deviceError.value = "Solicitação recusada: a autorização geral do Projeto Super não está ativa."
                                        try { ControlApi.decide(context, candidate.optString("id"), false) }
                                        catch (error: Exception) { deviceError.value = error.message ?: "Não foi possível recusar a solicitação sem autorização." }
                                        continue
                                    }
                                    if (requestedAction == "screen_share") {
                                        val displaySupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                                        if (!displaySupported) {
                                            deviceError.value = "Sessão recusada: a captura contínua JPEG exige Android 11 ou superior."
                                            try { ControlApi.decide(context, candidate.optString("id"), false) }
                                            catch (error: Exception) { deviceError.value = error.message ?: "Não foi possível recusar a sessão incompatível." }
                                            continue
                                        }
                                        if (!isAccessibilityServiceEnabled(context)) {
                                            deviceError.value = "Sessão recusada: ative primeiro o serviço de Acessibilidade nas configurações do Android."
                                            try { ControlApi.decide(context, candidate.optString("id"), false) }
                                            catch (error: Exception) { deviceError.value = error.message ?: "Não foi possível recusar a sessão sem Acessibilidade." }
                                            continue
                                        }
                                        if (!busy.value) {
                                            busy.value = true
                                            var createdSessionId: String? = null
                                            try {
                                                val response = ControlApi.decide(context, candidate.optString("id"), true)
                                                createdSessionId = response.optString("session_id")
                                                check(!createdSessionId.isNullOrBlank()) { "O servidor não confirmou a sessão JPEG." }
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
                                                deviceError.value = error.message ?: "Não foi possível iniciar a captura JPEG."
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
                                    "A transmissão JPEG captura a tela principal pelo Serviço de Acessibilidade. Câmera e microfone continuam exigindo suas permissões específicas do Android."
                            )
                        }
                        item {
                            Text("Vincular ao endpoint", style = MaterialTheme.typography.titleLarge)
                            Text(deviceStatus.value)
                            Text(
                                if (devicePaired.value) "Este aparelho está conectado e vinculado à conta administrativa."
                                else "Conectando o telefone à conta administrativa..."
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
                            PermissionRow("Permissão de câmera (funções de câmera)", cameraGranted)
                            PermissionRow("Permissão de microfone (funções de áudio)", microphoneGranted)
                            PermissionRow("Acessibilidade para captura JPEG e controle remoto", accessibilityEnabled)
                            Text(
                                "Transmissão de imagens: usa o Serviço de Acessibilidade para capturar a tela principal e enviar quadros JPEG. " +
                                    "Não usa MediaProjection nem cria displays virtuais.",
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
                                else "Para abrir aplicativos, executar comandos remotos e enviar quadros JPEG da tela, ative manualmente o serviço Projeto Super nesta tela de configurações.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "A visualização envia somente quadros JPEG da tela principal, capturados pelo Serviço de Acessibilidade após a ativação manual pelo usuário. Aplicativos protegidos pelo Android podem ocultar seu conteúdo da captura.",
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
                            Text(if (appControlAuthorized.value) {
                                if (accessibilityEnabled) "Autorização geral concedida e Acessibilidade ativada. O controle e a captura JPEG estão disponíveis durante uma sessão ativa."
                                else "Autorização geral concedida, mas o controle ainda NÃO está pronto: ative manualmente o serviço de Acessibilidade nas configurações do Android."
                            } else "Autorização geral ainda não concedida. Nenhum comando remoto de aplicativo será executado.")
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
                                        .edit()
                                        .putBoolean("app_control_authorized", false)
                                        .remove("remote_target_package")
                                        .apply()
                                    appsSynced.value = false
                                    pendingRequest.value?.let { pending ->
                                        scope.launch {
                                            try { ControlApi.decide(context, pending.optString("id"), false) }
                                            catch (error: Exception) { deviceError.value = error.message ?: "Falha ao recusar a solicitação pendente." }
                                        }
                                    }
                                    pendingRequest.value = null
                                    context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
                                    context.startService(Intent(context, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_STOP))
                                    context.startService(Intent(context, CameraCaptureService::class.java).setAction(CameraCaptureService.ACTION_STOP))
                                    deviceError.value = "Autorização geral revogada. Sessões locais encerradas e novos comandos bloqueados."
                                },
                                enabled = appControlAuthorized.value,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Revogar autorização geral") }
                            Text("A autorização vale para todos os aplicativos identificados. A Acessibilidade precisa ser ativada nas configurações do Android e permite a captura JPEG da tela e os comandos remotos. Câmera e microfone continuam dependendo de suas permissões específicas.")
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

                if (showInitialAuthorization.value && !appControlAuthorized.value &&
                    startupPermissionFlowComplete.value) {
                    AlertDialog(
                        onDismissRequest = { },
                        title = { Text("Autorização geral do Projeto Super") },
                        text = {
                            Text(
                                "Esta é a autorização geral interna do Projeto Super; ela não é uma permissão do Android. Ao conceder, você autoriza o controle dos aplicativos identificados e as sessões solicitadas pelo dashboard. " +
                                    "Para captura JPEG contínua da tela real e para toques, rolagem, digitação e abertura de aplicativos, o Android exige que você ative manualmente o Serviço de Acessibilidade nas configurações. O Projeto Super não consegue ativá-lo por você. " +
                                    "Na primeira abertura, o Super solicita as permissões de câmera, microfone e notificações disponíveis no Android. Câmera e microfone são usados somente nas respectivas funções; não são necessários para transmitir a tela. " +
                                    "Você pode revogar a autorização geral neste aplicativo."
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
                            TextButton(onClick = { this@MainActivity.finish() }) {
                                Text("Sair do aplicativo")
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
                        "microphone_live" -> "O painel administrativo solicitou áudio ao vivo pelo microfone. O microfone ficará ativo enquanto a sessão estiver aberta, com indicador de serviço em primeiro plano do Android."
                        "microphone_record" -> "O painel administrativo solicitou uma gravação de áudio. A gravação só começará após sua confirmação e será executada como serviço em primeiro plano do Android."
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
                                        "microphone_live", "microphone_record" -> listOf(Manifest.permission.RECORD_AUDIO)
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
                                                    check(sessionId.isNotBlank()) { "O servidor não confirmou a sessão de captura JPEG." }
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
