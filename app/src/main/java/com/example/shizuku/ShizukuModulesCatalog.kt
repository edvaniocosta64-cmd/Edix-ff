package com.example.shizuku

object ShizukuModulesCatalog {

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
    list += "Resolução & DPI Nativo" to "wm size reset; wm density reset"
    list += "Taxa de Tela Padrão" to
      "settings delete system min_refresh_rate; settings delete system peak_refresh_rate; settings delete system user_refresh_rate; settings delete secure user_refresh_rate; settings delete system miui_refresh_rate; settings delete system thermal_limit_refresh_rate"
    list += "Power / Fixed Perf" to "cmd power set-fixed-performance-mode-enabled false; cmd power set-adaptive-power-saver-enabled true"
    list += "Limite Térmico Padrão" to "cmd thermalservice reset"
    list += "Animações 1.0x" to "settings put global window_animation_scale 1.0; settings put global transition_animation_scale 1.0; settings put global animator_duration_scale 1.0"
    list += "Renderização Padrão" to "setprop debug.hwui.renderer ''; setprop debug.renderengine.backend ''; setprop debug.egl.force_msaa 0; settings delete global angle_gl_driver_all_angle; settings delete global angle_gl_driver_selection_pkgs; settings delete global angle_gl_driver_selection_values; settings delete global game_driver_opt_in_apps; settings delete global updatable_driver_production_opt_in_apps; settings delete global updatable_driver_production_opt_out_apps"
    list += "Toque & SurfaceFlinger Padrão" to buildRestoreTouchDefaultsCommand()
    list += "Doze Padrão" to "dumpsys deviceidle enable"
    list += "VOID RAM Padrão" to "device_config delete activity_manager max_cached_processes"
    list += "Governador CPU/GPU Padrão" to "su -c 'for c in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do [ -w \"\$c\" ] && echo schedutil > \"\$c\"; done; for gpu in /sys/class/kgsl/kgsl-3d0/devfreq/governor /sys/class/devfreq/*gpu*/governor; do [ -w \"\$gpu\" ] && for g in msm-adreno-tz simple_ondemand schedutil; do echo \$g > \"\$gpu\" 2>/dev/null && break; done; done; [ -w /proc/sys/vm/swappiness ] && echo 60 > /proc/sys/vm/swappiness; [ -w /proc/sys/vm/vfs_cache_pressure ] && echo 100 > /proc/sys/vm/vfs_cache_pressure; [ -w /sys/module/workqueue/parameters/power_efficient ] && echo Y > /sys/module/workqueue/parameters/power_efficient; [ -w /proc/sys/kernel/sched_boost ] && echo 0 > /proc/sys/kernel/sched_boost' 2>/dev/null || true"
    if (addedGames.isNotEmpty()) {
      list += "Game Mode & AppOps Padrão" to
        addedGames.joinToString("; ") {
          "cmd game mode standard $it; cmd appops set $it RUN_ANY_IN_BACKGROUND default"
        }
    }
    return list
  }
}
