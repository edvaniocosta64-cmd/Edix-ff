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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
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

  suspend fun applyBoostProfile(
    settings: BoosterSettings,
    targetGamePackage: String? = null,
  ): ShizukuBoostReport? =
    withContext(Dispatchers.IO) {
      val service = shellService ?: return@withContext null
      val logs = mutableListOf<String>()

      val hz = if (settings.fpsUnlocked) settings.targetRefreshRate.hz else 60
      val hzRes =
        runSafe(
          service,
          "settings put system min_refresh_rate ${hz}.0; settings put system peak_refresh_rate ${hz}.0",
        )
      logs += "Taxa ${hz}Hz: $hzRes"

      val animScale = if (settings.fpsUnlocked) "0.25" else "1.0"
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

      val rendererProp =
        when (settings.graphicsBackend) {
          GraphicsBackend.VULKAN_MAX -> "skiavk"
          GraphicsBackend.SKIAGL_MAX,
          GraphicsBackend.ANGLE_MAX -> "skiagl"
        }
      runSafe(service, "setprop debug.hwui.renderer $rendererProp")
      logs += "HWUI=$rendererProp"

      val packagesToTune =
        buildList {
          if (targetGamePackage != null) add(targetGamePackage)
          addAll(settings.addedGamePackages)
        }.distinct()

      if (packagesToTune.isNotEmpty()) {
        if (settings.graphicsBackend == GraphicsBackend.ANGLE_MAX) {
          val joinedPkgs = packagesToTune.joinToString(",")
          val joinedVals = packagesToTune.joinToString(",") { "angle" }
          runSafe(
            service,
            "settings put global angle_gl_driver_selection_pkgs '$joinedPkgs'; settings put global angle_gl_driver_selection_values '$joinedVals'",
          )
          logs += "ANGLE (${packagesToTune.size} app)"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          packagesToTune.forEach { pkg ->
            runSafe(service, "cmd game mode performance $pkg")
          }
          logs += "GameMode (${packagesToTune.size})"
        }
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
      val service = shellService ?: return@withContext null
      val module = ShizukuModulesCatalog.modules.firstOrNull { it.id == moduleId } ?: return@withContext null
      val cmd = ShizukuModulesCatalog.buildApplyCommand(moduleId, settings)
      val out = runSafe(service, cmd)
      val msg = "[ATIVADO] #${module.index} ${module.title} → $out"
      _status.update { it.copy(lastCommandSummary = msg) }
      msg
    }

  suspend fun disableSingleModule(moduleId: String, settings: BoosterSettings): String? =
    withContext(Dispatchers.IO) {
      val service = shellService ?: return@withContext null
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
      val service = shellService ?: return@withContext null to null
      return@withContext try {
        val topRaw =
          service.runShellCommand(
            "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -n 1"
          )
        val pkgMatch = Regex("([a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+)/").find(topRaw)
        val topPkg = pkgMatch?.groupValues?.getOrNull(1)
        val latencyRaw =
          service.runShellCommand("dumpsys SurfaceFlinger --latency 2>/dev/null | tail -n 30")
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
      val service = shellService ?: return@withContext null
      val logs = mutableListOf<String>()
      ShizukuModulesCatalog.buildRestoreAllCommands(addedGames).forEach { (label, cmd) ->
        val out = runSafe(service, cmd)
        logs += "$label: $out"
      }
      val summary = "Restauração concluída via Shizuku (${logs.size} ajustes revertidos ao padrão)."
      _status.update { it.copy(lastCommandSummary = summary) }
      ShizukuBoostReport(executedCount = logs.size, summary = summary, details = logs)
    }

  suspend fun applyRootTotalPerformance(enable: Boolean): ShizukuBoostReport? =
    withContext(Dispatchers.IO) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext null
      val service = shellService ?: return@withContext null
      val uid =
        try {
          service.uid
        } catch (_: Exception) {
          _status.value.privilegedUid ?: 2000
        }
      val isRootUid = uid == 0
      val logs = mutableListOf<String>()
      ShizukuModulesCatalog.buildRootTotalPerformanceCommands(enable, isRootUid).forEach { (label, cmd) ->
        val out = runSafe(service, cmd)
        logs += "$label: $out"
      }
      val summary =
        if (enable) {
          "DESEMPENHO TOTAL (Root/Shizuku) ativado em UID $uid!"
        } else {
          "Desempenho Total (Root/Shizuku) desativado e restaurado."
        }
      _status.update { it.copy(lastCommandSummary = summary) }
      ShizukuBoostReport(executedCount = logs.size, summary = summary, details = logs)
    }

  suspend fun applyCustomResolution(widthPx: Int, heightPx: Int, densityDpi: Int): String? =
    withContext(Dispatchers.IO) {
      val service = shellService ?: return@withContext null
      val out = runSafe(service, "wm size ${widthPx}x${heightPx}; wm density $densityDpi")
      val msg = "Resolução alterada para ${widthPx}x${heightPx} (${densityDpi} DPI) • $out"
      _status.update { it.copy(lastCommandSummary = msg) }
      msg
    }

  suspend fun restoreNativeResolution(): String? =
    withContext(Dispatchers.IO) {
      val service = shellService ?: return@withContext null
      val out = runSafe(service, "wm size reset; wm density reset")
      val msg = "Resolução restaurada para o padrão nativo do aparelho • $out"
      _status.update { it.copy(lastCommandSummary = msg) }
      msg
    }

  suspend fun runCoolerSweep(): String? =
    withContext(Dispatchers.IO) {
      val service = shellService ?: return@withContext null
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
