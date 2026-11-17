package com.example.guardian

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
import com.example.data.BoosterPreferencesRepository
import com.example.data.DeviceTelemetryManager
import com.example.model.GuardianAlert
import com.example.model.GuardianSeverity
import com.example.shizuku.ShizukuBridge
import com.example.shizuku.ShizukuConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Guardian24hSnapshot(
  val running24h: Boolean = false,
  val repairs24hCount: Int = 0,
  val lastProbeLabel: String = "Aguardando Shizuku ativo...",
  val recentBackgroundAlerts: List<GuardianAlert> = emptyList(),
)

object GuardianLiveBus {
  private val _snapshot = MutableStateFlow(Guardian24hSnapshot())
  val snapshot: StateFlow<Guardian24hSnapshot> = _snapshot.asStateFlow()

  fun setRunning(running: Boolean, label: String) {
    _snapshot.update { it.copy(running24h = running, lastProbeLabel = label) }
  }

  fun recordFix(alert: GuardianAlert, probeLabel: String) {
    _snapshot.update { cur ->
      val nextAlerts =
        buildList {
          add(alert)
          addAll(cur.recentBackgroundAlerts.filterNot { it.id == alert.id })
        }.take(6)
      cur.copy(
        running24h = true,
        repairs24hCount = cur.repairs24hCount + 1,
        lastProbeLabel = probeLabel,
        recentBackgroundAlerts = nextAlerts,
      )
    }
  }

  fun dismissBackgroundAlert(alertId: String) {
    _snapshot.update { cur ->
      cur.copy(recentBackgroundAlerts = cur.recentBackgroundAlerts.filterNot { it.id == alertId })
    }
  }

  fun resetAll() {
    _snapshot.value = Guardian24hSnapshot()
  }
}

class GuardianMonitorService : Service() {

  companion object {
    const val CHANNEL_ID = "infinit_guardian_24h_channel"
    const val NOTIFICATION_ID = 3102
    const val ACTION_START = "com.example.action.GUARDIAN_24H_START"
    const val ACTION_STOP = "com.example.action.GUARDIAN_24H_STOP"

    @Volatile var isRunning: Boolean = false

    fun start(context: Context) {
      if (isRunning) return
      val intent = Intent(context, GuardianMonitorService::class.java).apply { action = ACTION_START }
      runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      }
    }

    fun stop(context: Context) {
      if (!isRunning) return
      runCatching {
        context.stopService(Intent(context, GuardianMonitorService::class.java))
      }
      GuardianLiveBus.setRunning(false, "Monitor 24h pausado (aguardando Shizuku)")
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
  private lateinit var shizukuBridge: ShizukuBridge
  private lateinit var telemetryManager: DeviceTelemetryManager
  private lateinit var repository: BoosterPreferencesRepository
  private var monitorJob: Job? = null

  private var lastFixAtMs = 0L
  private var shizukuOfflineStreak = 0

  override fun onCreate() {
    super.onCreate()
    shizukuBridge = ShizukuBridge(this)
    telemetryManager = DeviceTelemetryManager(this)
    repository = BoosterPreferencesRepository(this)
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopMonitorCleanly("Monitor 24h pausado pelo usuário")
      return START_NOT_STICKY
    }

    val notification = buildNotification("Monitorando travamentos 24h via Shizuku")
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
    GuardianLiveBus.setRunning(true, "IA Guardião 24h ativa via Shizuku")

    if (monitorJob?.isActive != true) {
      monitorJob = scope.launch { run24hLoop() }
    }
    return START_STICKY
  }

