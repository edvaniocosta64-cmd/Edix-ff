package com.example.shizuku

import com.example.model.BoosterSettings
import com.example.model.GraphicsBackend
import com.example.model.IphoneTouchConfig
import com.example.model.PerformanceModule
import java.util.Locale

object ShizukuModulesCatalog {

  val modules: List<PerformanceModule> =
    listOf(
      PerformanceModule(
        id = "mod_01_fps_unlocker",
        index = 1,
        title = "FPS Unlocker Extreme (Hz Travado)",
        category = "FLUÍDEZ",
        description = "Força min/peak/user refresh rate no sistema para travar em 60, 90 ou 120Hz reais.",
        commandPreview = "settings put system min_refresh_rate / peak_refresh_rate",
      ),
      PerformanceModule(
        id = "mod_02_fixed_perf",
        index = 2,
        title = "Fixed Performance Boost (CPU/GPU)",
        category = "EXTREME",
        description = "Ativa o modo de clock fixo de alto desempenho no PowerManager (Android 11+).",
        commandPreview = "cmd power set-fixed-performance-mode-enabled true",
      ),
      PerformanceModule(
        id = "mod_03_thermal_bypass",
        index = 3,
        title = "Thermal Unlimiter (Zero Throttling)",
        category = "TÉRMICO",
        description = "Remove o status de restrição térmica via thermalservice para rodar no máximo.",
        commandPreview = "cmd thermalservice override-status 0",
      ),
      PerformanceModule(
        id = "mod_04_vulkan_hwui",
        index = 4,
        title = "SkiaVK & Threaded Render Extreme",
        category = "GPU",
        description = "Força backend Vulkan multi-thread no RenderEngine e HWUI para menor latência.",
        commandPreview = "setprop debug.hwui.renderer skiavk / skiagl",
      ),
      PerformanceModule(
        id = "mod_05_angle_driver",
        index = 5,
        title = "ANGLE Shader & Driver Optimizer",
        category = "GPU",
        description = "Configura seleção do driver ANGLE e desativa MSAA forçado em jogos adicionados.",
        commandPreview = "settings put global angle_gl_driver_selection_values",
      ),
      PerformanceModule(
        id = "mod_06_game_mode",
        index = 6,
        title = "Game Mode Performance Injector",
        category = "JOGOS",
        description = "Injeta perfil 'performance' nativo do Android GameManager nos jogos adicionados.",
        commandPreview = "cmd game mode performance <pacotes>",
      ),
      PerformanceModule(
        id = "mod_07_zero_anim",
        index = 7,
        title = "Zero-Lag Frame Pacing (0.25x)",
        category = "LATÊNCIA",
        description = "Reduz escalas de janela e transição para 0.25x eliminando atraso de resposta.",
        commandPreview = "settings put global *_animation_scale 0.25",
      ),
      PerformanceModule(
        id = "mod_08_bg_purge",
        index = 8,
        title = "Deep Background Killer",
        category = "RAM",
        description = "Encerra processos em segundo plano ociosos liberando CPU e RAM instantaneamente.",
        commandPreview = "am kill-all && cmd activity kill-all",
      ),
      PerformanceModule(
        id = "mod_09_trim_cache",
        index = 9,
        title = "Cache & ZRAM Pressure Sweep",
        category = "MEMÓRIA",
        description = "Solicita limpeza agressiva de caches do sistema para reduzir engasgos de I/O.",
        commandPreview = "pm trim-caches 4294967296",
      ),
      PerformanceModule(
        id = "mod_10_touch_boost",
        index = 10,
        title = "Touch Sampling & Input Response+",
        category = "TOQUE",
        description = "Acelera velocidade de ponteiro e reduz tempo de resposta ao toque em jogos.",
        commandPreview = "settings put system pointer_speed 3 / long_press 250",
      ),
      PerformanceModule(
        id = "mod_11_doze_bypass",
        index = 11,
        title = "Anti-Doze & Standby Unlocker",
        category = "ENERGIA",
        description = "Desativa suspensão Doze e libera execução contínua sem cortes nos jogos.",
        commandPreview = "dumpsys deviceidle disable",
      ),
      PerformanceModule(
        id = "mod_12_dexopt_speed",
        index = 12,
        title = "ART Speed-Profile JIT Compiler",
        category = "COMPILADOR",
        description = "Compila binários dos seus jogos em perfil 'speed-profile' para carregamento rápido.",
        commandPreview = "cmd package compile -m speed-profile -f",
      ),
      PerformanceModule(
        id = "mod_13_void_ram",
        index = 13,
        title = "VOID RAM Extreme (ADB Engine)",
        category = "VOID RAM",
        description = "Módulo VOID RAM: limpa processos em 2º plano, ajusta limite de processos em cache e força trim de memória dinâmica para jogos.",
        commandPreview = "am kill-all; pm trim-caches 8G; device_config max_cached_processes",
      ),
      PerformanceModule(
        id = "mod_14_iphone_touch",
        index = 14,
        title = "iPhone Touch Engine (AxManager WebUI)",
        category = "iOS TOUCH",
        description = "Módulo estilo AxManager/Magisk WebUI: curva linear iOS, zero input-lag, boost de amostragem e toque ultra responsivo.",
        commandPreview = "pointer_speed / tap_timeout / sf.latch_unsignaled",
      ),
    )

