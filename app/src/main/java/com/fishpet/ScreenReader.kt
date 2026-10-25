package com.fishpet

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 一次性读取“用户当前正在看的那个 App”的界面文字。
 * 只在用户主动触发时调用；不读密码框；默认跳过银行 / 支付 / 密码管理 / 系统设置；
 * 只取文字（text / contentDescription），不取图片和画面；读出来的内容只拼进这一次请求，不落盘、不写日志。
 */
object ScreenReader {
    sealed class Result {
        data class Ok(val appLabel: String, val pkg: String, val text: String, val truncated: Boolean) : Result()
        data class Err(val code: String, val detail: String = "") : Result()
    }

    private class Item(val top: Int, val left: Int, val text: String)

    private val BLOCK_EXACT = setOf(
        "com.android.settings",
        "com.eg.android.AlipayGphone",   // 支付宝
        "com.unionpay",                  // 云闪付
        "com.icbc", "com.chinamworld.main", "com.android.bankabc", "com.bankcomm.Bankcomm",
        "com.chinamworld.bocmbci", "cmb.pb", "com.cmbchina.ccd.pluto.cmbActivity",
        "com.x8bit.bitwarden", "com.lastpass.lpandroid", "com.onepassword.android",
        "com.google.android.apps.authenticator2", "com.azure.authenticator",
    )
    private val BLOCK_WORDS = listOf(
        "bank", "pay", "wallet", "password", "passwd", "authenticator", "keepass", "bitwarden", "lastpass", "vault", "totp",
    )

    fun isSensitive(pkg: String): Boolean {
        if (pkg in BLOCK_EXACT) return true
        val low = pkg.lowercase()
        return BLOCK_WORDS.any { low.contains(it) }
    }

    fun read(ctx: Context, maxChars: Int = 2500): Result {
        val svc = ScreenReaderService.instance ?: return Result.Err("NOT_ENABLED")
        val root = pickRoot(svc, ctx.packageName) ?: return Result.Err("NO_WINDOW")
        try {
            val pkg = root.packageName?.toString() ?: ""
            val label = appLabel(ctx, pkg)
            if (PetPrefs.screenBlockSensitive(ctx) && isSensitive(pkg)) return Result.Err("SENSITIVE", label)

            val items = ArrayList<Item>()
            collect(root, items, IntArray(1), 0)
            if (items.isEmpty()) return Result.Err("EMPTY", label)
            items.sortWith(compareBy({ it.top }, { it.left }))

            val sb = StringBuilder()
            var last = ""
            var truncated = false
            for (item in items) {
                if (item.text == last) continue
                if (sb.length + item.text.length + 1 > maxChars) { truncated = true; break }
                sb.append(item.text).append('\n')
                last = item.text
            }
            return Result.Ok(label, pkg, sb.toString().trim(), truncated)
        } finally {
            recycleCompat(root)
        }
    }

    /**
     * 选出“最上层的、不是本 App 的应用窗口”。
     * 聊天面板是可聚焦的悬浮窗，打开后它会变成“当前活动窗口”，所以不能只用 rootInActiveWindow。
     */
    private fun pickRoot(svc: ScreenReaderService, ownPkg: String): AccessibilityNodeInfo? {
        val wins = try { svc.windows } catch (_: Exception) { emptyList<AccessibilityWindowInfo>() }
        var best: AccessibilityNodeInfo? = null
        var bestLayer = -1
        for (w in wins) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val r = w.root ?: continue
            val p = r.packageName?.toString()
            if (p == null || p == ownPkg) { recycleCompat(r); continue }
            if (w.layer > bestLayer) {
                recycleCompat(best)
                best = r
                bestLayer = w.layer
            } else {
                recycleCompat(r)
            }
        }
        if (best != null) return best
        val r = svc.rootInActiveWindow ?: return null
        if (r.packageName?.toString() == ownPkg) { recycleCompat(r); return null }
        return r
    }

    private fun collect(node: AccessibilityNodeInfo, out: ArrayList<Item>, count: IntArray, depth: Int) {
        if (count[0] >= 700 || depth > 45) return
        count[0]++
        if (!node.isVisibleToUser || node.isPassword) return
        val t = node.text?.toString()?.trim().orEmpty()
        val d = node.contentDescription?.toString()?.trim().orEmpty()
        val s = if (t.isNotEmpty()) t else d
        if (s.isNotEmpty()) {
            val r = Rect()
            node.getBoundsInScreen(r)
            out.add(Item(r.top, r.left, s.take(240)))
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            try { collect(c, out, count, depth + 1) } finally { recycleCompat(c) }
        }
    }

    private fun appLabel(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    @Suppress("DEPRECATION")
    private fun recycleCompat(n: AccessibilityNodeInfo?) {
        if (Build.VERSION.SDK_INT < 33) {
            try { n?.recycle() } catch (_: Exception) {}
        }
    }
}
