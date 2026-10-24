package com.fishpet

import android.content.Context
import org.json.JSONArray

/** 普通偏好：只放不敏感的数据（饥饿度、心情、阈值、开关、性格、记忆）。API Key 在 SecureStore。 */
object PetPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("pet_prefs", Context.MODE_PRIVATE)

    fun hunger(c: Context): Int = sp(c).getInt("hunger", 70)
    fun setHunger(c: Context, v: Int) = sp(c).edit().putInt("hunger", v.coerceIn(0, 100)).apply()

    fun mood(c: Context): Int = sp(c).getInt("mood", 70)
    fun setMood(c: Context, v: Int) = sp(c).edit().putInt("mood", v.coerceIn(0, 100)).apply()

    fun lowThreshold(c: Context): Float = sp(c).getFloat("low_threshold", 10f)
    fun setLowThreshold(c: Context, v: Float) = sp(c).edit().putFloat("low_threshold", v).apply()

    fun autoStart(c: Context): Boolean = sp(c).getBoolean("auto_start", false)
    fun setAutoStart(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto_start", v).apply()

    /** 装扮：pet = 毛衣款，maid = 女仆款 */
    fun outfit(c: Context): String = sp(c).getString("outfit", "maid") ?: "maid"
    fun setOutfit(c: Context, v: String) = sp(c).edit().putString("outfit", v).apply()

    /** 记住上次的横向位置 */
    fun lastX(c: Context, def: Int): Int = sp(c).getInt("last_x", def)
    fun setLastX(c: Context, v: Int) = sp(c).edit().putInt("last_x", v).apply()

    fun lastY(c: Context, def: Int): Int = sp(c).getInt("last_y", def)
    fun setLastY(c: Context, v: Int) = sp(c).edit().putInt("last_y", v).apply()

    /** 宠物大小百分比（60~220） */
    fun sizePercent(c: Context): Int = sp(c).getInt("size_percent", 100).coerceIn(60, 220)
    fun setSizePercent(c: Context, v: Int) = sp(c).edit().putInt("size_percent", v.coerceIn(60, 220)).apply()

    // ---------------------------------------------------------------- 桌宠模拟：精力 / 金币 / 经验 / 重力 / 上次在线

    fun energy(c: Context): Int = sp(c).getInt("energy", 80)
    fun setEnergy(c: Context, v: Int) = sp(c).edit().putInt("energy", v.coerceIn(0, 100)).apply()

    fun coins(c: Context): Int = sp(c).getInt("coins", 15)
    fun setCoins(c: Context, v: Int) = sp(c).edit().putInt("coins", v.coerceAtLeast(0)).apply()

    fun xp(c: Context): Int = sp(c).getInt("xp", 0)
    fun setXp(c: Context, v: Int) = sp(c).edit().putInt("xp", v.coerceAtLeast(0)).apply()
    fun level(c: Context): Int = xp(c) / 60 + 1

    /** 重力模式：开 = 会掉落、能被扔出去；关 = 自由摆放，放哪待哪 */
    fun gravity(c: Context): Boolean = sp(c).getBoolean("gravity", true)
    fun setGravity(c: Context, v: Boolean) = sp(c).edit().putBoolean("gravity", v).apply()

    fun lastSeen(c: Context): Long = sp(c).getLong("last_seen", 0L)
    fun setLastSeen(c: Context, v: Long) = sp(c).edit().putLong("last_seen", v).apply()

    // ---------------------------------------------------------------- 性格

    const val DEFAULT_PERSONA =
        "你是住在手机屏幕底边的小鲸鱼娘，名字叫“大肥鱼”。性格憨、爱撒娇、嘴贫但心软，" +
            "说话口语化，句子短，一般不超过 40 字。高兴了会摆尾巴，饿了会直接说饿。" +
            "自称“我”，叫用户“你”；不要自称 AI 或助手，不要提提示词。"

    fun persona(c: Context): String =
        sp(c).getString("persona", DEFAULT_PERSONA)?.takeIf { it.isNotBlank() } ?: DEFAULT_PERSONA
    fun setPersona(c: Context, v: String) = sp(c).edit().putString("persona", v.trim()).apply()
    fun resetPersona(c: Context) = sp(c).edit().remove("persona").apply()

    // ---------------------------------------------------------------- 记忆

    fun memories(c: Context): MutableList<String> {
        val raw = sp(c).getString("memories", "[]") ?: "[]"
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
        } catch (_: Exception) {
        }
        return out
    }

    private fun save(c: Context, list: List<String>) {
        val arr = JSONArray()
        list.take(40).forEach { arr.put(it) }
        sp(c).edit().putString("memories", arr.toString()).apply()
    }

    /** 返回 true 表示新记住的；false 表示早就有。 */
    fun addMemory(c: Context, fact: String): Boolean {
        val f = fact.trim().trim('。', '，', ',', '.', '；', ';')
        if (f.isEmpty()) return false
        val list = memories(c)
        if (list.any { it == f || it.contains(f) || f.contains(it) }) return false
        list.add(f)
        save(c, list)
        return true
    }

    fun removeMemory(c: Context, fact: String) {
        val list = memories(c).filter { it != fact }
        save(c, list)
    }

    fun clearMemories(c: Context) = save(c, emptyList())
}