  private suspend fun run24hLoop() {
    // Allow initial Shizuku bind settle
    delay(1200L)
    var loopTick = 0
    while (scope.isActive) {
      loopTick++
      val connState = shizukuBridge.status.value.connectionState
      if (connState != ShizukuConnectionState.CONNECTED) {
        shizukuBridge.refreshState()
        shizukuOfflineStreak++
        if (shizukuOfflineStreak >= 3) {
          stopMonitorCleanly("Shizuku desconectado • Monitor 24h aguardando reconexão")
          return
        }
        delay(3000L)
        continue
      }
      shizukuOfflineStreak = 0

      val settings = runCatching { repository.settingsFlow.first() }.getOrNull()
      if (settings != null && !settings.guardianAutoRepairEnabled) {
        stopMonitorCleanly("Monitor 24h pausado")
        return
      }

      val telemetry =
        withContext(Dispatchers.IO) {
          telemetryManager.readTelemetry(60, 16.6f)
        }
      val probe = shizukuBridge.probeLiveStuttersViaShizuku()
      val now = System.currentTimeMillis()
      val ts = timeFormat.format(Date(now))

      val ramHigh = telemetry.isLowMemory || telemetry.ramPercent >= 82
      val stutterDetected = probe.hasAnrOrLag || probe.loadAvgHigh
      val batterySaverLimiting = telemetry.isPowerSaveMode && (settings?.boostActive == true)
      val cooldownOk = (now - lastFixAtMs) >= 22_000L

      val probeText =
        if (probe.summaryNote.isNotBlank()) "24h Shizuku: ${probe.summaryNote} • RAM ${telemetry.ramPercent}%"
        else "24h Shizuku ativo • RAM ${telemetry.ramPercent}% • ${String.format(Locale.US, "%.1f", telemetry.batteryTempC)}°C"

      if ((ramHigh || stutterDetected || batterySaverLimiting) && cooldownOk) {
        lastFixAtMs = now
        val freedMb = withContext(Dispatchers.IO) { telemetryManager.performMemoryAndCacheCleanup() }
        val fgGame =
          probe.foregroundPackage?.takeIf { pkg -> settings?.addedGamePackages?.contains(pkg) == true }
        val fixedList =
          shizukuBridge.runGuardianMaintenance(
            trimMemoryAndCache = ramHigh || stutterDetected,
            liftBatterySaver = batterySaverLimiting,
            boostActivePackage = fgGame,
          )
        val reason =
          when {
            probe.hasAnrOrLag -> "Travamento/queda de FPS detectado (${probe.liveFps ?: "--"} FPS)"
            ramHigh -> "RAM alta (${telemetry.ramPercent}%)"
            probe.loadAvgHigh -> "Pico de carga na CPU"
            else -> "Economia de bateria limitando Hz"
          }
        val detail =
          "$reason → ${fixedList.joinToString("; ").ifBlank { "Caches limpos (${freedMb} MB)" }}. Suas otimizações foram mantidas."
        val alert =
          GuardianAlert(
            id = "guardian_24h_${if (probe.hasAnrOrLag) "lag" else "ram"}",
            title = "IA 24h corrigiu em tempo real",
            detail = detail,
            severity = GuardianSeverity.FIXED,
            timestampLabel = ts,
            autoFixed = true,
          )
        GuardianLiveBus.recordFix(alert, probeText)
        updateNotification(
          "IA 24h via Shizuku • ${GuardianLiveBus.snapshot.value.repairs24hCount} correções",
          detail,
        )
      } else {
        GuardianLiveBus.setRunning(true, probeText)
        if (loopTick % 15 == 1) {
          updateNotification(
            "IA Guardião 24h • Ativa via Shizuku",
            probeText,
          )
        }
      }

      delay(4000L)
    }
  }

  private fun stopMonitorCleanly(label: String) {
    isRunning = false
    monitorJob?.cancel()
    GuardianLiveBus.setRunning(false, label)
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun updateNotification(title: String, text: String) {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(NOTIFICATION_ID, buildNotification(text, title))
  }

  private fun buildNotification(
    contentText: String,
    title: String = "IA Guardião 24h • Shizuku Ativo",
  ): Notification {
    val openIntent =
      Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      }
    val openPending =
      PendingIntent.getActivity(
        this,
        501,
        openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val stopIntent =
      Intent(this, GuardianMonitorService::class.java).apply {
        action = ACTION_STOP
      }
    val stopPending =
      PendingIntent.getService(
        this,
        502,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(contentText)
      .setOngoing(true)
      .setSilent(true)
      .setContentIntent(openPending)
      .addAction(0, "Pausar", stopPending)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          "IA Guardião 24h (Shizuku)",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Monitora travamentos e corrige engasgos em tempo real enquanto o Shizuku estiver ativo"
          setShowBadge(false)
        }
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      nm.createNotificationChannel(channel)
    }
  }

  override fun onDestroy() {
    isRunning = false
    monitorJob?.cancel()
    shizukuBridge.release()
    scope.cancel()
    GuardianLiveBus.setRunning(false, "IA Guardião 24h em espera (conecte o Shizuku)")
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
