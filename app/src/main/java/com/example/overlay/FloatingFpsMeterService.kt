package com.example.overlay

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
import android.view.Choreographer
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
import com.example.shizuku.ShizukuBridge
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FloatingFpsMeterService : Service() {

  companion object {
    const val CHANNEL_ID = "infinit_floating_fps_channel"
    const val NOTIFICATION_ID = 3092
    const val ACTION_START = "com.example.action.FLOAT_FPS_START"
    const val ACTION_STOP = "com.example.action.FLOAT_FPS_STOP"

    fun start(context: Context) {
      val intent = Intent(context, FloatingFpsMeterService::class.java).apply { action = ACTION_START }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      val intent = Intent(context, FloatingFpsMeterService::class.java).apply { action = ACTION_STOP }
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
  private var fpsTextView: TextView? = null
  private var detailTextView: TextView? = null
  private var meterJob: Job? = null

  @Volatile private var choreoFps: Int = 60
  @Volatile private var choreoFrameMs: Float = 16.6f
  private var lastFrameNs = 0L
  private var frameCount = 0
  private var accumNs = 0L
  private var runningCallback = false

  private val frameCallback =
    object : Choreographer.FrameCallback {
      override fun doFrame(frameTimeNanos: Long) {
        if (!runningCallback) return
        if (lastFrameNs != 0L) {
          val delta = (frameTimeNanos - lastFrameNs).coerceAtLeast(1L)
          accumNs += delta
          frameCount++
          if (accumNs >= 500_000_000L) {
            val avgNs = accumNs.toFloat() / frameCount.toFloat()
            choreoFps = (1_000_000_000f / avgNs).roundToInt().coerceIn(1, 240)
            choreoFrameMs = avgNs / 1_000_000f
            accumNs = 0L
            frameCount = 0
          }
        }
        lastFrameNs = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
      }
    }

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
      scope.launch {
        repository.setFloatingFpsMeterEnabled(false)
        stopMeterAndSelf()
      }
      return START_NOT_STICKY
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !Settings.canDrawOverlays(this)) {
      scope.launch { repository.setFloatingFpsMeterEnabled(false) }
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
      attachFpsMeterHud()
      runningCallback = true
      Choreographer.getInstance().postFrameCallback(frameCallback)
      startFpsSamplingLoop()
    }
    return START_STICKY
  }

  private fun attachFpsMeterHud() {
    val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
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
        x = dp(16)
        y = dp(48)
      }
    layoutParams = params

    val rootBg =
      GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(Color.parseColor("#CC090612"), Color.parseColor("#CC190B2E")),
      ).apply {
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), Color.parseColor("#22E58A"))
      }

    val root =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rootBg
        elevation = dp(10).toFloat()
        setPadding(dp(10), dp(6), dp(10), dp(6))
      }

    val fpsLabel =
      TextView(this).apply {
        text = "60 FPS"
        setTextColor(Color.parseColor("#22E58A"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
      }
    fpsTextView = fpsLabel
    root.addView(fpsLabel)

    val detail =
      TextView(this).apply {
        text = "  •  16.6ms  •  32°C"
        setTextColor(Color.parseColor("#E0DCFA"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        typeface = Typeface.MONOSPACE
      }
    detailTextView = detail
    root.addView(detail)

    val close =
      TextView(this).apply {
        text = "  ✕"
        setTextColor(Color.parseColor("#FF4D6D"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(4), 0, 0, 0)
        setOnClickListener {
          scope.launch {
            repository.setFloatingFpsMeterEnabled(false)
            stopMeterAndSelf()
          }
        }
      }
    root.addView(close)

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
    try {
      windowManager.addView(root, params)
    } catch (_: Exception) {
      stopSelf()
    }
  }

  private fun startFpsSamplingLoop() {
    meterJob?.cancel()
    meterJob =
      scope.launch {
        while (isActive) {
          val (sfFps, topPkg) = shizukuBridge.sampleAndroid11FpsTelemetry()
          val displayFps = sfFps ?: choreoFps
          val t = telemetryManager.readTelemetry(displayFps, choreoFrameMs)

          val fpsColor =
            when {
              displayFps >= 85 -> Color.parseColor("#00E5FF")
              displayFps >= 50 -> Color.parseColor("#22E58A")
              displayFps >= 35 -> Color.parseColor("#FFB703")
              else -> Color.parseColor("#FF2A5F")
            }
          fpsTextView?.setTextColor(fpsColor)
          fpsTextView?.text = "$displayFps FPS"

          val appShort = topPkg?.substringAfterLast('.')?.take(12) ?: t.deviceName.take(12)
          val srcTag = if (sfFps != null) "SF" else "VSync"
          detailTextView?.text =
            " • ${String.format("%.1f", choreoFrameMs)}ms • ${String.format("%.1f", t.batteryTempC)}°C • ${t.currentDisplayHz}Hz [$srcTag:$appShort]"

          delay(1000L)
        }
      }
  }

  private fun stopMeterAndSelf() {
    runningCallback = false
    Choreographer.getInstance().removeFrameCallback(frameCallback)
    meterJob?.cancel()
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
        307,
        openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val stopIntent = Intent(this, FloatingFpsMeterService::class.java).apply { action = ACTION_STOP }
    val stopPending =
      PendingIntent.getService(
        this,
        308,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("Infinit Booster v3.0 • Medidor de FPS")
      .setContentText("Medidor de FPS em tempo real ativo (Android 11+)")
      .setOngoing(true)
      .setSilent(true)
      .setContentIntent(openPending)
      .addAction(0, "Fechar", stopPending)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          "Medidor de FPS em Tempo Real",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Exibe contador flutuante de FPS e temperatura em Android 11+"
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
    runningCallback = false
    Choreographer.getInstance().removeFrameCallback(frameCallback)
    meterJob?.cancel()
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
