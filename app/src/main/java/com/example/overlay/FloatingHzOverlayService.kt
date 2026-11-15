package com.example.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.BoosterPreferencesRepository
import com.example.data.DeviceTelemetryManager
import com.example.model.RefreshRateOption
import com.example.shizuku.ShizukuBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FloatingHzOverlayService : Service() {

  companion object {
    const val CHANNEL_ID = "infinit_floating_hz_channel"
    const val NOTIFICATION_ID = 3090
    const val ACTION_START = "com.example.action.FLOAT_HZ_START"
    const val ACTION_STOP = "com.example.action.FLOAT_HZ_STOP"

    fun start(context: Context) {
      val intent = Intent(context, FloatingHzOverlayService::class.java).apply { action = ACTION_START }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      val intent = Intent(context, FloatingHzOverlayService::class.java).apply { action = ACTION_STOP }
      context.startService(intent)
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private lateinit var windowManager: WindowManager
  private lateinit var telemetryManager: DeviceTelemetryManager
  private lateinit var repository: BoosterPreferencesRepository
  private lateinit var shizukuBridge: ShizukuBridge

  private var overlayRoot: LinearLayout? = null
  private var layoutParams: WindowManager.LayoutParams? = null
  private var statsTextView: TextView? = null
  private var bodyRow: LinearLayout? = null
  private var minimizeBtn: TextView? = null
  private var btn60: TextView? = null
  private var btn90: TextView? = null
  private var btn120: TextView? = null
  private var pulseDot: View? = null
  private var pulseAnimator: ObjectAnimator? = null
  private var telemetryJob: Job? = null
  private var selectedHz: Int = 120
  private var isMinimized = false

  override fun onCreate() {
    super.onCreate()
    windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    telemetryManager = DeviceTelemetryManager(this)
    repository = BoosterPreferencesRepository(this)
    shizukuBridge = ShizukuBridge(this)
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      scope.launch { repository.setFloatingHzOverlayEnabled(false) }
      stopOverlayAndSelf()
      return START_NOT_STICKY
    }

    if (!Settings.canDrawOverlays(this)) {
      scope.launch { repository.setFloatingHzOverlayEnabled(false) }
      stopSelf()
      return START_NOT_STICKY
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

    if (overlayRoot == null) {
      scope.launch {
        selectedHz = repository.settingsFlow.first().targetRefreshRate.hz
        attachRedFloatingPanel()
        startLiveTelemetryLoop()
      }
    }
    return START_STICKY
  }

  private fun attachRedFloatingPanel() {
    val overlayType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
      }

    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(18)
        y = dp(120)
        preferredRefreshRate = selectedHz.toFloat()
      }
    layoutParams = params

    val rootBg =
      GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.parseColor("#2A050D"), Color.parseColor("#110206")),
      ).apply {
        cornerRadius = dp(18).toFloat()
        setStroke(dp(2), Color.parseColor("#FF1E42"))
      }

    val root =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rootBg
        elevation = dp(12).toFloat()
        setPadding(dp(12), dp(10), dp(12), dp(10))
      }

    // Header bar (Draggable + Crimson Pulse + Minimize + Close)
    val header =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }

    val dot =
      View(this).apply {
        background =
          GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#FF2A5F"))
          }
      }
    pulseDot = dot
    header.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(7) })

    pulseAnimator =
      ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
        duration = 650L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        start()
      }

    val title =
      TextView(this).apply {
        text = "INFINIT HZ • v3.0"
        setTextColor(Color.parseColor("#FFEDF1"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
      }
    header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

    val minBtn =
      TextView(this).apply {
        text = "—"
        setTextColor(Color.parseColor("#FF8FA3"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(10), dp(2), dp(8), dp(2))
        setOnClickListener { toggleMinimize() }
      }
    minimizeBtn = minBtn
    header.addView(minBtn)

    val closeBtn =
      TextView(this).apply {
        text = "✕"
        setTextColor(Color.parseColor("#FF4D6D"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(8), dp(2), dp(2), dp(2))
        setOnClickListener {
          scope.launch { repository.setFloatingHzOverlayEnabled(false) }
          stopOverlayAndSelf()
        }
      }
    header.addView(closeBtn)
    root.addView(header)

    // Live Telemetry Status Line
    val stats =
      TextView(this).apply {
        text = "${selectedHz}Hz ATIVO • Lendo sensor..."
        setTextColor(Color.parseColor("#FF8FA3"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        typeface = Typeface.MONOSPACE
        setPadding(0, dp(4), 0, dp(8))
      }
    statsTextView = stats
    root.addView(stats)

    // 60 / 90 / 120Hz Crimson Selector Row
    val row =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
      }
    val b60 = createHzChip("60 Hz", 60)
    val b90 = createHzChip("90 Hz", 90)
    val b120 = createHzChip("120 Hz", 120)
    btn60 = b60
    btn90 = b90
    btn120 = b120

    val chipLp =
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { rightMargin = dp(6) }
    row.addView(b60, chipLp)
    row.addView(b90, chipLp)
    row.addView(
      b120,
      LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
      ),
    )
    bodyRow = row
    root.addView(row)

    // Drag gesture on root
    root.setOnTouchListener(
      object : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(v: View?, event: MotionEvent): Boolean {
          val lp = layoutParams ?: return false
          when (event.action) {
            MotionEvent.ACTION_DOWN -> {
              initialX = lp.x
              initialY = lp.y
              touchX = event.rawX
              touchY = event.rawY
              return true
            }
            MotionEvent.ACTION_MOVE -> {
              lp.x = initialX + (event.rawX - touchX).toInt()
              lp.y = initialY + (event.rawY - touchY).toInt()
              try {
                windowManager.updateViewLayout(root, lp)
              } catch (_: Exception) {}
              return true
            }
          }
          return false
        }
      }
    )

    overlayRoot = root
    updateHzChipsVisual()
    try {
      windowManager.addView(root, params)
    } catch (_: Exception) {
      stopSelf()
    }
  }

  private fun createHzChip(label: String, hz: Int): TextView {
    return TextView(this).apply {
      text = label
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      typeface = Typeface.DEFAULT_BOLD
      setPadding(dp(12), dp(8), dp(12), dp(8))
      setOnClickListener { applyHzRealtime(hz) }
    }
  }

  private fun applyHzRealtime(hz: Int) {
    selectedHz = hz
    updateHzChipsVisual()
    telemetryManager.pulseHaptic(false)

    val option = RefreshRateOption.entries.firstOrNull { it.hz == hz } ?: RefreshRateOption.HZ_120
    layoutParams?.let { lp ->
      lp.preferredRefreshRate = hz.toFloat()
      overlayRoot?.let { root ->
        try {
          windowManager.updateViewLayout(root, lp)
        } catch (_: Exception) {}
      }
    }

    telemetryManager.applySystemRefreshRateIfAllowed(hz)

    scope.launch {
      repository.setTargetRefreshRate(option)
      val currentSettings = repository.settingsFlow.first().copy(targetRefreshRate = option, fpsUnlocked = true)
      shizukuBridge.applyBoostProfile(currentSettings)
      refreshTelemetryLine()
    }
  }

  private fun updateHzChipsVisual() {
    listOf(60 to btn60, 90 to btn90, 120 to btn120).forEach { (hz, view) ->
      val active = selectedHz == hz
      val bg =
        GradientDrawable().apply {
          cornerRadius = dp(10).toFloat()
          if (active) {
            setColor(Color.parseColor("#FF1E42"))
            setStroke(dp(1), Color.parseColor("#FFCCD5"))
          } else {
            setColor(Color.parseColor("#2E0A14"))
            setStroke(dp(1), Color.parseColor("#7A162B"))
          }
        }
      view?.background = bg
      view?.setTextColor(if (active) Color.WHITE else Color.parseColor("#FFB3C1"))
    }
  }

  private fun toggleMinimize() {
    isMinimized = !isMinimized
    bodyRow?.visibility = if (isMinimized) View.GONE else View.VISIBLE
    minimizeBtn?.text = if (isMinimized) "+" else "—"
  }

  private fun startLiveTelemetryLoop() {
    telemetryJob?.cancel()
    telemetryJob =
      scope.launch {
        while (isActive) {
          refreshTelemetryLine()
          delay(1200L)
        }
      }
  }

  private fun refreshTelemetryLine() {
    val t = telemetryManager.readTelemetry(selectedHz, 1000f / selectedHz.toFloat())
    statsTextView?.text =
      "${selectedHz}Hz (Tela ${t.currentDisplayHz}Hz) • ${String.format("%.1f", t.batteryTempC)}°C • ${t.ramAvailMb}MB"
  }

  private fun stopOverlayAndSelf() {
    telemetryJob?.cancel()
    pulseAnimator?.cancel()
    overlayRoot?.let {
      try {
        windowManager.removeView(it)
      } catch (_: Exception) {}
    }
    overlayRoot = null
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun buildNotification(): Notification {
    val openIntent =
      Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      }
    val openPending =
      PendingIntent.getActivity(
        this,
        301,
        openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val stopIntent = Intent(this, FloatingHzOverlayService::class.java).apply { action = ACTION_STOP }
    val stopPending =
      PendingIntent.getService(
        this,
        302,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("Infinit Booster v3.0 • Painel Flutuante Hz")
      .setContentText("Controle vermelho de 60/90/120Hz ativo sobre os jogos")
      .setOngoing(true)
      .setSilent(true)
      .setContentIntent(openPending)
      .addAction(0, "Fechar Painel", stopPending)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          "Painel Flutuante Vermelho de Hz",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Exibe o seletor flutuante em tempo real de 60/90/120Hz sobre os jogos"
          setShowBadge(false)
        }
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      nm.createNotificationChannel(channel)
    }
  }

  private fun dp(value: Int): Int =
    TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      value.toFloat(),
      resources.displayMetrics,
    ).toInt()

  override fun onDestroy() {
    telemetryJob?.cancel()
    pulseAnimator?.cancel()
    overlayRoot?.let {
      try {
        windowManager.removeView(it)
      } catch (_: Exception) {}
    }
    shizukuBridge.release()
    scope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
