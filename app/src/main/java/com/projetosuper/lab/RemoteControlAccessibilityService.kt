package com.projetosuper.lab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import org.json.JSONObject

/**
 * Serviço opt-in do Projeto Super.
 * Só captura a tela ou executa comandos durante uma sessão autorizada e ativa.
 * A captura usa a API oficial takeScreenshot do próprio AccessibilityService e
 * envia quadros JPEG; não cria display virtual nem usa MediaProjection.
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

    /**
     * Captura somente o display principal autorizado pelo Android.
     * A API exige a capability canTakeScreenshot declarada no XML do serviço.
     */
    suspend fun captureJpegFrame(): ByteArray? = withContext(Dispatchers.Main) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return@withContext null
        suspendCancellableCoroutine { continuation ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    var hardwareBitmap: Bitmap? = null
                    var argbBitmap: Bitmap? = null
                    var scaledBitmap: Bitmap? = null
                    try {
                        hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            ?: error("O Android não disponibilizou o buffer da captura.")
                        argbBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            ?: error("Não foi possível converter a captura para bitmap.")
                        hardwareBitmap.recycle()
                        hardwareBitmap = null

                        val targetWidth = minOf(800, argbBitmap.width)
                        val targetHeight = (argbBitmap.height * (targetWidth.toFloat() / argbBitmap.width))
                            .toInt().coerceAtLeast(1)
                        scaledBitmap = if (targetWidth == argbBitmap.width) argbBitmap
                            else Bitmap.createScaledBitmap(argbBitmap, targetWidth, targetHeight, true)

                        // Gestos usam coordenadas do display real, não do JPEG reduzido.
                        getSharedPreferences("projeto_super_device", MODE_PRIVATE).edit()
                            .putInt(RemoteDisplayLauncher.PREF_DISPLAY_WIDTH, argbBitmap.width)
                            .putInt(RemoteDisplayLauncher.PREF_DISPLAY_HEIGHT, argbBitmap.height)
                            .apply()

                        val output = ByteArrayOutputStream()
                        check(scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 58, output)) {
                            "Não foi possível codificar o quadro JPEG."
                        }
                        if (continuation.isActive) continuation.resume(output.toByteArray())
                    } catch (error: Exception) {
                        android.util.Log.w("ProjetoSuperScreen", "Falha ao capturar JPEG: ${error.message}")
                        if (continuation.isActive) continuation.resume(null)
                    } finally {
                        hardwareBuffer.close()
                        hardwareBitmap?.recycle()
                        if (scaledBitmap !== argbBitmap) scaledBitmap?.recycle()
                        argbBitmap?.recycle()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    android.util.Log.w("ProjetoSuperScreen", "A captura de tela foi recusada pelo Android. Código: $errorCode")
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }
    }

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
                                    action.startsWith("swipe:") ||
                                    action.startsWith("type_text:") || action == "remote_back") {
                                    executeAuthorizedCommand(request)
                                    break
                                }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                android.util.Log.w("ProjetoSuperControl", "Falha transitória ao consultar comandos: ${error.message}")
            }
            delay(1500)
        }
    }

    private suspend fun executeAuthorizedCommand(request: JSONObject) {
        val requestId = request.optString("id")
        val action = request.optString("action")
        if (requestId.isBlank()) return
        var decided = false
        try {
            check(getSharedPreferences("projeto_super_device", MODE_PRIVATE)
                .getBoolean("app_control_authorized", false)) {
                "A autorização geral do Projeto Super foi revogada."
            }
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
                    withContext(Dispatchers.Main) {
                        getSharedPreferences("projeto_super_device", MODE_PRIVATE)
                            .edit().putString("remote_target_package", packageName).apply()
                        RemoteDisplayLauncher.start(this@RemoteControlAccessibilityService, launch)
                    }
                    check(waitForAppOnDefaultDisplay(packageName)) {
                        "O Android não confirmou que o aplicativo solicitado abriu na tela principal."
                    }
                    ControlApi.decide(this, requestId, true)
                    decided = true
                }
                action.startsWith("type_text:") -> {
                    val value = action.substringAfter("type_text:")
                    check(value.isNotBlank() && value.length <= 2000) { "Texto inválido." }
                    check(setFocusedText(value)) {
                        "Não foi possível inserir texto no campo focado do aplicativo."
                    }
                    ControlApi.decide(this, requestId, true)
                    decided = true
                }
                action == "remote_back" -> {
                    check(withContext(Dispatchers.Main) { performGlobalAction(GLOBAL_ACTION_BACK) }) {
                        "O Android não aceitou o comando Voltar."
                    }
                    ControlApi.decide(this, requestId, true)
                    decided = true
                }
                action.startsWith("swipe:") -> {
                    val parts = action.split(":")
                    check(parts.size == 6) { "Parâmetros de rolagem inválidos." }
                    val values = parts.drop(1).map { it.toIntOrNull() ?: error("Coordenada de rolagem inválida.") }
                    check(values.take(4).all { it in 0..1000 }) { "Rolagem fora dos limites." }
                    val duration = values[4].toLong().coerceIn(100L, 1500L)
                    check(swipeNormalized(values[0], values[1], values[2], values[3], duration)) {
                        "O Android não aceitou a rolagem."
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
                    check(tapNormalized(x, y)) { "O Android não aceitou o toque." }
                    ControlApi.decide(this, requestId, true)
                    decided = true
                }
            }
        } catch (error: Exception) {
            android.util.Log.e(
                "ProjetoSuperControl",
                "Falha ao executar comando remoto '$action': ${error.message}",
                error
            )
            if (!decided) {
                try { ControlApi.decide(this, requestId, false) } catch (ackError: Exception) {
                    android.util.Log.e("ProjetoSuperControl", "Falha ao informar rejeição do comando.", ackError)
                }
            }
        }
    }

    private suspend fun waitForAppOnDefaultDisplay(targetPackage: String): Boolean =
        withContext(Dispatchers.Main) {
            repeat(24) {
                val targetVisible = windows.any { window ->
                    if (!window.isFocused && !window.isActive) return@any false
                    val root = window.root ?: return@any false
                    try {
                        root.packageName?.toString() == targetPackage
                    } finally {
                        root.recycle()
                    }
                }
                if (targetVisible) return@withContext true
                delay(250)
            }
            false
        }

    private fun packageNameOfSelf(): String = packageName

    private suspend fun dispatchGestureOnDefaultDisplay(path: Path, durationMs: Long): Boolean =
        withContext(Dispatchers.Main) {
            if (!getSharedPreferences("projeto_super_device", MODE_PRIVATE)
                    .getBoolean("app_control_authorized", false)) return@withContext false
            val expectedPackage = getSharedPreferences("projeto_super_device", MODE_PRIVATE)
                .getString("remote_target_package", null) ?: return@withContext false
            val selectedAppFocused = windows.any { window ->
                if (!window.isFocused && !window.isActive) return@any false
                val root = window.root ?: return@any false
                try {
                    root.packageName?.toString() == expectedPackage
                } finally {
                    root.recycle()
                }
            }
            if (!selectedAppFocused) return@withContext false
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(80, 1500)))
                .build()
            suspendCancellableCoroutine { continuation ->
                val accepted = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    },
                    Handler(mainLooper)
                )
                if (!accepted && continuation.isActive) continuation.resume(false)
            }
        }

    suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGestureOnDefaultDisplay(path, 80)
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatchGestureOnDefaultDisplay(path, durationMs)
    }

    suspend fun swipeNormalized(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val prefs = getSharedPreferences("projeto_super_device", MODE_PRIVATE)
        val width = prefs.getInt(RemoteDisplayLauncher.PREF_DISPLAY_WIDTH, 0)
        val height = prefs.getInt(RemoteDisplayLauncher.PREF_DISPLAY_HEIGHT, 0)
        if (width <= 0 || height <= 0) return false
        return swipe(
            (x1.coerceIn(0, 1000) / 1000f) * width,
            (y1.coerceIn(0, 1000) / 1000f) * height,
            (x2.coerceIn(0, 1000) / 1000f) * width,
            (y2.coerceIn(0, 1000) / 1000f) * height,
            durationMs
        )
    }

    suspend fun tapNormalized(x: Int, y: Int): Boolean {
        val prefs = getSharedPreferences("projeto_super_device", MODE_PRIVATE)
        val width = prefs.getInt(RemoteDisplayLauncher.PREF_DISPLAY_WIDTH, 0)
        val height = prefs.getInt(RemoteDisplayLauncher.PREF_DISPLAY_HEIGHT, 0)
        if (width <= 0 || height <= 0) return false
        return tap(
            (x.coerceIn(0, 1000) / 1000f) * width,
            (y.coerceIn(0, 1000) / 1000f) * height
        )
    }

    /** Insere texto somente no campo editável focado do aplicativo atualmente controlado. */
    fun setFocusedText(value: String): Boolean {
        if (value.isBlank() || value.length > 2000) return false
        if (!getSharedPreferences("projeto_super_device", MODE_PRIVATE)
                .getBoolean("app_control_authorized", false)) return false
        val expectedPackage = getSharedPreferences("projeto_super_device", MODE_PRIVATE)
            .getString("remote_target_package", null) ?: return false
        val targetWindow = windows.firstOrNull { window ->
            if (!window.isFocused && !window.isActive) return@firstOrNull false
            val candidateRoot = window.root ?: return@firstOrNull false
            try {
                candidateRoot.packageName?.toString() == expectedPackage
            } finally {
                candidateRoot.recycle()
            }
        } ?: return false
        val root = targetWindow.root ?: return false
        try {
            if (root.packageName?.toString() != expectedPackage) return false
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
