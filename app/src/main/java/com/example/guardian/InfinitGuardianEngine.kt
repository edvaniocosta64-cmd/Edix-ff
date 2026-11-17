package com.example.guardian

import android.content.Context
import android.os.Build
import com.example.ax.AxExtensionManager
import com.example.data.BoosterPreferencesRepository
import com.example.data.DeviceTelemetryManager
import com.example.model.BoosterSettings
import com.example.model.DeviceTelemetry
import com.example.model.GuardianAlert
import com.example.model.GuardianCause
import com.example.model.GuardianOutcome
import com.example.model.GuardianReport
import com.example.model.GuardianSeverity
import com.example.model.GuardianState
import com.example.overlay.FloatingFpsMeterService
import com.example.overlay.FloatingHzOverlayService
import com.example.overlay.FloatingResolutionOverlayService
import com.example.resolution.ResolutionPanelService
import com.example.shizuku.ShizukuBridge
import com.example.shizuku.ShizukuConnectionState
import com.example.shizuku.ShizukuStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GuardianCycleResult(
  val state: GuardianState,
  val userNotice: String? = null,
)

class InfinitGuardianEngine(private val context: Context) {

  private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
  private val alertHistory = mutableListOf<GuardianAlert>()

  private var totalRepairsCount = 0
  private var lastPipelineRunAtMs = 0L
  private var lastDriftCheckAtMs = 0L
  private var lastAxCheckAtMs = 0L
  private var lastShizukuRebindAtMs = 0L
  private var bindingStartedAtMs = 0L
  private val seenBackgroundAlertKeys = mutableSetOf<String>()

