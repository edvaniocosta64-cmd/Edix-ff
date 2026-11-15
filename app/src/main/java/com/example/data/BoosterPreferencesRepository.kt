package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.BoosterSettings
import com.example.model.CoolerSpeed
import com.example.model.GraphicsBackend
import com.example.model.IphoneTouchConfig
import com.example.model.IphoneTouchPreset
import com.example.model.PerGameConfig
import com.example.model.RefreshRateOption
import com.example.model.WallpaperTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.boosterDataStore by preferencesDataStore(name = "infinit_booster_prefs")

class BoosterPreferencesRepository(private val context: Context) {

  private object Keys {
    val BOOST_ACTIVE = booleanPreferencesKey("boost_active")
    val FPS_UNLOCKED = booleanPreferencesKey("fps_unlocked")
    val PERFORMANCE_MAX = booleanPreferencesKey("performance_max")
    val IGNORE_THERMAL_LIMIT = booleanPreferencesKey("ignore_thermal_limit")
    val GRAPHICS_BACKEND = stringPreferencesKey("graphics_backend")
    val TARGET_HZ = intPreferencesKey("target_hz")
    val COOLER_ACTIVE = booleanPreferencesKey("cooler_active")
    val COOLER_SPEED = stringPreferencesKey("cooler_speed")
    val VOICE_ENABLED = booleanPreferencesKey("voice_enabled")
    val ADDED_GAMES = stringSetPreferencesKey("added_games")
    val ENABLED_MODULES = stringSetPreferencesKey("enabled_modules")
    val RES_SCALE_PERCENT = intPreferencesKey("res_scale_percent")
    val RES_CUSTOM_W = intPreferencesKey("res_custom_w")
    val RES_CUSTOM_H = intPreferencesKey("res_custom_h")
    val LOCK_ASPECT_RATIO = booleanPreferencesKey("lock_aspect_ratio")
    val BG_RES_PANEL = booleanPreferencesKey("bg_res_panel")
    val ROOT_TOTAL_PERF = booleanPreferencesKey("root_total_perf")
    val FLOATING_HZ_OVERLAY = booleanPreferencesKey("floating_hz_overlay")
    val FLOATING_RES_OVERLAY = booleanPreferencesKey("floating_res_overlay")
    val FLOATING_FPS_METER = booleanPreferencesKey("floating_fps_meter")
    val CUSTOM_DOMAIN_AUDIO_NAME = stringPreferencesKey("custom_domain_audio_name")
    val BG_MUSIC_ENABLED = booleanPreferencesKey("bg_music_enabled")
    val BG_MUSIC_NAME = stringPreferencesKey("bg_music_name")
    val BG_MUSIC_VOLUME = floatPreferencesKey("bg_music_volume")
    val WALLPAPER_THEME = stringPreferencesKey("wallpaper_theme")
    val CUSTOM_WALLPAPER_NAME = stringPreferencesKey("custom_wallpaper_name")
    val WALLPAPER_REVISION = intPreferencesKey("wallpaper_revision")
    val ANIMATION_SCALE = floatPreferencesKey("animation_scale")
    val PER_GAME_CONFIGS = stringSetPreferencesKey("per_game_configs")
    val IPHONE_TOUCH_ENABLED = booleanPreferencesKey("iphone_touch_enabled")
    val IPHONE_TOUCH_PRESET = stringPreferencesKey("iphone_touch_preset")
    val IPHONE_TOUCH_POINTER = intPreferencesKey("iphone_touch_pointer")
    val IPHONE_TOUCH_LONG_PRESS = intPreferencesKey("iphone_touch_long_press")
    val IPHONE_TOUCH_BACKPRESSURE = booleanPreferencesKey("iphone_touch_backpressure")
    val IPHONE_TOUCH_SMOOTH_FLING = booleanPreferencesKey("iphone_touch_smooth_fling")
    val IPHONE_TOUCH_MULTITOUCH = booleanPreferencesKey("iphone_touch_multitouch")
  }

