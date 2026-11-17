package com.example.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.BackgroundMusicEngine
import com.example.audio.DomainVoiceEngine
import com.example.ax.AxExtensionManager
import com.example.data.AppCrashLogger
import com.example.data.BoosterPreferencesRepository
import com.example.data.DeviceTelemetryManager
import com.example.guardian.GuardianLiveBus
import com.example.guardian.GuardianMemoryStore
import com.example.guardian.GuardianMonitorService
import com.example.guardian.InfinitGuardianEngine
import com.example.model.AxExtensionModule
import com.example.model.AxToggleableOption
import com.example.model.BoosterSettings
import com.example.model.BottleneckFinding
import com.example.model.CoolerSpeed
import com.example.model.DeviceTelemetry
import com.example.model.DiagnosticAction
import com.example.model.GameRendererMode
import com.example.model.GraphicsBackend
import com.example.model.GraphicsRendererStatus
import com.example.model.GuardianCause
import com.example.model.GuardianOutcome
import com.example.model.GuardianReport
import com.example.model.GuardianState
import com.example.model.InstalledAppItem
import com.example.model.PerGameConfig
import com.example.model.RefreshRateOption
import com.example.model.WallpaperTheme
import com.example.overlay.FloatingFpsMeterService
import com.example.overlay.FloatingHzOverlayService
import com.example.overlay.FloatingResolutionOverlayService
import com.example.overlay.GameDpiSessionService
import com.example.resolution.ResolutionPanelService
import com.example.shizuku.ShizukuBridge
import com.example.shizuku.ShizukuConnectionState
import com.example.shizuku.ShizukuStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class BoosterUiState(
  val settings: BoosterSettings = BoosterSettings(),
  val telemetry: DeviceTelemetry = DeviceTelemetry(),
  val findings: List<BottleneckFinding> = emptyList(),
  val installedApps: List<InstalledAppItem> = emptyList(),
  val addedGames: List<InstalledAppItem> = emptyList(),
  val showDomainIntro: Boolean = true,
  val isScanningDiagnostics: Boolean = false,
  val isCoolingCycleRunning: Boolean = false,
  val coolingProgress: Float = 0f,
  val lastFreedRamMb: Long = 0L,
  val boostPulseKey: Int = 0,
  val statusBanner: String? = null,
  val shizukuStatus: ShizukuStatus = ShizukuStatus(),
  val graphicsStatus: GraphicsRendererStatus = GraphicsRendererStatus(),
  val shizukuLogs: List<String> = emptyList(),
  val axExtensions: List<AxExtensionModule> = emptyList(),
  val isImportingAxPlugin: Boolean = false,
  val canWriteSystemSettings: Boolean = false,
  val canDrawOverlays: Boolean = false,
  val hasCrashOrErrorLogs: Boolean = false,
  val lastCrashPreview: String? = null,
  val guardianState: GuardianState = GuardianState(),
)

class BoosterViewModel(application: Application) : AndroidViewModel(application) {

  private val repository = BoosterPreferencesRepository(application)
  private val telemetryManager = DeviceTelemetryManager(application)
  private val voiceEngine = DomainVoiceEngine(application)
  private val musicEngine = BackgroundMusicEngine(application)
  private val shizukuBridge = ShizukuBridge(application)
  private val guardianEngine = InfinitGuardianEngine(application)
  private val axExtensionManager = AxExtensionManager(application)
  private val guardianMutex = Mutex()

  private val _installedApps = MutableStateFlow<List<InstalledAppItem>>(emptyList())
  private val _uiState = MutableStateFlow(BoosterUiState())
  val uiState: StateFlow<BoosterUiState> = _uiState.asStateFlow()

  @Volatile private var latestFps: Int = 60
  @Volatile private var latestFrameTimeMs: Float = 16.6f
  private var hasSyncedInitialMusic = false

  init {
    AppCrashLogger.install(application)
    GuardianMemoryStore.ensureLoaded(application)
    refreshSpecialPermissions()
    refreshCrashLogState()
    viewModelScope.launch {
      GuardianMemoryStore.snapshot.collect { mem ->
        _uiState.update { state ->
          state.copy(
            guardianState =
              state.guardianState.copy(
                currentStage = mem.currentStage,
                lastCauseDetected = mem.lastCauseDetected,
                avoidedRepeatsCount = mem.avoidedRepeatsCount,
                reports = mem.reports,
                gameHealthMap = mem.gameHealthMap,
              ),
          )
        }
      }
    }
    viewModelScope.launch {
      val (loadedPlugins, repairedCount) =
        withContext(Dispatchers.IO) { axExtensionManager.verifyIntegrityAndCleanup() }
      if (repairedCount > 0) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        GuardianMemoryStore.recordFixVerification(
          application,
          GuardianReport(
            id = "rep_module_ax_boot",
            timestampLabel = ts,
            detectedProblem = "Plugin AX com diretório ausente na inicialização ($repairedCount)",
            probableCause = GuardianCause.MODULE_STATE,
            causeDetail = "Pasta de plugin removido externamente antes de abrir o app.",
            correctionApplied = "Catálogo do AX Extension Center normalizado automaticamente.",
            strategyKey = "AX_CATALOG_REPAIR",
            outcome = GuardianOutcome.RESOLVED,
            verificationResult = "Módulos do app consistentes ($repairedCount reparo).",
          ),
        )
      }
      _uiState.update { it.copy(axExtensions = loadedPlugins) }
    }
    viewModelScope.launch {
      val apps = withContext(Dispatchers.IO) { telemetryManager.queryLaunchableApps() }
      _installedApps.value = apps
      _uiState.update { it.copy(installedApps = apps) }
    }

    viewModelScope.launch {
      combine(repository.settingsFlow, _installedApps) { settings, apps ->
          val added = apps.filter { it.packageName in settings.addedGamePackages }
          Triple(settings, apps, added)
        }
        .collect { (settings, apps, added) ->
          if (!hasSyncedInitialMusic) {
            hasSyncedInitialMusic = true
            if (!_uiState.value.showDomainIntro && settings.bgMusicEnabled && musicEngine.hasMusic()) {
              musicEngine.startOrResume(settings.bgMusicVolume)
            }
          }
          _uiState.update { state ->
            val updatedFindings =
              telemetryManager.analyzeBottlenecks(
                telemetry = state.telemetry,
                fpsUnlocked = settings.fpsUnlocked,
                ignoreThermalLimit = settings.ignoreThermalLimit,
              )
            state.copy(
              settings = settings,
              installedApps = apps,
              addedGames = added,
              findings = updatedFindings,
              guardianState = state.guardianState.copy(autoRepairEnabled = settings.guardianAutoRepairEnabled),
            )
          }
        }
    }

    viewModelScope.launch {
      var prevConn: ShizukuConnectionState? = null
      shizukuBridge.status.collect { shizuku ->
        _uiState.update { it.copy(shizukuStatus = shizuku) }
        if (shizuku.connectionState == ShizukuConnectionState.CONNECTED) {
          if (_uiState.value.settings.guardianAutoRepairEnabled) {
            GuardianMonitorService.start(getApplication())
          }
          if (prevConn != ShizukuConnectionState.CONNECTED) {
            val verified = shizukuBridge.verifyGraphicsBackendStatus(_uiState.value.settings.addedGamePackages)
            _uiState.update { it.copy(graphicsStatus = verified) }
          }
        } else {
          GuardianMonitorService.stop(getApplication())
          if (prevConn == ShizukuConnectionState.CONNECTED) {
            _uiState.update {
              it.copy(
                graphicsStatus =
                  GraphicsRendererStatus(
                    verifiedViaShizuku = false,
                    lastVerifiedLabel = "Shizuku desconectado — conecte para verificar ao vivo.",
                  )
              )
            }
          }
        }
        prevConn = shizuku.connectionState
      }
    }

