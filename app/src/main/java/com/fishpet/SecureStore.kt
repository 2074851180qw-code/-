package com.fishpet

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * API Key 只存在这里（EncryptedSharedPreferences），只在后台代码里读取，
 * 不写日志、不传给任何 WebView。
 */
object SecureStore {
    @Volatile private var cached: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val app = ctx.applicationContext
            val masterKey = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val p = EncryptedSharedPreferences.create(
                app, "secure_prefs", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            cached = p
            return p
        }
    }

    fun getApiKey(ctx: Context): String =
        try { prefs(ctx).getString("api_key", "") ?: "" } catch (e: Exception) { "" }

    fun setApiKey(ctx: Context, key: String) {
        try { prefs(ctx).edit().putString("api_key", key).apply() } catch (_: Exception) {}
    }

    fun hasApiKey(ctx: Context): Boolean = getApiKey(ctx).isNotEmpty()

    /** 在 IO 线程读取（首次初始化 Keystore 可能较慢），协程里用这个。 */
    suspend fun apiKeyAsync(ctx: Context): String = withContext(Dispatchers.IO) { getApiKey(ctx) }
}
