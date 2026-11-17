package com.example.overlay

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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.BoosterPreferencesRepository
import com.example.data.DeviceTelemetryManager
import com.example.model.NativeScreenSpec
import com.example.resolution.ResolutionHelper
import com.example.shizuku.ShizukuBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FloatingResolutionOverlayService : Service() {

  companion object {
    const val CHANNEL_ID = "infinit_floating_res_channel"
    const val NOTIFICATION_ID = 3091
    const val ACTION_START = "com.example.action.FLOAT_RES_START"
    const val ACTION_STOP = "com.example.action.FLOAT_RES_STOP"

    @Volatile var isRunning: Boolean = false

    fun start(context: Context) {
      val intent = Intent(context, FloatingResolutionOverlayService::class.java).apply { action = ACTION_START }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      if (!isRunning) return
      val intent = Intent(context, FloatingResolutionOverlayService::class.java).apply { action = ACTION_STOP }
      val sent = runCatching { context.startService(intent) }.isSuccess
      if (!sent) {
        runCatching { context.stopService(Intent(context, FloatingResolutionOverlayService::class.java)) }
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
  private var bodyContainer: LinearLayout? = null
  private var minimizeBtn: TextView? = null
  private var widthInput: EditText? = null
  private var heightInput: EditText? = null
  private var statusLine: TextView? = null
  private var lockAspectCheck: CheckBox? = null
  private var screenSpec = NativeScreenSpec()
  private var isMinimized = false
  private var isUpdatingFields = false

  override fun onCreate() {
    super.onCreate()
    windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    telemetryManager = DeviceTelemetryManager(this)
    repository = BoosterPreferencesRepository(this)
    shizukuBridge = ShizukuBridge(this)
    screenSpec = telemetryManager.readNativeScreenSpec()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      scope.launch {
        repository.setFloatingResolutionOverlayEnabled(false)
        stopOverlayAndSelf()
      }
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

    if (!Settings.canDrawOverlays(this)) {
      scope.launch { repository.setFloatingResolutionOverlayEnabled(false) }
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }
    isRunning = true

    if (overlayRoot == null) {
      attachCustomResolutionOverlay()
    }
    return START_STICKY
  }

  private fun attachCustomResolutionOverlay() {
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
        gravity = Gravity.TOP or Gravity.END
        x = dp(16)
        y = dp(180)
      }
    layoutParams = params

    val rootBg =
      GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.parseColor("#1D0B33"), Color.parseColor("#0A0614")),
      ).apply {
        cornerRadius = dp(18).toFloat()
        setStroke(dp(2), Color.parseColor("#00E5FF"))
      }

    val root =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rootBg
        elevation = dp(14).toFloat()
        setPadding(dp(12), dp(10), dp(12), dp(10))
      }

    // Header bar
    val header =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }
    val title =
      TextView(this).apply {
        text = "RESOLUÇÃO LIVRE • ${screenSpec.aspectRatioLabel}"
        setTextColor(Color.parseColor("#F4F2FF"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = Typeface.DEFAULT_BOLD
      }
    header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

    val minBtn =
      TextView(this).apply {
        text = "—"
        setTextColor(Color.parseColor("#00E5FF"))
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
        setTextColor(Color.parseColor("#FF2A5F"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(8), dp(2), dp(2), dp(2))
        setOnClickListener {
          scope.launch {
            repository.setFloatingResolutionOverlayEnabled(false)
            stopOverlayAndSelf()
          }
        }
      }
    header.addView(closeBtn)
    root.addView(header)

    val body =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, 0)
      }
    bodyContainer = body

    val info =
      TextView(this).apply {
        text = "Nativa: ${screenSpec.widthPx}x${screenSpec.heightPx} • Crie sua resolução:"
        setTextColor(Color.parseColor("#A7A0CC"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
      }
    statusLine = info
    body.addView(info)

    // Custom Width x Height Input Row
    val inputRow =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
      }

    val wField = createNumberField(screenSpec.widthPx.toString(), "Largura")
    val xLabel =
      TextView(this).apply {
        text = " × "
        setTextColor(Color.parseColor("#00E5FF"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
      }
    val hField = createNumberField(screenSpec.heightPx.toString(), "Altura")

    widthInput = wField
    heightInput = hField

    val lockBox =
      CheckBox(this).apply {
        text = "Travar proporção (${screenSpec.aspectRatioLabel})"
        isChecked = true
        setTextColor(Color.parseColor("#D5CEFA"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
      }
    lockAspectCheck = lockBox
    hField.isEnabled = !lockBox.isChecked
    lockBox.setOnCheckedChangeListener { _, isChecked ->
      hField.isEnabled = !isChecked
      if (isChecked) {
        val w = wField.text?.toString()?.toIntOrNull()
        if (w != null && w in 320..4320) {
          isUpdatingFields = true
          val computed = ResolutionHelper.computeFromWidth(screenSpec, w)
          hField.setText(computed.heightPx.toString())
          isUpdatingFields = false
        }
      }
    }

    wField.addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
          if (isUpdatingFields || !lockBox.isChecked) return
          val w = s?.toString()?.toIntOrNull() ?: return
          if (w in 320..4320) {
            isUpdatingFields = true
            val computed = ResolutionHelper.computeFromWidth(screenSpec, w)
            hField.setText(computed.heightPx.toString())
            isUpdatingFields = false
          }
        }
      }
    )

    inputRow.addView(wField, LinearLayout.LayoutParams(dp(74), LinearLayout.LayoutParams.WRAP_CONTENT))
    inputRow.addView(xLabel)
    inputRow.addView(hField, LinearLayout.LayoutParams(dp(74), LinearLayout.LayoutParams.WRAP_CONTENT))

    val minusBtn = createActionChip("-40px", "#1B1636", "#C77DFF") {
      stepResolution(-40)
    }
    val plusBtn = createActionChip("+40px", "#1B1636", "#C77DFF") {
      stepResolution(40)
    }
    inputRow.addView(minusBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
    inputRow.addView(plusBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4) })
    body.addView(inputRow)
    body.addView(lockBox)

    // Action Buttons Row
    val actionsRow =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, 0)
      }

    val applyBtn =
      createActionChip("Aplicar", "#7B2CBF", "#FFFFFF") {
        widthInput?.clearFocus()
        heightInput?.clearFocus()
        setFocusableForKeyboard(false)
        applyTypedResolution()
      }
    val gameBtn =
      createActionChip("Voltar Jogo", "#112233", "#00E5FF") {
        widthInput?.clearFocus()
        heightInput?.clearFocus()
        setFocusableForKeyboard(false)
        toggleMinimize()
      }

    val lpBtn =
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { rightMargin = dp(6) }
    actionsRow.addView(applyBtn, lpBtn)
    actionsRow.addView(gameBtn)
    body.addView(actionsRow)

    root.addView(body)

    // Draggable header
    header.setOnTouchListener(
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
              lp.x = initialX - (event.rawX - touchX).toInt()
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

  private fun createNumberField(initialValue: String, hintText: String): EditText {
    val bg =
      GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(Color.parseColor("#0B0818"))
        setStroke(dp(1), Color.parseColor("#5A189A"))
      }
    return EditText(this).apply {
      setText(initialValue)
      hint = hintText
      inputType = InputType.TYPE_CLASS_NUMBER
      setTextColor(Color.WHITE)
      setHintTextColor(Color.parseColor("#7D75A8"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
      typeface = Typeface.MONOSPACE
      gravity = Gravity.CENTER
      background = bg
      setPadding(dp(6), dp(6), dp(6), dp(6))
      setOnTouchListener { v, event ->
        if (event.action == MotionEvent.ACTION_UP) {
          setFocusableForKeyboard(true)
          v.requestFocus()
          v.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
          }
        }
        false
      }
      setOnClickListener {
        setFocusableForKeyboard(true)
        requestFocus()
        post {
          val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
          imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
      }
      setOnFocusChangeListener { _, hasFocus ->
        if (hasFocus) {
          setFocusableForKeyboard(true)
        }
      }
    }
  }

  private fun createActionChip(label: String, bgHex: String, textHex: String, onClick: () -> Unit): TextView {
    val bg =
      GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(Color.parseColor(bgHex))
        setStroke(dp(1), Color.parseColor(textHex))
      }
    return TextView(this).apply {
      text = label
      background = bg
      setTextColor(Color.parseColor(textHex))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
      typeface = Typeface.DEFAULT_BOLD
      setPadding(dp(10), dp(7), dp(10), dp(7))
      setOnClickListener { onClick() }
    }
  }

  private fun setFocusableForKeyboard(focusable: Boolean) {
    val lp = layoutParams ?: return
    val root = overlayRoot ?: return
    val newFlags =
      if (focusable) {
        lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
      } else {
        lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
      }
    if (lp.flags != newFlags) {
      lp.flags = newFlags
      try {
        windowManager.updateViewLayout(root, lp)
      } catch (_: Exception) {}
    }
    if (!focusable) {
      val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
      imm.hideSoftInputFromWindow(root.windowToken, 0)
    }
  }

  private fun stepResolution(deltaW: Int) {
    val curW = widthInput?.text?.toString()?.toIntOrNull() ?: screenSpec.widthPx
    val nextW = (curW + deltaW).coerceIn(360, 4320)
    isUpdatingFields = true
    widthInput?.setText(nextW.toString())
    if (lockAspectCheck?.isChecked == true) {
      val c = ResolutionHelper.computeFromWidth(screenSpec, nextW)
      heightInput?.setText(c.heightPx.toString())
    }
    isUpdatingFields = false
  }

  private fun applyTypedResolution() {
    val w = widthInput?.text?.toString()?.toIntOrNull()?.coerceIn(360, 4320) ?: screenSpec.widthPx
    val h = heightInput?.text?.toString()?.toIntOrNull()?.coerceIn(480, 7680) ?: screenSpec.heightPx
    val dpi = ResolutionHelper.computeProportionalDpi(screenSpec, w)
    val pct = ((w.toFloat() / screenSpec.widthPx.coerceAtLeast(1)) * 100f).toInt().coerceIn(35, 150)

    scope.launch {
      shizukuBridge.refreshState()
      val msg = shizukuBridge.applyCustomResolution(w, h, dpi)
      repository.setResolutionConfig(pct, w, h, lockAspectCheck?.isChecked ?: true)
      statusLine?.text = msg ?: "Autorize o Shizuku no app para aplicar ${w}x${h}"
    }
  }

  private fun toggleMinimize() {
    isMinimized = !isMinimized
    if (isMinimized) {
      widthInput?.clearFocus()
      heightInput?.clearFocus()
      setFocusableForKeyboard(false)
    }
    bodyContainer?.visibility = if (isMinimized) View.GONE else View.VISIBLE
    minimizeBtn?.text = if (isMinimized) "+" else "—"
  }

  private fun stopOverlayAndSelf() {
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
        305,
        openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val stopIntent = Intent(this, FloatingResolutionOverlayService::class.java).apply { action = ACTION_STOP }
    val stopPending =
      PendingIntent.getService(
        this,
        306,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("Infinit Booster v3.0 • Resolução Flutuante")
      .setContentText("Editor de resolução livre ativo em 2º plano")
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
          "Editor Flutuante de Resolução",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Permite digitar e aplicar qualquer resolução ao vivo dentro dos jogos"
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
    lp.x = lp.x.coerceIn(0, (screenW - dp(220)).coerceAtLeast(0))
    lp.y = lp.y.coerceIn(dp(20), (screenH - dp(180)).coerceAtLeast(0))
    runCatching { windowManager.updateViewLayout(root, lp) }
  }

  override fun onDestroy() {
    isRunning = false
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
