package com.kerynth.remote.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import org.json.JSONArray
import org.json.JSONObject

class KerynthControlAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        private var instance: KerynthControlAccessibilityService? = null

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            if (!manager.isEnabled) return false
            val expected = ComponentName(context, KerynthControlAccessibilityService::class.java)
                .flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        fun tap(context: Context, xRatio: Double, yRatio: Double): Boolean {
            return instance?.dispatchTap(xRatio, yRatio) ?: false
        }

        fun swipe(
            context: Context,
            startX: Double,
            startY: Double,
            endX: Double,
            endY: Double,
            durationMs: Long
        ): Boolean {
            return instance?.dispatchSwipe(startX, startY, endX, endY, durationMs) ?: false
        }

        fun globalAction(action: String): Boolean {
            val service = instance ?: return false
            val actionId = when (action) {
                "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                else -> return false
            }
            return service.performGlobalAction(actionId)
        }

        fun setFocusedText(text: String): Boolean {
            if (text.length > 2000) return false
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            return try {
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            } finally {
                target.recycle()
                root.recycle()
            }
        }

        fun openApp(context: Context, packageName: String): Boolean {
            val service = instance ?: return false
            if (packageName == context.packageName) return false
            val intent = service.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            return runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                service.startActivity(intent)
                true
            }.getOrDefault(false)
        }

        fun installedApps(context: Context): JSONArray {
            val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = context.packageManager.queryIntentActivities(launchIntent, 0)
            val apps = activities
                .asSequence()
                .mapNotNull { info ->
                    val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (packageName == context.packageName) return@mapNotNull null
                    JSONObject()
                        .put("label", info.loadLabel(context.packageManager).toString())
                        .put("packageName", packageName)
                }
                .distinctBy { it.optString("packageName") }
                .sortedBy { it.optString("label").lowercase() }
                .take(100)
            return JSONArray().also { array -> apps.forEach(array::put) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun dispatchTap(xRatio: Double, yRatio: Double): Boolean {
        val metrics = resources.displayMetrics
        val x = (xRatio.coerceIn(0.0, 1.0) * (metrics.widthPixels - 1)).toFloat()
        val y = (yRatio.coerceIn(0.0, 1.0) * (metrics.heightPixels - 1)).toFloat()
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 90L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun dispatchSwipe(
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        durationMs: Long
    ): Boolean {
        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels - 1).coerceAtLeast(1)
        val height = (metrics.heightPixels - 1).coerceAtLeast(1)
        val path = Path().apply {
            moveTo(
                (startX.coerceIn(0.0, 1.0) * width).toFloat(),
                (startY.coerceIn(0.0, 1.0) * height).toFloat()
            )
            lineTo(
                (endX.coerceIn(0.0, 1.0) * width).toFloat(),
                (endY.coerceIn(0.0, 1.0) * height).toFloat()
            )
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs.coerceIn(120L, 2000L)
                )
            )
            .build()
        return dispatchGesture(gesture, null, null)
    }
}
