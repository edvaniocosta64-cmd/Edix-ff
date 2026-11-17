package com.example.overlay

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
import com.example.MainActivity
import com.example.R
import com.example.data.DeviceTelemetryManager
import com.example.shizuku.ShizukuBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GameDpiSessionService : Service() {

  companion object {
    const val CHANNEL_ID = "infinit_game_dpi_session_channel"
    const val NOTIFICATION_ID = 3095
    const val ACTION_START = "com.example.action.GAME_DPI_START"
    const val ACTION_RESTORE_AND_STOP = "com.example.action.GAME_DPI_RESTORE_STOP"

    const val EXTRA_PACKAGE = "extra_game_package"
    const val EXTRA_LABEL = "extra_game_label"
    const val EXTRA_DPI = "extra_game_dpi"
    const val EXTRA_TOUCH_ENABLED = "extra_game_touch_enabled"
    const val EXTRA_TOUCH_SENS = "extra_game_touch_sens"

    @Volatile var isRunning: Boolean = false

    fun start(
      context: Context,
      packageName: String,
      label: String,
      customDpi: Int?,
      touchEnabled: Boolean,
      touchSensitivity: Int,
    ) {
      val intent =
        Intent(context, GameDpiSessionService::class.java).apply {
          action = ACTION_START
          putExtra(EXTRA_PACKAGE, packageName)
          putExtra(EXTRA_LABEL, label)
          putExtra(EXTRA_DPI, customDpi ?: -1)
          putExtra(EXTRA_TOUCH_ENABLED, touchEnabled)
          putExtra(EXTRA_TOUCH_SENS, touchSensitivity)
        }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun restoreAndStop(context: Context) {
      if (!isRunning) return
      val intent =
        Intent(context, GameDpiSessionService::class.java).apply {
          action = ACTION_RESTORE_AND_STOP
        }
      val sent = runCatching { context.startService(intent) }.isSuccess
      if (!sent) {
        runCatching { context.stopService(Intent(context, GameDpiSessionService::class.java)) }
      }
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private lateinit var shizukuBridge: ShizukuBridge
  private lateinit var telemetryManager: DeviceTelemetryManager
  private var watchJob: Job? = null

  private var activeDpiApplied = false
  private var activeTouchApplied = false

  override fun onCreate() {
    super.onCreate()
    shizukuBridge = ShizukuBridge(this)
    telemetryManager = DeviceTelemetryManager(this)
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_RESTORE_AND_STOP) {
      watchJob?.cancel()
      scope.launch {
        if (activeDpiApplied || activeTouchApplied) {
          shizukuBridge.restoreGameSessionTweaks(
            restoreDpi = activeDpiApplied,
            restoreTouch = activeTouchApplied,
          )
        }
        activeDpiApplied = false
        activeTouchApplied = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
      }
      return START_NOT_STICKY
    }

    val targetPackage = intent?.getStringExtra(EXTRA_PACKAGE)
    if (targetPackage.isNullOrBlank()) {
      stopSelf()
      return START_NOT_STICKY
    }
    val gameLabel = intent.getStringExtra(EXTRA_LABEL) ?: targetPackage
    val rawDpi = intent.getIntExtra(EXTRA_DPI, -1)
    val customDpi = rawDpi.takeIf { it in 400..1440 }
    val touchEnabled = intent.getBooleanExtra(EXTRA_TOUCH_ENABLED, false)
    val touchSensitivity = intent.getIntExtra(EXTRA_TOUCH_SENS, 7).coerceIn(1, 7)

    if (customDpi == null && !touchEnabled) {
      stopSelf()
      return START_NOT_STICKY
    }

    val notification = buildNotification(gameLabel, customDpi, touchEnabled)
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

    watchJob?.cancel()
    watchJob =
      scope.launch {
        activeDpiApplied = customDpi != null
        activeTouchApplied = touchEnabled || (customDpi != null)
        val fallbackShortSide = telemetryManager.readNativeScreenSpec().widthPx
        shizukuBridge.applyGameSessionTweaks(
          customDpi = customDpi,
          touchScriptEnabled = touchEnabled,
          touchSensitivity = touchSensitivity,
          fallbackShortSidePx = fallbackShortSide,
        )

        var sawGameInForeground = false
        var ticksSinceLaunch = 0
        var offGameStreak = 0

        while (isActive) {
          delay(1500L)
          ticksSinceLaunch++
          val topPkg = shizukuBridge.getTopResumedPackage()
          if (topPkg == targetPackage) {
            sawGameInForeground = true
            offGameStreak = 0
          } else if (
            topPkg != null &&
              topPkg != "com.android.systemui" &&
              !topPkg.contains("permissioncontroller")
          ) {
            if (sawGameInForeground) {
              offGameStreak++
              if (offGameStreak >= 3) {
                break
              }
            } else if (ticksSinceLaunch >= 12 && topPkg != packageName) {
              // Game did not enter foreground or user switched to another app
              break
            }
          }
        }

        shizukuBridge.restoreGameSessionTweaks(
          restoreDpi = activeDpiApplied,
          restoreTouch = activeTouchApplied,
        )
        activeDpiApplied = false
        activeTouchApplied = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
      }

    return START_NOT_STICKY
  }

  private fun buildNotification(gameLabel: String, customDpi: Int?, touchEnabled: Boolean): Notification {
    val openIntent =
      Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      }
    val openPending =
      PendingIntent.getActivity(
        this,
        401,
        openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val restoreIntent =
      Intent(this, GameDpiSessionService::class.java).apply {
        action = ACTION_RESTORE_AND_STOP
      }
    val restorePending =
      PendingIntent.getService(
        this,
        402,
        restoreIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val detail =
      buildList {
        if (customDpi != null) add("DPI $customDpi")
        if (touchEnabled) add("Script Touch")
      }.joinToString(" + ")

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("$gameLabel • $detail ativo")
      .setContentText("Restaura o DPI original automaticamente ao sair do jogo")
      .setOngoing(true)
      .setSilent(true)
      .setContentIntent(openPending)
      .addAction(0, "Restaurar Agora", restorePending)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          "Sessão DPI & Touch em Jogo",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Mantém DPI e Script Touch ativos apenas durante o jogo e restaura ao sair"
          setShowBadge(false)
        }
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      nm.createNotificationChannel(channel)
    }
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    if (activeDpiApplied || activeTouchApplied) {
      watchJob?.cancel()
      val restoreDpi = activeDpiApplied
      val restoreTouch = activeTouchApplied
      activeDpiApplied = false
      activeTouchApplied = false
      scope.launch {
        runCatching {
          shizukuBridge.restoreGameSessionTweaks(
            restoreDpi = restoreDpi,
            restoreTouch = restoreTouch,
          )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
      }
    }
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    isRunning = false
    watchJob?.cancel()
    if (activeDpiApplied || activeTouchApplied) {
      val restoreDpi = activeDpiApplied
      val restoreTouch = activeTouchApplied
      activeDpiApplied = false
      activeTouchApplied = false
      val bridgeToCleanup = shizukuBridge
      CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        runCatching {
          bridgeToCleanup.restoreGameSessionTweaks(
            restoreDpi = restoreDpi,
            restoreTouch = restoreTouch,
          )
        }
        bridgeToCleanup.release()
      }
    } else {
      shizukuBridge.release()
    }
    scope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
