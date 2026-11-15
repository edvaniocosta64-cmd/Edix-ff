package com.example.model

enum class AppTab(val route: String) {
  BOOSTER("booster"),
  MODULES("modules"),
  GAMES("games"),
  COOLER("cooler"),
  DIAGNOSTICS("diagnostics"),
}

enum class GraphicsBackend(
  val id: String,
  val title: String,
  val badge: String,
  val description: String,
) {
  SKIAGL_MAX(
    id = "SKIAGL_MAX",
    title = "SkiaGL Máximo",
    badge = "2D / UI + GPU",
    description = "Pipeline Skia OpenGL no máximo para fluidez de shaders e interface sem engasgos.",
  ),
  VULKAN_MAX(
    id = "VULKAN_MAX",
    title = "Vulkan Máximo",
    badge = "API Baixo Nível",
    description = "Prioriza renderização multi-thread Vulkan com menor overhead de CPU nos jogos.",
  ),
  ANGLE_MAX(
    id = "ANGLE_MAX",
    title = "ANGLE (GLES -> Vulkan)",
    badge = "Compatibilidade+",
    description = "Camada ANGLE no máximo traduzindo OpenGL ES para pipelines gráficos modernos.",
  ),
}

enum class RefreshRateOption(val hz: Int, val label: String, val frameBudgetMs: Float) {
  HZ_60(60, "60 Hz", 16.67f),
  HZ_90(90, "90 Hz", 11.11f),
  HZ_120(120, "120 Hz", 8.33f),
}

enum class AnimationScaleOption(val scale: Float, val label: String, val subtitle: String) {
  OFF(0.0f, "0.0x", "Sem Animação"),
  ULTRA(0.25f, "0.25x", "Ultra Rápida"),
  FAST(0.5f, "0.5x", "Fluida"),
  BALANCED(0.75f, "0.75x", "Suave"),
  DEFAULT(1.0f, "1.0x", "Padrão"),
}

enum class IphoneTouchPreset(
  val id: String,
  val label: String,
  val subtitle: String,
  val pointerSpeed: Int,
  val longPressMs: Int,
  val tapTimeoutMs: Int,
) {
  IOS_LINEAR(
    id = "IOS_LINEAR",
    label = "iOS Linear 120Hz",
    subtitle = "Curva suave e precisa inspirada no toque do iPhone",
    pointerSpeed = 5,
    longPressMs = 200,
    tapTimeoutMs = 75,
  ),
  ZERO_DELAY(
    id = "ZERO_DELAY",
    label = "Zero-Delay Pro",
    subtitle = "Resposta imediata com atraso mínimo de toque",
    pointerSpeed = 6,
    longPressMs = 160,
    tapTimeoutMs = 60,
  ),
  ULTRA_PUXADA(
    id = "ULTRA_PUXADA",
    label = "Sensibilidade Alta+",
    subtitle = "Velocidade máxima de arraste para jogos competitivos",
    pointerSpeed = 7,
    longPressMs = 135,
    tapTimeoutMs = 50,
  ),
}

data class IphoneTouchConfig(
  val enabled: Boolean = false,
  val preset: IphoneTouchPreset = IphoneTouchPreset.IOS_LINEAR,
  val pointerSpeed: Int = 5,
  val longPressMs: Int = 180,
  val zeroBackpressure: Boolean = true,
  val smoothFlingScroll: Boolean = true,
  val multiTouchGameBoost: Boolean = true,
)

data class PerGameConfig(
  val packageName: String,
  val customDpi: Int? = null,
  val touchScriptEnabled: Boolean = false,
  val touchSensitivity: Int = 7,
)

enum class CoolerSpeed(val id: String, val label: String, val rpmText: String, val speedFactor: Float) {
  ECO("ECO", "Eco Silencioso", "2.400 RPM", 1.0f),
  TURBO("TURBO", "Turbo Ativo", "4.800 RPM", 2.1f),
  CRYO_MAX("CRYO_MAX", "Criogênico Máx", "7.500 RPM", 3.4f),
}

enum class WallpaperTheme(val id: String, val label: String) {
  VAZIO_ROXO("VAZIO_ROXO", "Vazio Roxo"),
  EXPANSAO_DOMINIO("EXPANSAO_DOMINIO", "Expansão Gojo"),
  REVERSAO_VERMELHA("REVERSAO_VERMELHA", "Carmesim Aka"),
  GALERIA_CUSTOM("GALERIA_CUSTOM", "Foto Própria"),
}

enum class BottleneckSeverity {
  OK,
  WARNING,
  CRITICAL,
}

enum class DiagnosticAction {
  NONE,
  OPTIMIZE_RAM,
  ACTIVATE_COOLER,
  OPEN_BATTERY_SETTINGS,
  UNLOCK_FPS,
}

