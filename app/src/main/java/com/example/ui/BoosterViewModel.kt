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
import com.example.data.BoosterPreferencesRepository
import com.example.data.DeviceTelemetryManager
import com.example.model.BoosterSettings
import com.example.model.BottleneckFinding
import com.example.model.CoolerSpeed
import com.example.model.DeviceTelemetry
import com.example.model.DiagnosticAction
import com.example.model.GraphicsBackend
import com.example.model.GraphicsRendererStatus
import com.example.model.InstalledAppItem
import com.example.model.IphoneTouchConfig
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
import com.example.shizuku.ShizukuModulesCatalog
import com.example.shizuku.ShizukuStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
  val moduleLogs: Map<String, String> = emptyMap(),
  val canWriteSystemSettings: Boolean = false,
  val canDrawOverlays: Boolean = false,
)

class BoosterViewModel(application: Application) : AndroidViewModel(application) {

  private val repository = BoosterPreferencesRepository(application)
  private val telemetryManager = DeviceTelemetryManager(application)
  private val voiceEngine = DomainVoiceEngine(application)
  private val musicEngine = BackgroundMusicEngine(application)
  private val shizukuBridge = ShizukuBridge(application)

  private val _uiState = MutableStateFlow(BoosterUiState())
  val uiState: StateFlow<BoosterUiState> = _uiState.asStateFlow()

  @Volatile private var latestFps: Int = 60
  @Volatile private var latestFrameTimeMs: Float = 16.6f
  private var hasSyncedInitialMusic = false

  init {
    refreshSpecialPermissions()
    viewModelScope.launch {
      val apps = withContext(Dispatchers.IO) { telemetryManager.queryLaunchableApps() }
      _uiState.update { it.copy(installedApps = apps) }
    }

    viewModelScope.launch {
      combine(repository.settingsFlow, _uiState) { settings, current ->
          val added = current.installedApps.filter { it.packageName in settings.addedGamePackages }
          settings to added
        }
        .collect { (settings, added) ->
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
            state.copy(settings = settings, addedGames = added, findings = updatedFindings)
          }
        }
    }

