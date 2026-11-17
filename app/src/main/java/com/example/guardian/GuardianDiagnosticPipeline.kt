package com.example.guardian

import android.content.Context
import com.example.data.DeviceTelemetryManager
import com.example.model.BottleneckSeverity
import com.example.model.DeviceTelemetry
import com.example.model.GameRendererMode
import com.example.model.GuardianAlert
import com.example.model.GuardianCause
import com.example.model.GuardianOutcome
import com.example.model.GuardianPipelineStage
import com.example.model.GuardianReport
import com.example.model.GuardianSeverity
import com.example.shizuku.ShizukuBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class PipelineExecutionResult(
  val report: GuardianReport,
  val alert: GuardianAlert,
)

object GuardianDiagnosticPipeline {

  private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

  suspend fun runFourStageCycle(
    context: Context,
    telemetryBefore: DeviceTelemetry,
    probeBefore: ShizukuBridge.ShizukuLiveGuardianProbe?,
    shizukuConnected: Boolean,
    activePackage: String?,
    gameLabel: String?,
    targetHz: Int,
    perGameRenderer: GameRendererMode?,
    isInGame: Boolean,
    telemetryManager: DeviceTelemetryManager,
    shizukuBridge: ShizukuBridge,
    forceDeepAnalyze: Boolean = false,
  ): PipelineExecutionResult? {
    GuardianMemoryStore.ensureLoaded(context)
    val now = System.currentTimeMillis()
    val ts = timeFormat.format(Date(now))

    // ---------------- STAGE 1: DETECTAR ----------------
    GuardianMemoryStore.setStage(GuardianPipelineStage.DETECT)
    val observedFpsBefore = probeBefore?.liveFps ?: telemetryBefore.liveFps.takeIf { it > 0 }
    val (suddenDrop, baselineFps) =
      GuardianMemoryStore.recordFpsAndCheckSuddenDrop(activePackage, observedFpsBefore)

    val preventiveHeat =
      telemetryBefore.batteryTempC in 39.5f..41.49f &&
        telemetryBefore.thermalSeverity != BottleneckSeverity.CRITICAL
    val criticalHeat =
      telemetryBefore.batteryTempC >= 41.5f ||
        telemetryBefore.thermalSeverity == BottleneckSeverity.CRITICAL
    val ramCritical = telemetryBefore.isLowMemory || telemetryBefore.ramPercent >= 82
    val powerSaveBlock = telemetryBefore.isPowerSaveMode
    val cpuOverload = probeBefore?.loadAvgHigh == true
    val anrDetected = probeBefore?.hasAnrOrLag == true && (observedFpsBefore == null || observedFpsBefore > 48)
    val storageTight = telemetryBefore.storagePercent >= 91
    val fpsLow =
      observedFpsBefore != null &&
        ((isInGame && targetHz >= 90 && observedFpsBefore in 1..48) || observedFpsBefore in 1..42)
    val frameJitter = telemetryBefore.avgFrameTimeMs > 22.5f

    val issueDetected =
      criticalHeat ||
        powerSaveBlock ||
        ramCritical ||
        cpuOverload ||
        anrDetected ||
        suddenDrop ||
        fpsLow ||
        frameJitter ||
        storageTight ||
        preventiveHeat

    if (!issueDetected && !forceDeepAnalyze) {
      GuardianMemoryStore.setStage(GuardianPipelineStage.IDLE)
      return null
    }

    // ---------------- STAGE 2: ANALISAR CAUSA + MEMÓRIA ----------------
    GuardianMemoryStore.setStage(GuardianPipelineStage.ANALYZE)
    val (cause, detectedProblem, causeDetail) =
      diagnoseRootCause(
        telemetry = telemetryBefore,
        observedFps = observedFpsBefore,
        suddenDrop = suddenDrop,
        baselineFps = baselineFps,
        cpuOverload = cpuOverload,
        anrDetected = anrDetected,
        fpsLow = fpsLow,
        frameJitter = frameJitter,
        ramCritical = ramCritical,
        powerSaveBlock = powerSaveBlock,
        storageTight = storageTight,
        criticalHeat = criticalHeat,
        preventiveHeat = preventiveHeat,
        gameLabel = gameLabel,
      )

    // Prevenção proativa de superaquecimento (39.5°C–41.4°C): avisa antes de ficar crítico, mantendo proteções
    if (cause == GuardianCause.TEMPERATURE && preventiveHeat && !criticalHeat && !fpsLow && !ramCritical) {
      GuardianMemoryStore.setStage(GuardianPipelineStage.VERIFY)
      val cleanedMb = withContext(Dispatchers.IO) { telemetryManager.performMemoryAndCacheCleanup() }
      val tempStr = String.format(Locale.US, "%.1f", telemetryBefore.batteryTempC)
      val report =
        GuardianReport(
          id = "rep_prev_${activePackage ?: "sys"}_${cause.id}",
          timestampLabel = ts,
          packageName = activePackage,
          gameLabel = gameLabel,
          detectedProblem = "Aquecimento preventivo (${tempStr}°C)",
          probableCause = GuardianCause.TEMPERATURE,
          causeDetail = "Temperatura subindo antes do limite crítico (41,5°C). Proteções térmicas e seus ajustes foram preservados.",
          correctionApplied = "Alívio leve de cache em 2º plano (+${cleanedMb} MB) sem reduzir desempenho do jogo.",
          strategyKey = "PREVENTIVE_THERMAL_GUARD",
          outcome = GuardianOutcome.PREVENTIVE,
          verificationResult = "Alerta preventivo emitido a ${tempStr}°C • cooler recomendado se subir.",
          avoidedRepeatedStrategy = false,
        )
      GuardianMemoryStore.recordFixVerification(context, report)
      GuardianMemoryStore.setStage(GuardianPipelineStage.IDLE)
      return PipelineExecutionResult(
        report = report,
        alert =
          GuardianAlert(
            id = "guardian_preventive_heat",
            title = "Prevenção Térmica (${tempStr}°C)",
            detail =
              "Temperatura perto do limite. Proteções térmicas preservadas e ${cleanedMb} MB de cache aliviados sem desfazer suas otimizações.",
            severity = GuardianSeverity.WARNING,
            timestampLabel = ts,
            autoFixed = false,
          ),
      )
    }

    val candidates = strategyCandidatesForCause(cause = cause, isInGame = isInGame)
    val picked =
      GuardianMemoryStore.pickBestStrategy(
        context = context,
        cause = cause,
        packageName = activePackage,
        candidates = candidates,
      )

    // ---------------- STAGE 3: CORRIGIR ADAPTATIVAMENTE ----------------
    GuardianMemoryStore.setStage(GuardianPipelineStage.FIX)
    val freedMb = withContext(Dispatchers.IO) { telemetryManager.performMemoryAndCacheCleanup() }
    val shizukuActions =
      if (shizukuConnected) {
        shizukuBridge.executeAdaptiveGuardianFix(
          cause = cause,
          strategyKey = picked.strategyKey,
          activePackage = activePackage,
          targetHz = targetHz,
          perGameRenderer = perGameRenderer,
          isInGame = isInGame,
        )
      } else {
        emptyList()
      }
    val actionSummary =
      buildList {
        if (shizukuActions.isNotEmpty()) addAll(shizukuActions)
        if (freedMb > 0L || shizukuActions.isEmpty()) add("Limpeza local (+${freedMb} MB RAM)")
      }.joinToString(" • ")

    // ---------------- STAGE 4: VERIFICAR SE RESOLVEU ----------------
    GuardianMemoryStore.setStage(GuardianPipelineStage.VERIFY)
    delay(850L)

    val telemetryAfter =
      withContext(Dispatchers.IO) {
        telemetryManager.readTelemetry(telemetryBefore.liveFps, telemetryBefore.avgFrameTimeMs)
      }
    val probeAfter =
      if (shizukuConnected) shizukuBridge.probeLiveStuttersViaShizuku() else null

    val (outcome, verificationMsg) =
      evaluateFixOutcome(
        cause = cause,
        telemetryBefore = telemetryBefore,
        telemetryAfter = telemetryAfter,
        fpsBefore = observedFpsBefore,
        probeAfter = probeAfter,
        freedMb = freedMb,
        shizukuApplied = shizukuActions.isNotEmpty(),
      )

    val report =
      GuardianReport(
        id = "rep_${activePackage ?: "sys"}_${cause.id}",
        timestampLabel = ts,
        packageName = activePackage,
        gameLabel = gameLabel,
        detectedProblem = detectedProblem,
        probableCause = cause,
        causeDetail = causeDetail,
        correctionApplied =
          if (picked.avoidedRepeated) "[Estratégia alternativa] $actionSummary" else actionSummary,
        strategyKey = picked.strategyKey,
        outcome = outcome,
        verificationResult = verificationMsg,
        avoidedRepeatedStrategy = picked.avoidedRepeated,
      )
    GuardianMemoryStore.recordFixVerification(context, report)
    GuardianMemoryStore.setStage(GuardianPipelineStage.IDLE)

    val scopePrefix = gameLabel?.let { "$it: " }.orEmpty()
    val alertSeverity =
      if (outcome == GuardianOutcome.PERSISTING) GuardianSeverity.WARNING else GuardianSeverity.FIXED
    val alert =
      GuardianAlert(
        id = "guardian_pipe_${activePackage ?: "sys"}_${cause.id}",
        title = "${scopePrefix}${outcome.label} • $detectedProblem",
        detail =
          "Causa: ${cause.label} ($causeDetail)\nAção: $actionSummary\nVerificação: $verificationMsg",
        severity = alertSeverity,
        timestampLabel = ts,
        autoFixed = outcome != GuardianOutcome.PERSISTING,
      )

    return PipelineExecutionResult(report = report, alert = alert)
  }

