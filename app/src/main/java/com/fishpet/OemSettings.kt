package com.fishpet

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** 尝试打开各家国产 ROM 的“自启动/后台管理”页面；都打不开就退回系统的应用详情页。 */
object OemSettings {
    private val candidates = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
    )

    /** 返回 true 表示打开了厂商页面；false 表示只打开了通用的应用详情页。 */
    fun openAutoStart(a: Activity): Boolean {
        for ((pkg, cls) in candidates) {
            try {
                a.startActivity(Intent().setComponent(ComponentName(pkg, cls)))
                return true
            } catch (_: Exception) { /* 这家没有，试下一家 */ }
        }
        try {
            a.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}"))
            )
        } catch (_: Exception) {}
        return false
    }
}
