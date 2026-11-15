package com.example.data

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import com.example.model.BottleneckFinding
import com.example.model.BottleneckSeverity
import com.example.model.DeviceTelemetry
import com.example.model.DiagnosticAction
import com.example.model.InstalledAppItem
import com.example.model.NativeScreenSpec
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

class DeviceTelemetryManager(private val context: Context) {

  private var cachedGpuRenderer: String? = null

  fun readTelemetry(liveFps: Int, avgFrameTimeMs: Float): DeviceTelemetry {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memInfo)

    val ramTotalMb = (memInfo.totalMem / (1024L * 1024L)).coerceAtLeast(1L)
    val ramAvailMb = (memInfo.availMem / (1024L * 1024L)).coerceAtLeast(0L)
    val ramUsedMb = (ramTotalMb - ramAvailMb).coerceAtLeast(0L)
    val ramPercent = ((ramUsedMb.toFloat() / ramTotalMb.toFloat()) * 100f).roundToInt().coerceIn(0, 100)

    val statFs = StatFs(Environment.getDataDirectory().path)
    val totalBytes = statFs.blockCountLong * statFs.blockSizeLong
    val availBytes = statFs.availableBlocksLong * statFs.blockSizeLong
    val usedBytes = (totalBytes - availBytes).coerceAtLeast(0L)
    val bytesPerGb = 1024f * 1024f * 1024f
    val storageTotalGb = (totalBytes / bytesPerGb).coerceAtLeast(0.1f)
    val storageAvailGb = (availBytes / bytesPerGb).coerceAtLeast(0f)
    val storageUsedGb = (usedBytes / bytesPerGb).coerceAtLeast(0f)
    val storagePercent = ((usedBytes.toDouble() / totalBytes.coerceAtLeast(1L).toDouble()) * 100.0)
      .roundToInt()
      .coerceIn(0, 100)