data class BottleneckFinding(
  val id: String,
  val title: String,
  val detail: String,
  val recommendation: String,
  val severity: BottleneckSeverity,
  val action: DiagnosticAction = DiagnosticAction.NONE,
)

data class InstalledAppItem(
  val packageName: String,
  val label: String,
  val isGameCategory: Boolean,
)

data class PerformanceModule(
  val id: String,
  val index: Int,
  val title: String,
  val category: String,
  val description: String,
  val commandPreview: String,
)

data class NativeScreenSpec(
  val widthPx: Int = 1080,
  val heightPx: Int = 2400,
  val densityDpi: Int = 420,
) {
  val aspectRatioLabel: String
    get() {
      val ratio = heightPx.toFloat() / widthPx.coerceAtLeast(1).toFloat()
      val normalized = (ratio * 9f)
      return "${String.format("%.1f", normalized)}:9"
    }
}

data class BoosterSettings(
  val boostActive: Boolean = false,
  val fpsUnlocked: Boolean = true,
  val performanceMax: Boolean = true,
  val ignoreThermalLimit: Boolean = false,
  val graphicsBackend: GraphicsBackend = GraphicsBackend.VULKAN_MAX,
  val targetRefreshRate: RefreshRateOption = RefreshRateOption.HZ_120,
  val animationScale: Float = 0.25f,
  val coolerActive: Boolean = false,
  val coolerSpeed: CoolerSpeed = CoolerSpeed.TURBO,
  val voiceEnabled: Boolean = true,
  val addedGamePackages: Set<String> = emptySet(),
  val perGameConfigs: Map<String, PerGameConfig> = emptyMap(),
  val iphoneTouchConfig: IphoneTouchConfig = IphoneTouchConfig(),
  val enabledModuleIds: Set<String> = emptySet(),
  val resolutionScalePercent: Int = 100,
  val resolutionCustomWidth: Int = 0,
  val resolutionCustomHeight: Int = 0,
  val lockAspectRatio: Boolean = true,
  val backgroundResolutionPanelEnabled: Boolean = false,
  val rootTotalPerformanceEnabled: Boolean = false,
  val floatingHzOverlayEnabled: Boolean = false,
  val floatingResolutionOverlayEnabled: Boolean = false,
  val floatingFpsMeterEnabled: Boolean = false,
  val customDomainAudioName: String = "",
  val bgMusicEnabled: Boolean = false,
  val bgMusicName: String = "",
  val bgMusicVolume: Float = 0.45f,
  val wallpaperTheme: WallpaperTheme = WallpaperTheme.VAZIO_ROXO,
  val customWallpaperName: String = "",
  val wallpaperRevision: Int = 0,
)

data class DeviceTelemetry(
  val deviceName: String = "",
  val manufacturer: String = "",
  val androidVersion: String = "",
  val sdkInt: Int = 0,
  val cpuAbi: String = "",
  val cpuCores: Int = 0,
  val cpuClockLabel: String = "--",
  val ramTotalMb: Long = 0L,
  val ramUsedMb: Long = 0L,
  val ramAvailMb: Long = 0L,
  val ramPercent: Int = 0,
  val isLowMemory: Boolean = false,
  val storageTotalGb: Float = 0f,
  val storageUsedGb: Float = 0f,
  val storageAvailGb: Float = 0f,
  val storagePercent: Int = 0,
  val batteryTempC: Float = 32.0f,
  val batteryPercent: Int = 100,
  val isCharging: Boolean = false,
  val thermalStatusLabel: String = "Normal",
  val thermalSeverity: BottleneckSeverity = BottleneckSeverity.OK,
  val isPowerSaveMode: Boolean = false,
  val vulkanSupported: Boolean = false,
  val vulkanVersionLabel: String = "Não detectado",
  val gpuRendererLabel: String = "Detectando GPU...",
  val liveFps: Int = 60,
  val avgFrameTimeMs: Float = 16.6f,
  val currentDisplayHz: Int = 60,
  val maxDisplayHz: Int = 60,
  val supportedHzList: List<Int> = listOf(60),
  val screenSpec: NativeScreenSpec = NativeScreenSpec(),
)

data class GraphicsRendererStatus(
  val hwuiRenderer: String = "Não verificado",
  val renderEngineBackend: String = "Padrão",
  val angleGlobalActive: Boolean = false,
  val anglePackagesSummary: String = "Nenhum",
  val verifiedViaShizuku: Boolean = false,
  val activeBackendDetected: GraphicsBackend? = null,
  val lastVerifiedLabel: String = "Toque em Verificar para checar o sistema",
)
