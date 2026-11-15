package com.example

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.AppTab
import com.example.ui.BoosterViewModel
import com.example.ui.components.DomainExpansionOverlay
import com.example.ui.components.VoidBackground
import com.example.ui.screens.BoosterDashboardScreen
import com.example.ui.screens.CoolerPanelScreen
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.GamesLauncherScreen
import com.example.ui.screens.ModulesAndResolutionScreen
import com.example.ui.theme.ColdWhite
import com.example.ui.theme.HollowPurple
import com.example.ui.theme.HollowPurpleBright
import com.example.ui.theme.InfinitBoosterTheme
import com.example.ui.theme.LimitlessCyan
import com.example.ui.theme.MutedLavender
import com.example.ui.theme.VoidObsidian
import com.example.ui.theme.VoidSurface
import com.example.ui.theme.VoidSurfaceElevated
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    setContent {
      InfinitBoosterTheme {
        InfinitBoosterApp()
      }
    }
  }
}

@Composable
fun InfinitBoosterApp(viewModel: BoosterViewModel = viewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val activity = context as? Activity
  var currentTab by rememberSaveable { mutableStateOf(AppTab.BOOSTER) }
  val snackbarHostState = remember { SnackbarHostState() }

  // BackHandler returns from sub-tabs to main Booster Dashboard
  BackHandler(enabled = currentTab != AppTab.BOOSTER && !state.showDomainIntro) {
    currentTab = AppTab.BOOSTER
  }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
        Lifecycle.Event.ON_RESUME -> {
          viewModel.refreshShizuku()
          viewModel.refreshSpecialPermissions()
        }
        Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  // Real-time frame pacing & FPS monitor via Choreographer frame callback
  LaunchedEffect(Unit) {
    var lastFrameNanos = 0L
    var frameCount = 0
    var accumNanos = 0L
    while (true) {
      withFrameNanos { frameNanos ->
        if (lastFrameNanos != 0L) {
          val delta = (frameNanos - lastFrameNanos).coerceAtLeast(1L)
          accumNanos += delta
          frameCount++
          if (accumNanos >= 500_000_000L) {
            val avgNs = accumNanos.toFloat() / frameCount.toFloat()
            val fps = (1_000_000_000f / avgNs).roundToInt().coerceIn(1, 240)
            val avgMs = avgNs / 1_000_000f
            viewModel.updateLiveFrameStats(fps, avgMs)
            accumNanos = 0L
            frameCount = 0
          }
        }
        lastFrameNanos = frameNanos
      }
    }
  }

  // Apply preferred display refresh rate when settings change
  LaunchedEffect(
    state.settings.targetRefreshRate,
    state.settings.fpsUnlocked,
    state.settings.boostActive,
    state.telemetry.maxDisplayHz,
  ) {
    viewModel.applyWindowPerformance(activity, state.settings)
  }

  // Show status messages cleanly
  LaunchedEffect(state.statusBanner) {
    val msg = state.statusBanner
    if (msg != null) {
      snackbarHostState.showSnackbar(msg)
      viewModel.clearStatusBanner()
    }
  }

  Box(modifier = Modifier.fillMaxSize()) {
    VoidBackground(
      boostActive = state.settings.boostActive,
      targetHz = state.settings.targetRefreshRate.hz,
      wallpaperTheme = state.settings.wallpaperTheme,
      wallpaperRevision = state.settings.wallpaperRevision,
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val useNavigationRail = maxWidth >= 600.dp

      Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = {
          SnackbarHost(hostState = snackbarHostState) { data ->
            Snackbar(
              snackbarData = data,
              containerColor = VoidSurfaceElevated,
              contentColor = ColdWhite,
              actionColor = LimitlessCyan,
            )
          }
        },
        bottomBar = {
          if (!useNavigationRail) {
            InfinitBottomNavigationBar(
              currentTab = currentTab,
              onSelectTab = { currentTab = it },
            )
          }
        },
      ) { innerPadding ->
        Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
          if (useNavigationRail) {
            InfinitNavigationRail(
              currentTab = currentTab,
              onSelectTab = { currentTab = it },
            )
          }

          Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val baseDuration =
              when (state.settings.targetRefreshRate.hz) {
                120 -> 180
                90 -> 210
                else -> 250
              }
            val animDuration = (baseDuration * state.settings.animationScale).roundToInt().coerceIn(0, 350)
            AnimatedContent(
              targetState = currentTab,
              transitionSpec = {
                fadeIn(tween(animDuration)) togetherWith fadeOut(tween(animDuration / 2))
              },
              label = "tab_transition",
            ) { tab ->
              when (tab) {
                AppTab.BOOSTER ->
                  BoosterDashboardScreen(
                    state = state,
                    onToggleBoost = { viewModel.activateOrToggleVazioRoxo(activity) },
                    onReplayIntro = { viewModel.replayDomainIntro() },
                    onToggleVoice = { viewModel.toggleVoice(it) },
                    onToggleFpsUnlocked = { viewModel.setFpsUnlocked(it, activity) },
                    onTogglePerformanceMax = { viewModel.setPerformanceMax(it, activity) },
                    onToggleIgnoreThermal = { viewModel.setIgnoreThermalLimit(it, activity) },
                    onSelectRefreshRate = { viewModel.selectRefreshRate(it, activity) },
                    onSelectAnimationScale = { viewModel.selectAnimationScale(it) },
                    onSelectGraphicsBackend = { viewModel.selectGraphicsBackend(it) },
                    onActivateGraphicsBackend = { viewModel.activateSelectedGraphicsBackend() },
                    onVerifyGraphicsBackend = { viewModel.verifyGraphicsBackendStatus() },
                    onRestoreGraphicsBackend = { viewModel.restoreGraphicsBackendDefaults() },
                    onOpenDeveloperOptions = { viewModel.openDeveloperGraphicsSettings(context) },
                    onShizukuAction = { viewModel.connectOrApplyShizuku(context) },
                    onRefreshShizuku = { viewModel.refreshShizuku() },
                    onRequestWriteSettings = { viewModel.openWriteSettingsPermission(context) },
                    onRequestOverlayPermission = { viewModel.openOverlayPermission(context) },
                    onToggleFloatingHzOverlay = { enable ->
                      viewModel.toggleFloatingHzOverlay(enable, context)
                    },
                    onToggleFloatingFpsMeter = { enable ->
                      viewModel.toggleFloatingFpsMeter(enable, context)
                    },
                    onImportCustomDomainAudio = { uri ->
                      viewModel.importCustomDomainAudio(uri)
                    },
                    onClearCustomDomainAudio = {
                      viewModel.clearCustomDomainAudio()
                    },
                    onImportBackgroundMusic = { uri ->
                      viewModel.importBackgroundMusic(uri)
                    },
                    onToggleBackgroundMusic = { enable ->
                      viewModel.toggleBackgroundMusic(enable)
                    },
                    onChangeBackgroundMusicVolume = { volume ->
                      viewModel.setBackgroundMusicVolume(volume)
                    },
                    onClearBackgroundMusic = {
                      viewModel.clearBackgroundMusic()
                    },
                    onSelectWallpaperTheme = { theme ->
                      viewModel.selectWallpaperTheme(theme)
                    },
                    onImportCustomWallpaper = { uri ->
                      viewModel.importCustomWallpaper(uri)
                    },
                    onClearCustomWallpaper = {
                      viewModel.clearCustomWallpaper()
                    },
                  )
                AppTab.MODULES ->
                  ModulesAndResolutionScreen(
                    state = state,
                    onShizukuAction = { viewModel.connectOrApplyShizuku(context) },
                    onToggleModule = { id, enabled -> viewModel.toggleModule(id, enabled) },
                    onRestoreAllDefaults = { viewModel.restoreAllFactoryDefaults(activity) },
                    onApplyResolution = { w, h, dpi, lock, pct ->
                      viewModel.applyResolution(w, h, dpi, lock, pct)
                    },
                    onRestoreNativeResolution = { viewModel.restoreNativeResolution() },
                    onToggleBackgroundPanel = { enable ->
                      viewModel.toggleBackgroundResolutionPanel(enable, context)
                    },
                    onToggleFloatingResolutionOverlay = { enable ->
                      viewModel.toggleFloatingResolutionOverlay(enable, context)
                    },
                    onToggleRootTotalPerformance = { enable ->
                      viewModel.setRootTotalPerformance(enable, activity)
                    },
                    onUpdateIphoneTouchConfig = { cfg ->
                      viewModel.updateIphoneTouchConfig(cfg)
                    },
                    onApplyIphoneTouchNow = {
                      viewModel.applyIphoneTouchModuleNow()
                    },
                    onRestoreIphoneTouchDefaults = {
                      viewModel.restoreIphoneTouchDefaults()
                    },
                  )
                AppTab.GAMES ->
                  GamesLauncherScreen(
                    state = state,
                    loadIcon = { pkg -> viewModel.loadAppIcon(pkg) },
                    onAddGame = { pkg -> viewModel.addGamePackage(pkg) },
                    onRemoveGame = { pkg -> viewModel.removeGamePackage(pkg) },
                    onUpdatePerGameConfig = { cfg -> viewModel.updatePerGameConfig(cfg) },
                    onLaunchGame = { game ->
                      viewModel.launchGameWithBoost(game.packageName, game.label, activity)
                    },
                    onRefreshApps = { viewModel.refreshInstalledApps() },
                  )
                AppTab.COOLER ->
                  CoolerPanelScreen(
                    state = state,
                    onToggleCooler = { viewModel.toggleCooler() },
                    onSelectSpeed = { viewModel.selectCoolerSpeed(it) },
                    onStartCoolingCycle = { viewModel.startCoolingCycle() },
                  )
                AppTab.DIAGNOSTICS ->
                  DiagnosticsScreen(
                    state = state,
                    onRunScan = { viewModel.runDiagnosticScan() },
                    onDiagnosticAction = { action ->
                      viewModel.executeDiagnosticAction(
                        action = action,
                        context = context,
                        activity = activity,
                        onSwitchToCooler = { currentTab = AppTab.COOLER },
                      )
                    },
                  )
              }
            }
          }
        }
      }
    }

    AnimatedVisibility(
      visible = state.showDomainIntro,
      enter = fadeIn(tween(220)),
      exit = fadeOut(tween(420)) + slideOutVertically { -it / 6 },
    ) {
      DomainExpansionOverlay(
        voiceEnabled = state.settings.voiceEnabled,
        onStartIntro = { viewModel.onDomainIntroStart() },
        onTriggerBurst = { viewModel.onDomainBurstTriggered() },
        onDismiss = { viewModel.dismissDomainIntro() },
      )
    }
  }
}