    val batteryStatus =
      context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val tempTenths = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 320) ?: 320
    val batteryTempC = tempTenths / 10f
    val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
    val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
    val batteryPercent = ((level * 100f) / scale.toFloat()).roundToInt().coerceIn(0, 100)
    val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val isCharging =
      status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isPowerSaveMode = powerManager.isPowerSaveMode
    val (thermalLabel, thermalSeverity) = readThermalState(powerManager, batteryTempC)

    val pm = context.packageManager
    val vulkanSupported = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
    val vulkanVersionLabel =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && vulkanSupported) {
        val features = pm.systemAvailableFeatures
        val vulkanFeature =
          features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
        if (vulkanFeature != null && vulkanFeature.version != 0) {
          val major = (vulkanFeature.version ushr 22) and 0x3FF
          val minor = (vulkanFeature.version ushr 12) and 0x3FF
          "Vulkan $major.$minor Suportado"
        } else {
          "Vulkan Hardware Suportado"
        }
      } else {
        "OpenGL ES / SkiaGL"
      }

    val gpuRenderer = cachedGpuRenderer ?: probeGpuRenderer().also { cachedGpuRenderer = it }

    val (currentHz, maxHz, supportedHz) = readDisplayRefreshRates()

    return DeviceTelemetry(
      deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
      manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
      androidVersion = "Android ${Build.VERSION.RELEASE}",
      sdkInt = Build.VERSION.SDK_INT,
      cpuAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
      cpuCores = Runtime.getRuntime().availableProcessors(),
      cpuClockLabel = readCpuClockLabel(),
      ramTotalMb = ramTotalMb,
      ramUsedMb = ramUsedMb,
      ramAvailMb = ramAvailMb,
      ramPercent = ramPercent,
      isLowMemory = memInfo.lowMemory,
      storageTotalGb = storageTotalGb,
      storageUsedGb = storageUsedGb,
      storageAvailGb = storageAvailGb,
      storagePercent = storagePercent,
      batteryTempC = batteryTempC,
      batteryPercent = batteryPercent,
      isCharging = isCharging,
      thermalStatusLabel = thermalLabel,
      thermalSeverity = thermalSeverity,
      isPowerSaveMode = isPowerSaveMode,
      vulkanSupported = vulkanSupported,
      vulkanVersionLabel = vulkanVersionLabel,
      gpuRendererLabel = gpuRenderer,
      liveFps = liveFps,
      avgFrameTimeMs = avgFrameTimeMs,
      currentDisplayHz = currentHz,
      maxDisplayHz = maxHz,
      supportedHzList = supportedHz,
      screenSpec = readNativeScreenSpec(),
    )
  }

  fun readNativeScreenSpec(): NativeScreenSpec {
    return try {
      val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
      @Suppress("DEPRECATION")
      val display: Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          context.display ?: wm.defaultDisplay
        } else {
          wm.defaultDisplay
        }
      val mode = display?.mode
      val rawW = mode?.physicalWidth ?: context.resources.displayMetrics.widthPixels
      val rawH = mode?.physicalHeight ?: context.resources.displayMetrics.heightPixels
      val portraitW = minOf(rawW, rawH).coerceAtLeast(320)
      val portraitH = maxOf(rawW, rawH).coerceAtLeast(480)
      val dpi = context.resources.displayMetrics.densityDpi.coerceIn(120, 640)
      NativeScreenSpec(widthPx = portraitW, heightPx = portraitH, densityDpi = dpi)
    } catch (_: Exception) {
      NativeScreenSpec()
    }
  }

  private fun readThermalState(
    powerManager: PowerManager,
    batteryTempC: Float,
  ): Pair<String, BottleneckSeverity> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      return when (powerManager.currentThermalStatus) {
        PowerManager.THERMAL_STATUS_NONE -> "Frio / Normal" to BottleneckSeverity.OK
        PowerManager.THERMAL_STATUS_LIGHT -> "Aquecimento Leve" to BottleneckSeverity.OK
        PowerManager.THERMAL_STATUS_MODERATE -> "Aquecimento Moderado" to BottleneckSeverity.WARNING
        PowerManager.THERMAL_STATUS_SEVERE,
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Superaquecimento" to BottleneckSeverity.CRITICAL
        else -> "Estável" to BottleneckSeverity.OK
      }
    }
    return when {
      batteryTempC >= 42f -> "Superaquecimento" to BottleneckSeverity.CRITICAL
      batteryTempC >= 38.5f -> "Aquecimento Moderado" to BottleneckSeverity.WARNING
      else -> "Frio / Normal" to BottleneckSeverity.OK
    }
  }

  private fun readCpuClockLabel(): String {
    return try {
      val curFile = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
      val maxFile = File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")
      val maxKhz = if (maxFile.canRead()) maxFile.readText().trim().toLongOrNull() else null
      val curKhz = if (curFile.canRead()) curFile.readText().trim().toLongOrNull() else null
      when {
        curKhz != null && maxKhz != null ->
          "${(curKhz / 1000)} / ${(maxKhz / 1000)} MHz"
        maxKhz != null -> "Máx ${(maxKhz / 1000)} MHz"
        else -> "${Runtime.getRuntime().availableProcessors()} Núcleos Ativos"
      }
    } catch (_: Exception) {
      "${Runtime.getRuntime().availableProcessors()} Núcleos Ativos"
    }
  }

  private fun readDisplayRefreshRates(): Triple<Int, Int, List<Int>> {
    return try {
      val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
      @Suppress("DEPRECATION")
      val display: Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          context.display ?: wm.defaultDisplay
        } else {
          wm.defaultDisplay
        }
      if (display == null) return Triple(60, 60, listOf(60))
      val currentHz = display.mode.refreshRate.roundToInt().coerceAtLeast(60)
      val supported =
        display.supportedModes
          .map { it.refreshRate.roundToInt() }
          .distinct()
          .sorted()
          .ifEmpty { listOf(currentHz) }
      Triple(currentHz, supported.maxOrNull() ?: currentHz, supported)
    } catch (_: Exception) {
      Triple(60, 60, listOf(60))
    }
  }

  fun applyDisplayRefreshRate(activity: Activity?, targetHz: Int, sustainedPerformance: Boolean) {
    if (activity == null) return
    try {
      val window = activity.window
      window.setSustainedPerformanceMode(sustainedPerformance)

      @Suppress("DEPRECATION")
      val display =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          activity.display ?: activity.windowManager.defaultDisplay
        } else {
          activity.windowManager.defaultDisplay
        }
      val currentMode = display?.mode ?: return
      val sameResModes =
        display.supportedModes.filter {
          it.physicalWidth == currentMode.physicalWidth &&
            it.physicalHeight == currentMode.physicalHeight
        }
      val maxSupportedHz = sameResModes.maxOfOrNull { it.refreshRate } ?: currentMode.refreshRate
      val matchingMode =
        if (targetHz.toFloat() >= maxSupportedHz - 1f) {
          sameResModes.maxByOrNull { it.refreshRate }
        } else {
          sameResModes.minByOrNull { abs(it.refreshRate - targetHz.toFloat()) }
        }
      if (matchingMode != null) {
        val params = window.attributes
        val needsUpdate =
          params.preferredDisplayModeId != matchingMode.modeId ||
            params.preferredRefreshRate != matchingMode.refreshRate
        if (needsUpdate) {
          params.preferredDisplayModeId = matchingMode.modeId
          params.preferredRefreshRate = matchingMode.refreshRate
          window.attributes = params
        }
      }
      applySystemRefreshRateIfAllowed(targetHz)
    } catch (_: Exception) {
      // Ignore on devices that disallow preferredDisplayModeId updates
    }
  }

  fun findDisplayModeForHz(targetHz: Int): Pair<Int, Float>? {
    return try {
      val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
      @Suppress("DEPRECATION")
      val display: Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          context.display ?: wm.defaultDisplay
        } else {
          wm.defaultDisplay
        }
      val currentMode = display?.mode ?: return null
      val sameResModes =
        display.supportedModes.filter {
          it.physicalWidth == currentMode.physicalWidth &&
            it.physicalHeight == currentMode.physicalHeight
        }
      if (sameResModes.isEmpty()) return null
      val maxSupportedHz = sameResModes.maxOfOrNull { it.refreshRate } ?: currentMode.refreshRate
      val matchingMode =
        if (targetHz.toFloat() >= maxSupportedHz - 1f) {
          sameResModes.maxByOrNull { it.refreshRate }
        } else {
          sameResModes.minByOrNull { abs(it.refreshRate - targetHz.toFloat()) }
        }
      matchingMode?.let { it.modeId to it.refreshRate }
    } catch (_: Exception) {
      null
    }
  }

  fun applySystemRefreshRateIfAllowed(targetHz: Int): Boolean {
    return try {
      if (Settings.System.canWrite(context)) {
        Settings.System.putFloat(context.contentResolver, "min_refresh_rate", targetHz.toFloat())
        Settings.System.putFloat(context.contentResolver, "peak_refresh_rate", targetHz.toFloat())
        runCatching {
          Settings.System.putFloat(context.contentResolver, "user_refresh_rate", targetHz.toFloat())
        }
        true
      } else {
        false
      }
    } catch (_: Exception) {
      false
    }
  }

  private fun probeGpuRenderer(): String {
    return try {
      val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      val version = IntArray(2)
      if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
        return "GPU OpenGL ES"
      }
      val configAttribs =
        intArrayOf(
          EGL14.EGL_RENDERABLE_TYPE,
          EGL14.EGL_OPENGL_ES2_BIT,
          EGL14.EGL_SURFACE_TYPE,
          EGL14.EGL_PBUFFER_BIT,
          EGL14.EGL_NONE,
        )
      val configs = arrayOfNulls<EGLConfig>(1)
      val numConfigs = IntArray(1)
      EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
      val config = configs.firstOrNull() ?: return "GPU OpenGL ES"
      val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
      val eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
      val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
      val surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
      EGL14.eglMakeCurrent(display, surface, surface, eglContext)
      val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "GPU OpenGL ES"
      EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
      EGL14.eglDestroySurface(display, surface)
      EGL14.eglDestroyContext(display, eglContext)
      renderer
    } catch (_: Exception) {
      "GPU OpenGL ES"
    }
  }

  fun performMemoryAndCacheCleanup(): Long {
    val beforeAvail = readAvailRamMb()
    try {
      context.cacheDir?.listFiles()?.forEach { file ->
        if (file.isDirectory) file.deleteRecursively() else file.delete()
      }
      System.runFinalization()
      Runtime.getRuntime().gc()
    } catch (_: Exception) {
      // Safe fallback
    }
    val afterAvail = readAvailRamMb()
    return (afterAvail - beforeAvail).coerceAtLeast(0L)
  }

  private fun readAvailRamMb(): Long {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    return info.availMem / (1024L * 1024L)
  }

  fun queryLaunchableApps(): List<InstalledAppItem> {
    val pm = context.packageManager
    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
      addCategory(Intent.CATEGORY_LAUNCHER)
    }
    val resolved =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
      } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(mainIntent, 0)
      }

    return resolved
      .mapNotNull { info ->
        val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
        if (pkg == context.packageName) return@mapNotNull null
        val appInfo = info.activityInfo.applicationInfo
        val isGame =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appInfo.category == ApplicationInfo.CATEGORY_GAME
          } else {
            @Suppress("DEPRECATION")
            (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
          }
        val label = info.loadLabel(pm)?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: pkg
        InstalledAppItem(packageName = pkg, label = label, isGameCategory = isGame)
      }
      .distinctBy { it.packageName }
      .sortedWith(compareByDescending<InstalledAppItem> { it.isGameCategory }.thenBy { it.label.lowercase() })
  }

  fun loadAppIcon(packageName: String): Drawable? {
    return try {
      context.packageManager.getApplicationIcon(packageName)
    } catch (_: Exception) {
      null
    }
  }

  fun launchPackage(packageName: String): Boolean {
    return try {
      val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
      launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(launchIntent)
      true
    } catch (_: Exception) {
      false
    }
  }

  fun analyzeBottlenecks(
    telemetry: DeviceTelemetry,
    fpsUnlocked: Boolean,
    ignoreThermalLimit: Boolean,
  ): List<BottleneckFinding> {
    val findings = mutableListOf<BottleneckFinding>()

    // 1. RAM Pressure Check
    when {
      telemetry.isLowMemory || telemetry.ramPercent >= 85 -> {
        findings +=
          BottleneckFinding(
            id = "ram_high",
            title = "Memória RAM sobrecarregada (${telemetry.ramPercent}%)",
            detail =
              "Restam apenas ${telemetry.ramAvailMb} MB livres de ${telemetry.ramTotalMb} MB. Isso força o Android a encerrar processos e gera travamentos bruscos.",
            recommendation = "Execute a limpeza imediata de cache e liberação de memória.",
            severity = BottleneckSeverity.CRITICAL,
            action = DiagnosticAction.OPTIMIZE_RAM,
          )
      }
      telemetry.ramPercent >= 70 -> {
        findings +=
          BottleneckFinding(
            id = "ram_warn",
            title = "Uso elevado de RAM (${telemetry.ramPercent}%)",
            detail =
              "${telemetry.ramUsedMb} MB ocupados no momento. Jogos pesados podem sofrer quedas de FPS ao carregar texturas.",
            recommendation = "Otimize a RAM antes de entrar no jogo.",
            severity = BottleneckSeverity.WARNING,
            action = DiagnosticAction.OPTIMIZE_RAM,
          )
      }
    }

    // 2. Thermal Throttling Check
    if (telemetry.thermalSeverity != BottleneckSeverity.OK || telemetry.batteryTempC >= 38.5f) {
      val severity =
        if (telemetry.batteryTempC >= 41.5f || telemetry.thermalSeverity == BottleneckSeverity.CRITICAL) {
          BottleneckSeverity.CRITICAL
        } else {
          BottleneckSeverity.WARNING
        }
      val thermalNote =
        if (ignoreThermalLimit) {
          "Modo Ignorar Aquecimento ativo (carga máxima mantida), mas o calor físico ainda exige resfriamento."
        } else {
          "O sistema reduz automaticamente o clock da CPU/GPU quando a bateria passa de 38,5°C."
        }
      findings +=
        BottleneckFinding(
          id = "thermal",
          title = "Aquecimento detectado (${String.format("%.1f", telemetry.batteryTempC)}°C)",
          detail = "${telemetry.thermalStatusLabel}. $thermalNote",
          recommendation = "Ative o Painel Cooler Criogênico e reduza o brilho da tela.",
          severity = severity,
          action = DiagnosticAction.ACTIVATE_COOLER,
        )
    }

    // 3. Storage Saturation Check
    if (telemetry.storagePercent >= 88) {
      findings +=
        BottleneckFinding(
          id = "storage_full",
          title = "Armazenamento quase cheio (${telemetry.storagePercent}%)",
          detail =
            "Apenas ${String.format("%.1f", telemetry.storageAvailGb)} GB livres. Memória flash lotada reduz a velocidade de leitura de mapas e assets.",
          recommendation = "Limpe caches temporários e mantenha pelo menos 15% livre.",
          severity = if (telemetry.storagePercent >= 94) BottleneckSeverity.CRITICAL else BottleneckSeverity.WARNING,
          action = DiagnosticAction.OPTIMIZE_RAM,
        )
    }

    // 4. Power Save Mode Check
    if (telemetry.isPowerSaveMode) {
      findings +=
        BottleneckFinding(
          id = "power_save",
          title = "Economia de Bateria está limitando o aparelho",
          detail =
            "O Modo Economia do Android corta a frequência da CPU, reduz animações e limita a taxa de quadros.",
          recommendation = "Desative a Economia de Bateria nas configurações do sistema.",
          severity = BottleneckSeverity.CRITICAL,
          action = DiagnosticAction.OPEN_BATTERY_SETTINGS,
        )
    }

    // 5. FPS Cap / Frame Pacing Check
    if (!fpsUnlocked || telemetry.avgFrameTimeMs > 20f) {
      findings +=
        BottleneckFinding(
          id = "fps_jitter",
          title =
            if (!fpsUnlocked) {
              "Desbloqueio de FPS está desativado"
            } else {
              "Instabilidade de quadros (${String.format("%.1f", telemetry.avgFrameTimeMs)} ms)"
            },
          detail =
            if (!fpsUnlocked) {
              "O perfil atual não está solicitando taxa máxima de atualização ao display."
            } else {
              "Tempo médio entre quadros acima do ideal para ${telemetry.currentDisplayHz}Hz (${telemetry.liveFps} FPS atuais)."
            },
          recommendation = "Ative o Modo Infinito de FPS e selecione 90/120Hz.",
          severity = BottleneckSeverity.WARNING,
          action = DiagnosticAction.UNLOCK_FPS,
        )
    }

    if (findings.isEmpty()) {
      findings +=
        BottleneckFinding(
          id = "all_clear",
          title = "Nenhum gargalo crítico detectado",
          detail =
            "RAM (${telemetry.ramPercent}%), Armazenamento (${telemetry.storagePercent}%) e Temperatura (${String.format("%.1f", telemetry.batteryTempC)}°C) estão em faixa ideal.",
          recommendation = "Seu aparelho está pronto para rodar com fluidez máxima.",
          severity = BottleneckSeverity.OK,
          action = DiagnosticAction.NONE,
        )
    }

    return findings
  }

  fun pulseHaptic(heavy: Boolean = false) {
    try {
      val duration = if (heavy) 55L else 22L
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator.vibrate(
          VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
        )
      } else {
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          vibrator.vibrate(
            VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
          )
        } else {
          @Suppress("DEPRECATION")
          vibrator.vibrate(duration)
        }
      }
    } catch (_: Exception) {
      // Ignore if vibrator unavailable
    }
  }
}