  fun buildApplyCommand(moduleId: String, settings: BoosterSettings): String {
    val hz = settings.targetRefreshRate.hz
    val games = settings.addedGamePackages.toList()
    return when (moduleId) {
      "mod_01_fps_unlocker" ->
        "settings put system min_refresh_rate ${hz}.0; settings put system peak_refresh_rate ${hz}.0; settings put system user_refresh_rate ${hz}.0"
      "mod_02_fixed_perf" ->
        "cmd power set-fixed-performance-mode-enabled true"
      "mod_03_thermal_bypass" ->
        "cmd thermalservice override-status 0"
      "mod_04_vulkan_hwui" -> {
        val renderer =
          when (settings.graphicsBackend) {
            GraphicsBackend.VULKAN_MAX -> "skiavk"
            GraphicsBackend.SKIAGL_MAX,
            GraphicsBackend.ANGLE_MAX -> "skiagl"
          }
        val renderEngine = if (renderer == "skiavk") "skiavkthreaded" else "skiaglthreaded"
        "setprop debug.hwui.renderer $renderer; setprop debug.renderengine.backend $renderEngine"
      }
      "mod_05_angle_driver" -> {
        if (games.isNotEmpty() && settings.graphicsBackend == GraphicsBackend.ANGLE_MAX) {
          val pkgs = games.joinToString(",")
          val vals = games.joinToString(",") { "angle" }
          "setprop debug.egl.force_msaa 0; settings put global angle_gl_driver_all_angle 1; settings put global angle_gl_driver_selection_pkgs '$pkgs'; settings put global angle_gl_driver_selection_values '$vals'"
        } else {
          "setprop debug.egl.force_msaa 0"
        }
      }
      "mod_06_game_mode" -> {
        if (games.isEmpty()) "cmd game list-configs || true"
        else games.joinToString("; ") { "cmd game mode performance $it" }
      }
      "mod_07_zero_anim" -> {
        val scaleStr = String.format(Locale.US, "%.2f", settings.animationScale)
        "settings put global window_animation_scale $scaleStr; settings put global transition_animation_scale $scaleStr; settings put global animator_duration_scale $scaleStr"
      }
      "mod_08_bg_purge" ->
        "am kill-all; cmd activity kill-all"
      "mod_09_trim_cache" ->
        "pm trim-caches 4294967296; am send-trim-memory --user 0 com.android.systemui RUNNING_MODERATE"
      "mod_10_touch_boost" ->
        "settings put system pointer_speed 3; settings put secure long_press_timeout 250"
      "mod_11_doze_bypass" -> {
        val gameOps =
          if (games.isEmpty()) ""
          else "; " + games.joinToString("; ") { "cmd appops set $it RUN_ANY_IN_BACKGROUND allow" }
        "dumpsys deviceidle disable$gameOps"
      }
      "mod_12_dexopt_speed" -> {
        val targets = if (games.isEmpty()) listOf("com.example") else games.take(3)
        targets.joinToString("; ") { "cmd package compile -m speed-profile -f $it" }
      }
      "mod_13_void_ram" ->
        "am kill-all; cmd activity idle-maintenance; pm trim-caches 8589934592; device_config put activity_manager max_cached_processes 16"
      "mod_14_iphone_touch" ->
        buildIphoneTouchCommands(settings.iphoneTouchConfig.copy(enabled = true), settings.addedGamePackages)
          .joinToString("; ") { it.second }
      else -> "true"
    }
  }

