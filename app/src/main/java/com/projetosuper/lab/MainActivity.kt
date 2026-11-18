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
            val devicePaired = remember { mutableStateOf(false) }
            val deviceStatus = remember { mutableStateOf("Registrando dispositivo seguro...") }
            val deviceError = remember { mutableStateOf<String?>(null) }
            val pendingRequest = remember { mutableStateOf<JSONObject?>(null) }
            val pendingScreenRequestId = remember { mutableStateOf<String?>(null) }

            val screenCaptureLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val requestId = pendingScreenRequestId.value
                pendingScreenRequestId.value = null
                if (requestId != null) {
                    scope.launch {
                        try {
                            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                                val response = ControlApi.decide(context, requestId, true)
                                val start = Intent(context, ScreenCaptureService::class.java)
                                    .setAction(ScreenCaptureService.ACTION_START)
                                    .putExtra(ScreenCaptureService.EXTRA_SESSION_ID, response.optString("session_id"))
                                    .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                                    .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                                ContextCompat.startForegroundService(context, start)
                            } else {
                                ControlApi.decide(context, requestId, false)
                                deviceError.value = "Compartilhamento de tela não autorizado pelo Android."
                            }
                        } catch (error: Exception) {
                            deviceError.value = error.message ?: "Não foi possível iniciar o compartilhamento."
                        }
                    }
                }
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) {
                context.getSharedPreferences("projeto_super_prefs", MODE_PRIVATE)
                    .edit().putBoolean("initial_permissions_requested", true).apply()
                refreshPermissions.value++
            }

            val photoPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri -> selectedMedia.value = uri }

            // SOLICITA CÂMERA + MICROFONE + NOTIFICAÇÃO (se Android 13+) NA PRIMEIRA ABERTURA
            LaunchedEffect(Unit) {
                val prefs = context.getSharedPreferences("projeto_super_prefs", MODE_PRIVATE)
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
                                "Dispositivo vinculado. Pedidos serão exibidos aqui e exigem sua confirmação."
                            } else {
                                "Dispositivo registrado. Aguardando aprovação no painel administrativo."
                            }
                            val requests = response.optJSONArray("requests")
                            if (pendingRequest.value == null && requests != null && requests.length() > 0) {
                                for (i in 0 until requests.length()) {
                                    val candidate = requests.getJSONObject(i)
                                    if (candidate.optString("action") != "stop_session") {
                                        pendingRequest.value = candidate
                                        break
                                    }
                                }
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
            val notificationsGranted = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

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
                                "Permissões de câmera, microfone e notificação solicitadas na primeira abertura. " +
                                    "Compartilhamento de tela terá confirmação nativa do sistema quando solicitado."
                            )
                        }
                        item {
                            Text("Vincular ao endpoint", style = MaterialTheme.typography.titleLarge)
                            Text(deviceStatus.value)
                            Text(
                                if (devicePaired.value) "Este aparelho está vinculado à conta administrativa."
                                else "O administrador precisa aprovar este aparelho no dashboard. Nenhum código é necessário."
                            )
                            Button(
                                onClick = {
                                    context.startService(
                                        Intent(context, CameraCaptureService::class.java).setAction(CameraCaptureService.ACTION_STOP)
                                    )
                                    context.startService(
                                        Intent(context, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP)
                                    )
                                    context.startService(
                                        Intent(context, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_STOP)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Encerrar qualquer sessão neste aparelho") }
                            deviceError.value?.let { Text("Aviso: $it", color = MaterialTheme.colorScheme.error) }
                        }
                        item {
                            Text("Permissões do aparelho", style = MaterialTheme.typography.titleLarge)
                            Text("Verificação: $permissionRefreshCount", style = MaterialTheme.typography.labelSmall)
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
                                "Se uma permissão for negada ou revogada, altere‑a manualmente nas configurações do Android.",
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
                            Text("Aplicativos encontrados:
                                 