    viewModelScope.launch {
      var prevConn: ShizukuConnectionState? = null
      shizukuBridge.status.collect { shizuku ->
        _uiState.update { it.copy(shizukuStatus = shizuku) }
        if (shizuku.connectionState == ShizukuConnectionState.CONNECTED &&
          prevConn != ShizukuConnectionState.CONNECTED
        ) {
          val verified = shizukuBridge.verifyGraphicsBackendStatus(_uiState.value.settings.addedGamePackages)
          _uiState.update { it.copy(graphicsStatus = verified) }
        } else if (shizuku.connectionState != ShizukuConnectionState.CONNECTED &&
          prevConn == ShizukuConnectionState.CONNECTED
        ) {
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
  }

  fun updateLiveFrameStats(fps: Int, avgFrameMs: Float) {
    latestFps = fps
    latestFrameTimeMs = avgFrameMs
  }

  fun onDomainIntroStart() {
    voiceEngine.playDomainExpansion(_uiState.value.settings.voiceEnabled)
    telemetryManager.pulseHaptic(heavy = true)
  }

  fun dismissDomainIntro() {
    _uiState.update { it.copy(showDomainIntro = false) }
    onAppForegrounded()
  }

  fun replayDomainIntro() {
    musicEngine.pause()
    _uiState.update { it.copy(showDomainIntro = true) }
    voiceEngine.playDomainExpansion(_uiState.value.settings.voiceEnabled)
    telemetryManager.pulseHaptic(heavy = true)
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
        val report = shizukuBridge.applyBoostProfile(_uiState.value.settings)
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

  fun toggleModule(moduleId: String, enabled: Boolean) {
    viewModelScope.launch {
      repository.setModuleEnabled(moduleId, enabled)
      telemetryManager.pulseHaptic(false)
      val currentSettings = _uiState.value.settings
      if (enabled) {
        val msg = shizukuBridge.applySingleModule(moduleId, currentSettings)
        val logText = msg ?: "Módulo ativo no perfil. Autorize o Shizuku para executar no sistema."
        _uiState.update {
          it.copy(
            moduleLogs = it.moduleLogs + (moduleId to logText),
            statusBanner = logText,
          )
        }
      } else {
        val msg = shizukuBridge.disableSingleModule(moduleId, currentSettings)
        val logText = msg ?: "Módulo desativado no perfil."
        _uiState.update {
          it.copy(
            moduleLogs = it.moduleLogs + (moduleId to logText),
            statusBanner = logText,
          )
        }
      }
      if (moduleId == "mod_14_iphone_touch") {
        repository.setIphoneTouchConfig(currentSettings.iphoneTouchConfig.copy(enabled = enabled))
      }
      if (moduleId == "mod_04_vulkan_hwui" || moduleId == "mod_05_angle_driver") {
        val verified = shizukuBridge.verifyGraphicsBackendStatus(currentSettings.addedGamePackages)
        _uiState.update { it.copy(graphicsStatus = verified) }
      }
    }
  }

  fun restoreAllFactoryDefaults(activity: Activity?) {
    viewModelScope.launch {
      val app = getApplication<Application>()
      val addedGames = _uiState.value.settings.addedGamePackages
      repository.restoreFactoryDefaultsState()
      FloatingHzOverlayService.stop(app)
      FloatingResolutionOverlayService.stop(app)
      FloatingFpsMeterService.stop(app)
      ResolutionPanelService.stop(app)
      GameDpiSessionService.restoreAndStop(app)
      val defaultSettings =
        _uiState.value.settings.copy(
          boostActive = false,
          fpsUnlocked = false,
          performanceMax = false,
          ignoreThermalLimit = false,
          coolerActive = false,
          graphicsBackend = GraphicsBackend.VULKAN_MAX,
          enabledModuleIds = emptySet(),
          targetRefreshRate = RefreshRateOption.HZ_60,
          floatingHzOverlayEnabled = false,
          floatingResolutionOverlayEnabled = false,
          floatingFpsMeterEnabled = false,
          backgroundResolutionPanelEnabled = false,
        )
      applyWindowPerformance(activity, defaultSettings)
      val report = shizukuBridge.restoreAllDefaults(addedGames)
      val verified = shizukuBridge.verifyGraphicsBackendStatus(addedGames)
      telemetryManager.pulseHaptic(false)
      _uiState.update {
        it.copy(
          shizukuLogs = report?.details ?: emptyList(),
          moduleLogs = emptyMap(),
          graphicsStatus = verified,
          statusBanner =
            report?.summary ?: "Todas as opções, overlays e renderização foram restaurados para o padrão!",
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

  fun restoreNativeResolution() {
    viewModelScope.launch {
      val spec = telemetryManager.readNativeScreenSpec()
      repository.setResolutionConfig(100, spec.widthPx, spec.heightPx, true)
      val msg = shizukuBridge.restoreNativeResolution()
      telemetryManager.pulseHaptic(false)
      _uiState.update {
        it.copy(statusBanner = msg ?: "Resolução padrão (${spec.widthPx}x${spec.heightPx}) selecionada.")
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
        val deviceMaxHz = maxOf(freshTelemetry.maxDisplayHz, 60)
        val maxOption = resolveMaxRefreshOption(deviceMaxHz)
        val canForceRootPerf =
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            _uiState.value.shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED

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
        val shizukuReport = shizukuBridge.applyBoostProfile(nextSettings, forcedMaxHz = deviceMaxHz)
        val rootReport =
          if (canForceRootPerf) {
            shizukuBridge.applyRootTotalPerformance(true, nextSettings.addedGamePackages)
          } else {
            null
          }
        val combinedLogs =
          ((shizukuReport?.details ?: emptyList()) + (rootReport?.details ?: emptyList())).distinct()
        val verifiedGraphics =
          if (_uiState.value.shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED) {
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
              "Modo Infinito Ativo • ${deviceMaxHz}Hz Máx + Desempenho Total Forçado$shizukuSuffix (${freed} MB)",
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
      telemetryManager.pulseHaptic(false)
    }
  }

  fun updateIphoneTouchConfig(config: IphoneTouchConfig) {
    viewModelScope.launch {
      val prevEnabled = _uiState.value.settings.iphoneTouchConfig.enabled
      repository.setIphoneTouchConfig(config)
      if (config.enabled != prevEnabled) {
        repository.setModuleEnabled("mod_14_iphone_touch", config.enabled)
      }
      telemetryManager.pulseHaptic(false)
      if (config.enabled != prevEnabled) {
        val report = shizukuBridge.applyIphoneTouchModule(config, _uiState.value.settings.addedGamePackages)
        if (report != null) {
          _uiState.update {
            it.copy(
              shizukuLogs = report.details,
              moduleLogs = it.moduleLogs + ("mod_14_iphone_touch" to report.summary),
              statusBanner = report.summary,
            )
          }
        }
      }
    }
  }

  fun applyIphoneTouchModuleNow() {
    viewModelScope.launch {
      val enabledCfg = _uiState.value.settings.iphoneTouchConfig.copy(enabled = true)
      repository.setIphoneTouchConfig(enabledCfg)
      repository.setModuleEnabled("mod_14_iphone_touch", true)
      val report = shizukuBridge.applyIphoneTouchModule(enabledCfg, _uiState.value.settings.addedGamePackages)
      telemetryManager.pulseHaptic(true)
      _uiState.update {
        it.copy(
          shizukuLogs = report?.details ?: it.shizukuLogs,
          moduleLogs = it.moduleLogs + ("mod_14_iphone_touch" to (report?.summary ?: "Salvo • Autorize Shizuku para aplicar")),
          statusBanner =
            report?.summary ?: "Módulo iPhone Touch salvo! Conecte o Shizuku para aplicar no sistema.",
        )
      }
    }
  }

  fun restoreIphoneTouchDefaults() {
    viewModelScope.launch {
      val resetCfg = _uiState.value.settings.iphoneTouchConfig.copy(enabled = false)
      repository.setIphoneTouchConfig(resetCfg)
      repository.setModuleEnabled("mod_14_iphone_touch", false)
      val msg = shizukuBridge.restoreTouchDefaults()
      telemetryManager.pulseHaptic(false)
      _uiState.update {
        it.copy(
          moduleLogs = it.moduleLogs + ("mod_14_iphone_touch" to (msg ?: "Toque restaurado ao padrão")),
          statusBanner = msg ?: "Toque restaurado para o padrão no perfil.",
        )
      }
    }
  }

  fun applyWindowPerformance(activity: Activity?, settings: BoosterSettings = _uiState.value.settings) {
    val targetHz =
      when {
        settings.boostActive ->
          maxOf(_uiState.value.telemetry.maxDisplayHz, settings.targetRefreshRate.hz, 60)
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
          repository.setFpsUnlocked(true)
          repository.setTargetRefreshRate(RefreshRateOption.HZ_120)
          applyWindowPerformance(
            activity,
            _uiState.value.settings.copy(
              fpsUnlocked = true,
              targetRefreshRate = RefreshRateOption.HZ_120,
            ),
          )
          telemetryManager.pulseHaptic(true)
          _uiState.update { it.copy(statusBanner = "FPS desbloqueado e taxa definida para 120 Hz!") }
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
      telemetryManager.pulseHaptic(false)
    }
  }

  fun refreshInstalledApps() {
    viewModelScope.launch {
      val apps = withContext(Dispatchers.IO) { telemetryManager.queryLaunchableApps() }
      _uiState.update { it.copy(installedApps = apps) }
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
      val deviceMaxHz = maxOf(freshTelemetry.maxDisplayHz, 60)
      val maxOption = resolveMaxRefreshOption(deviceMaxHz)
      val canForceRootPerf =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
          _uiState.value.shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED

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
          activeSettings,
          targetGamePackage = packageName,
          forcedMaxHz = deviceMaxHz,
        )
      val rootReport =
        if (canForceRootPerf) {
          shizukuBridge.applyRootTotalPerformance(true, (current.addedGamePackages + packageName))
        } else {
          null
        }
      val combinedLogs =
        ((shizukuReport?.details ?: emptyList()) + (rootReport?.details ?: emptyList())).distinct()
      val gameCfg = current.perGameConfigs[packageName]
      val hasGameDpiOrTouch =
        gameCfg != null && (gameCfg.customDpi != null || gameCfg.touchScriptEnabled)
      if (hasGameDpiOrTouch && gameCfg != null) {
        GameDpiSessionService.start(
          context = getApplication(),
          packageName = packageName,
          label = label,
          customDpi = gameCfg.customDpi,
          touchEnabled = gameCfg.touchScriptEnabled,
          touchSensitivity = gameCfg.touchSensitivity,
        )
      }

      voiceEngine.playHollowPurpleBoost(current.voiceEnabled)
      telemetryManager.pulseHaptic(heavy = true)
      val extra = buildList {
        if (shizukuReport != null) add("Shizuku")
        if (gameCfg?.customDpi != null) add("DPI ${gameCfg.customDpi}")
        if (gameCfg?.touchScriptEnabled == true) add("Touch Nv.${gameCfg.touchSensitivity}")
      }.joinToString(" + ")
      val suffix = if (extra.isNotBlank()) " + $extra" else ""
      _uiState.update {
        it.copy(
          boostPulseKey = it.boostPulseKey + 1,
          shizukuLogs = combinedLogs.ifEmpty { it.shizukuLogs },
          statusBanner = "Vazio Roxo ${deviceMaxHz}Hz (${current.graphicsBackend.title}$suffix) • Abrindo $label...",
        )
      }
      delay(if (hasGameDpiOrTouch) 700L else 450L)
      val launched = telemetryManager.launchPackage(packageName)
      if (!launched) {
        if (hasGameDpiOrTouch) {
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