  val settingsFlow: Flow<BoosterSettings> =
    context.boosterDataStore.data.map { prefs ->
      val backendId = prefs[Keys.GRAPHICS_BACKEND] ?: GraphicsBackend.VULKAN_MAX.id
      val targetHz = prefs[Keys.TARGET_HZ] ?: 120
      val coolerSpeedId = prefs[Keys.COOLER_SPEED] ?: CoolerSpeed.TURBO.id
      val wallpaperThemeId = prefs[Keys.WALLPAPER_THEME] ?: WallpaperTheme.VAZIO_ROXO.id

      val rawPerGame = prefs[Keys.PER_GAME_CONFIGS] ?: emptySet()
      val perGameMap =
        rawPerGame.mapNotNull { entry ->
          val parts = entry.split("|")
          if (parts.size < 4 || parts[0].isBlank()) return@mapNotNull null
          val dpiVal = parts[1].toIntOrNull()?.takeIf { it in 400..1440 }
          val touchEnabled = parts[2].toBooleanStrictOrNull() ?: false
          val sens = (parts[3].toIntOrNull() ?: 7).coerceIn(1, 7)
          parts[0] to
            PerGameConfig(
              packageName = parts[0],
              customDpi = dpiVal,
              touchScriptEnabled = touchEnabled,
              touchSensitivity = sens,
            )
        }.toMap()

      val iphonePresetId = prefs[Keys.IPHONE_TOUCH_PRESET] ?: IphoneTouchPreset.IOS_LINEAR.id
      val iphoneConfig =
        IphoneTouchConfig(
          enabled = prefs[Keys.IPHONE_TOUCH_ENABLED] ?: false,
          preset =
            IphoneTouchPreset.entries.firstOrNull { it.id == iphonePresetId }
              ?: IphoneTouchPreset.IOS_LINEAR,
          pointerSpeed = (prefs[Keys.IPHONE_TOUCH_POINTER] ?: 5).coerceIn(1, 7),
          longPressMs = (prefs[Keys.IPHONE_TOUCH_LONG_PRESS] ?: 180).coerceIn(120, 350),
          zeroBackpressure = prefs[Keys.IPHONE_TOUCH_BACKPRESSURE] ?: true,
          smoothFlingScroll = prefs[Keys.IPHONE_TOUCH_SMOOTH_FLING] ?: true,
          multiTouchGameBoost = prefs[Keys.IPHONE_TOUCH_MULTITOUCH] ?: true,
        )

      BoosterSettings(
        boostActive = prefs[Keys.BOOST_ACTIVE] ?: false,
        fpsUnlocked = prefs[Keys.FPS_UNLOCKED] ?: true,
        performanceMax = prefs[Keys.PERFORMANCE_MAX] ?: true,
        ignoreThermalLimit = prefs[Keys.IGNORE_THERMAL_LIMIT] ?: false,
        graphicsBackend =
          GraphicsBackend.entries.firstOrNull { it.id == backendId } ?: GraphicsBackend.VULKAN_MAX,
        targetRefreshRate =
          RefreshRateOption.entries.firstOrNull { it.hz == targetHz } ?: RefreshRateOption.HZ_120,
        animationScale = (prefs[Keys.ANIMATION_SCALE] ?: 0.25f).coerceIn(0f, 1f),
        coolerActive = prefs[Keys.COOLER_ACTIVE] ?: false,
        coolerSpeed =
          CoolerSpeed.entries.firstOrNull { it.id == coolerSpeedId } ?: CoolerSpeed.TURBO,
        voiceEnabled = prefs[Keys.VOICE_ENABLED] ?: true,
        addedGamePackages = prefs[Keys.ADDED_GAMES] ?: emptySet(),
        perGameConfigs = perGameMap,
        iphoneTouchConfig = iphoneConfig,
        enabledModuleIds = prefs[Keys.ENABLED_MODULES] ?: emptySet(),
        resolutionScalePercent = prefs[Keys.RES_SCALE_PERCENT] ?: 100,
        resolutionCustomWidth = prefs[Keys.RES_CUSTOM_W] ?: 0,
        resolutionCustomHeight = prefs[Keys.RES_CUSTOM_H] ?: 0,
        lockAspectRatio = prefs[Keys.LOCK_ASPECT_RATIO] ?: true,
        backgroundResolutionPanelEnabled = prefs[Keys.BG_RES_PANEL] ?: false,
        rootTotalPerformanceEnabled = prefs[Keys.ROOT_TOTAL_PERF] ?: false,
        floatingHzOverlayEnabled = prefs[Keys.FLOATING_HZ_OVERLAY] ?: false,
        floatingResolutionOverlayEnabled = prefs[Keys.FLOATING_RES_OVERLAY] ?: false,
        floatingFpsMeterEnabled = prefs[Keys.FLOATING_FPS_METER] ?: false,
        customDomainAudioName = prefs[Keys.CUSTOM_DOMAIN_AUDIO_NAME] ?: "",
        bgMusicEnabled = prefs[Keys.BG_MUSIC_ENABLED] ?: false,
        bgMusicName = prefs[Keys.BG_MUSIC_NAME] ?: "",
        bgMusicVolume = prefs[Keys.BG_MUSIC_VOLUME] ?: 0.45f,
        wallpaperTheme =
          WallpaperTheme.entries.firstOrNull { it.id == wallpaperThemeId }
            ?: WallpaperTheme.VAZIO_ROXO,
        customWallpaperName = prefs[Keys.CUSTOM_WALLPAPER_NAME] ?: "",
        wallpaperRevision = prefs[Keys.WALLPAPER_REVISION] ?: 0,
      )
    }

