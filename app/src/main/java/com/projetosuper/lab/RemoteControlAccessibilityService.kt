package com.projetosuper.lab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.Build
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
                                    action.startsWith("type_text:")) {
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

    private suspend fun executeAuthorizedCommand(request: JSONObject) {
        val requestId = request.optString("id")
        val action = request.optString("action")
        if (requestId.isBlank()) return
        var decided = false
        try {
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
                    withContext(Dispatchers.Main) { RemoteDisplayLauncher.start(this@RemoteControlAccessibilityService, launch) }
                    ControlApi.decide(this, requestId, true)
                    decided = true
                }
                action.startsWith("type_text:") -> {
                    val value = action.substringAfter("type_text:")
                    check(value.isNotBlank() && value.length <= 2000) { "Texto inválido." }
                    check(setFocusedText(value)) {
                        "Não foi possível inserir texto no campo focado do display remoto."
                    }
                    ControlApi.decide(this, requestId, true)
                    decided = true
                }
                action.startsWith("tap:") -> {
                    val parts = action.split(":")
                    check(parts.size == 3) { "Coordenadas inválidas." }
                    val x = parts[1].toIntOrNull() ?: error("Coordenada X inválida.")
                    val y = parts[2].toIntOrNull() ?: error("Coordenada Y inválida.")
                    check(x in 0..1000 && y in 0..1000) { "Toque fora dos limites." }
                    check(tapNormalized(x, y)) { "O Android não aceitou o toque no display remoto." }
                    ControlApi.decide(this, requestId, true)
                    decided = true
                }
            }
        } catch (_: Exception) {
            if (!decided) {
                try { ControlApi.decide(this, requestId, false) } catch (_: Exception) {}
            }
        }
    }

    private fun packageNameOfSelf(): String = packageName

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val displayId = getSharedPreferences("projeto_super_device", MODE_PRIVATE)
            .getInt(RemoteDisplayLauncher.PREF_DISPLAY_ID, android.view.Display.DEFAULT_DISPLAY)
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val displayId = getSharedPreferences("projeto_super_device", MODE_PRIVATE)
            .getInt(RemoteDisplayLauncher.PREF_DISPLAY_ID, android.view.Display.DEFAULT_DISPLAY)
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(100, 1500)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun tapNormalized(x: Int, y: Int): Boolean {
        val prefs = getSharedPreferences("projeto_super_device", MODE_PRIVATE)
        val width = prefs.getInt(RemoteDisplayLauncher.PREF_DISPLAY_WIDTH, 0)
        val height = prefs.getInt(RemoteDisplayLauncher.PREF_DISPLAY_HEIGHT, 0)
        if (width <= 0 || height <= 0) return false
        return tap(
            (x.coerceIn(0, 1000) / 1000f) * width,
            (y.coerceIn(0, 1000) / 1000f) * height
        )
    }

    /** Insere texto somente no campo que está focado naquele instante. */
    fun setFocusedText(value: String): Boolean {
        if (value.isBlank() || value.length > 2000) return false
        val displayId = getSharedPreferences("projeto_super_device", MODE_PRIVATE)
            .getInt(RemoteDisplayLauncher.PREF_DISPLAY_ID, android.view.Display.DEFAULT_DISPLAY)
        val root = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            displayId != android.view.Display.DEFAULT_DISPLAY) {
            val windows = getWindowsOnAllDisplays().get(displayId).orEmpty()
            val targetWindow = windows.firstOrNull { it.isFocused } ?: windows.firstOrNull { it.isActive } ?: windows.firstOrNull()
            targetWindow?.root
        } else {
            rootInActiveWindow
        } ?: return false
        try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFocusedEditable(root)
                ?: return false
            try {
                if (!focused.isEditable || !focused.isVisibleToUser) return false
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }
                return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } finally {
                focused.recycle()
            }
        } finally {
            root.recycle()
        }
    }

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