  fun buildDisableCommand(moduleId: String, settings: BoosterSettings): String {
    val games = settings.addedGamePackages.toList()
    return when (moduleId) {
      "mod_01_fps_unlocker" ->
        "settings delete system min_refresh_rate; settings delete system peak_refresh_rate; settings delete system user_refresh_rate"
      "mod_02_fixed_perf" ->
        "cmd power set-fixed-performance-mode-enabled false"
      "mod_03_thermal_bypass" ->
        "cmd thermalservice reset"
      "mod_04_vulkan_hwui" ->
        "setprop debug.hwui.renderer ''; setprop debug.renderengine.backend ''"
      "mod_05_angle_driver" ->
        "settings delete global angle_gl_driver_all_angle; settings delete global angle_gl_driver_selection_pkgs; settings delete global angle_gl_driver_selection_values"
      "mod_06_game_mode" ->
        if (games.isEmpty()) "true" else games.joinToString("; ") { "cmd game mode standard $it" }
      "mod_07_zero_anim" ->
        "settings put global window_animation_scale 1.0; settings put global transition_animation_scale 1.0; settings put global animator_duration_scale 1.0"
      "mod_08_bg_purge",
      "mod_09_trim_cache" -> "pm trim-caches 0"
      "mod_10_touch_boost" ->
        "settings put system pointer_speed 0; settings put secure long_press_timeout 400"
      "mod_11_doze_bypass" ->
        "dumpsys deviceidle enable"
      "mod_12_dexopt_speed" -> "true"
      "mod_13_void_ram" ->
        "device_config delete activity_manager max_cached_processes"
      "mod_14_iphone_touch" ->
        buildRestoreTouchDefaultsCommand()
      else -> "true"
    }
  }

  fun buildIphoneTouchCommands(
    config: IphoneTouchConfig,
    addedGames: Set<String> = emptySet(),
  ): List<Pair<String, String>> {
    if (!config.enabled) {
      return listOf("Restaurar Touch Padrão" to buildRestoreTouchDefaultsCommand())
    }
    val pointer = config.pointerSpeed.coerceIn(1, 7)
    val longPress = config.longPressMs.coerceIn(120, 350)
    val tapTimeout = config.preset.tapTimeoutMs.coerceIn(40, 120)

    val list = mutableListOf<Pair<String, String>>()
    list +=
      "Curva Tátil ${config.preset.label}" to
        "settings put system pointer_speed $pointer; settings put secure long_press_timeout $longPress; settings put secure tap_timeout $tapTimeout"

    if (config.zeroBackpressure) {
      list +=
        "SurfaceFlinger Zero Input-Lag" to
          "setprop debug.sf.disable_backpressure 1; setprop debug.sf.latch_unsignaled 1; setprop debug.egl.force_msaa 0"
    } else {
      list +=
        "SurfaceFlinger Padrão" to
          "setprop debug.sf.disable_backpressure 0; setprop debug.sf.latch_unsignaled 0"
    }

    if (config.smoothFlingScroll) {
      list +=
        "Deslize Linear iOS (Fling/HWUI)" to
          "setprop debug.performance.tuning 1; setprop debug.hwui.use_buffer_age true"
    } else {
      list +=
        "Tuning Linear Padrão" to
          "setprop debug.performance.tuning 0"
    }

    if (config.multiTouchGameBoost && addedGames.isNotEmpty()) {
      list +=
        "Multi-Touch & Prioridade Jogos (${addedGames.size})" to
          addedGames.joinToString("; ") {
            "cmd game mode performance $it; am set-standby-bucket $it active; cmd appops set $it RUN_ANY_IN_BACKGROUND allow"
          }
    }

    return list
  }

