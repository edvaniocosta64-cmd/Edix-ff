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
import com.example.data.AppCrashLogger
import com.example.model.AxExtensionModule
import com.example.model.BoosterSettings
import com.example.model.GameRendererMode
import com.example.model.GraphicsBackend
import com.example.model.GraphicsRendererStatus
import com.example.model.GuardianCause
import com.example.resolution.ResolutionHelper
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
      AppCrashLogger.logEvent(context, "ShizukuBind", e.message ?: "erro ao vincular")
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
        "setprop debug.hwui.renderer ''; setprop debug.renderengine.backend ''; settings delete global angle_gl_driver_all_angle; settings delete global angle_gl_driver_selection_pkgs; settings delete global angle_gl_driver_selection_values; settings delete global game_driver_opt_in_apps; settings delete global updatable_driver_production_opt_in_apps; settings delete global updatable_driver_production_opt_out_apps",
      )
      val status = verifyGraphicsBackendStatus(addedGames)
      val msg = "Renderização restaurada para o padrão de fábrica do sistema."
      _status.update { it.copy(lastCommandSummary = msg) }
      msg to status
    }

  suspend fun applyPerGameRenderer(
    packageName: String,
    mode: GameRendererMode,
  ): String? =
    withContext(Dispatchers.IO) {
      val service = ensureBound(1000L) ?: return@withContext null
      val safePkg = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
      if (safePkg.isBlank()) return@withContext null

      if (mode == GameRendererMode.GLOBAL) {
        updateCommaSeparatedSetting(service, "game_driver_opt_in_apps", safePkg, add = false)
        updateCommaSeparatedSetting(service, "updatable_driver_production_opt_in_apps", safePkg, add = false)
        updateCommaSeparatedSetting(service, "updatable_driver_production_opt_out_apps", safePkg, add = false)
        return@withContext "Renderização de $safePkg restaurada para o perfil Global."
      }

      runSafe(
        service,
        "settings delete global angle_gl_driver_all_angle; settings delete global angle_gl_driver_selection_pkgs; settings delete global angle_gl_driver_selection_values",
      )

      when (mode) {
        GameRendererMode.SKIAVK -> {
          runSafe(
            service,
            "setprop debug.hwui.renderer skiavk; setprop debug.renderengine.backend skiavkthreaded",
          )
          updateCommaSeparatedSetting(service, "updatable_driver_production_opt_out_apps", safePkg, add = false)
          updateCommaSeparatedSetting(service, "game_driver_opt_in_apps", safePkg, add = false)
          updateCommaSeparatedSetting(service, "updatable_driver_production_opt_in_apps", safePkg, add = true)
        }
        GameRendererMode.VULKAN -> {
          runSafe(
            service,
            "setprop debug.hwui.renderer skiavk; setprop debug.renderengine.backend skiavkthreaded",
          )
          updateCommaSeparatedSetting(service, "updatable_driver_production_opt_out_apps", safePkg, add = false)
          updateCommaSeparatedSetting(service, "game_driver_opt_in_apps", safePkg, add = true)
          updateCommaSeparatedSetting(service, "updatable_driver_production_opt_in_apps", safePkg, add = true)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runSafe(service, "cmd game mode performance '$safePkg'")
          }
        }
        GameRendererMode.SKIAGL -> {
          runSafe(
            service,
            "setprop debug.hwui.renderer skiagl; setprop debug.renderengine.backend skiaglthreaded",
          )
          updateCommaSeparatedSetting(service, "game_driver_opt_in_apps", safePkg, add = false)
          updateCommaSeparatedSetting(service, "updatable_driver_production_opt_in_apps", safePkg, add = false)
        }
        GameRendererMode.GLOBAL -> Unit
      }

      val summary = "Render ${mode.label} aplicado em $safePkg"
      _status.update { it.copy(lastCommandSummary = summary) }
      summary
    }

  suspend fun clearPerGameDriverOptIn(packageName: String): Boolean =
    withContext(Dispatchers.IO) {
      val service = ensureBound(600L) ?: return@withContext false
      val safePkg = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
      if (safePkg.isBlank()) return@withContext false
      updateCommaSeparatedSetting(service, "game_driver_opt_in_apps", safePkg, add = false)
      updateCommaSeparatedSetting(service, "updatable_driver_production_opt_in_apps", safePkg, add = false)
      updateCommaSeparatedSetting(service, "updatable_driver_production_opt_out_apps", safePkg, add = false)
      true
    }

  private fun updateCommaSeparatedSetting(
    service: IShizukuShellService,
    key: String,
    pkg: String,
    add: Boolean,
  ) {
    val existing =
      readPropValue(service, "settings get global $key")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        .toMutableList()
    val changed =
      if (add) {
        if (pkg !in existing) {
          existing += pkg
          true
        } else {
          false
        }
      } else {
        existing.removeAll { it == pkg }
      }
    if (!changed) return
    if (existing.isEmpty()) {
      runSafe(service, "settings delete global $key")
    } else {
      val joined = existing.distinct().joinToString(",")
      runSafe(service, "settings put global $key '$joined'")
    }
  }

  suspend fun probeHardwareMaxHzViaShizuku(): Int? =
    withContext(Dispatchers.IO) {
      val service = ensureBound(500L) ?: return@withContext null
      try {
        val raw =
          service.runShellCommand(
            "dumpsys display 2>/dev/null | grep -E 'fps=|refreshRate=|DisplayModeRecord' | head -n 40",
          )
        val matches =
          Regex("(?:fps|refreshRate)\\s*=\\s*(\\d+(?:\\.\\d+)?)")
            .findAll(raw)
            .mapNotNull { it.groupValues.getOrNull(1)?.toFloatOrNull()?.toInt() }
            .filter { it in 60..240 }
            .toList()
        matches.maxOrNull()
      } catch (_: Exception) {
        null
      }
    }

  suspend fun applyBoostProfile(
    settings: BoosterSettings,
    targetGamePackage: String? = null,
    forcedMaxHz: Int? = null,
    screenWidthPx: Int = 0,
    screenHeightPx: Int = 0,
  ): ShizukuBoostReport? =
    withContext(Dispatchers.IO) {
      val service = ensureBound() ?: return@withContext null
      val logs = mutableListOf<String>()

      val hardwareProbeHz =
        if (settings.boostActive && (forcedMaxHz == null || forcedMaxHz <= 60)) {
          val raw =
            runCatching {
              service.runShellCommand(
                "dumpsys display 2>/dev/null | grep -E 'fps=|refreshRate=' | head -n 40",
              )
            }.getOrNull().orEmpty()
          Regex("(?:fps|refreshRate)\\s*=\\s*(\\d+(?:\\.\\d+)?)")
            .findAll(raw)
            .mapNotNull { it.groupValues.getOrNull(1)?.toFloatOrNull()?.toInt() }
            .filter { it in 60..240 }
            .maxOrNull()
        } else {
          null
        }

      val hz =
        when {
          settings.boostActive ->
            maxOf(forcedMaxHz ?: 60, hardwareProbeHz ?: 60, settings.targetRefreshRate.hz, 60)
          settings.fpsUnlocked -> settings.targetRefreshRate.hz
          else -> 0
        }

      if (hz > 0) {
        val hzCmd = StringBuilder().apply {
          if (settings.boostActive) {
            append("settings put global low_power 0; ")
          }
          append("settings put system min_refresh_rate ${hz}.0; ")
          append("settings put system peak_refresh_rate ${hz}.0; ")
          append("settings put system user_refresh_rate ${hz}.0; ")
          append("settings put secure user_refresh_rate $hz; ")
          append("settings put system miui_refresh_rate $hz; ")
          append("settings put system thermal_limit_refresh_rate $hz")
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && screenWidthPx > 0 && screenHeightPx > 0) {
            append("; cmd display set-user-preferred-display-mode $screenWidthPx $screenHeightPx ${hz}.0 2>/dev/null || true")
          }
        }
        val hzRes = runSafe(service, hzCmd.toString())
        logs += "Taxa Máx ${hz}Hz: $hzRes"
      } else {
        runSafe(
          service,
          "settings delete system min_refresh_rate; settings delete system peak_refresh_rate; settings delete system user_refresh_rate",
        )
        logs += "Taxa Auto Restaurada"
      }

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
        val targetGameFps = if (hz > 0) hz else 60
        packagesToTune.forEach { pkg ->
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && settings.boostActive) {
            runSafe(
              service,
              "cmd game mode performance $pkg; cmd game set --mode performance --fps $targetGameFps $pkg 2>/dev/null || cmd game set --fps $targetGameFps $pkg 2>/dev/null || true",
            )
          } else {
            runSafe(service, "cmd game mode performance $pkg")
          }
        }
        logs += "GameMode ${targetGameFps}FPS (${packagesToTune.size})"
      }

      val trimRes = runSafe(service, "pm trim-caches 2147483648; am kill-all")
      logs += "Trim/Kill: $trimRes"

      val hzLabel = if (hz > 0) "${hz}Hz" else "Auto Hz"
      val summary = "Shizuku aplicou ${logs.size} otimizações ($hzLabel • ${settings.graphicsBackend.title})"
      _status.update { it.copy(lastCommandSummary = summary) }
      ShizukuBoostReport(executedCount = logs.size, summary = summary, details = logs)
    }

  suspend fun executeAxExtensionModule(
    module: AxExtensionModule,
    enable: Boolean,
  ): Pair<Boolean, String> =
    withContext(Dispatchers.IO) {
      val service = ensureBound()
        ?: return@withContext (false to "Shizuku não conectado. Conecte o Shizuku para aplicar '${module.name}'.")

      val commands = if (enable) module.applyCommands else module.disableCommands
      if (commands.isEmpty()) {
        val statusMsg =
          if (enable) "Plugin '${module.name}' marcado ativo (${module.pluginType.label})."
          else "Plugin '${module.name}' desativado."
        _status.update { it.copy(lastCommandSummary = statusMsg) }
        return@withContext (true to statusMsg)
      }

      val results = mutableListOf<String>()
      commands.forEach { cmd ->
        val effectiveCmd =
          if (module.requiresRoot && (cmd.startsWith("resetprop ") || cmd.contains("/sys/"))) {
            "su -c '$cmd' 2>/dev/null || true"
          } else {
            cmd
          }
        val out = runSafe(service, effectiveCmd)
        results += out
      }
      val actionWord = if (enable) "ATIVADO" else "DESATIVADO"
      val summary = "[$actionWord] ${module.name} via Shizuku (${commands.size} cmd(s)) → ${results.lastOrNull().orEmpty()}"
      _status.update { it.copy(lastCommandSummary = summary) }
      true to summary
    }

  suspend fun runRawPluginCommand(
    command: String,
    moduleId: String? = null,
  ): Triple<Int, String, String> =
    withContext(Dispatchers.IO) {
      val service = ensureBound(1200L)
        ?: return@withContext Triple(127, "", "Shizuku desconectado. Conecte o Shizuku para executar comandos.")
      val safeId =
        (moduleId ?: "default").lowercase(Locale.US).replace(Regex("[^a-z0-9._-]"), "_")
      val modDir = "/data/local/tmp/infinit_ax/$safeId"
      val normalizedCmd =
        command
          .replace("/data/adb/modules/$safeId", modDir)
          .replace("/data/adb/ax/modules/$safeId", modDir)
          .replace("/data/adb/modules_update/$safeId", modDir)
      val wrapped =
        "mkdir -p '$modDir' 2>/dev/null; export MODDIR='$modDir'; export MODPATH='$modDir'; export AX_MODULE_DIR='$modDir'; cd '$modDir' 2>/dev/null || true; $normalizedCmd"
      val raw =
        try {
          service.runShellCommand(wrapped)
        } catch (e: Exception) {
          return@withContext Triple(1, "", e.message ?: "Erro ao executar no Shizuku")
        }
      parseShellServiceOutput(raw)
    }

  suspend fun syncPluginWorkspaceToTmp(
    module: AxExtensionModule,
    pluginDir: java.io.File,
  ): Boolean =
    withContext(Dispatchers.IO) {
      val service = ensureBound(1000L) ?: return@withContext false
      val safeId = module.id.lowercase(Locale.US).replace(Regex("[^a-z0-9._-]"), "_")
      val modDir = "/data/local/tmp/infinit_ax/$safeId"
      runSafe(service, "mkdir -p '$modDir'")
      module.allFiles
        .filter { it.isTextReadable && it.sizeBytes in 1L..(128 * 1024L) }
        .take(30)
        .forEach { entry ->
          val src = java.io.File(pluginDir, entry.path)
          if (src.exists() && src.isFile) {
            val cleanRel = entry.path.replace("'", "")
            val parentRel = cleanRel.substringBeforeLast("/", "")
            val b64 =
              android.util.Base64.encodeToString(
                src.readBytes(),
                android.util.Base64.NO_WRAP,
              )
            val mkdirPart =
              if (parentRel.isNotBlank()) "mkdir -p '$modDir/$parentRel'; " else ""
            runSafe(service, "${mkdirPart}echo '$b64' | base64 -d > '$modDir/$cleanRel' && chmod +x '$modDir/$cleanRel' 2>/dev/null || true")
          }
        }
      true
    }

  suspend fun executeSinglePluginOption(
    enableCmd: String,
    disableCmd: String,
    enable: Boolean,
    optionTitle: String,
  ): Pair<Boolean, String> =
    withContext(Dispatchers.IO) {
      val cmd = if (enable) enableCmd else disableCmd
      if (cmd.isBlank()) {
        val msg =
          if (enable) "Opção '$optionTitle' marcada ativa."
          else "Opção '$optionTitle' desmarcada (sem comando de reversão no script)."
        return@withContext (true to msg)
      }
      val service = ensureBound()
        ?: return@withContext (false to "Conecte e autorize o Shizuku para alterar '$optionTitle'.")
      val (code, stdout, stderr) = parseShellServiceOutput(
        try {
          service.runShellCommand(cmd)
        } catch (e: Exception) {
          "error=${e.message ?: "erro"}"
        }
      )
      val outText = stdout.ifBlank { stderr }.ifBlank { "exit=$code" }.take(90)
      val verb = if (enable) "ATIVADO" else "DESATIVADO"
      val summary = "[$verb] $optionTitle → $outText"
      _status.update { it.copy(lastCommandSummary = summary) }
      (code == 0 || outText.isNotBlank()) to summary
    }

  suspend fun executePluginScriptText(
    module: AxExtensionModule,
    scriptPath: String,
    scriptText: String,
  ): Triple<Int, String, String> =
    withContext(Dispatchers.IO) {
      val service = ensureBound(1200L)
        ?: return@withContext Triple(127, "", "Conecte e autorize o Shizuku para rodar $scriptPath.")
      val safeId = module.id.lowercase(Locale.US).replace(Regex("[^a-z0-9._-]"), "_")
      val modDir = "/data/local/tmp/infinit_ax/$safeId"
      val b64 =
        android.util.Base64.encodeToString(
          scriptText.toByteArray(Charsets.UTF_8),
          android.util.Base64.NO_WRAP,
        )
      val safeName = scriptPath.substringAfterLast("/").replace(Regex("[^a-zA-Z0-9._-]"), "_")
      val cmd =
        "mkdir -p '$modDir'; export MODDIR='$modDir'; export MODPATH='$modDir'; cd '$modDir'; " +
          "echo '$b64' | base64 -d > '$modDir/$safeName' && chmod 755 '$modDir/$safeName' && sh '$modDir/$safeName'"
      val raw =
        try {
          service.runShellCommand(cmd)
        } catch (e: Exception) {
          return@withContext Triple(1, "", e.message ?: "Erro ao executar $scriptPath")
        }
      parseShellServiceOutput(raw)
    }

  private fun parseShellServiceOutput(raw: String): Triple<Int, String, String> {
    if (raw.startsWith("error=")) {
      return Triple(1, "", raw.removePrefix("error="))
    }
    if (raw.startsWith("exit=")) {
      val header = raw.substringBefore(" | ")
      val code = header.removePrefix("exit=").trim().toIntOrNull() ?: 0
      val body = if (" | " in raw) raw.substringAfter(" | ") else ""
      return if (code == 0) Triple(0, body, "") else Triple(code, body, body)
    }
    return Triple(0, raw, "")
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

  suspend fun applyGameSessionTweaks(
    customDpi: Int?,
    touchScriptEnabled: Boolean,
    touchSensitivity: Int,
    fallbackShortSidePx: Int = 1080,
  ): String? =
    withContext(Dispatchers.IO) {
      val service = ensureBound() ?: return@withContext null
      val parts = mutableListOf<String>()
      var dpiSpeedBoost = 0
      if (customDpi != null && customDpi in 400..1440) {
        val activeShortSide = resolveActiveShortSidePx(service, fallbackShortSidePx)
        val realDensityDpi = ResolutionHelper.computeDensityForSmallestWidth(activeShortSide, customDpi)
        val dpiOut = runSafe(service, "wm density $realDensityDpi")
        dpiSpeedBoost = ResolutionHelper.computeDpiPointerSpeedBoost(customDpi)
        parts += "DPI $customDpi (${realDensityDpi} dpi • $dpiOut)"
      }
      if (touchScriptEnabled || dpiSpeedBoost > 0) {
        val targetSens =
          if (touchScriptEnabled) maxOf(touchSensitivity, dpiSpeedBoost)
          else dpiSpeedBoost
        val touchOut =
          if (touchScriptEnabled) {
            runSafe(service, ShizukuModulesCatalog.buildGameTouchScriptCommand(targetSens))
          } else {
            runSafe(service, "settings put system pointer_speed $targetSens")
          }
        parts += "Touch Nv.$targetSens ($touchOut)"
      }
      val summary =
        if (parts.isEmpty()) "Sessão de jogo pronta."
        else "Ativo no jogo: ${parts.joinToString(" • ")}"
      _status.update { it.copy(lastCommandSummary = summary) }
      summary
    }

  private fun resolveActiveShortSidePx(service: IShizukuShellService, fallbackShortSidePx: Int): Int {
    return try {
      val raw = service.runShellCommand("wm size")
      val overrideMatch = Regex("Override size:\\s*(\\d+)x(\\d+)").find(raw)
      val physicalMatch = Regex("Physical size:\\s*(\\d+)x(\\d+)").find(raw)
      val chosen = overrideMatch ?: physicalMatch
      if (chosen != null) {
        val w = chosen.groupValues[1].toIntOrNull() ?: fallbackShortSidePx
        val h = chosen.groupValues[2].toIntOrNull() ?: fallbackShortSidePx
        minOf(w, h).coerceAtLeast(320)
      } else {
        fallbackShortSidePx.coerceAtLeast(320)
      }
    } catch (_: Exception) {
      fallbackShortSidePx.coerceAtLeast(320)
    }
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

  data class ShizukuLiveGuardianProbe(
    val connected: Boolean,
    val foregroundPackage: String? = null,
    val liveFps: Int? = null,
    val hasAnrOrLag: Boolean = false,
    val loadAvgHigh: Boolean = false,
    val summaryNote: String = "",
  )

  suspend fun probeLiveStuttersViaShizuku(): ShizukuLiveGuardianProbe =
    withContext(Dispatchers.IO) {
      val service = ensureBound(500L) ?: return@withContext ShizukuLiveGuardianProbe(connected = false)
      val (fps, topPkg) = sampleAndroid11FpsTelemetry()
      val anrOrLoadRaw =
        try {
          service.runShellCommand(
            "anr=\$(dumpsys activity processes 2>/dev/null | grep -E 'notResponding=true|ANR in' | head -n 1); " +
              "load=\$(cat /proc/loadavg 2>/dev/null | cut -d' ' -f1); " +
              "echo \"ANR=\${anr}|LOAD=\${load}\""
          )
        } catch (_: Exception) {
          ""
        }
      val hasAnr = anrOrLoadRaw.contains("notResponding=true", true) || anrOrLoadRaw.contains("ANR in", true)
      val loadVal =
        Regex("LOAD=([0-9]+\\.?[0-9]*)").find(anrOrLoadRaw)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
      val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
      val loadHigh = loadVal > cores * 1.35f
      val note =
        buildList {
          if (!topPkg.isNullOrBlank()) add("App: ${topPkg.substringAfterLast('.')}")
          if (fps != null) add("${fps} FPS")
          if (hasAnr) add("ANR detectado")
          if (loadHigh) add("Carga CPU ${String.format(Locale.US, "%.1f", loadVal)}")
        }.joinToString(" • ")
      ShizukuLiveGuardianProbe(
        connected = true,
        foregroundPackage = topPkg,
        liveFps = fps,
        hasAnrOrLag = hasAnr || (fps != null && fps in 1..42),
        loadAvgHigh = loadHigh,
        summaryNote = note,
      )
    }

  suspend fun runGuardianMaintenance(
    trimMemoryAndCache: Boolean,
    liftBatterySaver: Boolean,
    boostActivePackage: String? = null,
  ): List<String> =
    withContext(Dispatchers.IO) {
      val service = ensureBound(600L) ?: return@withContext emptyList()
      val fixed = mutableListOf<String>()
      if (liftBatterySaver) {
        runSafe(service, "settings put global low_power 0; cmd power set-adaptive-power-saver-enabled false")
        fixed += "Economia de energia suspensa via Shizuku"
      }
      if (!boostActivePackage.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runSafe(service, "cmd game mode performance $boostActivePackage")
        fixed += "Prioridade GameMode mantida em $boostActivePackage"
      }
      if (trimMemoryAndCache) {
        runSafe(service, "am kill-all; pm trim-caches 2147483648")
        fixed += "Cache global e processos pesados em 2º plano limpos sem desligar otimizações"
      }
      fixed
    }

  suspend fun healGameStutterBeta(
    packageName: String,
    targetHz: Int = 120,
  ): List<String> =
    withContext(Dispatchers.IO) {
      val service = ensureBound(600L) ?: return@withContext emptyList()
      val safePkg = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
      val clampedHz = targetHz.coerceIn(60, 165)
      val fixed = mutableListOf<String>()

      runSafe(service, "settings put global low_power 0; cmd power set-adaptive-power-saver-enabled false")
      if (safePkg.isNotBlank()) {
        runSafe(service, "am set-standby-bucket '$safePkg' active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          runSafe(
            service,
            "cmd game mode performance '$safePkg'; cmd game set --mode performance --fps $clampedHz '$safePkg' 2>/dev/null || true",
          )
          fixed += "GameMode ${clampedHz}FPS re-priorizado em ${safePkg.substringAfterLast('.')}"
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          runSafe(service, "cmd game mode performance '$safePkg'")
          fixed += "Prioridade GameMode reforçada em ${safePkg.substringAfterLast('.')}"
        }
      }
      runSafe(service, "am kill-all; pm trim-caches 2147483648")
      fixed += "2º plano e caches limpos ao vivo sem desfazer DPI/Touch/Render"
      fixed
    }

  suspend fun executeAdaptiveGuardianFix(
    cause: GuardianCause,
    strategyKey: String,
    activePackage: String? = null,
    targetHz: Int = 120,
    perGameRenderer: GameRendererMode? = null,
    isInGame: Boolean = false,
  ): List<String> =
    withContext(Dispatchers.IO) {
      val service = ensureBound(650L) ?: return@withContext emptyList()
      val safePkg = activePackage?.replace(Regex("[^a-zA-Z0-9._]"), "")?.takeIf { it.isNotBlank() }
      val clampedHz = targetHz.coerceIn(60, 165)
      val actions = mutableListOf<String>()

      when (strategyKey) {
        "RAM_TRIM_LIGHT" -> {
          runSafe(service, "pm trim-caches 1073741824")
          actions += "Trim de caches leves (1 GB) sem interromper o jogo"
        }
        "RAM_AGGRESSIVE_KILL" -> {
          runSafe(service, "am kill-all; pm trim-caches 2147483648")
          actions += "Processos ociosos encerrados e 2 GB de cache liberados"
        }
        "CPU_GAMEMODE_BOOST" -> {
          runSafe(service, "settings put global low_power 0; cmd power set-adaptive-power-saver-enabled false")
          if (safePkg != null) {
            runSafe(service, "am set-standby-bucket '$safePkg' active")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
              runSafe(
                service,
                "cmd game mode performance '$safePkg'; cmd game set --mode performance --fps $clampedHz '$safePkg' 2>/dev/null || true",
              )
              actions += "GameMode ${clampedHz}FPS e prioridade ativa em ${safePkg.substringAfterLast('.')}"
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              runSafe(service, "cmd game mode performance '$safePkg'")
              actions += "Prioridade GameMode aplicada em ${safePkg.substringAfterLast('.')}"
            } else {
              actions += "Prioridade de processo ativo aplicada em ${safePkg.substringAfterLast('.')}"
            }
          } else {
            actions += "Economia adaptativa suspensa para estabilizar CPU"
          }
        }
        "CPU_PERF_LOCK_AND_TRIM" -> {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runSafe(service, "cmd power set-fixed-performance-mode-enabled true")
            actions += "Modo de performance fixa ativado na CPU"
          }
          if (safePkg != null) {
            runSafe(service, "am set-standby-bucket '$safePkg' active")
          }
          runSafe(service, "am kill-all")
          actions += "Concorrência de CPU em 2º plano reduzida"
        }
        "GPU_SF_ZERO_LAG" -> {
          runSafe(
            service,
            "setprop debug.sf.disable_backpressure 1; setprop debug.sf.latch_unsignaled 1",
          )
          actions += "Latência SurfaceFlinger reduzida ao vivo (sem fechar o jogo)"
          if (safePkg != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runSafe(service, "cmd game set --mode performance --fps $clampedHz '$safePkg' 2>/dev/null || true")
            actions += "Meta de ${clampedHz} FPS reforçada"
          }
        }
        "GPU_REASSERT_RENDERER" -> {
          when (perGameRenderer) {
            GameRendererMode.SKIAVK,
            GameRendererMode.VULKAN -> {
              runSafe(service, "setprop debug.hwui.renderer skiavk; setprop debug.renderengine.backend skiavkthreaded")
              actions += "Pipeline Vulkan/SkiaVK reestabilizado ao vivo"
            }
            GameRendererMode.SKIAGL -> {
              runSafe(service, "setprop debug.hwui.renderer skiagl; setprop debug.renderengine.backend skiaglthreaded")
              actions += "Pipeline SkiaGL reestabilizado ao vivo"
            }
            else -> {
              runSafe(service, "setprop debug.sf.disable_backpressure 1; pm trim-caches 536870912")
              actions += "Fila gráfica GPU aliviada ao vivo"
            }
          }
          if (safePkg != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runSafe(service, "cmd game mode performance '$safePkg'")
          }
        }
        "TEMP_RELIEF_SAFE" -> {
          runSafe(service, "am kill-all; pm trim-caches 2147483648")
          actions += "2º plano e caches encerrados p/ aliviar calor mantendo Boost/DPI/Render"
        }
        "TEMP_COOL_FIXED_PERF" -> {
          runSafe(service, "am kill-all; pm trim-caches 2147483648; cmd activity idle-maintenance 2>/dev/null || true")
          actions += "Manutenção ociosa e corte de processos em 2º plano (proteções e otimizações preservadas)"
        }
        "STORAGE_TRIM_DEEP" -> {
          runSafe(service, "pm trim-caches 4294967296")
          actions += "Limpeza profunda de cache de armazenamento (até 4 GB)"
        }
        "POWER_LIFT_SAVERS" -> {
          runSafe(
            service,
            "settings put global low_power 0; settings put global low_power_sticky 0; cmd power set-adaptive-power-saver-enabled false; settings put system min_refresh_rate ${clampedHz}.0; settings put system peak_refresh_rate ${clampedHz}.0",
          )
          actions += "Economia de energia suspensa e ${clampedHz}Hz restaurado"
        }
        "MODULE_REASSERT_BOOST" -> {
          runSafe(
            service,
            "settings put global low_power 0; settings put system min_refresh_rate ${clampedHz}.0; settings put system peak_refresh_rate ${clampedHz}.0",
          )
          if (safePkg != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runSafe(service, "cmd game mode performance '$safePkg'")
          }
          actions += "Parâmetros do Boost e ${clampedHz}Hz reestabelecidos no sistema"
        }
        else -> {
          runSafe(service, "pm trim-caches 1073741824")
          actions +=
            if (isInGame) "Ajuste leve ao vivo (${cause.label}) sem afetar a partida"
            else "Otimização adaptativa aplicada (${cause.label})"
        }
      }
      if (actions.isNotEmpty()) {
        _status.update { it.copy(lastCommandSummary = "IA Guardião: ${actions.first()}") }
      }
      actions
    }

  suspend fun checkAndRepairBoostDriftViaShizuku(
    settings: BoosterSettings,
    targetHz: Int,
  ): List<String> =
    withContext(Dispatchers.IO) {
      if (!settings.boostActive) return@withContext emptyList()
      val service = ensureBound(500L) ?: return@withContext emptyList()
      val repaired = mutableListOf<String>()
      val clampedHz = targetHz.coerceIn(60, 165)

      val lowPower = readPropValue(service, "settings get global low_power")
      if (lowPower == "1") {
        runSafe(service, "settings put global low_power 0")
        repaired += "Economia forçada pelo sistema revertida"
      }

      val minHzRaw = readPropValue(service, "settings get system min_refresh_rate").toFloatOrNull()?.toInt()
      val peakHzRaw = readPropValue(service, "settings get system peak_refresh_rate").toFloatOrNull()?.toInt()
      if ((minHzRaw != null && minHzRaw < clampedHz - 2) || (peakHzRaw != null && peakHzRaw < clampedHz - 2)) {
        runSafe(
          service,
          "settings put system min_refresh_rate ${clampedHz}.0; settings put system peak_refresh_rate ${clampedHz}.0; settings put system user_refresh_rate ${clampedHz}.0",
        )
        repaired += "Taxa ${clampedHz}Hz re-forçada após queda automática"
      }

      val winScale = readPropValue(service, "settings get global window_animation_scale").toFloatOrNull()
      if (winScale != null && winScale > settings.animationScale + 0.15f) {
        val animScale = String.format(Locale.US, "%.2f", settings.animationScale)
        runSafe(
          service,
          "settings put global window_animation_scale $animScale; settings put global transition_animation_scale $animScale; settings put global animator_duration_scale $animScale",
        )
        repaired += "Escala de animação ${animScale}x restaurada"
      }

      repaired
    }

  suspend fun getTopResumedPackage(): String? =
    withContext(Dispatchers.IO) {
      val service = ensureBound(500L) ?: return@withContext null
      return@withContext try {
        val topRaw =
          service.runShellCommand(
            "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity' | head -n 1"
          )
        val firstMatch = Regex("([a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+)/").find(topRaw)?.groupValues?.getOrNull(1)
        if (!firstMatch.isNullOrBlank()) return@withContext firstMatch
        val winRaw =
          service.runShellCommand(
            "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' | head -n 2"
          )
        Regex("([a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+)/").find(winRaw)?.groupValues?.getOrNull(1)
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
      AppCrashLogger.logEvent(context, "ShizukuCmd", "${cmd.take(40)} -> ${e.message ?: "erro"}")
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
