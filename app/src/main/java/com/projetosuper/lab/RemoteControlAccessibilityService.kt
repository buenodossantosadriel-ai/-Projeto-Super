package com.projetosuper.lab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Serviço opt-in. O usuário ativa manualmente nas configurações do Android.
 * A árvore de acessibilidade só é consultada no momento de um comando remoto autorizado;
 * eventos recebidos não são lidos, armazenados ou enviados ao servidor.
 */
class RemoteControlAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var activeInstance: RemoteControlAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Não coleta conteúdo de eventos. A árvore só é acessada sob comando explícito.
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
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(100, 1500)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun tapNormalized(x: Int, y: Int): Boolean {
        val metrics = resources.displayMetrics
        return tap(
            (x.coerceIn(0, 1000) / 1000f) * metrics.widthPixels,
            (y.coerceIn(0, 1000) / 1000f) * metrics.heightPixels
        )
    }

    /** Insere texto somente no campo que está focado naquele instante. */
    fun setFocusedText(value: String): Boolean {
        if (value.isBlank() || value.length > 2000) return false
        val root = rootInActiveWindow ?: return false
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

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
}
