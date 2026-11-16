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
import android.content.res.Configuration
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
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.BoosterPreferencesRepository
import com.example.data.DeviceTelemetryManager
import com.example.model.GraphicsBackend
import com.example.model.RefreshRateOption
import com.example.shizuku.ShizukuBridge
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingHzOverlayService : Service() {

  companion object {
    const val CHANNEL_ID = "infinit_floating_hz_channel"
    const val NOTIFICATION_ID = 3090
    const val ACTION_START = "com.example.action.FLOAT_HZ_START"
    const val ACTION_STOP = "com.example.action.FLOAT_HZ_STOP"

    @Volatile var isRunning: Boolean = false

    fun start(context: Context) {
      val intent = Intent(context, FloatingHzOverlayService::class.java).apply { action = ACTION_START }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      if (!isRunning) return
      val intent = Intent(context, FloatingHzOverlayService::class.java).apply { action = ACTION_STOP }
      val sent = runCatching { context.startService(intent) }.isSuccess
      if (!sent) {
        runCatching { context.stopService(Intent(context, FloatingHzOverlayService::class.java)) }
      }
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private lateinit var windowManager: WindowManager
  private lateinit var telemetryManager: DeviceTelemetryManager
  private lateinit var repository: BoosterPreferencesRepository
  private lateinit var shizukuBridge: ShizukuBridge

  private var overlayRoot: LinearLayout? = null
  private var layoutParams: WindowManager.LayoutParams? = null

  private var miniPillContainer: LinearLayout? = null
  private var expandedContainer: LinearLayout? = null
  private var miniPillText: TextView? = null
  private var statsTextView: TextView? = null

  private val hzButtons = mutableMapOf<Int, TextView>()
  private val backendButtons = mutableMapOf<GraphicsBackend, TextView>()
  private var restoreRenderBtn: TextView? = null
  private val animButtons = mutableListOf<Pair<Float, TextView>>()

  private var miniPulseDot: View? = null
  private var headerPulseDot: View? = null
  private var miniPulseAnimator: ObjectAnimator? = null
  private var headerPulseAnimator: ObjectAnimator? = null
  private var telemetryJob: Job? = null
  private var settingsSyncJob: Job? = null

  private var selectedHz: Int = 120
  private var selectedBackend: GraphicsBackend = GraphicsBackend.VULKAN_MAX
  private var selectedAnimScale: Float = 0.25f
  private var isGameTurboMini: Boolean = false
  private var actionToastText: String? = null
  private var actionToastExpiresAt: Long = 0L

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
        repository.setFloatingHzOverlayEnabled(false)
        stopOverlayAndSelf()
      }
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
    isRunning = true

    if (overlayRoot == null) {
      scope.launch {
        val initial = repository.settingsFlow.first()
        selectedHz = initial.targetRefreshRate.hz
        selectedBackend = initial.graphicsBackend
        selectedAnimScale = initial.animationScale
        attachGameTurboPanel()
        startLiveTelemetryLoop()
        startSettingsSyncLoop()
      }
    }
    return START_STICKY
  }

  private fun attachGameTurboPanel() {
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
        x = dp(12)
        y = dp(96)
        preferredRefreshRate = selectedHz.toFloat()
        telemetryManager.findDisplayModeForHz(selectedHz)?.let { (modeId, rate) ->
          preferredDisplayModeId = modeId
          preferredRefreshRate = rate
        }
      }
    layoutParams = params

    val root =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
      }

    // 1. Ultra-compact Game Turbo Mini Pill (doesn't block game HUD)
    val pill = buildGameTurboMiniPill()
    miniPillContainer = pill
    root.addView(pill)

    // 2. Compact Game Turbo Expanded Floating Panel
    val expanded = buildExpandedGameTurboPanel()
    expandedContainer = expanded
    root.addView(expanded)

    overlayRoot = root
    applyDisplayModeVisibility()
    updateAllChipVisuals()

    try {
      windowManager.addView(root, params)
    } catch (_: Exception) {
      stopSelf()
    }
  }

  private fun buildGameTurboMiniPill(): LinearLayout {
    val pillBg =
      GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(Color.parseColor("#E6210514"), Color.parseColor("#E611030B")),
      ).apply {
        cornerRadius = dp(20).toFloat()
        setStroke(dp(1), Color.parseColor("#FF2A5F"))
      }

    val pill =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pillBg
        elevation = dp(10).toFloat()
        setPadding(dp(10), dp(7), dp(11), dp(7))
      }

    val dot = createPulseDot()
    miniPulseDot = dot
    miniPulseAnimator = createPulseAnimator(dot)
    pill.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(6) })

    val txt =
      TextView(this).apply {
        text = "GT • ${selectedHz}Hz"
        setTextColor(Color.parseColor("#FFF0F5"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.DEFAULT_BOLD
      }
    miniPillText = txt
    pill.addView(txt)

    attachDragAndTapListener(
      target = pill,
      onTap = {
        isGameTurboMini = false
        applyDisplayModeVisibility()
        clampWindowPositionToScreen()
        telemetryManager.pulseHaptic(false)
      },
    )

    return pill
  }

  private fun buildExpandedGameTurboPanel(): LinearLayout {
    val panelBg =
      GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.parseColor("#EB1D0412"), Color.parseColor("#EB0C0208")),
      ).apply {
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), Color.parseColor("#FF2A5F"))
      }

    val panel =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBg
        elevation = dp(12).toFloat()
        setPadding(dp(10), dp(8), dp(10), dp(9))
        layoutParams =
          LinearLayout.LayoutParams(dp(248), LinearLayout.LayoutParams.WRAP_CONTENT)
      }

    // Header (Draggable + Game Turbo Title + Minimize to GT Pill + Close)
    val header =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }

    val dot = createPulseDot()
    headerPulseDot = dot
    headerPulseAnimator = createPulseAnimator(dot)
    header.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(6) })

    val title =
      TextView(this).apply {
        text = "GAME TURBO • INFINIT"
        setTextColor(Color.parseColor("#FFF0F5"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.DEFAULT_BOLD
      }
    header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

    val miniBtn =
      TextView(this).apply {
        text = "GT —"
        setTextColor(Color.parseColor("#00E5FF"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background =
          GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#2200E5FF"))
            setStroke(dp(1), Color.parseColor("#6600E5FF"))
          }
        setOnClickListener {
          isGameTurboMini = true
          applyDisplayModeVisibility()
          telemetryManager.pulseHaptic(false)
        }
      }
    header.addView(miniBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
      rightMargin = dp(6)
    })

    val closeBtn =
      TextView(this).apply {
        text = "✕"
        setTextColor(Color.parseColor("#FF5C7A"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(7), dp(3), dp(5), dp(3))
        setOnClickListener {
          scope.launch {
            repository.setFloatingHzOverlayEnabled(false)
            stopOverlayAndSelf()
          }
        }
      }
    header.addView(closeBtn)

    attachDragAndTapListener(target = header, onTap = null)
    panel.addView(header)

    // Slim live readout line
    val stats =
      TextView(this).apply {
        text = "Tela: ${selectedHz}Hz • Lendo sensor..."
        setTextColor(Color.parseColor("#FF9EB2"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
        typeface = Typeface.MONOSPACE
        maxLines = 1
        setPadding(0, dp(4), 0, dp(6))
      }
    statsTextView = stats
    panel.addView(stats)

    // Section 1: Real-Time Refresh Rate (60 / 90 / 120 [+ 144 if supported])
    panel.addView(createMicroSectionLabel("TAXA DE ATUALIZAÇÃO (TEMPO REAL)"))
    val hzRow =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
      }
    val deviceMaxHz = telemetryManager.readTelemetry(selectedHz, 16.6f).maxDisplayHz
    val hzValues = if (deviceMaxHz >= 144) listOf(60, 90, 120, 144) else listOf(60, 90, 120)
    hzButtons.clear()
    hzValues.forEachIndexed { idx, hz ->
      val chip = createCompactChip("${hz}Hz") { applyHzRealtime(hz) }
      hzButtons[hz] = chip
      val lp =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
          if (idx < hzValues.lastIndex) rightMargin = dp(4)
        }
      hzRow.addView(chip, lp)
    }
    panel.addView(hzRow)

    // Section 2: In-Game Graphics Rendering (Vulkan / SkiaGL / ANGLE + Restore)
    panel.addView(createMicroSectionLabel("RENDERIZAÇÃO NO JOGO"))
    val renderRow =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
      }
    backendButtons.clear()
    val backendSpecs =
      listOf(
        GraphicsBackend.VULKAN_MAX to "Vulkan",
        GraphicsBackend.SKIAGL_MAX to "SkiaGL",
        GraphicsBackend.ANGLE_MAX to "ANGLE",
      )
    backendSpecs.forEach { (backend, shortLabel) ->
      val chip = createCompactChip(shortLabel) { applyGraphicsRealtime(backend) }
      backendButtons[backend] = chip
      val lp =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
          rightMargin = dp(4)
        }
      renderRow.addView(chip, lp)
    }
    val restoreChip =
      createCompactChip("↺") { restoreGraphicsRealtime() }
    restoreRenderBtn = restoreChip
    renderRow.addView(
      restoreChip,
      LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.WRAP_CONTENT),
    )
    panel.addView(renderRow)

    // Section 3: Animation Scale (ONLY 3 OPTIONS: 0,0 | 0,25 | 0,5)
    panel.addView(createMicroSectionLabel("ANIMAÇÃO (SOMENTE 0,0 • 0,25 • 0,5)"))
    val animRow =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
      }
    animButtons.clear()
    val animOptions =
      listOf(
        0.0f to "0,0",
        0.25f to "0,25",
        0.5f to "0,5",
      )
    animOptions.forEachIndexed { idx, (scaleVal, label) ->
      val chip = createCompactChip(label) { applyAnimationScaleRealtime(scaleVal, label) }
      animButtons += scaleVal to chip
      val lp =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
          if (idx < animOptions.lastIndex) rightMargin = dp(4)
        }
      animRow.addView(chip, lp)
    }
    panel.addView(animRow)

    return panel
  }

  private fun createMicroSectionLabel(label: String): TextView {
    return TextView(this).apply {
      text = label
      setTextColor(Color.parseColor("#CBB6E8"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
      typeface = Typeface.DEFAULT_BOLD
      setPadding(0, dp(5), 0, dp(3))
    }
  }

  private fun createCompactChip(label: String, onClick: () -> Unit): TextView {
    return TextView(this).apply {
      text = label
      gravity = Gravity.CENTER
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
      typeface = Typeface.DEFAULT_BOLD
      setPadding(dp(4), dp(6), dp(4), dp(6))
      maxLines = 1
      setOnClickListener { onClick() }
    }
  }

  private fun applyDisplayModeVisibility() {
    miniPillContainer?.visibility = if (isGameTurboMini) View.VISIBLE else View.GONE
    expandedContainer?.visibility = if (isGameTurboMini) View.GONE else View.VISIBLE
  }

  private fun applyHzRealtime(hz: Int) {
    selectedHz = hz
    updateAllChipVisuals()
    telemetryManager.pulseHaptic(false)

    val option =
      when {
        hz >= 120 -> RefreshRateOption.HZ_120
        hz >= 90 -> RefreshRateOption.HZ_90
        else -> RefreshRateOption.HZ_60
      }

    layoutParams?.let { lp ->
      val modeMatch = telemetryManager.findDisplayModeForHz(hz)
      if (modeMatch != null) {
        lp.preferredDisplayModeId = modeMatch.first
        lp.preferredRefreshRate = modeMatch.second
      } else {
        lp.preferredRefreshRate = hz.toFloat()
      }
      overlayRoot?.let { root ->
        try {
          windowManager.updateViewLayout(root, lp)
        } catch (_: Exception) {}
      }
    }

    telemetryManager.applySystemRefreshRateIfAllowed(hz)

    scope.launch {
      repository.setTargetRefreshRate(option)
      repository.setFpsUnlocked(true)
      val spec = telemetryManager.readNativeScreenSpec()
      val res = shizukuBridge.applyRealtimeRefreshRate(hz, spec.widthPx, spec.heightPx)
      showTransientAction(
        if (res != null) "✓ ${hz}Hz aplicado em tempo real"
        else "⚠ ${hz}Hz na janela • Conecte Shizuku p/ forçar tela",
      )
      delay(220L)
      refreshTelemetryLine()
    }
  }

  private fun applyGraphicsRealtime(backend: GraphicsBackend) {
    selectedBackend = backend
    updateAllChipVisuals()
    telemetryManager.pulseHaptic(false)

    scope.launch {
      repository.setGraphicsBackend(backend)
      val currentSettings = repository.settingsFlow.first()
      val topPkg = shizukuBridge.getTopResumedPackage()
      val targetPkgs =
        if (!topPkg.isNullOrBlank() && topPkg != packageName) {
          currentSettings.addedGamePackages + topPkg
        } else {
          currentSettings.addedGamePackages
        }
      val (msg, status) = shizukuBridge.applyGraphicsBackend(backend, targetPkgs)
      val shortName =
        when (backend) {
          GraphicsBackend.VULKAN_MAX -> "Vulkan"
          GraphicsBackend.SKIAGL_MAX -> "SkiaGL"
          GraphicsBackend.ANGLE_MAX -> "ANGLE"
        }
      showTransientAction(
        if (msg != null && status.verifiedViaShizuku) "✓ $shortName ativo no jogo!"
        else "⚠ Conecte Shizuku para ativar $shortName",
      )
      refreshTelemetryLine()
    }
  }

  private fun restoreGraphicsRealtime() {
    telemetryManager.pulseHaptic(false)
    scope.launch {
      val currentSettings = repository.settingsFlow.first()
      repository.setGraphicsBackend(GraphicsBackend.VULKAN_MAX)
      val (msg, _) = shizukuBridge.restoreGraphicsBackendDefaults(currentSettings.addedGamePackages)
      showTransientAction(
        if (msg != null) "✓ Render padrão restaurado!"
        else "⚠ Conecte Shizuku para restaurar",
      )
      refreshTelemetryLine()
    }
  }

  private fun applyAnimationScaleRealtime(scale: Float, label: String) {
    selectedAnimScale = scale
    updateAllChipVisuals()
    telemetryManager.pulseHaptic(false)

    scope.launch {
      repository.setAnimationScale(scale)
      val msg = shizukuBridge.applyAnimationScale(scale)
      showTransientAction(
        if (msg != null) "✓ Animação ${label}x aplicada!"
        else "⚠ ${label}x salvo • Conecte Shizuku",
      )
      refreshTelemetryLine()
    }
  }

  private fun showTransientAction(text: String) {
    actionToastText = text
    actionToastExpiresAt = System.currentTimeMillis() + 2600L
    statsTextView?.text = text
  }

  private fun updateAllChipVisuals() {
    // Hz chips
    hzButtons.forEach { (hz, view) ->
      val active = selectedHz == hz
      styleChip(
        view = view,
        active = active,
        activeFill = "#FF1E42",
        activeBorder = "#FFCCD5",
        inactiveFill = "#2A0916",
        inactiveBorder = "#6E1632",
        inactiveText = "#FFB3C1",
      )
    }

    // Graphics renderer chips
    backendButtons.forEach { (backend, view) ->
      val active = selectedBackend == backend
      styleChip(
        view = view,
        active = active,
        activeFill = "#7B2CBF",
        activeBorder = "#00E5FF",
        inactiveFill = "#1C0B2B",
        inactiveBorder = "#4A2170",
        inactiveText = "#D6C4F0",
      )
    }
    restoreRenderBtn?.let { btn ->
      styleChip(
        view = btn,
        active = false,
        activeFill = "#FF1E42",
        activeBorder = "#FFCCD5",
        inactiveFill = "#230B18",
        inactiveBorder = "#7A2842",
        inactiveText = "#FF8FA3",
      )
    }

    // Animation scale chips (only 0.0, 0.25, 0.5)
    animButtons.forEach { (scale, view) ->
      val active = abs(selectedAnimScale - scale) < 0.04f
      styleChip(
        view = view,
        active = active,
        activeFill = "#008FA3",
        activeBorder = "#A6F6FF",
        inactiveFill = "#0E1D29",
        inactiveBorder = "#234F63",
        inactiveText = "#9EE8F5",
      )
    }
  }

  private fun styleChip(
    view: TextView,
    active: Boolean,
    activeFill: String,
    activeBorder: String,
    inactiveFill: String,
    inactiveBorder: String,
    inactiveText: String,
  ) {
    view.background =
      GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        if (active) {
          setColor(Color.parseColor(activeFill))
          setStroke(dp(1), Color.parseColor(activeBorder))
        } else {
          setColor(Color.parseColor(inactiveFill))
          setStroke(dp(1), Color.parseColor(inactiveBorder))
        }
      }
    view.setTextColor(if (active) Color.WHITE else Color.parseColor(inactiveText))
  }

  private fun startSettingsSyncLoop() {
    settingsSyncJob?.cancel()
    settingsSyncJob =
      scope.launch {
        repository.settingsFlow.collect { s ->
          val changed =
            selectedHz != s.targetRefreshRate.hz ||
              selectedBackend != s.graphicsBackend ||
              abs(selectedAnimScale - s.animationScale) >= 0.02f
          if (changed) {
            selectedHz = s.targetRefreshRate.hz
            selectedBackend = s.graphicsBackend
            selectedAnimScale = s.animationScale
            updateAllChipVisuals()
            refreshTelemetryLine()
          }
        }
      }
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

  private suspend fun refreshTelemetryLine() {
    val t =
      withContext(Dispatchers.IO) {
        telemetryManager.readTelemetry(selectedHz, 1000f / selectedHz.coerceAtLeast(1).toFloat())
      }
    miniPillText?.text = "GT • ${t.currentDisplayHz}Hz"

    val now = System.currentTimeMillis()
    val toast = actionToastText
    if (toast != null && now < actionToastExpiresAt) {
      statsTextView?.text = toast
      return
    }
    actionToastText = null

    val shortRender =
      when (selectedBackend) {
        GraphicsBackend.VULKAN_MAX -> "Vulkan"
        GraphicsBackend.SKIAGL_MAX -> "SkiaGL"
        GraphicsBackend.ANGLE_MAX -> "ANGLE"
      }
    val animTxt =
      when {
        selectedAnimScale <= 0.05f -> "0,0"
        abs(selectedAnimScale - 0.25f) < 0.05f -> "0,25"
        abs(selectedAnimScale - 0.5f) < 0.05f -> "0,5"
        else -> String.format(Locale.US, "%.2f", selectedAnimScale)
      }
    statsTextView?.text =
      "Tela:${t.currentDisplayHz}Hz • ${String.format(Locale.US, "%.1f", t.batteryTempC)}°C • $shortRender • ${animTxt}x"
  }

  private fun attachDragAndTapListener(target: View, onTap: (() -> Unit)?) {
    val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
    target.setOnTouchListener(
      object : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(v: View?, event: MotionEvent): Boolean {
          val lp = layoutParams ?: return false
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              initialX = lp.x
              initialY = lp.y
              touchX = event.rawX
              touchY = event.rawY
              moved = false
              return true
            }
            MotionEvent.ACTION_MOVE -> {
              val dx = (event.rawX - touchX).toInt()
              val dy = (event.rawY - touchY).toInt()
              if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                moved = true
              }
              if (moved) {
                lp.x = initialX + dx
                lp.y = initialY + dy
                overlayRoot?.let { root ->
                  try {
                    windowManager.updateViewLayout(root, lp)
                  } catch (_: Exception) {}
                }
              }
              return true
            }
            MotionEvent.ACTION_UP -> {
              if (!moved && onTap != null) {
                onTap.invoke()
              }
              return true
            }
          }
          return false
        }
      }
    )
  }

  private fun clampWindowPositionToScreen() {
    val lp = layoutParams ?: return
    val root = overlayRoot ?: return
    val (screenW, screenH) =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = windowManager.currentWindowMetrics.bounds
        bounds.width() to bounds.height()
      } else {
        val dm = resources.displayMetrics
        dm.widthPixels to dm.heightPixels
      }
    val maxX = (screenW - dp(252)).coerceAtLeast(0)
    val maxY = (screenH - dp(220)).coerceAtLeast(0)
    lp.x = lp.x.coerceIn(0, maxX)
    lp.y = lp.y.coerceIn(dp(20), maxY)
    try {
      windowManager.updateViewLayout(root, lp)
    } catch (_: Exception) {}
  }

  private fun createPulseDot(): View {
    return View(this).apply {
      background =
        GradientDrawable().apply {
          shape = GradientDrawable.OVAL
          setColor(Color.parseColor("#FF2A5F"))
        }
    }
  }

  private fun createPulseAnimator(dot: View): ObjectAnimator {
    return ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
      duration = 650L
      repeatCount = ValueAnimator.INFINITE
      repeatMode = ValueAnimator.REVERSE
      start()
    }
  }

  private fun stopOverlayAndSelf() {
    telemetryJob?.cancel()
    settingsSyncJob?.cancel()
    miniPulseAnimator?.cancel()
    headerPulseAnimator?.cancel()
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
      .setContentTitle("Infinit Game Turbo • Painel Flutuante")
      .setContentText("Hz em tempo real, Renderização e Animação (0,0 / 0,25 / 0,5) ativos")
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
          "Painel Game Turbo (Hz, Render e Animação)",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Painel compacto estilo Game Turbo sobre os jogos"
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

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    overlayRoot?.post { clampWindowPositionToScreen() }
  }

  override fun onDestroy() {
    isRunning = false
    telemetryJob?.cancel()
    settingsSyncJob?.cancel()
    miniPulseAnimator?.cancel()
    headerPulseAnimator?.cancel()
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