  suspend fun auditAndHeal(
    telemetry: DeviceTelemetry,
    settings: BoosterSettings,
    shizukuStatus: ShizukuStatus,
    canDrawOverlays: Boolean,
    installedPackageSet: Set<String>,
    forceManualHeal: Boolean,
    repository: BoosterPreferencesRepository,
    telemetryManager: DeviceTelemetryManager,
    shizukuBridge: ShizukuBridge,
  ): GuardianCycleResult {
    GuardianMemoryStore.ensureLoaded(context)
    val now = System.currentTimeMillis()
    val timestamp = timeFormat.format(Date(now))
    val autoRepair = settings.guardianAutoRepairEnabled
    val shouldHeal = autoRepair || forceManualHeal
    val shizukuConnected = shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED
    val newAlerts = mutableListOf<GuardianAlert>()
    val notices = mutableListOf<String>()
    var activeIssuePenalty = 0

    // 1. Check & fix Overlay Permission Desync / Killed Overlay Services
    val anyOverlayFlag =
      settings.floatingHzOverlayEnabled ||
        settings.floatingResolutionOverlayEnabled ||
        settings.floatingFpsMeterEnabled

    if (anyOverlayFlag && !canDrawOverlays) {
      activeIssuePenalty += 10
      if (shouldHeal) {
        if (settings.floatingHzOverlayEnabled) {
          repository.setFloatingHzOverlayEnabled(false)
          FloatingHzOverlayService.stop(context)
        }
        if (settings.floatingResolutionOverlayEnabled) {
          repository.setFloatingResolutionOverlayEnabled(false)
          FloatingResolutionOverlayService.stop(context)
        }
        if (settings.floatingFpsMeterEnabled) {
          repository.setFloatingFpsMeterEnabled(false)
          FloatingFpsMeterService.stop(context)
        }
        totalRepairsCount++
        val alert =
          GuardianAlert(
            id = "overlay_perm_sync",
            title = "Estado de painel flutuante corrigido",
            detail =
              "A permissão de sobreposição foi revogada no Android; a IA desativou os botões presos para evitar falhas.",
            severity = GuardianSeverity.FIXED,
            timestampLabel = timestamp,
            autoFixed = true,
          )
        newAlerts += alert
        notices += "IA corrigiu estado de painel flutuante sem permissão"
      } else {
        newAlerts +=
          GuardianAlert(
            id = "overlay_perm_sync",
            title = "Aviso: Painel marcado ativo sem permissão",
            detail = "Permissão 'Sobrepor a outros apps' desativada. Ative o Auto-Reparo ou conceda acesso.",
            severity = GuardianSeverity.WARNING,
            timestampLabel = timestamp,
          )
      }
    } else if (canDrawOverlays && shouldHeal) {
      // Restart overlay services automatically if OEM killed them in background while marked enabled
      var restartedAny = false
      if (settings.floatingHzOverlayEnabled && !FloatingHzOverlayService.isRunning) {
        FloatingHzOverlayService.start(context)
        restartedAny = true
      }
      if (settings.floatingResolutionOverlayEnabled && !FloatingResolutionOverlayService.isRunning) {
        FloatingResolutionOverlayService.start(context)
        restartedAny = true
      }
      if (settings.floatingFpsMeterEnabled &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        !FloatingFpsMeterService.isRunning
      ) {
        FloatingFpsMeterService.start(context)
        restartedAny = true
      }
      if (restartedAny) {
        totalRepairsCount++
        newAlerts +=
          GuardianAlert(
            id = "overlay_revived",
            title = "Painel flutuante restaurado",
            detail = "O sistema fechou o serviço flutuante em segundo plano e a IA reativou automaticamente.",
            severity = GuardianSeverity.FIXED,
            timestampLabel = timestamp,
            autoFixed = true,
          )
      }
    }

    // Check Android < 11 FPS meter guard
    if (settings.floatingFpsMeterEnabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      if (shouldHeal) {
        repository.setFloatingFpsMeterEnabled(false)
        FloatingFpsMeterService.stop(context)
        totalRepairsCount++
        newAlerts +=
          GuardianAlert(
            id = "fps_meter_sdk",
            title = "Compatibilidade corrigida",
            detail = "Medidor de FPS flutuante requer Android 11+ e foi ajustado com segurança.",
            severity = GuardianSeverity.FIXED,
            timestampLabel = timestamp,
            autoFixed = true,
          )
      }
    }

    // 2. Check & fix background resolution panel service sync
    if (settings.backgroundResolutionPanelEnabled && !ResolutionPanelService.isRunning && shouldHeal) {
      ResolutionPanelService.start(context)
      totalRepairsCount++
      newAlerts +=
        GuardianAlert(
          id = "bg_res_panel_revived",
          title = "Painel de resolução reativado",
          detail = "A notificação persistente foi interrompida pelo sistema e reestabelecida pela IA.",
          severity = GuardianSeverity.FIXED,
          timestampLabel = timestamp,
          autoFixed = true,
        )
    }

    // 3. Orphan Per-Game Configs & Stale Music Toggle Bug Fix
    if (installedPackageSet.isNotEmpty() && settings.perGameConfigs.isNotEmpty()) {
      val hasOrphans = settings.perGameConfigs.keys.any { it !in installedPackageSet }
      if (hasOrphans) {
        activeIssuePenalty += 5
        if (shouldHeal) {
          val pruned = repository.pruneOrphanPerGameConfigs(installedPackageSet)
          if (pruned > 0) {
            totalRepairsCount++
            newAlerts +=
              GuardianAlert(
                id = "orphan_game_cfgs",
                title = "Configurações de jogos removidos limpas ($pruned)",
                detail = "A IA removeu perfis antigos de DPI/Touch de aplicativos que não existem mais.",
                severity = GuardianSeverity.FIXED,
                timestampLabel = timestamp,
                autoFixed = true,
              )
          }
        }
      }
    }

    if (settings.bgMusicEnabled && settings.bgMusicName.isBlank()) {
      activeIssuePenalty += 5
      if (shouldHeal) {
        repository.setBgMusicEnabled(false)
        totalRepairsCount++
        newAlerts +=
          GuardianAlert(
            id = "stale_bg_music",
            title = "Botão de música sem arquivo corrigido",
            detail = "A chave de música de fundo estava ativa sem trilha carregada e foi normalizada.",
            severity = GuardianSeverity.FIXED,
            timestampLabel = timestamp,
            autoFixed = true,
          )
      }
    }

    // 4. Temp file leaks & heavy app cache (>8MB)
    val (removedTmp, cacheFreedMb) =
      withContext(Dispatchers.IO) { telemetryManager.cleanupOrphanTempFilesAndHeavyCache() }
    if (removedTmp > 0 || cacheFreedMb > 0L) {
      totalRepairsCount++
      newAlerts +=
        GuardianAlert(
          id = "temp_cache_sweep",
          title = "Arquivos temporários e cache limpos",
          detail = "Removidos $removedTmp temporário(s) e liberados ${cacheFreedMb} MB para evitar engasgos.",
          severity = GuardianSeverity.FIXED,
          timestampLabel = timestamp,
          autoFixed = true,
        )
    }

    // 5. Shizuku stalled in BINDING or disconnected while Root Perf is on
    if (shizukuStatus.connectionState == ShizukuConnectionState.BINDING) {
      if (bindingStartedAtMs == 0L) bindingStartedAtMs = now
      if (now - bindingStartedAtMs >= 5000L && now - lastShizukuRebindAtMs >= 8000L) {
        activeIssuePenalty += 10
        if (shouldHeal) {
          lastShizukuRebindAtMs = now
          shizukuBridge.refreshState()
          totalRepairsCount++
          newAlerts +=
            GuardianAlert(
              id = "shizuku_rebind",
              title = "Reconexão Shizuku reiniciada",
              detail = "O vínculo com o Shizuku demorou para responder e a IA forçou uma nova checagem.",
              severity = GuardianSeverity.FIXED,
              timestampLabel = timestamp,
              autoFixed = true,
            )
        }
      }
    } else {
      bindingStartedAtMs = 0L
    }

    if (settings.rootTotalPerformanceEnabled && !shizukuConnected) {
      activeIssuePenalty += 8
      newAlerts +=
        GuardianAlert(
          id = "root_perf_warn",
          title = "Aviso: Shizuku desconectado",
          detail = "Suas otimizações foram mantidas salvas. Conecte o Shizuku para o monitoramento 24h voltar ao ativo.",
          severity = GuardianSeverity.INFO,
          timestampLabel = timestamp,
        )
    }

    // 5b. Autorreparo seguro: AX Extension Center integrity + Boost drift check
    if (shouldHeal && (forceManualHeal || now - lastAxCheckAtMs >= 30_000L)) {
      lastAxCheckAtMs = now
      val (_, axRepaired) = withContext(Dispatchers.IO) { AxExtensionManager(context).verifyIntegrityAndCleanup() }
      if (axRepaired > 0) {
        totalRepairsCount++
        val axReport =
          GuardianReport(
            id = "rep_module_ax_integrity",
            timestampLabel = timestamp,
            detectedProblem = "Módulo AX com diretório ausente ($axRepaired)",
            probableCause = GuardianCause.MODULE_STATE,
            causeDetail = "Plugin marcado ativo estava sem pasta válida no armazenamento.",
            correctionApplied = "Plugin normalizado com segurança no catálogo do AX Extension Center.",
            strategyKey = "AX_CATALOG_REPAIR",
            outcome = GuardianOutcome.RESOLVED,
            verificationResult = "Integridade do AX Center restaurada ($axRepaired reparo(s)).",
          )
        GuardianMemoryStore.recordFixVerification(context, axReport)
        newAlerts +=
          GuardianAlert(
            id = "ax_integrity_repair",
            title = "Autorreparo: Plugin AX corrigido ($axRepaired)",
            detail = axReport.verificationResult,
            severity = GuardianSeverity.FIXED,
            timestampLabel = timestamp,
            autoFixed = true,
          )
      }
    }

    if (shouldHeal && shizukuConnected && settings.boostActive &&
      (forceManualHeal || now - lastDriftCheckAtMs >= 20_000L)
    ) {
      lastDriftCheckAtMs = now
      val targetHz = maxOf(telemetry.maxDisplayHz, settings.targetRefreshRate.hz, 60)
      val driftRepairs = shizukuBridge.checkAndRepairBoostDriftViaShizuku(settings, targetHz)
      if (driftRepairs.isNotEmpty()) {
        totalRepairsCount++
        val summary = driftRepairs.joinToString(" • ")
        val driftReport =
          GuardianReport(
            id = "rep_module_boost_drift",
            timestampLabel = timestamp,
            detectedProblem = "Sistema reduziu ajustes do Boost em 2º plano",
            probableCause = GuardianCause.MODULE_STATE,
            causeDetail = "Android reverteu taxa de Hz, escala de animação ou ativou economia.",
            correctionApplied = summary,
            strategyKey = "MODULE_REASSERT_BOOST",
            outcome = GuardianOutcome.RESOLVED,
            verificationResult = "Parâmetros de Boost restaurados sem desligar nenhuma função.",
          )
        GuardianMemoryStore.recordFixVerification(context, driftReport)
        newAlerts +=
          GuardianAlert(
            id = "module_boost_drift",
            title = "Autorreparo: Boost restaurado",
            detail = summary,
            severity = GuardianSeverity.FIXED,
            timestampLabel = timestamp,
            autoFixed = true,
          )
        notices += "IA Guardião restaurou ajustes do Boost automaticamente"
      }
    }

    // 6. Pipeline Inteligente em 4 Etapas: Detectar -> Analisar -> Corrigir -> Verificar
    val pipelineCooldownOk = forceManualHeal || (now - lastPipelineRunAtMs >= 22_000L)
    if (shouldHeal && pipelineCooldownOk) {
      val probe = if (shizukuConnected && forceManualHeal) shizukuBridge.probeLiveStuttersViaShizuku() else null
      val pipelineResult =
        GuardianDiagnosticPipeline.runFourStageCycle(
          context = context,
          telemetryBefore = telemetry,
          probeBefore = probe,
          shizukuConnected = shizukuConnected,
          activePackage = probe?.foregroundPackage,
          gameLabel = probe?.foregroundPackage?.substringAfterLast('.'),
          targetHz = maxOf(telemetry.maxDisplayHz, settings.targetRefreshRate.hz, 60),
          perGameRenderer = null,
          isInGame = false,
          telemetryManager = telemetryManager,
          shizukuBridge = shizukuBridge,
          forceDeepAnalyze = forceManualHeal,
        )
      if (pipelineResult != null) {
        lastPipelineRunAtMs = System.currentTimeMillis()
        if (pipelineResult.report.outcome != GuardianOutcome.PERSISTING) {
          totalRepairsCount++
        } else {
          activeIssuePenalty += 12
        }
        newAlerts += pipelineResult.alert
        notices += "${pipelineResult.report.outcome.label}: ${pipelineResult.report.detectedProblem}"
      }
    } else if (!autoRepair) {
      // Auto-repair paused: detect warnings passively without mutating settings
      if (telemetry.isLowMemory || telemetry.ramPercent >= 84) {
        activeIssuePenalty += 20
        newAlerts +=
          GuardianAlert(
            id = "ram_warn",
            title = "Aviso: RAM crítica em ${telemetry.ramPercent}%",
            detail = "Restam ${telemetry.ramAvailMb} MB livres. Toque em Analisar & Corrigir.",
            severity = GuardianSeverity.WARNING,
            timestampLabel = timestamp,
          )
      }
      if (telemetry.batteryTempC >= 39.5f) {
        activeIssuePenalty += if (telemetry.batteryTempC >= 41.5f) 18 else 10
        newAlerts +=
          GuardianAlert(
            id = "thermal_warn",
            title = "Prevenção Térmica (${String.format(Locale.US, "%.1f", telemetry.batteryTempC)}°C)",
            detail = "Temperatura subindo. Suas otimizações continuam ativas; use o Cooler se precisar.",
            severity = GuardianSeverity.WARNING,
            timestampLabel = timestamp,
          )
      }
      if (telemetry.isPowerSaveMode) {
        activeIssuePenalty += 16
        newAlerts +=
          GuardianAlert(
            id = "power_save_warn",
            title = "Aviso: Economia de Bateria ativa",
            detail = "Economia do Android limita CPU e Hz.",
            severity = GuardianSeverity.WARNING,
            timestampLabel = timestamp,
          )
      }
    }

    // Merge only newly emitted 24h background service alerts
    val live24h = GuardianLiveBus.snapshot.value
    val freshBackgroundAlerts =
      live24h.recentBackgroundAlerts.filter { bg ->
        val key = "${bg.id}@${bg.timestampLabel}"
        seenBackgroundAlertKeys.add(key)
      }
    if (seenBackgroundAlertKeys.size > 60) {
      seenBackgroundAlertKeys.clear()
      live24h.recentBackgroundAlerts.forEach { seenBackgroundAlertKeys.add("${it.id}@${it.timestampLabel}") }
    }
    val combinedIncoming = freshBackgroundAlerts + newAlerts

    if (combinedIncoming.isNotEmpty()) {
      combinedIncoming.forEach { incoming ->
        alertHistory.removeAll { it.id == incoming.id }
        alertHistory.add(0, incoming)
      }
      while (alertHistory.size > 10) {
        alertHistory.removeAt(alertHistory.lastIndex)
      }
    }

    val memSnap = GuardianMemoryStore.snapshot.value
    val combinedRepairs = totalRepairsCount + live24h.repairs24hCount
    val score = (100 - activeIssuePenalty).coerceIn(25, 100)
    val summary =
      when {
        !shizukuConnected ->
          "Aguardando Shizuku ativo • Pipeline Detectar→Analisar→Corrigir→Verificar pronto ($score%)"
        !autoRepair -> "Monitor 24h pausado manualmente • Saúde $score%"
        live24h.running24h ->
          "24h Ativo (Detectar→Analisar→Corrigir→Verificar) • Otimizações preservadas ($score%)"
        newAlerts.any { it.autoFixed } ->
          "IA corrigiu e verificou ${newAlerts.count { it.autoFixed }} ocorrência(s) • Saúde $score%"
        else -> "IA Guardião 24h pronta • Memória e relatórios ativos ($score%)"
      }

    return GuardianCycleResult(
      state =
        GuardianState(
          autoRepairEnabled = autoRepair,
          isAnalyzing = false,
          currentStage = memSnap.currentStage,
          healthScore = score,
          statusSummary = summary,
          totalRepairsCount = combinedRepairs,
          lastCheckLabel =
            if (live24h.running24h) live24h.lastProbeLabel
            else "Última checagem: $timestamp",
          lastCauseDetected = memSnap.lastCauseDetected,
          avoidedRepeatsCount = memSnap.avoidedRepeatsCount,
          alerts = alertHistory.toList(),
          reports = memSnap.reports,
          gameHealthMap = memSnap.gameHealthMap,
        ),
      userNotice = notices.firstOrNull(),
    )
  }

