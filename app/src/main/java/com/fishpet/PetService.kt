package com.fishpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务：持有悬浮窗，并定时查余额。
 * Android 8+ 前台服务必须有常驻通知；Android 12+ 不能在后台启动前台服务，
 * 所以只从 App 界面（前台）启动；开机自启在 BootReceiver 里 try/catch。
 */
class PetService : Service() {
    companion object {
        const val ACTION_STOP = "com.fishpet.STOP"
        private const val CH_FGS = "pet_fgs"
        private const val CH_ALERT = "pet_alert"
        private const val NOTIF_ID = 1
        private const val ALERT_ID = 2
        private const val POLL_MS = 30 * 60 * 1000L

        @Volatile var running = false

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PetService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PetService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: PetController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startFg(buildNotification("大肥鱼在桌面上"))
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        controller = PetController(this, scope) { stopSelf() }.also { it.show() }
        running = true
        ScreenReaderService.onForegroundApp = { pkg -> controller?.onForegroundApp(pkg) }
        startBalanceLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        ScreenReaderService.onForegroundApp = null
        controller?.destroy()
        scope.cancel()
        super.onDestroy()
    }

    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_FGS, "桌宠运行中", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "余额提醒", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CH_FGS)
            .setSmallIcon(R.drawable.ic_fish)
            .setContentTitle("蓝色大肥鱼")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "关闭桌宠", stop)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (_: SecurityException) {}
    }

    private fun alertNotification(text: String) {
        try {
            val n = NotificationCompat.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_fish)
                .setContentTitle("余额不足")
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(ALERT_ID, n)
        } catch (_: SecurityException) {}
    }

    /** 前台服务里的协程定时器：每 30 分钟查一次余额，更新通知；低于阈值时提醒。 */
    private fun startBalanceLoop() {
        scope.launch {
            while (isActive) {
                val key = SecureStore.apiKeyAsync(this@PetService)
                if (key.isNotEmpty()) {
                    val b = DeepSeekApi.balance(key)
                    val total = b.total
                    if (b.ok && total != null) {
                        updateNotification(DeepSeekApi.describe(b))
                        if (total < PetPrefs.lowThreshold(this@PetService)) {
                            controller?.onLowBalance(b)
                            alertNotification(DeepSeekApi.describe(b))
                        }
                    }
                }
                delay(POLL_MS)
            }
        }
    }
}
