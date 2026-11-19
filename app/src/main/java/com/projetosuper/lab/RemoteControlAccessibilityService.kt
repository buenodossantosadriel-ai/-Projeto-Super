package com.projetosuper.lab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Serviço opt-in: o usuário precisa ativá-lo manualmente nas configurações do Android.
 * Não consulta árvore de acessibilidade nem captura texto/conteúdo de outros apps.
 * A implementação de comandos remotos será conectada ao fluxo autenticado e consentido.
 */
class RemoteControlAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intencionalmente não inspeciona eventos ou conteúdo de aplicativos.
    }

    override fun onInterrupt() = Unit

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    durationMs.coerceIn(100, 1500)
                )
            )
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
}
