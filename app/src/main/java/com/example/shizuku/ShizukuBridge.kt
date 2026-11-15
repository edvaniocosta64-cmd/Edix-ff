package com.example.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.example.BuildConfig
import com.example.model.BoosterSettings
import com.example.model.GraphicsBackend
import com.example.model.GraphicsRendererStatus
import com.example.model.IphoneTouchConfig
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

enum class ShizukuConnectionState {
  NOT_INSTALLED,
  NOT_RUNNING,
  PERMISSION_REQUIRED,
  BINDING,
  CONNECTED,
}

data class ShizukuStatus(
  val connectionState: ShizukuConnectionState = ShizukuConnectionState.NOT_INSTALLED,
  val privilegedUid: Int? = null,
  val shizukuVersion: Int? = null,
  val lastCommandSummary: String = "Aguardando conexão Shizuku",
)

data class ShizukuBoostReport(
  val executedCount: Int,
  val summary: String,
  val details: List<String>,
)

class ShizukuBridge(private val context: Context) {

  companion object {
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE_SHIZUKU = 9001
  }

  private val _status = MutableStateFlow(ShizukuStatus())
  val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

  private var shellService: IShizukuShellService? = null
  private var isBound = false

  private val userServiceArgs by lazy {
    Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuShellService::class.java.name)
      )
      .daemon(false)
      .processNameSuffix("infinit_shizuku")
      .debuggable(BuildConfig.DEBUG)
      .version(1)
  }

  private val serviceConnection =
    object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val bound = IShizukuShellService.Stub.asInterface(service)
        shellService = bound
        isBound = true
        val uid =
          try {
            bound?.uid
          } catch (_: Exception) {
            null
          }
        val privilegeLabel =
          when (uid) {
            0 -> "UID 0 (Root)"
            2000 -> "UID 2000 (ADB Shell)"
            else -> "UID ${uid ?: "--"}"
          }
        _status.update {
          it.copy(
            connectionState = ShizukuConnectionState.CONNECTED,
            privilegedUid = uid,
            lastCommandSummary = "Shizuku conectado via $privilegeLabel",
          )
        }
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        shellService = null
        isBound = false
        refreshState()
      }
    }

  private val binderReceivedListener =
    Shizuku.OnBinderReceivedListener { refreshState() }

  private val binderDeadListener =
    Shizuku.OnBinderDeadListener {
      shellService = null
      isBound = false
      refreshState()
    }

  private val permissionResultListener =
    Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
      if (requestCode == REQUEST_CODE_SHIZUKU) {
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
          bindShellService()
        } else {
          _status.update {
            it.copy(
              connectionState = ShizukuConnectionState.PERMISSION_REQUIRED,
              lastCommandSummary = "Permissão Shizuku recusada pelo usuário.",
            )
          }
        }
      }
    }

  init {
    try {
      Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
      Shizuku.addBinderDeadListener(binderDeadListener)
      Shizuku.addRequestPermissionResultListener(permissionResultListener)
    } catch (_: Exception) {
      // Safe fallback
    }
    refreshState()
  }

  fun refreshState() {
    val installed = isShizukuAppInstalled()
    val binderAlive =
      try {
        Shizuku.pingBinder()
      } catch (_: Exception) {
        false
      }

    if (!installed && !binderAlive) {
      _status.update {
        it.copy(
          connectionState = ShizukuConnectionState.NOT_INSTALLED,
          privilegedUid = null,
          lastCommandSummary = "App Shizuku não detectado neste aparelho.",
        )
      }
      return
    }

    if (!binderAlive) {
      _status.update {
        it.copy(
          connectionState = ShizukuConnectionState.NOT_RUNNING,
          privilegedUid = null,
          lastCommandSummary = "Shizuku instalado, mas o serviço está parado.",
        )
      }
      return
    }

    val version =
      try {
        Shizuku.getVersion()
      } catch (_: Exception) {
        null
      }

    val granted =
      try {
        !Shizuku.isPreV11() &&
          Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
      } catch (_: Exception) {
        false
      }

    if (!granted) {
      _status.update {
        it.copy(
          connectionState = ShizukuConnectionState.PERMISSION_REQUIRED,
          shizukuVersion = version,
          privilegedUid = null,
          lastCommandSummary = "Shizuku ativo! Toque em Autorizar para liberar os 12 módulos.",
        )
      }
      return
    }

    if (shellService != null && isBound) {
      _status.update {
        it.copy(
          connectionState = ShizukuConnectionState.CONNECTED,
          shizukuVersion = version,
        )
      }
    } else {
      bindShellService()
    }
  }

  fun requestOrConnect(contextForIntent: Context) {
    refreshState()
    when (_status.value.connectionState) {
      ShizukuConnectionState.NOT_INSTALLED -> openShizukuDownload(contextForIntent)
      ShizukuConnectionState.NOT_RUNNING -> openShizukuApp(contextForIntent)
      ShizukuConnectionState.PERMISSION_REQUIRED -> {
        try {
          if (!Shizuku.isPreV11()) {
            Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
          }
        } catch (_: Exception) {
          openShizukuApp(contextForIntent)
        }
      }
      ShizukuConnectionState.BINDING,
      ShizukuConnectionState.CONNECTED -> bindShellService()
    }
  }

  private fun bindShellService() {
    if (shellService != null && isBound) return
    try {
      _status.update {
        it.copy(
          connectionState = ShizukuConnectionState.BINDING,
          lastCommandSummary = "Conectando serviço privilegiado Shizuku...",
        )
      }
      Shizuku.bindUserService(userServiceArgs, serviceConnection)
    } catch (e: Exception) {
      _status.update {
        it.copy(
          connectionState = ShizukuConnectionState.NOT_RUNNING,
          lastCommandSummary = "Falha ao vincular Shizuku: ${e.message ?: "erro"}",
        )
      }
    }
  }

  private suspend fun ensureBound(timeoutMs: Long = 1600L): IShizukuShellService? {
    shellService?.let { return it }
    refreshState()
    return withTimeoutOrNull(timeoutMs) {
      while (shellService == null) {
        if (_status.value.connectionState == ShizukuConnectionState.NOT_INSTALLED ||
          _status.value.connectionState == ShizukuConnectionState.NOT_RUNNING ||
          _status.value.connectionState == ShizukuConnectionState.PERMISSION_REQUIRED
        ) {
          return@withTimeoutOrNull null
        }
        delay(50L)
      }
      shellService
    }
  }

  suspend fun applyGraphicsBackend(
    backend: GraphicsBackend,
    addedGames: Set<String>,
  ): Pair<String?, GraphicsRendererStatus> =
    withContext(Dispatchers.IO) {
      val service = ensureBound()
      if (service == null) {
        return@withContext null to
          GraphicsRendererStatus(
            verifiedViaShizuku = false,
            lastVerifiedLabel = "Conecte e autorize o Shizuku para ativar/verificar a renderização.",
          )
      }

      when (backend) {
        GraphicsBackend.VULKAN_MAX -> {
          runSafe(
            service,
            "settings delete global angle_gl_driver_all_angle; settings delete global angle_gl_driver_selection_pkgs; settings delete global angle_gl_driver_selection_values",
          )
          runSafe(
            service,
            "setprop debug.hwui.renderer skiavk; setprop debug.renderengine.backend skiavkthreaded",
          )
        }
        GraphicsBackend.SKIAGL_MAX -> {
          runSafe(
            service,
            "settings delete global angle_gl_driver_all_angle; settings delete global angle_gl_driver_selection_pkgs; settings delete global angle_gl_driver_selection_values",
          )
          runSafe(
            service,
            "setprop debug.hwui.renderer skiagl; setprop debug.renderengine.backend skiaglthreaded",
          )
        }
        GraphicsBackend.ANGLE_MAX -> {
          runSafe(
            service,
            "setprop debug.hwui.renderer skiagl; setprop debug.renderengine.backend skiaglthreaded; settings put global angle_gl_driver_all_angle 1",
          )
          if (addedGames.isNotEmpty()) {
            val joinedPkgs = addedGames.joinToString(",")
            val joinedVals = addedGames.joinToString(",") { "angle" }
            runSafe(
              service,
              "settings put global angle_gl_driver_selection_pkgs '$joinedPkgs'; settings put global angle_gl_driver_selection_values '$joinedVals'",
            )
          }
        }
      }

      val status = verifyGraphicsBackendStatus(addedGames)
      val summary = "Renderização ${backend.title} aplicada! ${status.lastVerifiedLabel}"
      _status.update { it.copy(lastCommandSummary = summary) }
      summary to status
    }

  suspend fun verifyGraphicsBackendStatus(addedGames: Set<String>): GraphicsRendererStatus =
    withContext(Dispatchers.IO) {
      val service = ensureBound(1000L)
      if (service == null) {
        return@withContext GraphicsRendererStatus(
          verifiedViaShizuku = false,
          lastVerifiedLabel = "Shizuku desconectado — não foi possível ler as propriedades do sistema.",
        )
      }

      val hwuiRaw = readPropValue(service, "getprop debug.hwui.renderer")
      val engineRaw = readPropValue(service, "getprop debug.renderengine.backend")
      val angleAllRaw = readPropValue(service, "settings get global angle_gl_driver_all_angle")
      val angleValsRaw = readPropValue(service, "settings get global angle_gl_driver_selection_values")

      val angleGlobal = angleAllRaw == "1"
      val anglePkgsActive =
        angleValsRaw.contains("angle", ignoreCase = true) && !angleValsRaw.equals("null", ignoreCase = true)

      val hwuiDisplay = hwuiRaw.ifBlank { "Padrão do Sistema" }
      val engineDisplay = engineRaw.ifBlank { "Padrão" }

      val detectedBackend =
        when {
          angleGlobal || anglePkgsActive -> GraphicsBackend.ANGLE_MAX
          hwuiRaw.contains("skiavk", ignoreCase = true) ||
            engineRaw.contains("skiavk", ignoreCase = true) -> GraphicsBackend.VULKAN_MAX
          hwuiRaw.contains("skiagl", ignoreCase = true) -> GraphicsBackend.SKIAGL_MAX
          else -> null
        }

      val angleText =
        when {
          angleGlobal -> "Global Ativo (1)"
          anglePkgsActive -> "Ativo (${addedGames.size.coerceAtLeast(1)} app)"
          else -> "Inativo"
        }

      val summary =
        "HWUI: $hwuiDisplay • Engine: $engineDisplay • ANGLE: $angleText"

      GraphicsRendererStatus(
        hwuiRenderer = hwuiDisplay,
        renderEngineBackend = engineDisplay,
        angleGlobalActive = angleGlobal || anglePkgsActive,
        anglePackagesSummary = angleText,
        verifiedViaShizuku = true,
        activeBackendDetected = detectedBackend,
        lastVerifiedLabel = summary,
      )
    }

  suspend fun restoreGraphicsBackendDefaults(
    addedGames: Set<String>,
  ): Pair<String?, GraphicsRendererStatus> =
    withContext(Dispatchers.IO) {
      val service = ensureBound()
      if (service == null) {
        return@withContext null to
          GraphicsRendererStatus(
            verifiedViaShizuku = false,
            lastVerifiedLabel = "Conecte e autorize o Shizuku para restaurar a renderização.",
          )
      }
      runSafe(
        service,
        "setprop debug.hwui.renderer ''; setprop debug.renderengine.backend ''; settings delete global angle_gl_driver_all_angle; settings delete global angle_gl_driver_selection_pkgs; settings delete global angle_gl_driver_selection_values",
      )
      val status = verifyGraphicsBackendStatus(addedGames)
      val msg = "Renderização restaurada para o padrão de fábrica do sistema."
      _status.update { it.copy(lastCommandSummary = msg) }
      msg to status
    }

  suspend fun applyBoostProfile(
    settings: BoosterSettings,
    targetGamePackage: String? = null,
    forcedMaxHz: Int? = null,
  ): ShizukuBoostReport? =
    withContext(Dispatchers.IO) {
      val service = ensureBound() ?: return@withContext null
      val logs = mutableListOf<String>()

      val hz =
        when {
          forcedMaxHz != null && settings.boostActive ->
            maxOf(forcedMaxHz, settings.targetRefreshRate.hz, 60)
          settings.fpsUnlocked -> settings.targetRefreshRate.hz
          else -> 60
        }
      val hzRes =
        runSafe(
          service,
          "settings put system min_refresh_rate ${hz}.0; settings put system peak_refresh_rate ${hz}.0; settings put system user_refresh_rate ${hz}.0",
        )
      logs += "Taxa ${hz}Hz: $hzRes"

      val effectiveScale = if (settings.boostActive) settings.animationScale else 1.0f
      val animScale = String.format(Locale.US, "%.2f", effectiveScale)
      runSafe(
        service,
        "settings put global window_animation_scale $animScale; settings put global transition_animation_scale $animScale; settings put global animator_duration_scale $animScale",
      )
      logs += "Animação ${animScale}x"

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val perfFlag = if (settings.performanceMax && settings.boostActive) "true" else "false"
        val perfRes = runSafe(service, "cmd power set-fixed-performance-mode-enabled $perfFlag")
        logs += "FixedPerf($perfFlag): $perfRes"
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val thermalCmd =
          if (settings.ignoreThermalLimit && settings.boostActive) {
            "cmd thermalservice override-status 0"
          } else {
            "cmd thermalservice reset"
          }
        val thermalRes = runSafe(service, thermalCmd)
        logs += "Térmico: $thermalRes"
      }

      val (rendererProp, engineProp) =
        when (settings.graphicsBackend) {
          GraphicsBackend.VULKAN_MAX -> "skiavk" to "skiavkthreaded"
          GraphicsBackend.SKIAGL_MAX,
          GraphicsBackend.ANGLE_MAX -> "skiagl" to "skiaglthreaded"
        }
      runSafe(service, "setprop debug.hwui.renderer $rendererProp; setprop debug.renderengine.backend $engineProp")
      logs += "HWUI=$rendererProp"

      val packagesToTune =
        buildList {
          if (targetGamePackage != null) add(targetGamePackage)
          addAll(settings.addedGamePackages)
        }.distinct()

      if (settings.graphicsBackend == GraphicsBackend.ANGLE_MAX) {
        runSafe(service, "settings put global angle_gl_driver_all_angle 1")
        if (packagesToTune.isNotEmpty()) {
          val joinedPkgs = packagesToTune.joinToString(",")
          val joinedVals = packagesToTune.joinToString(",") { "angle" }
          runSafe(
            service,
            "settings put global angle_gl_driver_selection_pkgs '$joinedPkgs'; settings put global angle_gl_driver_selection_values '$joinedVals'",
          )
        }
        logs += "ANGLE Ativo"
      } else {
        runSafe(
          service,
          "settings delete global angle_gl_driver_all_angle; settings delete global angle_gl_driver_selection_pkgs; settings delete global angle_gl_driver_selection_values",
        )
      }

      if (packagesToTune.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        packagesToTune.forEach { pkg ->
          runSafe(service, "cmd game mode performance $pkg")
        }
        logs += "GameMode (${packagesToTune.size})"
      }

      settings.enabledModuleIds.forEach { moduleId ->
        val cmd = ShizukuModulesCatalog.buildApplyCommand(moduleId, settings)
        runSafe(service, cmd)
      }
      if (settings.enabledModuleIds.isNotEmpty()) {
        logs += "${settings.enabledModuleIds.size} módulos aplicados"
      }

      val trimRes = runSafe(service, "pm trim-caches 2147483648; am kill-all")
      logs += "Trim/Kill: $trimRes"

      val summary = "Shizuku aplicou ${logs.size} otimizações (${hz}Hz • ${settings.graphicsBackend.title})"
      _status.update { it.copy(lastCommandSummary = summary) }
      ShizukuBoostReport(executedCount = logs.size, summary = summary, details = logs)
    }

  suspend fun applySingleModule(moduleId: String, settings: BoosterSettings): String? =
    withContext(Dispatchers.IO) {
      val service = ensureBound() ?: return@withContext null
      val module = ShizukuModulesCatalog.modules.firstOrNull { it.id == moduleId } ?: return@withContext null
      val cmd = ShizukuModulesCatalog.buildApplyCommand(moduleId, settings)
      val out = runSafe(service, cmd)
      val msg = "[ATIVADO] #${module.index} ${module.title} → $out"
      _status.update { it.copy(lastCommandSummary = msg) }
      msg
    }

  suspend fun disableSingleModule(moduleId: String, settings: BoosterSettings): String? =
    withContext(Dispatchers.IO) {
      val service = ensureBound() ?: return@withContext null
      val module = ShizukuModulesCatalog.modules.firstOrNull { it.id == moduleId } ?: return@withContext null
      val cmd = ShizukuModulesCatalog.buildDisableCommand(moduleId, settings)
      val out = runSafe(service, cmd)
      val msg = "[DESATIVADO] #${module.index} ${module.title} → $out"
      _status.update { it.copy(lastCommandSummary = msg) }
      msg
    }

  suspend fun sampleAndroid11FpsTelemetry(): Pair<Int?, String?> =
    withContext(Dispatchers.IO) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext null to null
      val service = ensureBound(400L) ?: return@withContext null to null
      return@withContext try {
        val topRaw =
          service.runShellCommand(
            "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -n 1"
          )
        val pkgMatch = Regex("([a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+)/").find(topRaw)
        val topPkg = pkgMatch?.groupValues?.getOrNull(1)

        val latencyCmd =
          if (!topPkg.isNullOrBlank()) {
            "layer=\$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F '$topPkg' | grep -E 'SurfaceView|BLAST' | head -n 1); " +
              "if [ -n \"\$layer\" ]; then dumpsys SurfaceFlinger --latency \"\$layer\" 2>/dev/null | tail -n 30; " +
              "else dumpsys SurfaceFlinger --latency 2>/dev/null | tail -n 30; fi"
          } else {
            "dumpsys SurfaceFlinger --latency 2>/dev/null | tail -n 30"
          }
        val latencyRaw = service.runShellCommand(latencyCmd)
        val timestamps =
          latencyRaw
            .lineSequence()
            .mapNotNull { line ->
              val parts = line.trim().split(Regex("\\s+"))
              if (parts.size >= 2) parts[1].toLongOrNull() else null
            }
            .filter { it > 0L && it != Long.MAX_VALUE }
            .toList()
        val computedFps =
          if (timestamps.size >= 4) {
            val spanNs = (timestamps.last() - timestamps.first()).coerceAtLeast(1L)
            val frames = timestamps.size - 1
            ((frames * 1_000_000_000.0) / spanNs.toDouble()).toInt().coerceIn(1, 240)
          } else {
            null
          }
        computedFps to topPkg
      } catch (_: Exception) {
        null to null
      }
    }

  suspend fun restoreAllDefaults(addedGames: Set<String>): ShizukuBoostReport? =
    withContext(Dispatchers.IO) {
      val service = ensureBound() ?: return@withContext null
      val logs = mutableListOf<String>()
      ShizukuModulesCatalog.buildRestoreAllCommands(addedGames).forEach { (label, cmd) ->
        val out = runSafe(service, cmd)
        logs += "$label: $out"
      }
      val summary = "Restauração concluída via Shizuku (${logs.size} ajustes revertidos ao padrão)."
      _status.update { it.copy(lastCommandSummary = summary) }
      ShizukuBoostReport(executedCount = logs.size, summary = summary, details = logs)
    }

  suspend fun applyRootTotalPerformance(
    enable: Boolean,
    addedGames: Set<String> = emptySet(),
  ): ShizukuBoostReport? =
    withContext(Dispatchers.IO) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext null
      val service = ensureBound() ?: return@withContext null
      val uid =
        try {
          service.uid
        } catch (_: Exception) {
          _status.value.privilegedUid ?: 2000
        }
      val isRootUid = uid == 0
      val logs = mutableListOf<String>()
      var kernelRootApplied = isRootUid
      ShizukuModulesCatalog.buildRootTotalPerformanceCommands(enable, isRootUid, addedGames).forEach { (label, cmd) ->
        val out = runSafe(service, cmd)
        if (out.contains("ROOT_APLICADO")) kernelRootApplied = true
        logs += "$label: $out"
      }
      val summary =
        if (enable) {
          if (kernelRootApplied) {
            "DESEMPENHO TOTAL (Root + Sistema) ativado com sucesso!"
          } else {
            "Desempenho Total ativado no máximo via Shizuku (${logs.size} pacotes de fluidez sem root)!"
          }
        } else {
          "Desempenho Total desativado e restaurado para o padrão."
        }
      _status.update { it.copy(lastCommandSummary = summary) }
      ShizukuBoostReport(executedCount = logs.size, summary = summary, details = logs)
    }

  suspend fun applyCustomResolution(widthPx: Int, heightPx: Int, densityDpi: Int): String? =
    withContext(Dispatchers.IO) {
      val service = ensureBound() ?: return@withContext null
      val out = runSafe(service, "wm size ${widthPx}x${heightPx}; wm density $densityDpi")
      val msg = "Resolução alterada para ${widthPx}x${heightPx} (${densityDpi} DPI) • $out"
      _status.update { it.copy(lastCommandSummary = msg) }
      msg
    }

  suspend fun restoreNativeResolution(): String? =
    withContext(Dispatchers.IO) {
      val service = ensureBound() ?: return@withContext null
      val out = runSafe(service, "wm size reset; wm density reset")
      val msg = "Resolução restaurada para o padrão nativo do aparelho • $out"
      _status.update { it.copy(lastCommandSummary = msg) }
      msg
    }

  suspend fun runCoolerSweep(): String? =
    withContext(Dispatchers.IO) {
      val service = ensureBound() ?: return@withContext null
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runSafe(service, "cmd power set-fixed-performance-mode-enabled false")
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runSafe(service, "cmd thermalservice reset")
      }
      runSafe(service, "am kill-all; pm trim-caches 2147483648")
      val msg = "Shizuku: processos em 2º plano encerrados e perfil térmico restaurado."
      _status.update { it.copy(lastCommandSummary = msg) }
      msg
    }

  suspend fun applyAnimationScale(scale: Float): String? =
    withContext(Dispatchers.IO) {
      val service = ensureBound() ?: return@withContext null
      val scaleStr = String.format(Locale.US, "%.2f", scale.coerceIn(0f, 1f))
      val out =
        runSafe(
          service,
          "settings put global window_animation_scale $scaleStr; settings put global transition_animation_scale $scaleStr; settings put global animator_duration_scale $scaleStr",
        )
      val msg = "Escala de animação aplicada no sistema: ${scaleStr}x ($out)"
      _status.update { it.copy(lastCommandSummary = msg) }
      msg
    }

  suspend fun applyRealtimeRefreshRate(
    hz: Int,
    widthPx: Int = 0,
    heightPx: Int = 0,
  ): String? =
    withContext(Dispatchers.IO) {
      val service = ensureBound() ?: return@withContext null
      val clampedHz = hz.coerceIn(60, 165)
      val cmdBuilder = StringBuilder().apply {
        append("settings put global low_power 0; ")
        append("settings put system min_refresh_rate ${clampedHz}.0; ")
        append("settings put system peak_refresh_rate ${clampedHz}.0; ")
        append("settings put system user_refresh_rate ${clampedHz}.0; ")
        append("settings put secure user_refresh_rate $clampedHz; ")
        append("settings put system miui_refresh_rate $clampedHz; ")
        append("settings put system thermal_limit_refresh_rate $clampedHz")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && widthPx > 0 && heightPx > 0) {
          append("; cmd display set-user-preferred-display-mode $widthPx $heightPx ${clampedHz}.0 2>/dev/null || true")
        }
      }
      val out = runSafe(service, cmdBuilder.toString())
      val msg = "Taxa ${clampedHz}Hz forçada no sistema em tempo real ($out)"
      _status.update { it.copy(lastCommandSummary = msg) }
      msg
    }

  suspend fun applyIphoneTouchModule(
    config: IphoneTouchConfig,
    addedGames: Set<String>,
  ): ShizukuBoostReport? =
    withContext(Dispatchers.IO) {
      val service = ensureBound() ?: return@withContext null
      val logs = mutableListOf<String>()
      ShizukuModulesCatalog.buildIphoneTouchCommands(config, addedGames).forEach { (label, cmd) ->
        val out = runSafe(service, cmd)
        logs += "$label: $out"
      }
      val summary =
        if (config.enabled) {
          "Módulo iPhone Touch (${config.preset.label}) ativo • ${logs.size} ajustes executados!"
        } else {
          "Módulo iPhone Touch desativado e toque restaurado."
        }
      _status.update { it.copy(lastCommandSummary = summary) }
      ShizukuBoostReport(executedCount = logs.size, summary = summary, details = logs)
    }

  suspend fun restoreTouchDefaults(): String? =
    withContext(Dispatchers.IO) {
      val service = ensureBound() ?: return@withContext null
      val out = runSafe(service, ShizukuModulesCatalog.buildRestoreTouchDefaultsCommand())
      val msg = "Sensibilidade e script de toque restaurados ao padrão ($out)"
      _status.update { it.copy(lastCommandSummary = msg) }
      msg
    }

  suspend fun applyGameSessionTweaks(
    customDpi: Int?,
    touchScriptEnabled: Boolean,
    touchSensitivity: Int,
  ): String? =
    withContext(Dispatchers.IO) {
      val service = ensureBound() ?: return@withContext null
      val parts = mutableListOf<String>()
      if (customDpi != null && customDpi in 400..1440) {
        val dpiOut = runSafe(service, "wm density $customDpi")
        parts += "DPI $customDpi ($dpiOut)"
      }
      if (touchScriptEnabled) {
        val touchOut =
          runSafe(service, ShizukuModulesCatalog.buildGameTouchScriptCommand(touchSensitivity))
        parts += "Script Touch Nível $touchSensitivity ($touchOut)"
      }
      val summary =
        if (parts.isEmpty()) "Sessão de jogo pronta."
        else "Ativo no jogo: ${parts.joinToString(" • ")}"
      _status.update { it.copy(lastCommandSummary = summary) }
      summary
    }

  suspend fun restoreGameSessionTweaks(
    restoreDpi: Boolean,
    restoreTouch: Boolean,
  ): String? =
    withContext(Dispatchers.IO) {
      val service = ensureBound() ?: return@withContext null
      val parts = mutableListOf<String>()
      if (restoreDpi) {
        val out = runSafe(service, "wm density reset")
        parts += "DPI Nativo ($out)"
      }
      if (restoreTouch) {
        val out = runSafe(service, ShizukuModulesCatalog.buildRestoreTouchDefaultsCommand())
        parts += "Touch Padrão ($out)"
      }
      val summary = "Sessão encerrada: ${parts.joinToString(" • ")}"
      _status.update { it.copy(lastCommandSummary = summary) }
      summary
    }

  suspend fun getTopResumedPackage(): String? =
    withContext(Dispatchers.IO) {
      val service = ensureBound(500L) ?: return@withContext null
      return@withContext try {
        val topRaw =
          service.runShellCommand(
            "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -n 1"
          )
        Regex("([a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+)/").find(topRaw)?.groupValues?.getOrNull(1)
      } catch (_: Exception) {
        null
      }
    }

  private fun readPropValue(service: IShizukuShellService, cmd: String): String {
    val raw = runSafe(service, cmd)
    val stripped = raw.substringAfter("|", raw).trim()
    return if (stripped.equals("null", ignoreCase = true) || stripped.startsWith("exit=")) "" else stripped
  }

  private fun runSafe(service: IShizukuShellService, cmd: String): String {
    return try {
      service.runShellCommand(cmd).take(80)
    } catch (e: Exception) {
      "err:${e.message}"
    }
  }

  private fun isShizukuAppInstalled(): Boolean {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
          SHIZUKU_PACKAGE,
          PackageManager.PackageInfoFlags.of(0L),
        )
      } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
      }
      true
    } catch (_: Exception) {
      false
    }
  }

  private fun openShizukuApp(ctx: Context) {
    try {
      val intent = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
      if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
      } else {
        openShizukuDownload(ctx)
      }
    } catch (_: Exception) {
      openShizukuDownload(ctx)
    }
  }

  private fun openShizukuDownload(ctx: Context) {
    try {
      val playIntent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      ctx.startActivity(playIntent)
    } catch (_: Exception) {
      // Ignore if no browser available
    }
  }

  fun release() {
    try {
      Shizuku.removeBinderReceivedListener(binderReceivedListener)
      Shizuku.removeBinderDeadListener(binderDeadListener)
      Shizuku.removeRequestPermissionResultListener(permissionResultListener)
      if (isBound) {
        shellService?.destroy()
        Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
      }
    } catch (_: Exception) {
      // Ignore cleanup errors
    }
  }
}