  suspend fun setBoostActive(active: Boolean) {
    context.boosterDataStore.edit { it[Keys.BOOST_ACTIVE] = active }
  }

  suspend fun setFpsUnlocked(unlocked: Boolean) {
    context.boosterDataStore.edit { it[Keys.FPS_UNLOCKED] = unlocked }
  }

  suspend fun setPerformanceMax(max: Boolean) {
    context.boosterDataStore.edit { it[Keys.PERFORMANCE_MAX] = max }
  }

  suspend fun setIgnoreThermalLimit(ignore: Boolean) {
    context.boosterDataStore.edit { it[Keys.IGNORE_THERMAL_LIMIT] = ignore }
  }

  suspend fun setGraphicsBackend(backend: GraphicsBackend) {
    context.boosterDataStore.edit { it[Keys.GRAPHICS_BACKEND] = backend.id }
  }

  suspend fun setTargetRefreshRate(option: RefreshRateOption) {
    context.boosterDataStore.edit { it[Keys.TARGET_HZ] = option.hz }
  }

  suspend fun setCoolerActive(active: Boolean) {
    context.boosterDataStore.edit { it[Keys.COOLER_ACTIVE] = active }
  }

  suspend fun setCoolerSpeed(speed: CoolerSpeed) {
    context.boosterDataStore.edit { it[Keys.COOLER_SPEED] = speed.id }
  }

  suspend fun setVoiceEnabled(enabled: Boolean) {
    context.boosterDataStore.edit { it[Keys.VOICE_ENABLED] = enabled }
  }

  suspend fun addGamePackage(packageName: String) {
    context.boosterDataStore.edit { prefs ->
      val current = prefs[Keys.ADDED_GAMES] ?: emptySet()
      prefs[Keys.ADDED_GAMES] = current + packageName
    }
  }

  suspend fun removeGamePackage(packageName: String) {
    context.boosterDataStore.edit { prefs ->
      val current = prefs[Keys.ADDED_GAMES] ?: emptySet()
      prefs[Keys.ADDED_GAMES] = current - packageName
    }
  }

  suspend fun setModuleEnabled(moduleId: String, enabled: Boolean) {
    context.boosterDataStore.edit { prefs ->
      val current = prefs[Keys.ENABLED_MODULES] ?: emptySet()
      prefs[Keys.ENABLED_MODULES] = if (enabled) current + moduleId else current - moduleId
    }
  }

  suspend fun setEnabledModules(moduleIds: Set<String>) {
    context.boosterDataStore.edit { it[Keys.ENABLED_MODULES] = moduleIds }
  }

  suspend fun setResolutionConfig(
    scalePercent: Int,
    customWidth: Int,
    customHeight: Int,
    lockAspect: Boolean,
  ) {
    context.boosterDataStore.edit { prefs ->
      prefs[Keys.RES_SCALE_PERCENT] = scalePercent
      prefs[Keys.RES_CUSTOM_W] = customWidth
      prefs[Keys.RES_CUSTOM_H] = customHeight
      prefs[Keys.LOCK_ASPECT_RATIO] = lockAspect
    }
  }

  suspend fun setBackgroundResolutionPanelEnabled(enabled: Boolean) {
    context.boosterDataStore.edit { it[Keys.BG_RES_PANEL] = enabled }
  }

  suspend fun setRootTotalPerformanceEnabled(enabled: Boolean) {
    context.boosterDataStore.edit { it[Keys.ROOT_TOTAL_PERF] = enabled }
  }

  suspend fun setFloatingHzOverlayEnabled(enabled: Boolean) {
    context.boosterDataStore.edit { it[Keys.FLOATING_HZ_OVERLAY] = enabled }
  }

  suspend fun setFloatingResolutionOverlayEnabled(enabled: Boolean) {
    context.boosterDataStore.edit { it[Keys.FLOATING_RES_OVERLAY] = enabled }
  }

  suspend fun setFloatingFpsMeterEnabled(enabled: Boolean) {
    context.boosterDataStore.edit { it[Keys.FLOATING_FPS_METER] = enabled }
  }

  suspend fun setCustomDomainAudioName(name: String) {
    context.boosterDataStore.edit { it[Keys.CUSTOM_DOMAIN_AUDIO_NAME] = name }
  }

