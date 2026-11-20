package com.projetosuper.lab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.view.Display
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
        @Volatile var activeWindowBounds: Rect? = null
            private set
        @Volatile var activeWindowPackage: String? = null
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
        // Guarda somente o pacote e os limites da janela ativa para recorte e coordenadas.
        // Nenhum texto ou conteúdo da árvore de acessibilidade é armazenado.
        val root = rootInActiveWindow ?: return
        try {
            val bounds = Rect()
            root.getBoundsInScreen(bounds)
            val activePackage = root.packageName?.toString()
            if (!bounds.isEmpty && !activePackage.isNullOrBlank()) {
                activeWindowBounds = Rect(bounds)
                activeWindowPackage = activePackage
            }
        } catch (_: Exception) {
            activeWindowBounds = null
            activeWindowPackage = null
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() = Unit

    private suspend fun pollAuthorizedCommands() {
        while (serviceScope.isActive) {
            try {
                val prefs = getSharedPreferences("projeto_super_device", MODE_PRIVATE)
                if (prefs.getBoolean("app_control_authorized", false)) {
                    val response = ControlApi.poll(this, "accessibility")
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
                    getSharedPreferences("projeto_super_device", MODE_PRIVATE)
                        .edit().putString("selected_app_package", packageName).apply()
                    activeWindowBounds = null
                    activeWindowPackage = null
                    val launch = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(info.packageName, info.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    withContext(Dispatchers.Main) { startActivity(launch) }
                    check(waitForPackage(packageName)) {
                        "O Android não confirmou a abertura do aplicativo na tela do telefone."
                    }
                }
                action.startsWith("type_text:") -> {
                    val value = action.substringAfter("type_text:")
                    check(value.isNotBlank() && value.length <= 2000) { "Texto inválido." }
                    val inserted = withContext(Dispatchers.Main) { setFocusedText(value) }
                    check(inserted) { "Não foi encontrado um campo de texto focado no aplicativo aberto no telefone." }
                }
                action.startsWith("tap:") -> {
                    val parts = action.split(":")
                    check(parts.size == 3) { "Coordenadas inválidas." }
                    val x = parts[1].toIntOrNull() ?: error("Coordenada X inválida.")
                    val y = parts[2].toIntOrNull() ?: error("Coordenada Y inválida.")
                    check(x in 0..1000 && y in 0..1000) { "Toque fora dos limites." }
                    check(tapNormalizedAndWait(x, y)) {
                        "O Android não concluiu o toque na tela do telefone."
                    }
                }
                action == "remote_home" -> {
                    check(withContext(Dispatchers.Main) {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }) { "O Android não conseguiu executar Início na tela do telefone." }
                }
                action == "remote_back" -> {
                    check(withContext(Dispatchers.Main) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }) { "O Android não conseguiu executar Voltar na tela do telefone." }
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

    private suspend fun waitForPackage(packageName: String): Boolean {
        repeat(20) {
            val found = withContext(Dispatchers.Main) {
                val root = rootInActiveWindow
                try { root?.packageName?.toString() == packageName }
                finally { root?.recycle() }
            }
            if (found) return true
            delay(150)
        }
        return false
    }

    private fun setFocusedText(value: String): Boolean {
        if (value.isBlank() || value.length > 2000) return false
        val root = rootInActiveWindow ?: return false
        try {
            val selectedPackage = getSharedPreferences("projeto_super_device", MODE_PRIVATE)
                .getString("selected_app_package", "").orEmpty()
            if (selectedPackage.isBlank() || root.packageName?.toString() != selectedPackage) return false
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

    private suspend fun tapNormalizedAndWait(x: Int, y: Int): Boolean = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow ?: return@withContext false
        val bounds = Rect()
        val selectedPackage = getSharedPreferences("projeto_super_device", MODE_PRIVATE)
            .getString("selected_app_package", "").orEmpty()
        try {
            if (selectedPackage.isBlank() || root.packageName?.toString() != selectedPackage) {
                return@withContext false
            }
            root.getBoundsInScreen(bounds)
        } finally {
            root.recycle()
        }
        if (bounds.isEmpty) return@withContext false
        val targetX = bounds.left + (x.coerceIn(0, 1000) / 1000f * bounds.width()).toInt()
        val targetY = bounds.top + (y.coerceIn(0, 1000) / 1000f * bounds.height()).toInt()
        val path = Path().apply { moveTo(targetX.toFloat(), targetY.toFloat()) }
        val gestureBuilder = GestureDescription.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            gestureBuilder.setDisplayId(Display.DEFAULT_DISPLAY)
        }
        val gesture = gestureBuilder
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        suspendCancellableCoroutine { continuation ->
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