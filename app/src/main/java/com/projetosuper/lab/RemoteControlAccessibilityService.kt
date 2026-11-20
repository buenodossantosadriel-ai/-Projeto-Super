package com.projetosuper.lab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.app.ActivityOptions
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONObject

/**
 * Serviço opt-in. O usuário ativa manualmente nas configurações do Android.
 * Não coleta eventos ou conteúdo em segundo plano. Consulta a árvore da tela apenas
 * quando recebe um comando de texto autorizado e explícito.
 */
class RemoteControlAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var activeInstance: RemoteControlAccessibilityService? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        serviceScope.launch { pollAuthorizedCommands() }
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Eventos não são lidos nem armazenados.
    }

    override fun onInterrupt() = Unit

    private suspend fun pollAuthorizedCommands() {
        while (serviceScope.isActive) {
            try {
                val prefs = getSharedPreferences("projeto_super_device", MODE_PRIVATE)
                if (prefs.getBoolean("app_control_authorized", false)) {
                    val response = ControlApi.poll(this)
                    if (response.optBoolean("paired")) {
                        val requests = response.optJSONArray("requests")
                        if (requests != null) {
                            for (index in 0 until requests.length()) {
                                val request = requests.optJSONObject(index) ?: continue
                                val action = request.optString("action")
                                if (action.startsWith("open_app:") ||
                                    action.startsWith("tap:") ||
                                    action.startsWith("type_text:") ||
                                    action == "remote_back" || action == "remote_home") {
                                    // Processa uma solicitação por ciclo para evitar ações concorrentes
                                    // e preservar a ordem em que o administrador as enviou.
                                    executeAuthorizedCommand(request)
                                    break
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Falhas transitórias de rede não encerram o serviço.
            }
            delay(2500)
        }
    }

    suspend fun executeAuthorizedCommand(request: JSONObject): Boolean {
        val requestId = request.optString("id")
        val action = request.optString("action")
        if (requestId.isBlank()) return false
        try {
            val displayId = ScreenCaptureService.remoteDisplayId
                ?: error("A captura do display virtual não está ativa.")
            when {
                action.startsWith("open_app:") -> {
                    val packageName = action.substringAfter("open_app:")
                    check(packageName != packageNameOfSelf() &&
                        packageName.matches(Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+"))) {
                        "Pacote de aplicativo inválido."
                    }
                    val launcherIntent = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(packageName)
                    val info = packageManager.queryIntentActivities(launcherIntent, 0).firstOrNull()?.activityInfo
                        ?: error("Aplicativo não disponível para abertura.")
                    val launch = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(info.packageName, info.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
                    withContext(Dispatchers.Main) { startActivity(launch, options.toBundle()) }
                    check(waitForPackageOnDisplay(packageName, displayId)) {
                        "O Android não confirmou a abertura do aplicativo no display remoto."
                    }
                }
                action.startsWith("type_text:") -> {
                    val value = action.substringAfter("type_text:")
                    check(value.isNotBlank() && value.length <= 2000) { "Texto inválido." }
                    val inserted = withContext(Dispatchers.Main) { setFocusedTextOnDisplay(value, displayId) }
                    check(inserted) { "Não foi encontrado um campo de texto focado no aplicativo remoto." }
                }
                action.startsWith("tap:") -> {
                    val parts = action.split(":")
                    check(parts.size == 3) { "Coordenadas inválidas." }
                    val x = parts[1].toIntOrNull() ?: error("Coordenada X inválida.")
                    val y = parts[2].toIntOrNull() ?: error("Coordenada Y inválida.")
                    check(x in 0..1000 && y in 0..1000) { "Toque fora dos limites." }
                    check(tapNormalizedAndWait(x, y, displayId)) {
                        "O Android não concluiu o toque no display remoto."
                    }
                }
                action == "remote_home" -> {
                    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
                    withContext(Dispatchers.Main) { startActivity(home, options.toBundle()) }
                    check(waitForAnyWindowOnDisplay(displayId)) {
                        "O Android não confirmou a tela inicial no display remoto."
                    }
                }
                action == "remote_back" -> {
                    error("O Android não oferece Voltar isolado por display pela ação global de acessibilidade. O comando foi bloqueado para não afetar a tela física.")
                }
                else -> error("Comando remoto não reconhecido.")
            }
            ControlApi.decide(this, requestId, true)
            return true
        } catch (error: Exception) {
            try { ControlApi.decide(this, requestId, false) } catch (_: Exception) {}
            android.util.Log.e("ProjetoSuperRemote", "Comando remoto recusado: ${error.message}", error)
            return false
        }
    }

    private suspend fun waitForPackageOnDisplay(packageName: String, displayId: Int): Boolean {
        repeat(20) {
            val found = withContext(Dispatchers.Main) {
                val windows = getWindowsOnAllDisplays()[displayId].orEmpty()
                windows.any { window ->
                    val root = window.root
                    try { root?.packageName?.toString() == packageName }
                    finally { root?.recycle() }
                }
            }
            if (found) return true
            delay(150)
        }
        return false
    }

    private suspend fun waitForAnyWindowOnDisplay(displayId: Int): Boolean {
        repeat(20) {
            val found = withContext(Dispatchers.Main) {
                getWindowsOnAllDisplays()[displayId].orEmpty().isNotEmpty()
            }
            if (found) return true
            delay(150)
        }
        return false
    }

    private fun setFocusedTextOnDisplay(value: String, displayId: Int): Boolean {
        if (value.isBlank() || value.length > 2000) return false
        val windows = getWindowsOnAllDisplays()[displayId].orEmpty()
        for (window in windows) {
            val root = window.root ?: continue
            try {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: findFocusedEditable(root)
                    ?: continue
                try {
                    if (!focused.isEditable || !focused.isVisibleToUser) continue
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                    }
                    if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
                } finally {
                    focused.recycle()
                }
            } finally {
                root.recycle()
            }
        }
        return false
    }

    private suspend fun tapNormalizedAndWait(x: Int, y: Int, displayId: Int): Boolean {
        val display = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(displayId) ?: return false
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return false
        val path = Path().apply {
            moveTo((x.coerceIn(0, 1000) / 1000f) * metrics.widthPixels,
                (y.coerceIn(0, 1000) / 1000f) * metrics.heightPixels)
        }
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return suspendCancellableCoroutine { continuation ->
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, null)
            if (!accepted && continuation.isActive) continuation.resume(false)
        }
    }

    private fun packageNameOfSelf(): String = packageName

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable && node.isVisibleToUser) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findFocusedEditable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

}