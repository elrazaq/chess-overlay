package com.chess.overlay.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class ChessAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ChessA11yService"

        @Volatile
        var instance: ChessAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance != null

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${ChessAccessibilityService::class.java.canonicalName}"
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedServiceName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "ChessAccessibilityService terhubung dan siap menginjeksi gerakan catur!")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.i(TAG, "ChessAccessibilityService dimatikan")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Tidak perlu memproses event tampilan untuk menghemat CPU & baterai
    }

    override fun onInterrupt() {
        Log.w(TAG, "ChessAccessibilityService diinterupsi")
    }

    /**
     * Menginjeksi pergerakan menggeser bidak catur (Drag) dari petak asal ke petak tujuan
     */
    fun dispatchDrag(fromX: Float, fromY: Float, toX: Float, toY: Float, callback: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callback?.invoke(false)
            return
        }

        try {
            val path = Path().apply {
                moveTo(fromX, fromY)
                lineTo(toX, toY)
            }

            val stroke = GestureDescription.StrokeDescription(path, 0L, 180L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Gesture drag berhasil diinjeksi ($fromX,$fromY -> $toX,$toY)")
                    callback?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Gesture drag dibatalkan oleh sistem")
                    callback?.invoke(false)
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menginjeksi gesture: ${e.message}", e)
            callback?.invoke(false)
        }
    }

    /**
     * Menginjeksi sentuhan (Tap) pada titik layar tertentu
     */
    fun dispatchTap(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callback?.invoke(false)
            return
        }

        try {
            val path = Path().apply {
                moveTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    callback?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    callback?.invoke(false)
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menginjeksi tap: ${e.message}", e)
            callback?.invoke(false)
        }
    }
}