@Composable
private fun InfinitBottomNavigationBar(
  currentTab: AppTab,
  onSelectTab: (AppTab) -> Unit,
) {
  NavigationBar(
    containerColor = VoidSurface.copy(alpha = 0.94f),
    contentColor = ColdWhite,
  ) {
    AppTab.entries.forEach { tab ->
      val selected = currentTab == tab
      val (labelRes, selectedIcon, unselectedIcon) = tabMetadata(tab)
      NavigationBarItem(
        selected = selected,
        onClick = { onSelectTab(tab) },
        modifier = Modifier.testTag("nav_${tab.route}"),
        icon = {
          Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = stringResource(labelRes),
          )
        },
        label = {
          Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
          )
        },
        colors =
          NavigationBarItemDefaults.colors(
            selectedIconColor = LimitlessCyan,
            selectedTextColor = ColdWhite,
            indicatorColor = HollowPurple.copy(alpha = 0.35f),
            unselectedIconColor = MutedLavender,
            unselectedTextColor = MutedLavender,
          ),
      )
    }
  }
}

@Composable
private fun InfinitNavigationRail(
  currentTab: AppTab,
  onSelectTab: (AppTab) -> Unit,
) {
  NavigationRail(
    containerColor = VoidObsidian.copy(alpha = 0.88f),
    contentColor = ColdWhite,
    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
  ) {
    AppTab.entries.forEach { tab ->
      val selected = currentTab == tab
      val (labelRes, selectedIcon, unselectedIcon) = tabMetadata(tab)
      NavigationRailItem(
        selected = selected,
        onClick = { onSelectTab(tab) },
        modifier = Modifier.testTag("rail_${tab.route}"),
        icon = {
          Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = stringResource(labelRes),
          )
        },
        label = {
          Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
          )
        },
        colors =
          NavigationRailItemDefaults.colors(
            selectedIconColor = LimitlessCyan,
            selectedTextColor = ColdWhite,
            indicatorColor = HollowPurple.copy(alpha = 0.35f),
            unselectedIconColor = MutedLavender,
            unselectedTextColor = MutedLavender,
          ),
      )
    }
  }
}

private fun tabMetadata(tab: AppTab) =
  when (tab) {
    AppTab.BOOSTER -> Triple(R.string.nav_booster, Icons.Rounded.Bolt, Icons.Outlined.Bolt)
    AppTab.MODULES -> Triple(R.string.nav_modules, Icons.Rounded.Extension, Icons.Outlined.Extension)
    AppTab.GAMES -> Triple(R.string.nav_games, Icons.Rounded.SportsEsports, Icons.Outlined.SportsEsports)
    AppTab.COOLER -> Triple(R.string.nav_cooler, Icons.Rounded.AcUnit, Icons.Outlined.AcUnit)
    AppTab.DIAGNOSTICS -> Triple(R.string.nav_diagnostics, Icons.Rounded.Analytics, Icons.Outlined.Analytics)
  }
