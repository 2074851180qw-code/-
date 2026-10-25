package com.fishpet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** 设置页：引导用户授权（悬浮窗 / 通知 / 电池优化 / 自启动）、保存 API Key、启停桌宠。 */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var keyInput: EditText

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "蓝色大肥鱼"
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        fun button(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply {
                text = label; setAllCaps(false); setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun label(text: String, size: Float = 14f) {
            root.addView(TextView(this).apply { this.text = text; textSize = size; setPadding(0, dp(12), 0, dp(4)) })
        }

        status = TextView(this).apply { textSize = 15f; setLineSpacing(0f, 1.3f) }
        root.addView(status)

        label("① 授权（按顺序点）", 16f)
        button("1. 允许悬浮窗") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("2. 允许通知（Android 13+）") {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else toast("这个系统版本不需要单独授权")
        }
        button("3. 忽略电池优化") {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        button("4. 自启动 / 后台运行（国产 ROM 必做）") {
            if (!OemSettings.openAutoStart(this)) toast("没找到厂商页面，已打开应用详情，请手动允许自启动和后台运行")
        }

        label("② DeepSeek API Key", 16f)
        keyInput = EditText(this).apply {
            hint = "sk-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        root.addView(keyInput)
        button("保存 Key（加密存储）") {
            val k = keyInput.text.toString().trim()
            if (k.isEmpty()) { toast("先填 Key"); return@button }
            SecureStore.setApiKey(this, k)
            keyInput.setText("")
            toast("已保存")
            refresh()
        }
        button("测试余额") {
            lifecycleScope.launch {
                val b = DeepSeekApi.balance(SecureStore.apiKeyAsync(this@MainActivity))
                toast(DeepSeekApi.describe(b))
            }
        }

        label("③ 桌宠", 16f)
        root.addView(CheckBox(this).apply {
            text = "开机自动启动桌宠"
            isChecked = PetPrefs.autoStart(this@MainActivity)
            setOnCheckedChangeListener { _, on -> PetPrefs.setAutoStart(this@MainActivity, on) }
        })
        button("启动桌宠") {
            if (!Settings.canDrawOverlays(this)) {
                toast("先授权悬浮窗")
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                PetService.start(this)
                toast("大肥鱼出发啦")
                root.postDelayed({ refresh() }, 600)
            }
        }
        button("关闭桌宠") {
            PetService.stop(this)
            root.postDelayed({ refresh() }, 300)
        }

        label("④ 性格与记忆", 16f)
        root.addView(TextView(this).apply {
            text = "性格决定它说话的样子；记忆是它长期记住的关于你的事。"
            textSize = 12f
            setPadding(0, 0, 0, dp(6))
        })

        val personaInput = EditText(this).apply {
            setText(PetPrefs.persona(this@MainActivity))
            minLines = 3
            gravity = Gravity.TOP
            textSize = 13f
        }
        root.addView(personaInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        button("保存性格") {
            PetPrefs.setPersona(this, personaInput.text.toString())
            toast("性格已保存，下轮对话生效")
        }
        button("恢复默认性格") {
            PetPrefs.resetPersona(this)
            personaInput.setText(PetPrefs.persona(this))
            toast("已恢复默认")
        }

        label("它记住的事", 14f)
        val memView = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        }
        memView.setOnClickListener {
            val list = PetPrefs.memories(this)
            if (list.isEmpty()) { toast("还没有记忆"); return@setOnClickListener }
            android.app.AlertDialog.Builder(this)
                .setTitle("点一条删除")
                .setItems(list.toTypedArray()) { _, i ->
                    PetPrefs.removeMemory(this, list[i]); toast("已删除")
                    refreshMemories(memView)
                }
                .show()
        }
        root.addView(memView)

        val memInput = EditText(this).apply {
            hint = "手动加一条，比如：我叫小明，喜欢猫"
            setSingleLine()
            textSize = 13f
        }
        root.addView(memInput)
        button("添加记忆") {
            val t = memInput.text.toString().trim()
            if (t.isEmpty()) { toast("先写点内容"); return@button }
            PetPrefs.addMemory(this, t)
            memInput.setText("")
            refreshMemories(memView)
            toast("记住了")
        }
        button("清空全部记忆") {
            PetPrefs.clearMemories(this)
            refreshMemories(memView)
            toast("已清空")
        }
        refreshMemories(memView)

        label("⑤ 屏幕感知（无障碍）", 16f)
        root.addView(TextView(this).apply {
            text = "开启后，只有你主动点“看屏幕”（菜单里，或聊天里的“附带屏幕”）时，才会一次性读取当前界面的文字，" +
                "并随这条消息发给 DeepSeek，用来回答你的问题。\n" +
                "• 不会在后台持续读取，也不会把屏幕内容存进长期记忆\n" +
                "• 不读密码框；默认不读银行 / 支付 / 密码管理器 / 系统设置\n" +
                "• 只读文字，看不到图片和视频画面；不会点击或控制你的手机\n" +
                "• 屏幕上别人的聊天内容、验证码等也会被一起发出去，敏感页面请不要点“看屏幕”"
            textSize = 12f
            setPadding(0, 0, 0, dp(6))
        })
        button("A. 开启无障碍服务") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("开关是灰的？允许“受限制的设置”（Android 13+）") {
            toast("在应用信息页：右上角 ⋮ → 允许受限制的设置，然后回去开无障碍")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button("B. 阅读说明并同意发送屏幕文字") {
            android.app.AlertDialog.Builder(this)
                .setTitle("确认开启屏幕感知")
                .setMessage(
                    "之后每次你主动点“看屏幕”，当前界面上的文字会发送到 DeepSeek 的服务器，" +
                        "按它的隐私政策处理。屏幕上如果有别人的消息、账号、验证码，也会一起发出去。\n\n" +
                        "你可以随时在这里撤销同意，或在系统设置里关闭无障碍服务。"
                )
                .setPositiveButton("同意") { _, _ -> PetPrefs.setScreenConsent(this, true); toast("已同意"); refresh() }
                .setNegativeButton("取消", null)
                .show()
        }
        button("撤销同意") { PetPrefs.setScreenConsent(this, false); toast("已撤销"); refresh() }
        root.addView(CheckBox(this).apply {
            text = "敏感应用不读取（强烈建议保持勾选）"
            isChecked = PetPrefs.screenBlockSensitive(this@MainActivity)
            setOnCheckedChangeListener { _, on -> PetPrefs.setScreenBlockSensitive(this@MainActivity, on) }
        })
        root.addView(CheckBox(this).apply {
            text = "应用感知：打开视频 / 聊天 / 游戏类 App 时评论两句（只看 App 包名，不读内容、不联网）"
            isChecked = PetPrefs.appReactions(this@MainActivity)
            setOnCheckedChangeListener { _, on -> PetPrefs.setAppReactions(this@MainActivity, on) }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val notif = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        status.text = buildString {
            appendLine("${mark(overlay)} 悬浮窗权限")
            appendLine("${mark(notif)} 通知权限")
            appendLine("${mark(battery)} 已忽略电池优化")
            appendLine("${mark(SecureStore.hasApiKey(this@MainActivity))} 已保存 API Key")
            appendLine("${mark(ScreenReaderService.isEnabled(this@MainActivity))} 无障碍服务已开启")
            appendLine("${mark(PetPrefs.screenConsent(this@MainActivity))} 已同意读取屏幕文字")
            append("${mark(PetService.running)} 桌宠运行中")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun refreshMemories(v: TextView) {
        val list = PetPrefs.memories(this)
        v.text = if (list.isEmpty()) "（还没有记忆。聊天时说“记住……”，或者它会自己慢慢记住你）"
        else list.mapIndexed { i, m -> "${i + 1}. $m" }.joinToString("\n") + "\n\n（点这里可以删除）"
    }
}