  fun dismissAlert(alertId: String, currentState: GuardianState): GuardianState {
    alertHistory.removeAll { it.id == alertId }
    GuardianMemoryStore.dismissReport(context, alertId)
    val memSnap = GuardianMemoryStore.snapshot.value
    return currentState.copy(
      alerts = alertHistory.toList(),
      reports = memSnap.reports,
      avoidedRepeatsCount = memSnap.avoidedRepeatsCount,
      gameHealthMap = memSnap.gameHealthMap,
    )
  }

  fun recordExternalFix(
    id: String,
    title: String,
    detail: String,
    currentState: GuardianState,
  ): GuardianState {
    val ts = timeFormat.format(Date())
    totalRepairsCount++
    alertHistory.removeAll { it.id == id }
    alertHistory.add(
      0,
      GuardianAlert(
        id = id,
        title = title,
        detail = detail,
        severity = GuardianSeverity.FIXED,
        timestampLabel = ts,
        autoFixed = true,
      ),
    )
    while (alertHistory.size > 10) {
      alertHistory.removeAt(alertHistory.lastIndex)
    }
    return currentState.copy(
      healthScore = 100,
      totalRepairsCount = totalRepairsCount,
      lastCheckLabel = "Atualizado: $ts",
      statusSummary = title,
      alerts = alertHistory.toList(),
    )
  }
}