  private fun diagnoseRootCause(
    telemetry: DeviceTelemetry,
    observedFps: Int?,
    suddenDrop: Boolean,
    baselineFps: Int?,
    cpuOverload: Boolean,
    anrDetected: Boolean,
    fpsLow: Boolean,
    frameJitter: Boolean,
    ramCritical: Boolean,
    powerSaveBlock: Boolean,
    storageTight: Boolean,
    criticalHeat: Boolean,
    preventiveHeat: Boolean,
    gameLabel: String?,
  ): Triple<GuardianCause, String, String> {
    val tempStr = String.format(Locale.US, "%.1f", telemetry.batteryTempC)
    val fpsStr = observedFps?.let { "${it} FPS" } ?: "-- FPS"
    val targetPrefix = gameLabel?.let { "$it • " }.orEmpty()

    return when {
      criticalHeat ->
        Triple(
          GuardianCause.TEMPERATURE,
          "${targetPrefix}Calor crítico (${tempStr}°C)",
          "Superaquecimento detectado com risco de estrangulamento térmico em CPU/GPU.",
        )
      powerSaveBlock ->
        Triple(
          GuardianCause.POWER_SAVER,
          "${targetPrefix}Economia de Energia limitando Hz/FPS",
          "Modo Economia do Android reduziu clock e taxa de atualização (${telemetry.currentDisplayHz}Hz).",
        )
      ramCritical && (fpsLow || suddenDrop || frameJitter) ->
        Triple(
          GuardianCause.RAM,
          "${targetPrefix}Travamento por RAM cheia (${telemetry.ramPercent}% • $fpsStr)",
          "Apenas ${telemetry.ramAvailMb} MB livres; pressão de memória causando gargalo de texturas.",
        )
      ramCritical ->
        Triple(
          GuardianCause.RAM,
          "${targetPrefix}Uso crítico de RAM (${telemetry.ramPercent}%)",
          "Restam ${telemetry.ramAvailMb} MB livres de ${telemetry.ramTotalMb} MB.",
        )
      cpuOverload ->
        Triple(
          GuardianCause.CPU,
          "${targetPrefix}Sobrecarga de CPU ($fpsStr)",
          "Fila alta de threads em 2º plano disputando núcleo com o aplicativo ativo.",
        )
      storageTight ->
        Triple(
          GuardianCause.STORAGE,
          "${targetPrefix}Armazenamento saturado (${telemetry.storagePercent}%)",
          "Pouco espaço em disco (${String.format(Locale.US, "%.1f", telemetry.storageAvailGb)} GB livres) gera travamento em I/O.",
        )
      suddenDrop ->
        Triple(
          GuardianCause.GPU,
          "${targetPrefix}Queda brusca de FPS (${baselineFps ?: "--"} → $fpsStr)",
          "Queda repentina de quadros comparada à média recente de fluidez.",
        )
      anrDetected ->
        Triple(
          GuardianCause.CPU,
          "${targetPrefix}Congelamento / ANR detectado",
          "Processo principal demorou para responder e exigiu re-priorização imediata.",
        )
      fpsLow || frameJitter ->
        Triple(
          GuardianCause.GPU,
          "${targetPrefix}Queda de quadros ($fpsStr • ${String.format(Locale.US, "%.1f", telemetry.avgFrameTimeMs)}ms)",
          "Tempo de renderização de quadro acima do ideal para ${telemetry.currentDisplayHz}Hz.",
        )
      preventiveHeat ->
        Triple(
          GuardianCause.TEMPERATURE,
          "${targetPrefix}Aquecimento em subida (${tempStr}°C)",
          "Monitoramento preventivo antes de alcançar 41,5°C.",
        )
      else ->
        Triple(
          GuardianCause.STABLE,
          "${targetPrefix}Varredura completa sem gargalos",
          "RAM (${telemetry.ramPercent}%), temperatura (${tempStr}°C) e quadros ($fpsStr) dentro do ideal.",
        )
    }
  }