  suspend fun setBgMusicConfig(enabled: Boolean, name: String) {
    context.boosterDataStore.edit { prefs ->
      prefs[Keys.BG_MUSIC_ENABLED] = enabled
      prefs[Keys.BG_MUSIC_NAME] = name
    }
  }

  suspend fun setBgMusicEnabled(enabled: Boolean) {
    context.boosterDataStore.edit { it[Keys.BG_MUSIC_ENABLED] = enabled }
  }

  suspend fun setBgMusicVolume(volume: Float) {
    context.boosterDataStore.edit { it[Keys.BG_MUSIC_VOLUME] = volume.coerceIn(0f, 1f) }
  }

  suspend fun setWallpaperTheme(theme: WallpaperTheme) {
    context.boosterDataStore.edit { it[Keys.WALLPAPER_THEME] = theme.id }
  }

  suspend fun setCustomWallpaper(name: String, theme: WallpaperTheme) {
    context.boosterDataStore.edit { prefs ->
      prefs[Keys.CUSTOM_WALLPAPER_NAME] = name
      prefs[Keys.WALLPAPER_THEME] = theme.id
      prefs[Keys.WALLPAPER_REVISION] = (prefs[Keys.WALLPAPER_REVISION] ?: 0) + 1
    }
  }

  suspend fun setAnimationScale(scale: Float) {
    context.boosterDataStore.edit { it[Keys.ANIMATION_SCALE] = scale.coerceIn(0f, 1f) }
  }

  suspend fun setPerGameConfig(config: PerGameConfig) {
    context.boosterDataStore.edit { prefs ->
      val current = (prefs[Keys.PER_GAME_CONFIGS] ?: emptySet())
        .filterNot { it.startsWith("${config.packageName}|") }
        .toMutableSet()
      val dpiVal = config.customDpi?.coerceIn(400, 1440) ?: 0
      current += "${config.packageName}|$dpiVal|${config.touchScriptEnabled}|${config.touchSensitivity.coerceIn(1, 7)}"
      prefs[Keys.PER_GAME_CONFIGS] = current
    }
  }

  suspend fun removePerGameConfig(packageName: String) {
    context.boosterDataStore.edit { prefs ->
      val current = (prefs[Keys.PER_GAME_CONFIGS] ?: emptySet())
        .filterNot { it.startsWith("$packageName|") }
        .toSet()
      prefs[Keys.PER_GAME_CONFIGS] = current
    }
  }

  suspend fun setIphoneTouchConfig(config: IphoneTouchConfig) {
    context.boosterDataStore.edit { prefs ->
      prefs[Keys.IPHONE_TOUCH_ENABLED] = config.enabled
      prefs[Keys.IPHONE_TOUCH_PRESET] = config.preset.id
      prefs[Keys.IPHONE_TOUCH_POINTER] = config.pointerSpeed.coerceIn(1, 7)
      prefs[Keys.IPHONE_TOUCH_LONG_PRESS] = config.longPressMs.coerceIn(120, 350)
      prefs[Keys.IPHONE_TOUCH_BACKPRESSURE] = config.zeroBackpressure
      prefs[Keys.IPHONE_TOUCH_SMOOTH_FLING] = config.smoothFlingScroll
      prefs[Keys.IPHONE_TOUCH_MULTITOUCH] = config.multiTouchGameBoost
    }
  }

  suspend fun restoreFactoryDefaultsState() {
    context.boosterDataStore.edit { prefs ->
      prefs[Keys.BOOST_ACTIVE] = false
      prefs[Keys.FPS_UNLOCKED] = false
      prefs[Keys.PERFORMANCE_MAX] = false
      prefs[Keys.IGNORE_THERMAL_LIMIT] = false
      prefs[Keys.COOLER_ACTIVE] = false
      prefs[Keys.GRAPHICS_BACKEND] = GraphicsBackend.VULKAN_MAX.id
      prefs[Keys.ENABLED_MODULES] = emptySet()
      prefs[Keys.RES_SCALE_PERCENT] = 100
      prefs[Keys.RES_CUSTOM_W] = 0
      prefs[Keys.RES_CUSTOM_H] = 0
      prefs[Keys.TARGET_HZ] = 60
      prefs[Keys.ANIMATION_SCALE] = 1.0f
      prefs[Keys.ROOT_TOTAL_PERF] = false
      prefs[Keys.BG_RES_PANEL] = false
      prefs[Keys.FLOATING_HZ_OVERLAY] = false
      prefs[Keys.FLOATING_RES_OVERLAY] = false
      prefs[Keys.FLOATING_FPS_METER] = false
      prefs[Keys.IPHONE_TOUCH_ENABLED] = false
    }
  }
}
