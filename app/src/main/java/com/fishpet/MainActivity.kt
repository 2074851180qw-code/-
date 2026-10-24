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