  private fun strategyCandidatesForCause(
    cause: GuardianCause,
    isInGame: Boolean,
  ): List<Pair<String, String>> =
    when (cause) {
      GuardianCause.RAM ->
        if (isInGame) {
          listOf(
            "RAM_TRIM_LIGHT" to "Limpar caches sem mexer na partida",
            "RAM_AGGRESSIVE_KILL" to "Encerrar apps ociosos + 2 GB trim",
            "CPU_GAMEMODE_BOOST" to "Reforçar prioridade do jogo",
          )
        } else {
          listOf(
            "RAM_AGGRESSIVE_KILL" to "Encerrar apps ociosos + 2 GB trim",
            "RAM_TRIM_LIGHT" to "Limpar caches de sistema",
            "CPU_GAMEMODE_BOOST" to "Reforçar prioridade",
          )
        }
      GuardianCause.CPU ->
        listOf(
          "CPU_GAMEMODE_BOOST" to "GameMode performance + active bucket",
          "CPU_PERF_LOCK_AND_TRIM" to "FixedPerf CPU + corte de concorrência",
          "RAM_AGGRESSIVE_KILL" to "Encerrar processos pesados em 2º plano",
        )
      GuardianCause.GPU ->
        listOf(
          "GPU_SF_ZERO_LAG" to "SurfaceFlinger baixa latência ao vivo",
          "GPU_REASSERT_RENDERER" to "Re-estabilizar pipeline Vulkan/SkiaGL",
          "CPU_GAMEMODE_BOOST" to "GameMode FPS + prioridade ativa",
        )
      GuardianCause.TEMPERATURE ->
        listOf(
          "TEMP_RELIEF_SAFE" to "Cortar carga oculta mantendo Boost/DPI/Render",
          "TEMP_COOL_FIXED_PERF" to "Manutenção ociosa e alívio térmico seguro",
        )
      GuardianCause.STORAGE ->
        listOf(
          "STORAGE_TRIM_DEEP" to "Reivindicar até 4 GB de cache de sistema",
          "RAM_TRIM_LIGHT" to "Limpeza rápida de cache local",
        )
      GuardianCause.POWER_SAVER ->
        listOf(
          "POWER_LIFT_SAVERS" to "Suspender Economia + restaurar Hz máx",
          "CPU_GAMEMODE_BOOST" to "Forçar bucket ativo",
        )
      GuardianCause.MODULE_STATE ->
        listOf(
          "MODULE_REASSERT_BOOST" to "Re-aplicar parâmetros de Boost",
        )
      GuardianCause.STABLE ->
        listOf("RAM_TRIM_LIGHT" to "Manutenção preventiva leve")
    }

