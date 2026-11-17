package com.example.data

import android.content.Context
import android.os.Build
import com.example.BuildConfig
import com.example.ui.BoosterUiState
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppCrashLogger {
  private const val CRASH_FILE = "infinit_last_crash.txt"
  private const val EVENTS_FILE = "infinit_debug_events.txt"
  private const val MAX_EVENTS = 20

  private val lock = Any()
  @Volatile private var installed = false

  fun install(context: Context) {
    if (installed) return
    synchronized(lock) {
      if (installed) return
      val appCtx = context.applicationContext
      val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
      Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
          writeCrashReport(appCtx, thread.name, throwable)
        }
        previousHandler?.uncaughtException(thread, throwable)
      }
      installed = true
    }
  }

  private fun writeCrashReport(context: Context, threadName: String, throwable: Throwable) {
    val sw = StringWriter()
    throwable.printStackTrace(PrintWriter(sw))
    val stack = sw.toString().lines().take(28).joinToString("\n")
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    val body =
      buildString {
        appendLine("Data: $ts • Thread: $threadName")
        appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Erro: ${throwable::class.java.simpleName}: ${throwable.message ?: "sem mensagem"}")
        appendLine("Stack:")
        append(stack)
      }
    File(context.filesDir, CRASH_FILE).writeText(body)
  }

  fun logEvent(context: Context, tag: String, detail: String) {
    synchronized(lock) {
      runCatching {
        val appCtx = context.applicationContext
        val file = File(appCtx.filesDir, EVENTS_FILE)
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$ts][$tag] ${detail.replace("\n", " ").take(200)}"
        val existing =
          if (file.exists()) file.readLines().toMutableList() else mutableListOf()
        existing.add(0, line)
        while (existing.size > MAX_EVENTS) {
          existing.removeAt(existing.lastIndex)
        }
        file.writeText(existing.joinToString("\n"))
      }
    }
  }

  fun readLastCrash(context: Context): String? {
    return synchronized(lock) {
      runCatching {
        val file = File(context.applicationContext.filesDir, CRASH_FILE)
        if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
      }.getOrNull()
    }
  }

  fun readRecentEvents(context: Context): List<String> {
    return synchronized(lock) {
      runCatching {
        val file = File(context.applicationContext.filesDir, EVENTS_FILE)
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
      }.getOrElse { emptyList() }
    }
  }

  fun clearLogs(context: Context) {
    synchronized(lock) {
      runCatching { File(context.applicationContext.filesDir, CRASH_FILE).delete() }
      runCatching { File(context.applicationContext.filesDir, EVENTS_FILE).delete() }
    }
  }

  fun buildFullDiagnosticReport(context: Context, state: BoosterUiState): String {
    val t = state.telemetry
    val s = state.settings
    val g = state.guardianState
    val shizuku = state.shizukuStatus
    val gfx = state.graphicsStatus
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    val lastCrash = readLastCrash(context)
    val recentEvents = readRecentEvents(context)

    return buildString {
      appendLine("=== INFINIT BOOSTER • RAIO-X DO APARELHO ===")
      appendLine("Gerado em: $ts • v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
      appendLine("Aparelho: ${t.manufacturer} ${t.deviceName} • Android ${t.androidVersion} (API ${t.sdkInt}) • ABI ${t.cpuAbi}")
      appendLine("GPU/Vulkan: ${t.gpuRendererLabel} • ${t.vulkanVersionLabel}")
      appendLine("Display: Atual ${t.currentDisplayHz}Hz / Máx ${t.maxDisplayHz}Hz • Suportados: ${t.supportedHzList.joinToString("/")}Hz • ${t.screenSpec.widthPx}x${t.screenSpec.heightPx}")
      appendLine("Desempenho: ${t.liveFps} FPS (${String.format(Locale.US, "%.1f", t.avgFrameTimeMs)}ms) • RAM ${t.ramPercent}% (${t.ramAvailMb}/${t.ramTotalMb} MB) • Temp ${String.format(Locale.US, "%.1f", t.batteryTempC)}°C • Armazenamento ${t.storagePercent}% • Economia=${t.isPowerSaveMode}")
      appendLine("Permissões: WriteSettings=${state.canWriteSystemSettings} • Overlay=${state.canDrawOverlays}")
      appendLine("Shizuku: ${shizuku.connectionState.name} • UID=${shizuku.privilegedUid ?: "N/A"} • Versão=${shizuku.shizukuVersion ?: "N/A"} • Último=${shizuku.lastCommandSummary}")
      appendLine("Boost: Vazio=${s.boostActive} • FPSLivre=${s.fpsUnlocked} • PerfMax=${s.performanceMax} • IgnorarTerm=${s.ignoreThermalLimit} • DesempenhoTotal=${s.rootTotalPerformanceEnabled} • AlvoHz=${s.targetRefreshRate.hz}Hz • Anim=${s.animationScale}x")
      appendLine("Renderização Global: Perfil=${s.graphicsBackend.id} • VerificadoShizuku=${gfx.verifiedViaShizuku} • ${gfx.lastVerifiedLabel}")
      appendLine("Jogos Configurados (${state.addedGames.size}):")
      if (state.addedGames.isEmpty()) {
        appendLine("  (nenhum)")
      } else {
        state.addedGames.take(12).forEach { game ->
          val cfg = s.perGameConfigs[game.packageName]
          val health = g.gameHealthMap[game.packageName]
          val renderLabel = cfg?.rendererMode?.shortLabel ?: "Global"
          val betaLabel = if (cfg?.guardianBetaEnabled == true) "ON" else "OFF"
          val dpiLabel = cfg?.customDpi?.toString() ?: "Padrão"
          val touchLabel = if (cfg?.touchScriptEnabled == true) "Nv.${cfg.touchSensitivity}" else "OFF"
          val healthPart =
            if (health != null) " | Lags=${health.lagEventsCount}, Curas=${health.resolvedFixesCount}, MinFPS=${health.lowestFpsSeen ?: "--"}, Causa=${health.lastCause?.label ?: "--"}"
            else ""
          appendLine("  - ${game.label} (${game.packageName}): Render=$renderLabel, IABeta=$betaLabel, DPI=$dpiLabel, Touch=$touchLabel$healthPart")
        }
      }
      appendLine("AX Plugins (${state.axExtensions.size}):")
      if (state.axExtensions.isEmpty()) {
        appendLine("  (nenhum)")
      } else {
        state.axExtensions.take(10).forEach { m ->
          appendLine("  - ${m.name} v${m.version} [${m.pluginType.label}] • Ativo=${m.enabled} • ${m.compatibility.name} • Log=${m.lastExecutionLog.take(80)}")
        }
      }
      appendLine("IA Guardião: Saúde=${g.healthScore}% • Etapa=${g.currentStage.label} • Reparos=${g.totalRepairsCount} • FalhasEvitadas=${g.avoidedRepeatsCount} • Causa=${g.lastCauseDetected.label}")
      if (g.reports.isNotEmpty()) {
        appendLine("Últimos Relatórios IA Guardião:")
        g.reports.take(5).forEach { r ->
          appendLine("  - [${r.timestampLabel}][${r.outcome.label}] ${r.detectedProblem} | Causa=${r.probableCause.label} | Ação=${r.correctionApplied} | Verif=${r.verificationResult}")
        }
      }
      if (state.findings.isNotEmpty()) {
        appendLine("Gargalos atuais:")
        state.findings.take(5).forEach { f ->
          appendLine("  - [${f.severity.name}] ${f.title}")
        }
      }
      if (lastCrash != null) {
        appendLine("--- ÚLTIMO CRASH REGISTRADO ---")
        appendLine(lastCrash)
      } else {
        appendLine("Crash registrado: Nenhum")
      }
      if (recentEvents.isNotEmpty()) {
        appendLine("--- LOG DE EVENTOS/ERROS RECENTES ---")
        recentEvents.take(10).forEach { appendLine("  $it") }
      }
      appendLine("=== FIM DO RAIO-X ===")
    }
  }
}
