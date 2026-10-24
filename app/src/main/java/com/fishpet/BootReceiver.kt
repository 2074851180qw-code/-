package com.fishpet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** 开机后按用户设置自动拉起桌宠。部分系统会拦截后台启动前台服务，所以包了 try/catch。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!PetPrefs.autoStart(context)) return
        if (!Settings.canDrawOverlays(context)) return
        try {
            PetService.start(context)
        } catch (_: Exception) {
            // Android 12+ 的后台启动限制：启动不了就算了，用户打开 App 再点启动
        }
    }
}