    viewModelScope.launch {
      while (isActive) {
        refreshTelemetryInternal()
        delay(1400L)
      }
    }
  }

  private suspend fun refreshTelemetryInternal() {
    val telemetry =
      withContext(Dispatchers.IO) {
        telemetryManager.readTelemetry(latestFps, latestFrameTimeMs)
      }
    _uiState.update { state ->
      val findings =
        telemetryManager.analyzeBottlenecks(
          telemetry = telemetry,
          fpsUnlocked = state.settings.fpsUnlocked,
          ignoreThermalLimit = state.settings.ignoreThermalLimit,
        )
      state.copy(telemetry = telemetry, findings = findings)
    }
    runGuardianCycle(forceManual = false)
  }

  private suspend fun runGuardianCycle(forceManual: Boolean) {
    guardianMutex.withLock {
      val snapshot = _uiState.value
      val installedSet = _installedApps.value.map { it.packageName }.toSet()
      val result =
        guardianEngine.auditAndHeal(
          telemetry = snapshot.telemetry,
          settings = snapshot.settings,
          shizukuStatus = snapshot.shizukuStatus,
          canDrawOverlays = Settings.canDrawOverlays(getApplication()),
          installedPackageSet = installedSet,
          forceManualHeal = forceManual,
          repository = repository,
          telemetryManager = telemetryManager,
          shizukuBridge = shizukuBridge,
        )
      _uiState.update { state ->
        state.copy(
          guardianState = result.state,
          statusBanner =
            when {
              forceManual -> result.state.statusSummary
              result.userNotice != null && state.statusBanner == null -> result.userNotice
              else -> state.statusBanner
            },
        )
      }
    }
  }

  fun toggleGuardianAutoRepair(enabled: Boolean) {
    viewModelScope.launch {
      repository.setGuardianAutoRepairEnabled(enabled)
      telemetryManager.pulseHaptic(false)
      val shizukuConnected =
        _uiState.value.shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED
      if (enabled && shizukuConnected) {
        GuardianMonitorService.start(getApplication())
      } else if (!enabled) {
        GuardianMonitorService.stop(getApplication())
      }
      _uiState.update {
        it.copy(
          guardianState = it.guardianState.copy(autoRepairEnabled = enabled),
          statusBanner =
            when {
              enabled && shizukuConnected ->
                "IA Guardião 24h ativa via Shizuku: corrigindo travamentos sem desativar suas otimizações."
              enabled ->
                "IA Guardião pronta: entrará em modo 24h automaticamente assim que o Shizuku conectar."
              else -> "Monitor 24h pausado."
            },
        )
      }
      if (enabled) {
        runGuardianCycle(forceManual = true)
      }
    }
  }

  fun runGuardianManualHeal() {
    viewModelScope.launch {
      _uiState.update {
        it.copy(guardianState = it.guardianState.copy(isAnalyzing = true))
      }
      refreshSpecialPermissions()
      val telemetry =
        withContext(Dispatchers.IO) {
          telemetryManager.readTelemetry(latestFps, latestFrameTimeMs)
        }
      _uiState.update { state ->
        val findings =
          telemetryManager.analyzeBottlenecks(
            telemetry = telemetry,
            fpsUnlocked = state.settings.fpsUnlocked,
            ignoreThermalLimit = state.settings.ignoreThermalLimit,
          )
        state.copy(telemetry = telemetry, findings = findings)
      }
      telemetryManager.pulseHaptic(true)
      runGuardianCycle(forceManual = true)
    }
  }

  fun dismissGuardianAlert(alertId: String) {
    GuardianLiveBus.dismissBackgroundAlert(alertId)
    GuardianMemoryStore.dismissReport(getApplication(), alertId)
    _uiState.update { state ->
      state.copy(guardianState = guardianEngine.dismissAlert(alertId, state.guardianState))
    }
  }

  fun updateLiveFrameStats(fps: Int, avgFrameMs: Float) {
    latestFps = fps
    latestFrameTimeMs = avgFrameMs
  }

  fun onDomainIntroStart() {
    telemetryManager.pulseHaptic(heavy = false)
  }

  fun onDomainBurstTriggered() {
    telemetryManager.pulseHaptic(heavy = true)
  }

  fun dismissDomainIntro() {
    _uiState.update { it.copy(showDomainIntro = false) }
    onAppForegrounded()
  }

  fun replayDomainIntro() {
    musicEngine.pause()
    _uiState.update { it.copy(showDomainIntro = true) }
    telemetryManager.pulseHaptic(heavy = false)
  }

  fun toggleVoice(enabled: Boolean) {
    viewModelScope.launch {
      repository.setVoiceEnabled(enabled)
      if (enabled) {
        voiceEngine.playHollowPurpleBoost(true)
      }
    }
  }

  fun importCustomDomainAudio(uri: Uri) {
    viewModelScope.launch {
      val importedName = withContext(Dispatchers.IO) { voiceEngine.importCustomAudio(uri) }
      if (importedName != null) {
        repository.setVoiceEnabled(true)
        repository.setCustomDomainAudioName(importedName)
        telemetryManager.pulseHaptic(true)
        voiceEngine.playDomainExpansion(true)
        _uiState.update {
          it.copy(statusBanner = "Áudio da Expansão carregado: $importedName")
        }
      } else {
        _uiState.update {
          it.copy(statusBanner = "Não foi possível ler o arquivo de áudio selecionado.")
        }
      }
    }
  }

  fun clearCustomDomainAudio() {
    viewModelScope.launch {
      withContext(Dispatchers.IO) { voiceEngine.clearCustomAudio() }
      repository.setCustomDomainAudioName("")
      telemetryManager.pulseHaptic(false)
      _uiState.update {
        it.copy(statusBanner = "Áudio restaurado para a voz japonesa HD padrão.")
      }
    }
  }

  fun importBackgroundMusic(uri: Uri) {
    viewModelScope.launch {
      val name = withContext(Dispatchers.IO) { musicEngine.importMusic(uri) }
      if (name != null) {
        val vol = _uiState.value.settings.bgMusicVolume
        repository.setBgMusicConfig(enabled = true, name = name)
        musicEngine.startOrResume(vol)
        telemetryManager.pulseHaptic(true)
        _uiState.update {
          it.copy(statusBanner = "Música de fundo ativada: $name")
        }
      } else {
        _uiState.update {
          it.copy(statusBanner = "Não foi possível carregar a música selecionada.")
        }
      }
    }
  }

  fun toggleBackgroundMusic(enable: Boolean) {
    viewModelScope.launch {
      if (enable && !musicEngine.hasMusic()) {
        _uiState.update {
          it.copy(statusBanner = "Escolha primeiro uma música baixada no celular.")
        }
        return@launch
      }
      repository.setBgMusicEnabled(enable)
      if (enable) {
        musicEngine.startOrResume(_uiState.value.settings.bgMusicVolume)
        _uiState.update { it.copy(statusBanner = "Música de fundo tocando.") }
      } else {
        musicEngine.pause()
        _uiState.update { it.copy(statusBanner = "Música de fundo pausada.") }
      }
    }
  }

  fun setBackgroundMusicVolume(volume: Float) {
    val clamped = volume.coerceIn(0f, 1f)
    musicEngine.setVolume(clamped)
    viewModelScope.launch { repository.setBgMusicVolume(clamped) }
  }

  fun clearBackgroundMusic() {
    viewModelScope.launch {
      withContext(Dispatchers.IO) { musicEngine.clearMusic() }
      repository.setBgMusicConfig(enabled = false, name = "")
      _uiState.update { it.copy(statusBanner = "Música de fundo removida.") }
    }
  }

  fun onAppForegrounded() {
    if (_uiState.value.showDomainIntro) return
    val s = _uiState.value.settings
    if (s.bgMusicEnabled && musicEngine.hasMusic()) {
      musicEngine.startOrResume(s.bgMusicVolume)
    }
    if (GameDpiSessionService.isRunning &&
      _uiState.value.shizukuStatus.connectionState != ShizukuConnectionState.CONNECTED &&
      (System.currentTimeMillis() - GameDpiSessionService.startedAtMs) >= 4000L
    ) {
      GameDpiSessionService.restoreAndStop(getApplication())
    }
  }

  fun onAppBackgrounded() {
    musicEngine.pause()
  }

  fun selectWallpaperTheme(theme: WallpaperTheme) {
    viewModelScope.launch {
      if (theme == WallpaperTheme.GALERIA_CUSTOM) {
        val file = java.io.File(getApplication<Application>().filesDir, "custom_wallpaper.jpg")
        if (!file.exists()) {
          _uiState.update {
            it.copy(statusBanner = "Escolha uma foto da galeria usando o botão Importar Papel de Parede.")
          }
          return@launch
        }
      }
      repository.setWallpaperTheme(theme)
      telemetryManager.pulseHaptic(false)
      _uiState.update { it.copy(statusBanner = "Tema de fundo alterado para ${theme.label}.") }
    }
  }

  fun importCustomWallpaper(uri: Uri) {
    viewModelScope.launch {
      val name =
        withContext(Dispatchers.IO) {
          saveScaledWallpaperFromUri(uri)
        }
      if (name != null) {
        repository.setCustomWallpaper(name = name, theme = WallpaperTheme.GALERIA_CUSTOM)
        telemetryManager.pulseHaptic(true)
        _uiState.update {
          it.copy(statusBanner = "Papel de parede aplicado: $name")
        }
      } else {
        _uiState.update {
          it.copy(statusBanner = "Não foi possível carregar a imagem selecionada.")
        }
      }
    }
  }

  fun clearCustomWallpaper() {
    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        val file = java.io.File(getApplication<Application>().filesDir, "custom_wallpaper.jpg")
        if (file.exists()) file.delete()
      }
      repository.setCustomWallpaper(name = "", theme = WallpaperTheme.VAZIO_ROXO)
      _uiState.update {
        it.copy(statusBanner = "Papel de parede restaurado para o tema Vazio Roxo.")
      }
    }
  }

  private fun saveScaledWallpaperFromUri(uri: Uri): String? {
    val app = getApplication<Application>()
    return try {
      val displayName =
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
          ?.use { cursor ->
            if (cursor.moveToFirst()) {
              val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
              if (idx >= 0) cursor.getString(idx) else null
            } else {
              null
            }
          } ?: "papel_de_parede.jpg"

      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
      var sample = 1
      val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
      while (maxDim / sample > 1920) {
        sample *= 2
      }
      val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
      val bitmap =
        app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
          ?: return null

      val outFile = java.io.File(app.filesDir, "custom_wallpaper.jpg")
      outFile.outputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
      }
      bitmap.recycle()
      displayName
    } catch (_: Exception) {
      null
    }
  }

  fun refreshShizuku() {
    shizukuBridge.refreshState()
  }

  fun refreshSpecialPermissions() {
    val ctx = getApplication<Application>()
    val canWrite = Settings.System.canWrite(ctx)
    val canOverlay = Settings.canDrawOverlays(ctx)
    _uiState.update {
      it.copy(canWriteSystemSettings = canWrite, canDrawOverlays = canOverlay)
    }
  }

  fun openWriteSettingsPermission(context: Context) {
    try {
      val intent =
        Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:${context.packageName}"),
          )
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    } catch (_: Exception) {
      _uiState.update {
        it.copy(statusBanner = "Abra Configurações > Apps > Infinit Booster > Modificar config. do sistema.")
      }
    }
  }

  fun openOverlayPermission(context: Context) {
    try {
      val intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
          )
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    } catch (_: Exception) {
      _uiState.update {
        it.copy(statusBanner = "Abra Configurações > Apps > Sobrepor a outros apps.")
      }
    }
  }

  fun toggleFloatingHzOverlay(enable: Boolean, context: Context) {
    viewModelScope.launch {
      if (enable && !Settings.canDrawOverlays(context)) {
        openOverlayPermission(context)
        _uiState.update {
          it.copy(statusBanner = "Autorize 'Sobrepor a outros apps' para exibir o Painel Vermelho de Hz.")
        }
        return@launch
      }
      repository.setFloatingHzOverlayEnabled(enable)
      if (enable) {
        FloatingHzOverlayService.start(context)
        telemetryManager.pulseHaptic(true)
        _uiState.update {
          it.copy(statusBanner = "Painel Flutuante Vermelho (60/90/120Hz) ativado sobre a tela!")
        }
      } else {
        FloatingHzOverlayService.stop(context)
        telemetryManager.pulseHaptic(false)
        _uiState.update {
          it.copy(statusBanner = "Painel Flutuante Vermelho desativado.")
        }
      }
    }
  }

  fun toggleFloatingResolutionOverlay(enable: Boolean, context: Context) {
    viewModelScope.launch {
      if (enable && !Settings.canDrawOverlays(context)) {
        openOverlayPermission(context)
        _uiState.update {
          it.copy(statusBanner = "Autorize 'Sobrepor a outros apps' para abrir o Painel Flutuante de Resolução Livre.")
        }
        return@launch
      }
      repository.setFloatingResolutionOverlayEnabled(enable)
      if (enable) {
        FloatingResolutionOverlayService.start(context)
        telemetryManager.pulseHaptic(true)
        _uiState.update {
          it.copy(statusBanner = "Painel Flutuante de Resolução Livre ativado! Digite qualquer resolução sobre os jogos.")
        }
      } else {
        FloatingResolutionOverlayService.stop(context)
        telemetryManager.pulseHaptic(false)
        _uiState.update { it.copy(statusBanner = "Painel Flutuante de Resolução desativado.") }
      }
    }
  }

  fun toggleFloatingFpsMeter(enable: Boolean, context: Context) {
    viewModelScope.launch {
      if (enable && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        _uiState.update {
          it.copy(statusBanner = "Medidor de FPS Flutuante em tempo real requer Android 11 ou superior.")
        }
        return@launch
      }
      if (enable && !Settings.canDrawOverlays(context)) {
        openOverlayPermission(context)
        _uiState.update {
          it.copy(statusBanner = "Autorize 'Sobrepor a outros apps' para exibir o Medidor de FPS em tempo real.")
        }
        return@launch
      }
      repository.setFloatingFpsMeterEnabled(enable)
      if (enable) {
        FloatingFpsMeterService.start(context)
        telemetryManager.pulseHaptic(false)
        _uiState.update {
          it.copy(statusBanner = "Medidor de FPS em Tempo Real ativado (Android 11+).")
        }
      } else {
        FloatingFpsMeterService.stop(context)
        telemetryManager.pulseHaptic(false)
        _uiState.update { it.copy(statusBanner = "Medidor de FPS Flutuante desativado.") }
      }
    }
  }

  fun connectOrApplyShizuku(context: Context) {
    val currentStatus = _uiState.value.shizukuStatus.connectionState
    if (currentStatus == ShizukuConnectionState.CONNECTED) {
      viewModelScope.launch {
        val currentSettings = _uiState.value.settings
        val freshTelemetry =
          withContext(Dispatchers.IO) {
            telemetryManager.readTelemetry(latestFps, latestFrameTimeMs)
          }
        val probedHz = shizukuBridge.probeHardwareMaxHzViaShizuku()
        val maxHz = maxOf(freshTelemetry.maxDisplayHz, probedHz ?: 60, 60)
        val report =
          shizukuBridge.applyBoostProfile(
            settings = currentSettings,
            forcedMaxHz = if (currentSettings.boostActive) maxHz else null,
            screenWidthPx = freshTelemetry.screenSpec.widthPx,
            screenHeightPx = freshTelemetry.screenSpec.heightPx,
          )
        if (report != null) {
          telemetryManager.pulseHaptic(false)
          _uiState.update {
            it.copy(shizukuLogs = report.details, statusBanner = report.summary)
          }
        }
      }
    } else {
      shizukuBridge.requestOrConnect(context)
    }
  }

  fun toggleAxOnlyMode(enabled: Boolean) {
    viewModelScope.launch {
      repository.setAxOnlyModeEnabled(enabled)
      telemetryManager.pulseHaptic(false)
      _uiState.update {
        it.copy(
          statusBanner =
            if (enabled)
              "Modo Só AxManager ativado: importando e filtrando apenas plugins AxManager."
            else
              "Modo Aberto ativado: aceita módulos ZIP do Magisk e plugins AxManager.",
        )
      }
    }
  }

  fun importAxPluginZip(uri: Uri) {
    viewModelScope.launch {
      _uiState.update { it.copy(isImportingAxPlugin = true) }
      val shizukuStatus = _uiState.value.shizukuStatus
      val shizukuConnected = shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED
      val isRootUid = shizukuStatus.privilegedUid == 0
      val onlyAx = _uiState.value.settings.axOnlyModeEnabled

      val outcome =
        withContext(Dispatchers.IO) {
          axExtensionManager.importZipPlugin(
            uri = uri,
            shizukuConnected = shizukuConnected,
            isRootUid = isRootUid,
            onlyAxManagerAllowed = onlyAx,
          )
        }
      telemetryManager.pulseHaptic(outcome.success)
      _uiState.update {
        it.copy(
          axExtensions = outcome.allModules,
          isImportingAxPlugin = false,
          statusBanner = outcome.message,
        )
      }
    }
  }

  fun toggleAxPlugin(pluginId: String, enable: Boolean) {
    viewModelScope.launch {
      val plugin = _uiState.value.axExtensions.firstOrNull { it.id == pluginId } ?: return@launch
      if (enable && !plugin.isSupportedForActivation) {
        telemetryManager.pulseHaptic(false)
        _uiState.update {
          it.copy(statusBanner = "Não suportado: ${plugin.compatibilityReason}")
        }
        return@launch
      }

      val shizukuConnected =
        _uiState.value.shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED
      val cmdsForAction = if (enable) plugin.applyCommands else plugin.disableCommands

      if (enable && plugin.requiresShizuku && !shizukuConnected && cmdsForAction.isNotEmpty()) {
        telemetryManager.pulseHaptic(false)
        _uiState.update {
          it.copy(
            statusBanner =
              "Conecte e autorize o Shizuku para ativar '${plugin.name}' no sistema.",
          )
        }
        return@launch
      }

      val (ok, executionLog) =
        if (shizukuConnected && cmdsForAction.isNotEmpty()) {
          shizukuBridge.executeAxExtensionModule(plugin, enable)
        } else {
          true to
            if (enable) "Plugin '${plugin.name}' ativado no AX Extension Center."
            else "Plugin '${plugin.name}' desativado."
        }

      if (!ok) {
        telemetryManager.pulseHaptic(false)
        _uiState.update { it.copy(statusBanner = executionLog) }
        return@launch
      }

      val updated =
        withContext(Dispatchers.IO) {
          axExtensionManager.updatePluginStatus(
            pluginId = pluginId,
            enabled = enable,
            lastLog = executionLog,
          )
        }
      telemetryManager.pulseHaptic(enable)
      _uiState.update {
        it.copy(
          axExtensions = updated,
          statusBanner = executionLog,
        )
      }
    }
  }

  fun removeAxPlugin(pluginId: String) {
    viewModelScope.launch {
      val plugin = _uiState.value.axExtensions.firstOrNull { it.id == pluginId } ?: return@launch
      val shizukuConnected =
        _uiState.value.shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED
      if (plugin.enabled && shizukuConnected && plugin.disableCommands.isNotEmpty()) {
        shizukuBridge.executeAxExtensionModule(plugin, enable = false)
      }
      val updated =
        withContext(Dispatchers.IO) {
          axExtensionManager.removePlugin(pluginId)
        }
      telemetryManager.pulseHaptic(false)
      _uiState.update {
        it.copy(
          axExtensions = updated,
          statusBanner = "Plugin '${plugin.name}' removido do AX Extension Center.",
        )
      }
    }
  }

  fun toggleAxPluginOption(
    pluginId: String,
    option: AxToggleableOption,
    enable: Boolean,
  ) {
    viewModelScope.launch {
      val shizukuConnected =
        _uiState.value.shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED
      val cmdToRun = if (enable) option.enableCommand else option.disableCommand
      if (cmdToRun.isNotBlank() && !shizukuConnected) {
        telemetryManager.pulseHaptic(false)
        _uiState.update {
          it.copy(
            statusBanner =
              "Conecte e autorize o Shizuku para aplicar '${option.title}' no sistema.",
          )
        }
        return@launch
      }

      val (ok, log) =
        shizukuBridge.executeSinglePluginOption(
          enableCmd = option.enableCommand,
          disableCmd = option.disableCommand,
          enable = enable,
          optionTitle = option.title,
        )
      if (!ok) {
        telemetryManager.pulseHaptic(false)
        _uiState.update { it.copy(statusBanner = log) }
        return@launch
      }

      val updated =
        withContext(Dispatchers.IO) {
          axExtensionManager.toggleOptionKey(
            pluginId = pluginId,
            optionKey = option.key,
            active = enable,
            lastLog = log,
          )
        }
      telemetryManager.pulseHaptic(enable)
      _uiState.update {
        it.copy(
          axExtensions = updated,
          statusBanner = log,
        )
      }
    }
  }

  fun runAxPluginScript(pluginId: String, scriptPath: String) {
    viewModelScope.launch {
      val plugin = _uiState.value.axExtensions.firstOrNull { it.id == pluginId } ?: return@launch
      val shizukuConnected =
        _uiState.value.shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED
      if (!shizukuConnected) {
        telemetryManager.pulseHaptic(false)
        _uiState.update {
          it.copy(statusBanner = "Conecte e autorize o Shizuku para executar $scriptPath.")
        }
        return@launch
      }
      val scriptText =
        withContext(Dispatchers.IO) {
          axExtensionManager.readPluginFileText(plugin, scriptPath, maxChars = 128_000)
        }
      if (scriptText.isNullOrBlank()) {
        _uiState.update { it.copy(statusBanner = "Arquivo $scriptPath está vazio ou ilegível.") }
        return@launch
      }

      withContext(Dispatchers.IO) {
        shizukuBridge.syncPluginWorkspaceToTmp(plugin, axExtensionManager.getPluginDirectory(plugin))
      }
      val (code, stdout, stderr) =
        shizukuBridge.executePluginScriptText(plugin, scriptPath, scriptText)
      val output = stdout.ifBlank { stderr }.ifBlank { "exit=$code" }.take(140)
      val log = "[$scriptPath • exit=$code] $output"
      val updated =
        withContext(Dispatchers.IO) {
          axExtensionManager.updatePluginStatus(pluginId, enabled = plugin.enabled || code == 0, lastLog = log)
        }
      telemetryManager.pulseHaptic(code == 0)
      _uiState.update {
        it.copy(
          axExtensions = updated,
          statusBanner = log,
        )
      }
    }
  }

  fun readAxPluginFileText(module: AxExtensionModule, relativePath: String): String? =
    axExtensionManager.readPluginFileText(module, relativePath)

  fun getAxPluginBannerFile(module: AxExtensionModule): File? =
    axExtensionManager.getBannerFile(module)

  fun buildAxToggleableOptions(module: AxExtensionModule): List<AxToggleableOption> =
    axExtensionManager.buildToggleableOptions(module)

  fun restoreAllFactoryDefaults(activity: Activity?) {
    viewModelScope.launch {
      val app = getApplication<Application>()
      val gamesToReset = _uiState.value.settings.addedGamePackages
      val activeAxPlugins = _uiState.value.axExtensions.filter { it.enabled }
      val shizukuConnected =
        _uiState.value.shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED

      FloatingHzOverlayService.stop(app)
      FloatingResolutionOverlayService.stop(app)
      FloatingFpsMeterService.stop(app)
      ResolutionPanelService.stop(app)
      GameDpiSessionService.restoreAndStop(app)
      GuardianMonitorService.stop(app)
      GuardianLiveBus.resetAll()
      GuardianMemoryStore.clearAll(app)

      if (shizukuConnected) {
        activeAxPlugins.forEach { plugin ->
          if (plugin.disableCommands.isNotEmpty()) {
            shizukuBridge.executeAxExtensionModule(plugin, enable = false)
          }
        }
      }

      withContext(Dispatchers.IO) {
        axExtensionManager.clearAllPlugins()
        musicEngine.clearMusic()
        voiceEngine.clearCustomAudio()
        runCatching {
          val customBg = File(app.filesDir, "custom_wallpaper.jpg")
          if (customBg.exists()) customBg.delete()
        }
        telemetryManager.cleanupOrphanTempFilesAndHeavyCache()
      }

      repository.restoreFactoryDefaultsState()
      telemetryManager.resetDisplayRefreshRateToDefault(activity)

      val report = shizukuBridge.restoreAllDefaults(gamesToReset)
      val verified = shizukuBridge.verifyGraphicsBackendStatus(emptySet())
      telemetryManager.pulseHaptic(true)
      _uiState.update { state ->
        val updatedGuardian =
          guardianEngine.recordExternalFix(
            id = "full_factory_restore",
            title = "Restauração Total Concluída",
            detail =
              "Tudo que foi alterado (plugins AX, DPI/Touch, painéis flutuantes, Hz, animação, renderização, Root e mídias) voltou ao padrão.",
            currentState = state.guardianState,
          )
        state.copy(
          shizukuLogs = report?.details ?: emptyList(),
          axExtensions = emptyList(),
          graphicsStatus = verified,
          guardianState = updatedGuardian,
          statusBanner =
            report?.summary
              ?: "Tudo foi restaurado ao padrão original (DPI, Hz, plugins AX, renderização, painéis e mídias)!",
        )
      }
      refreshTelemetryInternal()
    }
  }

  fun applyResolution(
    widthPx: Int,
    heightPx: Int,
    densityDpi: Int,
    lockAspect: Boolean,
    scalePercent: Int,
  ) {
    viewModelScope.launch {
      repository.setResolutionConfig(scalePercent, widthPx, heightPx, lockAspect)
      val msg = shizukuBridge.applyCustomResolution(widthPx, heightPx, densityDpi)
      telemetryManager.pulseHaptic(false)
      _uiState.update {
        it.copy(
          statusBanner =
            msg ?: "Resolução ${widthPx}x${heightPx} salva! Autorize o Shizuku para aplicar no sistema.",
        )
      }
    }
  }

  fun toggleBackgroundResolutionPanel(enable: Boolean, context: Context) {
    viewModelScope.launch {
      repository.setBackgroundResolutionPanelEnabled(enable)
      if (enable) {
        ResolutionPanelService.start(context)
        _uiState.update {
          it.copy(statusBanner = "Painel de Resolução em 2º plano ativado na barra de notificações!")
        }
      } else {
        ResolutionPanelService.stop(context)
        _uiState.update { it.copy(statusBanner = "Painel de Resolução em 2º plano encerrado.") }
      }
    }
  }

  fun setRootTotalPerformance(enable: Boolean, activity: Activity?) {
    viewModelScope.launch {
      if (enable) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
          _uiState.update {
            it.copy(statusBanner = "Desempenho Total Root requer Android 11 ou superior.")
          }
          return@launch
        }
        if (_uiState.value.shizukuStatus.connectionState != ShizukuConnectionState.CONNECTED) {
          _uiState.update {
            it.copy(statusBanner = "Conecte e autorize o Shizuku antes de ativar Desempenho Total Root.")
          }
          return@launch
        }
      }

      repository.setRootTotalPerformanceEnabled(enable)
      if (enable) {
        repository.setBoostActive(true)
        repository.setPerformanceMax(true)
        repository.setIgnoreThermalLimit(true)
      }
      val nextSettings =
        _uiState.value.settings.copy(
          rootTotalPerformanceEnabled = enable,
          boostActive = if (enable) true else _uiState.value.settings.boostActive,
          performanceMax = if (enable) true else _uiState.value.settings.performanceMax,
          ignoreThermalLimit = if (enable) true else _uiState.value.settings.ignoreThermalLimit,
        )
      applyWindowPerformance(activity, nextSettings)
      if (enable) {
        withContext(Dispatchers.IO) { telemetryManager.performMemoryAndCacheCleanup() }
      }
      val report = shizukuBridge.applyRootTotalPerformance(enable, _uiState.value.settings.addedGamePackages)
      if (enable) {
        voiceEngine.playHollowPurpleBoost(nextSettings.voiceEnabled)
        telemetryManager.pulseHaptic(heavy = true)
      } else {
        telemetryManager.pulseHaptic(heavy = false)
      }
      _uiState.update {
        it.copy(
          boostPulseKey = if (enable) it.boostPulseKey + 1 else it.boostPulseKey,
          shizukuLogs = report?.details ?: it.shizukuLogs,
          statusBanner =
            report?.summary ?: "Desempenho Total ${if (enable) "ativado" else "desativado"}.",
        )
      }
      refreshTelemetryInternal()
    }
  }

  private fun resolveMaxRefreshOption(maxHz: Int): RefreshRateOption =
    when {
      maxHz >= 144 -> RefreshRateOption.HZ_144
      maxHz >= 120 -> RefreshRateOption.HZ_120
      maxHz >= 90 -> RefreshRateOption.HZ_90
      else -> RefreshRateOption.HZ_60
    }

  fun activateOrToggleVazioRoxo(activity: Activity?) {
    viewModelScope.launch {
      val current = _uiState.value.settings
      val nextActive = !current.boostActive
      if (nextActive) {
        val freshTelemetry =
          withContext(Dispatchers.IO) {
            telemetryManager.readTelemetry(latestFps, latestFrameTimeMs)
          }
        val shizukuConnected =
          _uiState.value.shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED
        val shizukuProbedMaxHz =
          if (shizukuConnected && freshTelemetry.maxDisplayHz <= 60) {
            shizukuBridge.probeHardwareMaxHzViaShizuku()
          } else {
            null
          }
        val deviceMaxHz = maxOf(freshTelemetry.maxDisplayHz, shizukuProbedMaxHz ?: 60, 60)
        val maxOption = resolveMaxRefreshOption(deviceMaxHz)
        val canForceRootPerf =
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && shizukuConnected

        repository.setBoostActive(true)
        repository.setFpsUnlocked(true)
        repository.setPerformanceMax(true)
        repository.setIgnoreThermalLimit(true)
        repository.setTargetRefreshRate(maxOption)
        if (canForceRootPerf) {
          repository.setRootTotalPerformanceEnabled(true)
        }

        val nextSettings =
          current.copy(
            boostActive = true,
            fpsUnlocked = true,
            performanceMax = true,
            ignoreThermalLimit = true,
            targetRefreshRate = maxOption,
            rootTotalPerformanceEnabled = canForceRootPerf || current.rootTotalPerformanceEnabled,
          )

        val freed = withContext(Dispatchers.IO) { telemetryManager.performMemoryAndCacheCleanup() }
        telemetryManager.applyDisplayRefreshRate(
          activity = activity,
          targetHz = deviceMaxHz,
          sustainedPerformance = false,
        )
        val shizukuReport =
          shizukuBridge.applyBoostProfile(
            settings = nextSettings,
            forcedMaxHz = deviceMaxHz,
            screenWidthPx = freshTelemetry.screenSpec.widthPx,
            screenHeightPx = freshTelemetry.screenSpec.heightPx,
          )
        val rootReport =
          if (canForceRootPerf) {
            shizukuBridge.applyRootTotalPerformance(true, nextSettings.addedGamePackages)
          } else {
            null
          }
        val combinedLogs =
          ((shizukuReport?.details ?: emptyList()) + (rootReport?.details ?: emptyList())).distinct()
        val verifiedGraphics =
          if (shizukuConnected) {
            shizukuBridge.verifyGraphicsBackendStatus(nextSettings.addedGamePackages)
          } else {
            _uiState.value.graphicsStatus
          }

        voiceEngine.playHollowPurpleBoost(current.voiceEnabled)
        telemetryManager.pulseHaptic(heavy = true)
        val shizukuSuffix =
          if (combinedLogs.isNotEmpty()) " + Shizuku (${combinedLogs.size} cmds)" else ""
        _uiState.update {
          it.copy(
            lastFreedRamMb = freed,
            boostPulseKey = it.boostPulseKey + 1,
            shizukuLogs = combinedLogs.ifEmpty { it.shizukuLogs },
            graphicsStatus = verifiedGraphics,
            statusBanner =
              "Vazio Roxo Ativo • ${deviceMaxHz}Hz Máx + FPS Máximo Forçado$shizukuSuffix (${freed} MB)",
          )
        }
      } else {
        val wasRootActive = current.rootTotalPerformanceEnabled
        repository.setBoostActive(false)
        if (wasRootActive) {
          repository.setRootTotalPerformanceEnabled(false)
        }
        val nextSettings =
          current.copy(
            boostActive = false,
            rootTotalPerformanceEnabled = false,
          )
        applyWindowPerformance(activity, nextSettings)
        shizukuBridge.applyBoostProfile(nextSettings)
        if (wasRootActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          shizukuBridge.applyRootTotalPerformance(false, current.addedGamePackages)
        }
        telemetryManager.pulseHaptic(heavy = false)
        _uiState.update { it.copy(statusBanner = "Modo Infinito pausado. Hz e perfil padrão restaurados.") }
      }
      refreshTelemetryInternal()
    }
  }

  fun setFpsUnlocked(unlocked: Boolean, activity: Activity?) {
    viewModelScope.launch {
      repository.setFpsUnlocked(unlocked)
      telemetryManager.pulseHaptic(false)
      applyWindowPerformance(activity, _uiState.value.settings.copy(fpsUnlocked = unlocked))
    }
  }

  fun setPerformanceMax(max: Boolean, activity: Activity?) {
    viewModelScope.launch {
      repository.setPerformanceMax(max)
      telemetryManager.pulseHaptic(false)
      applyWindowPerformance(activity, _uiState.value.settings.copy(performanceMax = max))
    }
  }

  fun setIgnoreThermalLimit(ignore: Boolean, activity: Activity?) {
    viewModelScope.launch {
      repository.setIgnoreThermalLimit(ignore)
      telemetryManager.pulseHaptic(false)
      applyWindowPerformance(activity, _uiState.value.settings.copy(ignoreThermalLimit = ignore))
      if (ignore) {
        _uiState.update {
          it.copy(statusBanner = "Limite térmico ignorado no app: mantendo carga máxima.")
        }
      }
    }
  }

  fun selectGraphicsBackend(backend: GraphicsBackend) {
    viewModelScope.launch {
      repository.setGraphicsBackend(backend)
      telemetryManager.pulseHaptic(false)
      _uiState.update {
        it.copy(statusBanner = "Perfil selecionado: ${backend.title}. Toque em Ativar Renderização para aplicar no sistema.")
      }
    }
  }

  fun activateSelectedGraphicsBackend() {
    viewModelScope.launch {
      val backend = _uiState.value.settings.graphicsBackend
      val addedGames = _uiState.value.settings.addedGamePackages
      val (msg, status) = shizukuBridge.applyGraphicsBackend(backend, addedGames)
      telemetryManager.pulseHaptic(msg != null)
      _uiState.update {
        it.copy(
          graphicsStatus = status,
          statusBanner =
            msg ?: "Conecte e autorize o Shizuku para ativar ${backend.title} no sistema.",
        )
      }
    }
  }

  fun verifyGraphicsBackendStatus() {
    viewModelScope.launch {
      val addedGames = _uiState.value.settings.addedGamePackages
      val status = shizukuBridge.verifyGraphicsBackendStatus(addedGames)
      telemetryManager.pulseHaptic(false)
      val activeLabel = status.activeBackendDetected?.title ?: "Padrão do Sistema"
      _uiState.update {
        it.copy(
          graphicsStatus = status,
          statusBanner =
            if (status.verifiedViaShizuku) "Verificado ($activeLabel): ${status.lastVerifiedLabel}"
            else status.lastVerifiedLabel,
        )
      }
    }
  }

  fun restoreGraphicsBackendDefaults() {
    viewModelScope.launch {
      val addedGames = _uiState.value.settings.addedGamePackages
      repository.setGraphicsBackend(GraphicsBackend.VULKAN_MAX)
      val (msg, status) = shizukuBridge.restoreGraphicsBackendDefaults(addedGames)
      telemetryManager.pulseHaptic(false)
      _uiState.update {
        it.copy(
          graphicsStatus = status,
          statusBanner = msg ?: "Perfil restaurado. Autorize o Shizuku para limpar as propriedades do sistema.",
        )
      }
    }
  }

  fun selectRefreshRate(option: RefreshRateOption, activity: Activity?) {
    viewModelScope.launch {
      repository.setTargetRefreshRate(option)
      telemetryManager.pulseHaptic(false)
      applyWindowPerformance(activity, _uiState.value.settings.copy(targetRefreshRate = option))
      val spec = telemetryManager.readNativeScreenSpec()
      val shizukuMsg = shizukuBridge.applyRealtimeRefreshRate(option.hz, spec.widthPx, spec.heightPx)
      refreshTelemetryInternal()
      _uiState.update {
        it.copy(statusBanner = shizukuMsg ?: "Taxa de fluidez ajustada para ${option.label}")
      }
    }
  }

  fun selectAnimationScale(scale: Float) {
    viewModelScope.launch {
      val clamped = scale.coerceIn(0f, 1f)
      repository.setAnimationScale(clamped)
      telemetryManager.pulseHaptic(false)
      val msg = shizukuBridge.applyAnimationScale(clamped)
      val label = "${String.format(java.util.Locale.US, "%.2f", clamped)}x"
      _uiState.update {
        it.copy(
          statusBanner = msg ?: "Escala de animação definida para $label (conecte o Shizuku para aplicar no sistema).",
        )
      }
    }
  }

  fun updatePerGameConfig(config: PerGameConfig) {
    viewModelScope.launch {
      repository.setPerGameConfig(config)
      if (config.rendererMode == GameRendererMode.GLOBAL &&
        _uiState.value.shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED
      ) {
        shizukuBridge.clearPerGameDriverOptIn(config.packageName)
      }
      telemetryManager.pulseHaptic(false)
    }
  }

  fun applyWindowPerformance(activity: Activity?, settings: BoosterSettings = _uiState.value.settings) {
    if (!settings.boostActive && !settings.fpsUnlocked) {
      telemetryManager.resetDisplayRefreshRateToDefault(activity)
      return
    }
    val hardwareMaxHz =
      maxOf(_uiState.value.telemetry.maxDisplayHz, telemetryManager.readHardwareMaxHzQuick(), 60)
    val targetHz =
      when {
        settings.boostActive ->
          maxOf(hardwareMaxHz, settings.targetRefreshRate.hz, 60)
        settings.fpsUnlocked -> settings.targetRefreshRate.hz
        else -> 60
      }
    val sustained =
      settings.performanceMax && !settings.ignoreThermalLimit && settings.boostActive
    telemetryManager.applyDisplayRefreshRate(
      activity = activity,
      targetHz = targetHz,
      sustainedPerformance = sustained,
    )
  }

  fun toggleCooler() {
    viewModelScope.launch {
      val next = !_uiState.value.settings.coolerActive
      repository.setCoolerActive(next)
      telemetryManager.pulseHaptic(false)
      if (next) {
        voiceEngine.playCoolerCue(_uiState.value.settings.voiceEnabled)
        startCoolingCycle()
      }
    }
  }

  fun selectCoolerSpeed(speed: CoolerSpeed) {
    viewModelScope.launch {
      repository.setCoolerSpeed(speed)
      telemetryManager.pulseHaptic(false)
    }
  }

  fun startCoolingCycle() {
    if (_uiState.value.isCoolingCycleRunning) return
    viewModelScope.launch {
      repository.setCoolerActive(true)
      voiceEngine.playCoolerCue(_uiState.value.settings.voiceEnabled)
      _uiState.update { it.copy(isCoolingCycleRunning = true, coolingProgress = 0.05f) }
      val freed = withContext(Dispatchers.IO) { telemetryManager.performMemoryAndCacheCleanup() }
      val shizukuNote = shizukuBridge.runCoolerSweep()
      for (step in 1..20) {
        delay(120L)
        _uiState.update { it.copy(coolingProgress = step / 20f) }
      }
      refreshTelemetryInternal()
      telemetryManager.pulseHaptic(true)
      _uiState.update {
        it.copy(
          isCoolingCycleRunning = false,
          coolingProgress = 1f,
          lastFreedRamMb = freed,
          statusBanner =
            if (shizukuNote != null) shizukuNote
            else
              "Resfriamento Criogênico concluído • ${freed} MB limpos • Temp: ${String.format("%.1f", it.telemetry.batteryTempC)}°C",
        )
      }
    }
  }

  fun runDiagnosticScan() {
    if (_uiState.value.isScanningDiagnostics) return
    viewModelScope.launch {
      _uiState.update { it.copy(isScanningDiagnostics = true) }
      telemetryManager.pulseHaptic(false)
      delay(900L)
      refreshTelemetryInternal()
      _uiState.update {
        it.copy(
          isScanningDiagnostics = false,
          statusBanner = "Verificação concluída: ${_uiState.value.findings.size} análise(s) atualizada(s).",
        )
      }
    }
  }

  fun executeDiagnosticAction(
    action: DiagnosticAction,
    context: Context,
    activity: Activity?,
    onSwitchToCooler: () -> Unit,
  ) {
    viewModelScope.launch {
      when (action) {
        DiagnosticAction.OPTIMIZE_RAM -> {
          val freed = withContext(Dispatchers.IO) { telemetryManager.performMemoryAndCacheCleanup() }
          refreshTelemetryInternal()
          telemetryManager.pulseHaptic(true)
          _uiState.update {
            it.copy(
              lastFreedRamMb = freed,
              statusBanner = "Otimização executada: ${freed} MB de RAM e caches liberados.",
            )
          }
        }
        DiagnosticAction.ACTIVATE_COOLER -> {
          onSwitchToCooler()
          startCoolingCycle()
        }
        DiagnosticAction.OPEN_BATTERY_SETTINGS -> {
          try {
            val intent =
              Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
          } catch (_: Exception) {
            _uiState.update {
              it.copy(statusBanner = "Abra Configurações > Bateria para desativar a Economia.")
            }
          }
        }
        DiagnosticAction.UNLOCK_FPS -> {
          val maxHz = telemetryManager.readHardwareMaxHzQuick()
          val maxOpt = resolveMaxRefreshOption(maxHz)
          repository.setFpsUnlocked(true)
          repository.setTargetRefreshRate(maxOpt)
          applyWindowPerformance(
            activity,
            _uiState.value.settings.copy(
              fpsUnlocked = true,
              targetRefreshRate = maxOpt,
            ),
          )
          telemetryManager.pulseHaptic(true)
          _uiState.update { it.copy(statusBanner = "FPS desbloqueado e taxa definida para ${maxOpt.label}!") }
        }
        DiagnosticAction.NONE -> Unit
      }
    }
  }

  fun addGamePackage(packageName: String) {
    viewModelScope.launch {
      repository.addGamePackage(packageName)
      telemetryManager.pulseHaptic(false)
      _uiState.update { it.copy(statusBanner = "Jogo adicionado ao Infinit Booster!") }
    }
  }

  fun removeGamePackage(packageName: String) {
    viewModelScope.launch {
      repository.removeGamePackage(packageName)
      repository.removePerGameConfig(packageName)
      GuardianMemoryStore.removeGame(getApplication(), packageName)
      if (_uiState.value.shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED) {
        shizukuBridge.clearPerGameDriverOptIn(packageName)
      }
      telemetryManager.pulseHaptic(false)
    }
  }

  fun refreshInstalledApps() {
    viewModelScope.launch {
      val apps = withContext(Dispatchers.IO) { telemetryManager.queryLaunchableApps() }
      _installedApps.value = apps
    }
  }

  fun loadAppIcon(packageName: String): Drawable? = telemetryManager.loadAppIcon(packageName)

  fun launchGameWithBoost(packageName: String, label: String, activity: Activity?) {
    viewModelScope.launch {
      val current = _uiState.value.settings
      val freshTelemetry =
        withContext(Dispatchers.IO) {
          telemetryManager.readTelemetry(latestFps, latestFrameTimeMs)
        }
      val shizukuConnected =
        _uiState.value.shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED
      val shizukuProbedMaxHz =
        if (shizukuConnected && freshTelemetry.maxDisplayHz <= 60) {
          shizukuBridge.probeHardwareMaxHzViaShizuku()
        } else {
          null
        }
      val deviceMaxHz = maxOf(freshTelemetry.maxDisplayHz, shizukuProbedMaxHz ?: 60, 60)
      val maxOption = resolveMaxRefreshOption(deviceMaxHz)
      val canForceRootPerf =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && shizukuConnected

      val activeSettings =
        current.copy(
          boostActive = true,
          fpsUnlocked = true,
          performanceMax = true,
          ignoreThermalLimit = true,
          targetRefreshRate = maxOption,
          rootTotalPerformanceEnabled = canForceRootPerf || current.rootTotalPerformanceEnabled,
        )
      repository.setBoostActive(true)
      repository.setFpsUnlocked(true)
      repository.setPerformanceMax(true)
      repository.setIgnoreThermalLimit(true)
      repository.setTargetRefreshRate(maxOption)
      if (canForceRootPerf) {
        repository.setRootTotalPerformanceEnabled(true)
      }

      withContext(Dispatchers.IO) { telemetryManager.performMemoryAndCacheCleanup() }
      telemetryManager.applyDisplayRefreshRate(
        activity = activity,
        targetHz = deviceMaxHz,
        sustainedPerformance = false,
      )
      val shizukuReport =
        shizukuBridge.applyBoostProfile(
          settings = activeSettings,
          targetGamePackage = packageName,
          forcedMaxHz = deviceMaxHz,
          screenWidthPx = freshTelemetry.screenSpec.widthPx,
          screenHeightPx = freshTelemetry.screenSpec.heightPx,
        )
      val rootReport =
        if (canForceRootPerf) {
          shizukuBridge.applyRootTotalPerformance(true, (current.addedGamePackages + packageName))
        } else {
          null
        }
      val gameCfg = current.perGameConfigs[packageName]
      val perGameRenderLog =
        if (shizukuConnected && gameCfg != null && gameCfg.rendererMode != GameRendererMode.GLOBAL) {
          shizukuBridge.applyPerGameRenderer(packageName, gameCfg.rendererMode)
        } else {
          if (shizukuConnected) {
            shizukuBridge.clearPerGameDriverOptIn(packageName)
          }
          null
        }
      val verifiedGraphics =
        if (shizukuConnected) {
          shizukuBridge.verifyGraphicsBackendStatus(current.addedGamePackages + packageName)
        } else {
          _uiState.value.graphicsStatus
        }
      val combinedLogs =
        buildList {
          shizukuReport?.details?.let { addAll(it) }
          rootReport?.details?.let { addAll(it) }
          perGameRenderLog?.let { add(it) }
        }.distinct()

      val needsGameSession =
        gameCfg != null &&
          (gameCfg.customDpi != null || gameCfg.touchScriptEnabled || gameCfg.guardianBetaEnabled)
      if (needsGameSession && gameCfg != null) {
        GameDpiSessionService.start(
          context = getApplication(),
          packageName = packageName,
          label = label,
          customDpi = gameCfg.customDpi,
          touchEnabled = gameCfg.touchScriptEnabled,
          touchSensitivity = gameCfg.touchSensitivity,
          guardianBetaEnabled = gameCfg.guardianBetaEnabled,
          targetHz = deviceMaxHz,
          rendererModeId = gameCfg.rendererMode.id,
        )
      } else if (GameDpiSessionService.isRunning) {
        GameDpiSessionService.restoreAndStop(getApplication())
      }

      voiceEngine.playHollowPurpleBoost(current.voiceEnabled)
      telemetryManager.pulseHaptic(heavy = true)
      val effectiveRendererLabel =
        if (gameCfg != null && gameCfg.rendererMode != GameRendererMode.GLOBAL) {
          gameCfg.rendererMode.label
        } else {
          current.graphicsBackend.title
        }
      val extra = buildList {
        if (shizukuReport != null) add("Shizuku")
        if (gameCfg?.customDpi != null) add("DPI ${gameCfg.customDpi}")
        if (gameCfg?.touchScriptEnabled == true) add("Touch Nv.${gameCfg.touchSensitivity}")
        if (gameCfg?.guardianBetaEnabled == true) add("IA Beta")
      }.joinToString(" + ")
      val suffix = if (extra.isNotBlank()) " + $extra" else ""
      val pendingParts = buildList {
        if (gameCfg != null && gameCfg.rendererMode != GameRendererMode.GLOBAL) {
          add(gameCfg.rendererMode.shortLabel)
        }
        if (gameCfg?.customDpi != null) add("DPI")
        if (gameCfg?.touchScriptEnabled == true) add("Touch")
      }
      val shizukuWarn =
        if (!shizukuConnected && pendingParts.isNotEmpty()) {
          " • Conecte Shizuku p/ aplicar ${pendingParts.joinToString("/")}"
        } else {
          ""
        }
      _uiState.update {
        it.copy(
          boostPulseKey = it.boostPulseKey + 1,
          graphicsStatus = verifiedGraphics,
          shizukuLogs = combinedLogs.ifEmpty { it.shizukuLogs },
          statusBanner =
            "Vazio Roxo ${deviceMaxHz}Hz ($effectiveRendererLabel$suffix) • Abrindo $label...$shizukuWarn",
        )
      }
      delay(if (needsGameSession) 700L else 450L)
      val launched = telemetryManager.launchPackage(packageName)
      if (!launched) {
        if (needsGameSession) {
          GameDpiSessionService.restoreAndStop(getApplication())
        }
        _uiState.update {
          it.copy(statusBanner = "Não foi possível abrir $label neste dispositivo.")
        }
      }
    }
  }

  fun openDeveloperGraphicsSettings(context: Context) {
    try {
      val devIntent =
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(devIntent)
    } catch (_: Exception) {
      try {
        val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(fallback)
        _uiState.update {
          it.copy(
            statusBanner =
              "Ative as Opções do Desenvolvedor para selecionar ANGLE/SkiaGL/Vulkan no sistema."
          )
        }
      } catch (_: Exception) {
        _uiState.update { it.copy(statusBanner = "Abra as Configurações do sistema.") }
      }
    }
  }

  private fun refreshCrashLogState() {
    val app = getApplication<Application>()
    val crash = AppCrashLogger.readLastCrash(app)
    val events = AppCrashLogger.readRecentEvents(app)
    _uiState.update {
      it.copy(
        hasCrashOrErrorLogs = crash != null || events.isNotEmpty(),
        lastCrashPreview = crash?.lineSequence()?.take(4)?.joinToString("\n"),
      )
    }
  }

  fun buildDiagnosticReportText(): String =
    AppCrashLogger.buildFullDiagnosticReport(getApplication(), _uiState.value)

  fun copyDiagnosticReportToClipboard(context: Context) {
    val text = buildDiagnosticReportText()
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText("Infinit Booster Raio-X", text)
    clipboard.setPrimaryClip(clip)
    telemetryManager.pulseHaptic(false)
    _uiState.update {
      it.copy(statusBanner = "Raio-X copiado! Cole aqui no chat para a IA analisar seu aparelho.")
    }
  }

  fun shareDiagnosticReport(context: Context) {
    val text = buildDiagnosticReportText()
    runCatching {
      val shareIntent =
        Intent(Intent.ACTION_SEND).apply {
          type = "text/plain"
          putExtra(Intent.EXTRA_SUBJECT, "Infinit Booster • Raio-X do Aparelho")
          putExtra(Intent.EXTRA_TEXT, text)
        }
      val chooser =
        Intent.createChooser(shareIntent, "Enviar Raio-X de Diagnóstico").apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      context.startActivity(chooser)
    }
  }

  fun clearDiagnosticCrashLogs() {
    AppCrashLogger.clearLogs(getApplication())
    refreshCrashLogState()
    telemetryManager.pulseHaptic(false)
    _uiState.update {
      it.copy(statusBanner = "Histórico de erros e crashes limpo.")
    }
  }

  fun clearStatusBanner() {
    _uiState.update { it.copy(statusBanner = null) }
  }

  override fun onCleared() {
    super.onCleared()
    voiceEngine.release()
    musicEngine.release()
    shizukuBridge.release()
  }
}
