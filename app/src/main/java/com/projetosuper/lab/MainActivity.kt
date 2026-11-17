package com.projetosuper.lab

import android.Manifest
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
            val refreshPermissions = remember { mutableStateOf(0) }
            val permissionRefreshCount = refreshPermissions.value
            val selectedMedia = remember { mutableStateOf<Uri?>(null) }
            val deviceId = remember { mutableStateOf<String?>(null) }
            val pairingCode = remember { mutableStateOf<String?>(null) }
            val devicePaired = remember { mutableStateOf(false) }
            val deviceStatus = remember { mutableStateOf("Registrando dispositivo seguro...") }
            val deviceError = remember { mutableStateOf<String?>(null) }
            val pendingRequest = remember { mutableStateOf<JSONObject?>(null) }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { refreshPermissions.value++ }

            val photoPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri -> selectedMedia.value = uri }

            // Solicita o conjunto inicial apenas uma vez.
            LaunchedEffect(Unit) {
                val prefs = context.getSharedPreferences("projeto_super_prefs", MODE_PRIVATE)
                if (!prefs.getBoolean("initial_permissions_requested", false)) {
                    prefs.edit().putBoolean("initial_permissions_requested", true).apply()
                    val requested = mutableListOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    if (Build.VERSION.SDK_INT >= 33) requested.add(Manifest.permission.POST_NOTIFICATIONS)
                    permissionLauncher.launch(requested.toTypedArray())
                }
            }

            LaunchedEffect(Unit) {
                try {
                    val credentials = ControlApi.ensureRegistered(context)
                    deviceId.value = credentials.deviceId
                    pairingCode.value = credentials.pairingCode
                    deviceStatus.value = "Dispositivo registrado. Aguardando pareamento pelo endpoint."
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
                                "Dispositivo pareado. Pedidos do endpoint serão exibidos aqui."
                            } else {
                                "Aguardando pareamento pelo código exibido abaixo."
                            }
                            val requests = response.optJSONArray("requests")
                            if (pendingRequest.value == null && requests != null && requests.length() > 0) {
                                pendingRequest.value = requests.getJSONObject(0)
                            }
                            deviceError.value = null
                        } catch (error: Exception) {
                            deviceError.value = error.message ?: "Falha na conexão."
                        }
                        delay(2500)
                    }
                }
            }

            fun granted(permission: String): Boolean =
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

            val cameraGranted = granted(Manifest.permission.CAMERA)
            val microphoneGranted = granted(Manifest.permission.RECORD_AUDIO)
            val locationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                granted(Manifest.permission.ACCESS_COARSE_LOCATION)
            val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
                granted(Manifest.permission.POST_NOTIFICATIONS)

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
                                "Permissões são solicitadas na primeira abertura. Conceder acesso não inicia captura. " +
                                    "Toda sessão exige confirmação neste aparelho e mantém notificação visível."
                            )
                        }
                        item {
                            Text("Vincular ao endpoint", style = MaterialTheme.typography.titleLarge)
                            Text(deviceStatus.value)
                            if (!devicePaired.value && !pairingCode.value.isNullOrBlank()) {
                                Text("Código de pareamento", style = MaterialTheme.typography.labelLarge)
                                Text(pairingCode.value!!, style = MaterialTheme.typography.headlineMedium)
                                Text("Digite este código no painel do endpoint. Ele expira em 10 minutos.")
                                Button(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                val credentials = ControlApi.renewPairing(context)
                                                pairingCode.value = credentials.pairingCode
                                                deviceError.value = null
                                            } catch (error: Exception) {
                                                deviceError.value = error.message
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Gerar novo código") }
                            }
                            if (devicePaired.value) {
                                Text("Este aparelho está vinculado à conta do painel.")
                            }
                            deviceError.value?.let { Text("Aviso: $it", color = MaterialTheme.colorScheme.error) }
                        }
                        item {
                            Text("Permissões do aparelho", style = MaterialTheme.typography.titleLarge)
                            Text("Verificação: $permissionRefreshCount", style = MaterialTheme.typography.labelSmall)
                            PermissionRow("Câmera", cameraGranted)
                            PermissionRow("Microfone", microphoneGranted)
                            PermissionRow("Localização", locationGranted)
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
                                        Text(app.label, style = MaterialTheme.typography.titleMedium)
                                        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Button(onClick = { openApp(app.packageName) }) { Text("Abrir") }
                                }
                            }
                        }
                    }
                }
            }

            pendingRequest.value?.let { request ->
                val requestId = request.optString("id")
                val action = request.optString("action")
                val description = when (action) {
                    "camera_front" -> "O endpoint está solicitando uma sessão com a câmera frontal."
                    "camera_back" -> "O endpoint está solicitando uma sessão com a câmera traseira."
                    "screen_share" -> "O endpoint está solicitando compartilhamento da tela."
                    "stop_session" -> "O endpoint está solicitando o encerramento da sessão ativa."
                    else -> "O endpoint enviou uma solicitação."
                }
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text("Solicitação do endpoint") },
                    text = { Text("$description\n\nNada será iniciado sem sua confirmação neste aparelho.") },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    if (action == "screen_share") {
                                        deviceError.value = "O compartilhamento de tela requer a confirmação própria do Android; módulo em integração."
                                        ControlApi.decide(context, requestId, false)
                                    } else {
                                        val result = ControlApi.decide(context, requestId, true)
                                        if (action == "camera_front" || action == "camera_back") {
                                            val start = Intent(context, CameraCaptureService::class.java)
                                                .setAction(CameraCaptureService.ACTION_START)
                                                .putExtra(CameraCaptureService.EXTRA_SESSION_ID, result.optString("session_id"))
                                                .putExtra(CameraCaptureService.EXTRA_LENS, if (action == "camera_front") "front" else "back")
                                            ContextCompat.startForegroundService(context, start)
                                        } else if (action == "stop_session") {
                                            context.startService(
                                                Intent(context, CameraCaptureService::class.java)
                                                    .setAction(CameraCaptureService.ACTION_STOP)
                                            )
                                        }
                                    }
                                } catch (error: Exception) {
                                    deviceError.value = error.message ?: "Não foi possível processar a solicitação."
                                } finally {
                                    pendingRequest.value = null
                                }
                            }
                        }) { Text(if (action == "stop_session") "Encerrar sessão" else "Aceitar") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            scope.launch {
                                try { ControlApi.decide(context, requestId, false) }
                                catch (error: Exception) { deviceError.value = error.message }
                                pendingRequest.value = null
                            }
                        }) { Text("Recusar") }
                    }
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Text(if (granted) "Concedida" else "Não concedida")
    }
}
