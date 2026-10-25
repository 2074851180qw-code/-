package com.fishpet

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务。只做两件事：
 * 1) 让 ScreenReader 能在“用户主动点了看屏幕”的那一刻读取当前界面的文字；
 * 2) （可选，由用户开关控制）告诉桌宠前台 App 的包名变了——只有包名，不含任何屏幕内容。
 * 它不会点击、滑动或控制手机，也不会在后台持续读取内容或联网。
 */
class ScreenReaderService : AccessibilityService() {
    companion object {
        @Volatile var instance: ScreenReaderService? = null
        @Volatile var onForegroundApp: ((String) -> Unit)? = null

        /** 系统设置里是否已经打开了本服务（用于主界面显示状态）。 */
        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, ScreenReaderService::class.java)
            val raw = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return raw.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        if (pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        onForegroundApp?.invoke(pkg)
    }
}
