package com.example.resolution

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
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.data.BoosterPreferencesRepository
import com.example.data.DeviceTelemetryManager
import com.example.shizuku.ShizukuBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ResolutionPanelService : Service() {

  companion object {
    const val CHANNEL_ID = "infinit_resolution_panel_channel"
    const val NOTIFICATION_ID = 2042
    const val ACTION_START = "com.example.action.RES_PANEL_START"
    const val ACTION_APPLY_65 = "com.example.action.RES_APPLY_65"
    const val ACTION_APPLY_80 = "com.example.action.RES_APPLY_80"
    const val ACTION_STOP = "com.example.action.RES_PANEL_STOP"

    @Volatile var isRunning: Boolean = false

    fun start(context: Context) {
      val intent = Intent(context, ResolutionPanelService::class.java).apply { action = ACTION_START }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      if (!isRunning) return
      val intent = Intent(context, ResolutionPanelService::class.java).apply { action = ACTION_STOP }
      val sent = runCatching { context.startService(intent) }.isSuccess
      if (!sent) {
        runCatching { context.stopService(Intent(context, ResolutionPanelService::class.java)) }
      }
    }
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private lateinit var telemetryManager: DeviceTelemetryManager
  private lateinit var shizukuBridge: ShizukuBridge
  private lateinit var repository: BoosterPreferencesRepository
  private var currentStatusLine = "Toque para mudar resolução dentro do jogo"

  override fun onCreate() {
    super.onCreate()
    telemetryManager = DeviceTelemetryManager(this)
    shizukuBridge = ShizukuBridge(this)
    repository = BoosterPreferencesRepository(this)
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        serviceScope.launch {
          repository.setBackgroundResolutionPanelEnabled(false)
          stopForeground(STOP_FOREGROUND_REMOVE)
          stopSelf()
        }
        return START_NOT_STICKY
      }
      ACTION_APPLY_65 -> applyScalePreset(65)
      ACTION_APPLY_80 -> applyScalePreset(80)
      else -> {
        val spec = telemetryManager.readNativeScreenSpec()
        currentStatusLine = "Tela ${spec.widthPx}x${spec.heightPx} (${spec.aspectRatioLabel}) • Toque para abrir painel"
      }
    }

    val notification = buildNotification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
    isRunning = true
    return START_STICKY
  }

  private fun applyScalePreset(percent: Int) {
    val spec = telemetryManager.readNativeScreenSpec()
    val computed = ResolutionHelper.computeFromPercent(spec, percent)
    serviceScope.launch {
      shizukuBridge.refreshState()
      val res = shizukuBridge.applyCustomResolution(computed.widthPx, computed.heightPx, computed.densityDpi)
      repository.setResolutionConfig(percent, computed.widthPx, computed.heightPx, true)
      currentStatusLine =
        if (res != null) "Ativa: ${computed.widthPx}x${computed.heightPx} (${percent}% • ${spec.aspectRatioLabel})"
        else "Autorize o Shizuku no app para aplicar ${computed.widthPx}x${computed.heightPx}"
      updateNotification()
    }
  }

  private fun updateNotification() {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(NOTIFICATION_ID, buildNotification())
  }

  private fun buildNotification(): Notification {
    val popupIntent =
      Intent(this, ResolutionPopupActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      }
    val popupPending =
      PendingIntent.getActivity(
        this,
        100,
        popupIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val p65 = actionPendingIntent(ACTION_APPLY_65, 101)
    val p80 = actionPendingIntent(ACTION_APPLY_80, 102)
    val pStop = actionPendingIntent(ACTION_STOP, 104)

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("Infinit Booster • Painel de Resolução")
      .setContentText(currentStatusLine)
      .setStyle(NotificationCompat.BigTextStyle().bigText(currentStatusLine))
      .setOngoing(true)
      .setSilent(true)
      .setContentIntent(popupPending)
      .addAction(0, "65% FPS", p65)
      .addAction(0, "80% HD+", p80)
      .addAction(0, "Fechar", pStop)
      .build()
  }

  private fun actionPendingIntent(action: String, reqCode: Int): PendingIntent {
    val intent = Intent(this, ResolutionPanelService::class.java).apply { this.action = action }
    return PendingIntent.getService(
      this,
      reqCode,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          "Painel de Resolução em 2º Plano",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Permite alterar a resolução proporcional rapidamente dentro dos jogos"
          setShowBadge(false)
        }
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      nm.createNotificationChannel(channel)
    }
  }

  override fun onDestroy() {
    isRunning = false
    shizukuBridge.release()
    serviceScope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