  fun buildRestoreTouchDefaultsCommand(): String =
    "settings put system pointer_speed 0; settings put secure long_press_timeout 400; settings delete secure tap_timeout; setprop debug.sf.disable_backpressure 0; setprop debug.sf.latch_unsignaled 0; setprop debug.performance.tuning 0"

  fun buildGameTouchScriptCommand(sensitivity: Int): String {
    val pointer = sensitivity.coerceIn(1, 7)
    val longPress = (280 - pointer * 20).coerceIn(120, 260)
    val tapMs = (95 - pointer * 6).coerceIn(45, 90)
    return "settings put system pointer_speed $pointer; settings put secure long_press_timeout $longPress; settings put secure tap_timeout $tapMs; setprop debug.sf.disable_backpressure 1; setprop debug.sf.latch_unsignaled 1; setprop debug.egl.force_msaa 0"
  }

  fun buildRootTotalPerformanceCommands(
    enable: Boolean,
    isRootUid: Boolean,
    addedGames: Set<String> = emptySet(),
  ): List<Pair<String, String>> {
    val gov = if (enable) "performance" else "schedutil"
    val swappiness = if (enable) "10" else "60"
    val vfsCache = if (enable) "50" else "100"
    val powerEfficient = if (enable) "N" else "Y"
    val schedBoost = if (enable) "1" else "0"
    val ioStats = if (enable) "0" else "1"
    val thermalCmd = if (enable) "cmd thermalservice override-status 0" else "cmd thermalservice reset"
    val animScale = if (enable) "0.25" else "1.0"
    val pointerSpeed = if (enable) "4" else "0"
    val longPressMs = if (enable) "220" else "400"

    val rawSysfsScript =
      "applied=0; " +
        "for cpu in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do " +
        "if [ -w \"\$cpu\" ]; then echo $gov > \"\$cpu\" 2>/dev/null && applied=1; fi; done; " +
        "for gpu in /sys/class/kgsl/kgsl-3d0/devfreq/governor /sys/class/devfreq/*gpu*/governor; do " +
        "if [ -w \"\$gpu\" ]; then " +
        (if (enable) "echo performance > \"\$gpu\" 2>/dev/null && applied=1; "
        else "for g in msm-adreno-tz simple_ondemand schedutil; do echo \$g > \"\$gpu\" 2>/dev/null && break; done; ") +
        "fi; done; " +
        "[ -w /proc/sys/vm/swappiness ] && echo $swappiness > /proc/sys/vm/swappiness 2>/dev/null && applied=1; " +
        "[ -w /proc/sys/vm/vfs_cache_pressure ] && echo $vfsCache > /proc/sys/vm/vfs_cache_pressure 2>/dev/null; " +
        "[ -w /sys/module/workqueue/parameters/power_efficient ] && echo $powerEfficient > /sys/module/workqueue/parameters/power_efficient 2>/dev/null; " +
        "[ -w /proc/sys/kernel/sched_boost ] && echo $schedBoost > /proc/sys/kernel/sched_boost 2>/dev/null; " +
        "for q in /sys/block/*/queue/iostats; do [ -w \"\$q\" ] && echo $ioStats > \"\$q\" 2>/dev/null; done; " +
        "if [ \$applied -eq 1 ]; then echo ROOT_APLICADO; else echo SEM_ROOT_USANDO_ADB_MAX; fi"

    val kernelCmd =
      if (isRootUid) rawSysfsScript
      else "su -c '$rawSysfsScript' 2>/dev/null || echo 'SEM_ROOT_USANDO_ADB_MAX'"

    val sfLatencyCmd =
      if (enable) {
        "setprop debug.performance.tuning 1; setprop debug.sf.disable_backpressure 1; setprop debug.sf.latch_unsignaled 1; setprop debug.egl.force_msaa 0"
      } else {
        "setprop debug.performance.tuning 0; setprop debug.sf.disable_backpressure 0; setprop debug.sf.latch_unsignaled 0"
      }

    val powerCmd =
      if (enable) {
        "cmd power set-fixed-performance-mode-enabled true; cmd power set-adaptive-power-saver-enabled false; settings put global low_power 0"
      } else {
        "cmd power set-fixed-performance-mode-enabled false; cmd power set-adaptive-power-saver-enabled true"
      }

    val touchAnimCmd =
      "settings put global window_animation_scale $animScale; settings put global transition_animation_scale $animScale; settings put global animator_duration_scale $animScale; settings put system pointer_speed $pointerSpeed; settings put secure long_press_timeout $longPressMs"

    val memDozeCmd =
      if (enable) {
        "dumpsys deviceidle disable; am kill-all; cmd activity kill-all; pm trim-caches 4294967296; device_config put activity_manager max_cached_processes 16"
      } else {
        "dumpsys deviceidle enable; device_config delete activity_manager max_cached_processes"
      }

    val list = mutableListOf<Pair<String, String>>(
      "Kernel/Root ($gov)" to kernelCmd,
      "Fixed Perf + Energia (Android 11+)" to powerCmd,
      "SurfaceFlinger Zero-Atraso" to sfLatencyCmd,
      "Thermal Override" to thermalCmd,
      "Toque & Fluidez UI" to touchAnimCmd,
      "Anti-Doze + Limpeza RAM" to memDozeCmd,
    )

    if (addedGames.isNotEmpty()) {
      val gamesCmd =
        if (enable) {
          addedGames.joinToString("; ") {
            "cmd game mode performance $it; am set-standby-bucket $it active; cmd appops set $it RUN_ANY_IN_BACKGROUND allow"
          }
        } else {
          addedGames.joinToString("; ") { "cmd game mode standard $it" }
        }
      list += "Prioridade Jogos (${addedGames.size})" to gamesCmd
    }

    return list
  }