  private fun evaluateFixOutcome(
    cause: GuardianCause,
    telemetryBefore: DeviceTelemetry,
    telemetryAfter: DeviceTelemetry,
    fpsBefore: Int?,
    probeAfter: ShizukuBridge.ShizukuLiveGuardianProbe?,
    freedMb: Long,
    shizukuApplied: Boolean,
  ): Pair<GuardianOutcome, String> {
    val fpsAfter = probeAfter?.liveFps ?: telemetryAfter.liveFps.takeIf { it > 0 }
    val ramDrop = telemetryBefore.ramPercent - telemetryAfter.ramPercent
    val frameDeltaMs = telemetryBefore.avgFrameTimeMs - telemetryAfter.avgFrameTimeMs
    val fpsRecovered = fpsBefore != null && fpsAfter != null && (fpsAfter - fpsBefore) >= 4
    val fpsHealthyNow = fpsAfter != null && fpsAfter >= 50

    return when (cause) {
      GuardianCause.POWER_SAVER ->
        if (!telemetryAfter.isPowerSaveMode) {
          GuardianOutcome.RESOLVED to "Economia desativada • display em ${telemetryAfter.currentDisplayHz}Hz."
        } else {
          GuardianOutcome.PERSISTING to "Android manteve economia de energia ativa; próxima tentativa usará alternativa."
        }
      GuardianCause.RAM ->
        when {
          telemetryAfter.ramPercent < 78 || ramDrop >= 4 ->
            GuardianOutcome.RESOLVED to "RAM caiu de ${telemetryBefore.ramPercent}% para ${telemetryAfter.ramPercent}% (+${freedMb} MB)."
          ramDrop >= 1 || freedMb > 0L || shizukuApplied ->
            GuardianOutcome.IMPROVED to "RAM aliviada (${telemetryAfter.ramPercent}% • +${freedMb} MB livres)."
          else ->
            GuardianOutcome.PERSISTING to "RAM segue alta (${telemetryAfter.ramPercent}%); outra estratégia será tentada."
        }
      GuardianCause.GPU ->
        when {
          fpsHealthyNow || fpsRecovered ->
            GuardianOutcome.RESOLVED to "FPS estabilizado (${fpsBefore ?: "--"} → ${fpsAfter ?: "--"} FPS)."
          frameDeltaMs >= 2.0f || shizukuApplied ->
            GuardianOutcome.IMPROVED to "Pipeline gráfico ajustado (${fpsAfter ?: "--"} FPS • ${String.format(Locale.US, "%.1f", telemetryAfter.avgFrameTimeMs)}ms)."
          else ->
            GuardianOutcome.PERSISTING to "Queda persistiu (${fpsAfter ?: "--"} FPS); memória registrou estratégia para alternar."
        }
      GuardianCause.CPU ->
        when {
          probeAfter?.hasAnrOrLag == false && probeAfter.loadAvgHigh == false ->
            GuardianOutcome.RESOLVED to "Carga de CPU normalizada e sem ANR (${fpsAfter ?: "--"} FPS)."
          fpsRecovered || shizukuApplied ->
            GuardianOutcome.IMPROVED to "Prioridade reforçada (${fpsAfter ?: "--"} FPS)."
          else ->
            GuardianOutcome.PERSISTING to "Carga elevada continuou; próxima varredura tentará estratégia alternativa."
        }
      GuardianCause.TEMPERATURE -> {
        val tempDelta = telemetryBefore.batteryTempC - telemetryAfter.batteryTempC
        when {
          telemetryAfter.batteryTempC < 41.0f || tempDelta >= 0.3f ->
            GuardianOutcome.IMPROVED to "Alívio térmico ativo (${String.format(Locale.US, "%.1f", telemetryAfter.batteryTempC)}°C) com otimizações intactas."
          else ->
            GuardianOutcome.IMPROVED to "2º plano reduzido (${String.format(Locale.US, "%.1f", telemetryAfter.batteryTempC)}°C); use o Cooler se continuar jogando."
        }
      }
      GuardianCause.STORAGE ->
        if (telemetryAfter.storagePercent < telemetryBefore.storagePercent || shizukuApplied) {
          GuardianOutcome.RESOLVED to "Caches liberados (${telemetryAfter.storagePercent}% em uso)."
        } else {
          GuardianOutcome.PERSISTING to "Espaço continua cheio (${telemetryAfter.storagePercent}%); arquivos externos ocupando flash."
        }
      GuardianCause.MODULE_STATE,
      GuardianCause.STABLE ->
        GuardianOutcome.RESOLVED to "Sistema verificado e fluido (${telemetryAfter.ramPercent}% RAM • ${fpsAfter ?: telemetryAfter.liveFps} FPS)."
    }
  }
}
