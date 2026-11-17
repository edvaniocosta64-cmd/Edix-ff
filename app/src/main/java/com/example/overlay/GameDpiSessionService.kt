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
import com.example.guardian.GuardianDiagnosticPipeline
import com.example.guardian.GuardianLiveBus
import com.example.guardian.GuardianMemoryStore
import com.example.model.GameRendererMode
import com.example.shizuku.ShizukuBridge
import com.example.shizuku.ShizukuConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    const val EXTRA_GUARDIAN_BETA = "extra_game_guardian_beta"
    const val EXTRA_TARGET_HZ = "extra_game_target_hz"
    const val EXTRA_RENDERER_MODE = "extra_game_renderer_mode"

    @Volatile var isRunning: Boolean = false
    @Volatile var activeTargetPackage: String? = null
    @Volatile var isBetaGuarding: Boolean = false
    @Volatile var startedAtMs: Long = 0L

    fun start(
      context: Context,
      packageName: String,
      label: String,
      customDpi: Int?,
      touchEnabled: Boolean,
      touchSensitivity: Int,
      guardianBetaEnabled: Boolean = false,
      targetHz: Int = 120,
      rendererModeId: String = GameRendererMode.GLOBAL.id,
    ) {
      val intent =
        Intent(context, GameDpiSessionService::class.java).apply {
          action = ACTION_START
          putExtra(EXTRA_PACKAGE, packageName)
          putExtra(EXTRA_LABEL, label)
          putExtra(EXTRA_DPI, customDpi ?: -1)
          putExtra(EXTRA_TOUCH_ENABLED, touchEnabled)
          putExtra(EXTRA_TOUCH_SENS, touchSensitivity)
          putExtra(EXTRA_GUARDIAN_BETA, guardianBetaEnabled)
          putExtra(EXTRA_TARGET_HZ, targetHz)
          putExtra(EXTRA_RENDERER_MODE, rendererModeId)
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
  private var activeGuardianBeta = false
  private var lastBetaFixAtMs = 0L
  private var betaFixCount = 0

  override fun onCreate() {
    super.onCreate()
    shizukuBridge = ShizukuBridge(this)
    telemetryManager = DeviceTelemetryManager(this)
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_RESTORE_AND_STOP) {
      isRunning = false
      isBetaGuarding = false
      activeGuardianBeta = false
      activeTargetPackage = null
      watchJob?.cancel()
      val restoreDpi = activeDpiApplied
      val restoreTouch = activeTouchApplied
      activeDpiApplied = false
      activeTouchApplied = false
      scope.launch {
        if (restoreDpi || restoreTouch) {
          shizukuBridge.restoreGameSessionTweaks(
            restoreDpi = restoreDpi,
            restoreTouch = restoreTouch,
          )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
      }
      return START_NOT_STICKY
    }

    val targetPackage = intent?.getStringExtra(EXTRA_PACKAGE)
    val gameLabel = intent?.getStringExtra(EXTRA_LABEL) ?: targetPackage.orEmpty().ifBlank { "Jogo" }
    val rawDpi = intent?.getIntExtra(EXTRA_DPI, -1) ?: -1
    val customDpi = rawDpi.takeIf { it in 400..1440 }
    val touchEnabled = intent?.getBooleanExtra(EXTRA_TOUCH_ENABLED, false) ?: false
    val touchSensitivity = (intent?.getIntExtra(EXTRA_TOUCH_SENS, 7) ?: 7).coerceIn(1, 7)
    val guardianBeta = intent?.getBooleanExtra(EXTRA_GUARDIAN_BETA, false) ?: false
    val targetHz = (intent?.getIntExtra(EXTRA_TARGET_HZ, 120) ?: 120).coerceIn(60, 165)
    val rendererId = intent?.getStringExtra(EXTRA_RENDERER_MODE).orEmpty()
    val rendererMode =
      GameRendererMode.entries.firstOrNull { it.id == rendererId } ?: GameRendererMode.GLOBAL

    val notification = buildNotification(gameLabel, customDpi, touchEnabled, guardianBeta, statusNote = null)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }

    if (targetPackage.isNullOrBlank() || (customDpi == null && !touchEnabled && !guardianBeta)) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    // If restarting for a different game while DPI/Touch was active, restore previous session first if needed
    val prevDpi = activeDpiApplied
    val prevTouch = activeTouchApplied

    activeGuardianBeta = guardianBeta
    activeTargetPackage = targetPackage
    isBetaGuarding = guardianBeta
    startedAtMs = System.currentTimeMillis()
    lastBetaFixAtMs = 0L
    betaFixCount = 0
    isRunning = true

    watchJob?.cancel()
    watchJob =
      scope.launch {
        if ((prevDpi && customDpi == null) || (prevTouch && !touchEnabled && customDpi == null)) {
          shizukuBridge.restoreGameSessionTweaks(
            restoreDpi = prevDpi && customDpi == null,
            restoreTouch = prevTouch && !touchEnabled && customDpi == null,
          )
        }
        activeDpiApplied = customDpi != null
        activeTouchApplied = touchEnabled || (customDpi != null)
        if (customDpi != null || touchEnabled) {
          val fallbackShortSide = telemetryManager.readNativeScreenSpec().widthPx
          shizukuBridge.applyGameSessionTweaks(
            customDpi = customDpi,
            touchScriptEnabled = touchEnabled,
            touchSensitivity = touchSensitivity,
            fallbackShortSidePx = fallbackShortSide,
          )
        }

        var sawGameInForeground = false
        var ticksSinceLaunch = 0
        var offGameStreak = 0

        while (isActive) {
          delay(1500L)
          ticksSinceLaunch++
          val shizukuConnected = shizukuBridge.status.value.connectionState == ShizukuConnectionState.CONNECTED
          val topPkg = if (shizukuConnected) shizukuBridge.getTopResumedPackage() else null

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
            } else if (ticksSinceLaunch >= 12) {
              // Game did not stay in foreground within 18s (user backed out or switched apps)
              break
            }
          } else if (!sawGameInForeground && ticksSinceLaunch >= 20 && shizukuConnected) {
            // Failsafe timeout if Shizuku could not observe the target game opening within 30s
            break
          }

          // IA Guardião Beta (Jogos): live stutter detection & non-destructive heal inside game
          val shouldCheckBeta =
            guardianBeta &&
              ((topPkg == targetPackage && ticksSinceLaunch % 2 == 0) ||
                (!shizukuConnected && ticksSinceLaunch >= 2 && ticksSinceLaunch % 4 == 0))

          if (shouldCheckBeta) {
            GuardianMemoryStore.ensureLoaded(this@GameDpiSessionService)
            val now = System.currentTimeMillis()
            if (now - lastBetaFixAtMs >= 18_000L) {
              val probe =
                if (shizukuConnected) shizukuBridge.probeLiveStuttersViaShizuku() else null
              val telemetry =
                withContext(Dispatchers.IO) {
                  telemetryManager.readTelemetry(probe?.liveFps ?: 60, 16.6f)
                }
              val pipelineResult =
                GuardianDiagnosticPipeline.runFourStageCycle(
                  context = this@GameDpiSessionService,
                  telemetryBefore = telemetry,
                  probeBefore = probe,
                  shizukuConnected = shizukuConnected,
                  activePackage = targetPackage,
                  gameLabel = gameLabel,
                  targetHz = targetHz,
                  perGameRenderer = rendererMode,
                  isInGame = true,
                  telemetryManager = telemetryManager,
                  shizukuBridge = shizukuBridge,
                  forceDeepAnalyze = false,
                )
              if (pipelineResult != null) {
                lastBetaFixAtMs = System.currentTimeMillis()
                betaFixCount++
                GuardianLiveBus.recordFix(
                  alert = pipelineResult.alert,
                  probeLabel =
                    "IA Beta ($gameLabel): ${pipelineResult.report.outcome.label} • $betaFixCount correção(ões)",
                )
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(
                  NOTIFICATION_ID,
                  buildNotification(
                    gameLabel = gameLabel,
                    customDpi = customDpi,
                    touchEnabled = touchEnabled,
                    guardianBeta = true,
                    statusNote =
                      "${pipelineResult.report.outcome.label}: ${pipelineResult.report.detectedProblem} → ${pipelineResult.report.verificationResult}",
                  ),
                )
              }
            }
          }
        }

        if (activeDpiApplied || activeTouchApplied) {
          shizukuBridge.restoreGameSessionTweaks(
            restoreDpi = activeDpiApplied,
            restoreTouch = activeTouchApplied,
          )
        }
        activeDpiApplied = false
        activeTouchApplied = false
        activeGuardianBeta = false
        activeTargetPackage = null
        isBetaGuarding = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
      }

    return START_NOT_STICKY
  }

  private fun buildNotification(
    gameLabel: String,
    customDpi: Int?,
    touchEnabled: Boolean,
    guardianBeta: Boolean,
    statusNote: String?,
  ): Notification {
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
        if (guardianBeta) add("IA Beta")
      }.joinToString(" + ").ifBlank { "Sessão Ativa" }

    val bodyText =
      statusNote
        ?: when {
          (customDpi != null || touchEnabled) && guardianBeta ->
            "DPI/Touch restauram ao sair • IA Beta vigiando travamentos ao vivo"
          guardianBeta ->
            "IA Guardião Beta corrigindo quedas de FPS e travamentos neste jogo"
          else -> "Restaura o DPI original automaticamente ao sair do jogo"
        }

    val actionTitle =
      if (customDpi != null || touchEnabled) "Restaurar Agora" else "Encerrar IA Beta"

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("$gameLabel • $detail ativo")
      .setContentText(bodyText)
      .setOngoing(true)
      .setSilent(true)
      .setContentIntent(openPending)
      .addAction(0, actionTitle, restorePending)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          "Sessão de Jogo (DPI, Touch & IA Beta)",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Mantém DPI, Touch e IA Guardião Beta ativos durante o jogo e restaura ao sair"
          setShowBadge(false)
        }
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      nm.createNotificationChannel(channel)
    }
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    if (activeDpiApplied || activeTouchApplied || activeGuardianBeta) {
      watchJob?.cancel()
      val restoreDpi = activeDpiApplied
      val restoreTouch = activeTouchApplied
      activeDpiApplied = false
      activeTouchApplied = false
      activeGuardianBeta = false
      activeTargetPackage = null
      isBetaGuarding = false
      scope.launch {
        if (restoreDpi || restoreTouch) {
          runCatching {
            shizukuBridge.restoreGameSessionTweaks(
              restoreDpi = restoreDpi,
              restoreTouch = restoreTouch,
            )
          }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
      }
    }
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    isRunning = false
    activeGuardianBeta = false
    activeTargetPackage = null
    isBetaGuarding = false
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