  fun buildRestoreAllCommands(addedGames: Set<String>): List<Pair<String, String>> {
    val list = mutableListOf<Pair<String, String>>()
    list += "Resolução Nativa" to "wm size reset; wm density reset"
    list += "Taxa de Tela Padrão" to "settings delete system min_refresh_rate; settings delete system peak_refresh_rate; settings delete system user_refresh_rate"
    list += "Power / Fixed Perf" to "cmd power set-fixed-performance-mode-enabled false; cmd power set-adaptive-power-saver-enabled true"
    list += "Limite Térmico Padrão" to "cmd thermalservice reset"
    list += "Animações 1.0x" to "settings put global window_animation_scale 1.0; settings put global transition_animation_scale 1.0; settings put global animator_duration_scale 1.0"
    list += "Renderização Padrão" to "setprop debug.hwui.renderer ''; setprop debug.renderengine.backend ''; setprop debug.performance.tuning 0; setprop debug.sf.disable_backpressure 0; setprop debug.sf.latch_unsignaled 0; settings delete global angle_gl_driver_all_angle; settings delete global angle_gl_driver_selection_pkgs; settings delete global angle_gl_driver_selection_values"
    list += "Toque Padrão" to "settings put system pointer_speed 0; settings put secure long_press_timeout 400"
    list += "Doze Padrão" to "dumpsys deviceidle enable"
    list += "VOID RAM Padrão" to "device_config delete activity_manager max_cached_processes"
    list += "Governador CPU/GPU Padrão" to "su -c 'for c in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do [ -w \"\$c\" ] && echo schedutil > \"\$c\"; done; [ -w /proc/sys/vm/swappiness ] && echo 60 > /proc/sys/vm/swappiness' 2>/dev/null || true"
    if (addedGames.isNotEmpty()) {
      list += "Game Mode Padrão" to addedGames.joinToString("; ") { "cmd game mode standard $it" }
    }
    return list
  }
}
