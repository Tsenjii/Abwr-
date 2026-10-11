package dev.local.aibrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/** Explicitly started foreground service. Android can still stop it due to OS policies. */
class BrowserService : Service() {
    companion object { const val ACTION_ROTATE_TOKEN = "dev.local.aibrowser.ACTION_ROTATE_TOKEN" }
    private var api: LocalApiServer? = null
    override fun onCreate() {
        super.onCreate()
        val channelId = "agent_browser_runtime"
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(channelId,
            "AI Browser Service", NotificationManager.IMPORTANCE_LOW))
        val openBrowser = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = Notification.Builder(this, channelId)
            .setContentTitle("Abwr · 本機瀏覽器")
            .setContentText("本機 Agent 連線服務正在執行")
            .setContentIntent(openBrowser)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notice)
        val controller = (application as BrowserApplication).controller
        controller.initialize()
        try {
            api = LocalApiServer(this, controller).also { it.start() }
        } catch (e: Exception) { Log.e("AIBrowser", "Unable to start local API", e); stopSelf() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ROTATE_TOKEN) {
            // Called only after explicit confirmation in the visible Android Activity.
            try {
                api?.stop()
                api = null
                LocalApiServer.rotateToken(this)
                val controller = (application as BrowserApplication).controller
                api = LocalApiServer(this, controller).also { it.start() }
            } catch (e: Exception) {
                Log.e("AIBrowser", "Unable to restart the local API after token rotation", e)
                stopSelf()
            }
        }
        // Restarts are best-effort only. User force-stop / OEM policy can still end this service.
        return START_STICKY
    }
    override fun onDestroy() { api?.stop(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}