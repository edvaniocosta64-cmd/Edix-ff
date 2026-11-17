package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AnimationScaleOption
import com.example.model.GraphicsBackend
import com.example.model.GuardianOutcome
import com.example.model.GuardianPipelineStage
import com.example.model.GuardianSeverity
import com.example.model.GuardianState
import com.example.model.RefreshRateOption
import com.example.model.WallpaperTheme
import com.example.shizuku.ShizukuConnectionState
import com.example.shizuku.ShizukuStatus
import com.example.ui.BoosterUiState
import com.example.ui.components.SectionHeader
import com.example.ui.components.TelemetryPill
import com.example.ui.components.VoidGlassCard
import com.example.ui.theme.ColdWhite
import com.example.ui.theme.HollowPurple
import com.example.ui.theme.HollowPurpleBright
import com.example.ui.theme.LimitlessCyan
import com.example.ui.theme.MutedLavender
import com.example.ui.theme.NeonSuccess
import com.example.ui.theme.ReversalRed
import com.example.ui.theme.VoidBorder
import com.example.ui.theme.VoidObsidian
import com.example.ui.theme.VoidSurfaceElevated
import com.example.ui.theme.WarningAmber

@Composable
fun BoosterDashboardScreen(
  state: BoosterUiState,
  onToggleBoost: () -> Unit,
  onReplayIntro: () -> Unit,
  onToggleVoice: (Boolean) -> Unit,
  onToggleFpsUnlocked: (Boolean) -> Unit,
  onTogglePerformanceMax: (Boolean) -> Unit,
  onToggleIgnoreThermal: (Boolean) -> Unit,
  onSelectRefreshRate: (RefreshRateOption) -> Unit,
  onSelectAnimationScale: (Float) -> Unit,
  onSelectGraphicsBackend: (GraphicsBackend) -> Unit,
  onActivateGraphicsBackend: () -> Unit,
  onVerifyGraphicsBackend: () -> Unit,
  onRestoreGraphicsBackend: () -> Unit,
  onOpenDeveloperOptions: () -> Unit,
  onShizukuAction: () -> Unit,
  onRefreshShizuku: () -> Unit,
  onRequestWriteSettings: () -> Unit,
  onRequestOverlayPermission: () -> Unit,
  onToggleFloatingHzOverlay: (Boolean) -> Unit,
  onToggleFloatingFpsMeter: (Boolean) -> Unit,
  onImportCustomDomainAudio: (Uri) -> Unit,
  onClearCustomDomainAudio: () -> Unit,
  onImportBackgroundMusic: (Uri) -> Unit,
  onToggleBackgroundMusic: (Boolean) -> Unit,
  onChangeBackgroundMusicVolume: (Float) -> Unit,
  onClearBackgroundMusic: () -> Unit,
  onSelectWallpaperTheme: (WallpaperTheme) -> Unit,
  onImportCustomWallpaper: (Uri) -> Unit,
  onClearCustomWallpaper: () -> Unit,
  onToggleGuardianAutoRepair: (Boolean) -> Unit,
  onRunGuardianHeal: () -> Unit,
  onDismissGuardianAlert: (String) -> Unit,
  onRestoreEverything: () -> Unit,
) {
  val settings = state.settings
  val telemetry = state.telemetry
  var showRestoreEverythingDialog by remember { mutableStateOf(false) }
  val audioPickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        onImportCustomDomainAudio(uri)
      }
    }
  val bgMusicPickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        onImportBackgroundMusic(uri)
      }
    }
  val wallpaperPickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri != null) {
        onImportCustomWallpaper(uri)
      }
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // Header Bar
    item {
      Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.weight(1f),
        ) {
          Surface(
            shape = CircleShape,
            color = VoidObsidian,
            border = BorderStroke(1.5.dp, HollowPurpleBright),
            modifier = Modifier.size(52.dp).testTag("app_profile_avatar"),
          ) {
            Image(
              painter = painterResource(id = R.drawable.img_launcher_core),
              contentDescription = "Foto de perfil do Infinit Booster",
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
          }

          Column {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = "INFINIT BOOSTER",
                style = MaterialTheme.typography.headlineMedium,
                color = ColdWhite,
              )
              Surface(
                shape = RoundedCornerShape(50),
                color = HollowPurple.copy(alpha = 0.25f),
                border = BorderStroke(1.dp, HollowPurpleBright.copy(alpha = 0.65f)),
              ) {
                Text(
                  text = stringResource(R.string.version_badge),
                  style = MaterialTheme.typography.labelSmall,
                  color = HollowPurpleBright,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
              }
            }
            Text(
              text = "Criador: edix ff",
              style = MaterialTheme.typography.labelMedium,
              color = ColdWhite,
            )
            Text(
              text = "App em teste — não está na versão completa",
              style = MaterialTheme.typography.labelSmall,
              color = WarningAmber,
            )
          }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          IconButton(
            onClick = onReplayIntro,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("replay_domain_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.AutoAwesome,
              contentDescription = "Repetir Expansão de Domínio",
              tint = HollowPurpleBright,
            )
          }
          IconButton(
            onClick = { onToggleVoice(!settings.voiceEnabled) },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("toggle_voice_button"),
          ) {
            Icon(
              imageVector =
                if (settings.voiceEnabled) Icons.AutoMirrored.Rounded.VolumeUp
                else Icons.AutoMirrored.Rounded.VolumeOff,
              contentDescription = "Alternar Voz Japonesa",
              tint = if (settings.voiceEnabled) LimitlessCyan else MutedLavender,
            )
          }
        }
      }
    }

    // Hollow Purple Reactor Hero
    item {
      val effectiveTargetHz =
        if (settings.boostActive) maxOf(telemetry.maxDisplayHz, settings.targetRefreshRate.hz, 60)
        else settings.targetRefreshRate.hz
      HollowPurpleReactorCard(
        boostActive = settings.boostActive,
        liveFps = telemetry.liveFps,
        targetHz = effectiveTargetHz,
        currentDisplayHz = telemetry.currentDisplayHz,
        maxHardwareHz = telemetry.maxDisplayHz,
        backendBadge = settings.graphicsBackend.title,
        tempC = telemetry.batteryTempC,
        ramPercent = telemetry.ramPercent,
        onToggleBoost = onToggleBoost,
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
      )
    }

    // Telemetry Quick Strip
    item {
      Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        TelemetryPill(
          label = "FPS AO VIVO",
          value = "${telemetry.liveFps} FPS",
          accentColor = LimitlessCyan,
          modifier = Modifier.weight(1f),
        )
        TelemetryPill(
          label = "TEMP BATERIA",
          value = "${String.format("%.1f", telemetry.batteryTempC)} °C",
          accentColor = if (telemetry.batteryTempC >= 39f) ReversalRed else NeonSuccess,
          modifier = Modifier.weight(1f),
        )
        TelemetryPill(
          label = "RAM USADA",
          value = "${telemetry.ramPercent}%",
          accentColor = if (telemetry.ramPercent >= 80) WarningAmber else HollowPurpleBright,
          modifier = Modifier.weight(1f),
        )
      }
    }

    // IA Guardião (Silent, Zero-API Self-Healing Anti-Bug & Warnings)
    item {
      GuardianAiCard(
        guardian = state.guardianState,
        onToggleAutoRepair = onToggleGuardianAutoRepair,
        onRunManualHeal = onRunGuardianHeal,
        onDismissAlert = onDismissGuardianAlert,
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
      )
    }

    // Shizuku Privileged Engine Card
    item {
      ShizukuEngineCard(
        status = state.shizukuStatus,
        logs = state.shizukuLogs,
        onAction = onShizukuAction,
        onRefresh = onRefreshShizuku,
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
      )
    }

    // Device Permissions (Alterar Sistema & Sobrepor a Apps)
    item {
      SystemPermissionsCard(
        canWriteSettings = state.canWriteSystemSettings,
        canDrawOverlays = state.canDrawOverlays,
        onRequestWriteSettings = onRequestWriteSettings,
        onRequestOverlayPermission = onRequestOverlayPermission,
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
      )
    }

    // Domain Expansion Voice / Custom MP3 Importer Card
    item {
      DomainAudioCard(
        voiceEnabled = settings.voiceEnabled,
        customAudioName = settings.customDomainAudioName,
        onPickAudio = { audioPickerLauncher.launch(arrayOf("audio/*")) },
        onTestDomainAudio = onReplayIntro,
        onClearCustomAudio = onClearCustomDomainAudio,
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
      )
    }

    // Background Music & Wallpaper Theme Personalization Card
    item {
      PersonalizationMediaCard(
        bgMusicEnabled = settings.bgMusicEnabled,
        bgMusicName = settings.bgMusicName,
        bgMusicVolume = settings.bgMusicVolume,
        wallpaperTheme = settings.wallpaperTheme,
        customWallpaperName = settings.customWallpaperName,
        onPickBgMusic = { bgMusicPickerLauncher.launch(arrayOf("audio/*")) },
        onToggleBgMusic = onToggleBackgroundMusic,
        onVolumeChange = onChangeBackgroundMusicVolume,
        onClearBgMusic = onClearBackgroundMusic,
        onSelectTheme = onSelectWallpaperTheme,
        onPickWallpaper = {
          wallpaperPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
          )
        },
        onClearWallpaper = onClearCustomWallpaper,
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
      )
    }

    // FPS, Performance & Thermal Override Controls
    item {
      VoidGlassCard(
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        borderColor = if (settings.boostActive) HollowPurpleBright.copy(alpha = 0.6f) else VoidBorder,
      ) {
        SectionHeader(
          title = "Limites & Desempenho",
          subtitle = "Desbloqueie fluidez e prioridade máxima do sistema",
        )
        Spacer(modifier = Modifier.height(12.dp))

        BoosterSwitchRow(
          title = "Tirar Limite de FPS",
          subtitle = "Solicita taxa de quadros máxima sem trava artificial",
          checked = settings.fpsUnlocked,
          accent = LimitlessCyan,
          testTag = "switch_unlock_fps",
          onCheckedChange = onToggleFpsUnlocked,
        )
        Spacer(modifier = Modifier.height(10.dp))

        BoosterSwitchRow(
          title = "Desempenho Máximo",
          subtitle = "Prioridade alta de execução e limpeza rápida de memória",
          checked = settings.performanceMax,
          accent = HollowPurpleBright,
          testTag = "switch_max_perf",
          onCheckedChange = onTogglePerformanceMax,
        )
        Spacer(modifier = Modifier.height(10.dp))

        BoosterSwitchRow(
          title = "Tirar Limite de Aquecimento",
          subtitle = "Roda no perfil máximo mesmo com temperatura elevada (${String.format("%.1f", telemetry.batteryTempC)}°C)",
          checked = settings.ignoreThermalLimit,
          accent = ReversalRed,
          testTag = "switch_ignore_thermal",
          onCheckedChange = onToggleIgnoreThermal,
        )
      }
    }

    // 60 + 90 + 120 Hz Refresh Rate Smoothness
    item {
      VoidGlassCard(modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          SectionHeader(
            title = "Fluidez & Animações",
            subtitle = "Tela atual: ${telemetry.currentDisplayHz}Hz (Máx hardware: ${telemetry.maxDisplayHz}Hz)",
          )
          Icon(
            imageVector = Icons.Rounded.Speed,
            contentDescription = null,
            tint = LimitlessCyan,
          )
        }
        Spacer(modifier = Modifier.height(12.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          val visibleHzOptions =
            RefreshRateOption.entries.filter {
              it.hz <= 120 ||
                telemetry.maxDisplayHz >= 144 ||
                settings.targetRefreshRate == RefreshRateOption.HZ_144
            }
          visibleHzOptions.forEach { option ->
            val selected = settings.targetRefreshRate == option
            val bgColor by
              animateColorAsState(
                targetValue =
                  if (selected) HollowPurple.copy(alpha = 0.35f) else VoidSurfaceElevated,
                label = "hz_bg",
              )
            Surface(
              modifier =
                Modifier.weight(1f)
                  .minimumInteractiveComponentSize()
                  .clip(RoundedCornerShape(14.dp))
                  .clickable { onSelectRefreshRate(option) }
                  .testTag("hz_option_${option.hz}"),
              shape = RoundedCornerShape(14.dp),
              color = bgColor,
              border =
                BorderStroke(
                  width = if (selected) 1.5.dp else 1.dp,
                  color = if (selected) LimitlessCyan else VoidBorder,
                ),
            ) {
              Column(
                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                Text(
                  text = option.label,
                  style = MaterialTheme.typography.titleLarge,
                  color = if (selected) ColdWhite else MutedLavender,
                )
                Text(
                  text = "${option.frameBudgetMs} ms",
                  style = MaterialTheme.typography.labelSmall,
                  color = if (selected) LimitlessCyan else MutedLavender,
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text(
          text = "Escala de Animação do Sistema (${String.format(java.util.Locale.US, "%.2f", settings.animationScale)}x)",
          style = MaterialTheme.typography.titleSmall,
          color = ColdWhite,
        )
        Text(
          text = "Controla velocidade de janelas, transições e efeitos do celular e do app",
          style = MaterialTheme.typography.bodySmall,
          color = MutedLavender,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          AnimationScaleOption.entries.forEach { animOpt ->
            val selected = kotlin.math.abs(settings.animationScale - animOpt.scale) < 0.05f
            Surface(
              modifier =
                Modifier.weight(1f)
                  .minimumInteractiveComponentSize()
                  .clip(RoundedCornerShape(10.dp))
                  .clickable { onSelectAnimationScale(animOpt.scale) }
                  .testTag("anim_scale_${animOpt.label}"),
              shape = RoundedCornerShape(10.dp),
              color = if (selected) HollowPurple.copy(alpha = 0.42f) else VoidSurfaceElevated,
              border = BorderStroke(1.dp, if (selected) LimitlessCyan else VoidBorder),
            ) {
              Column(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                Text(
                  text = animOpt.label,
                  style = MaterialTheme.typography.labelLarge,
                  color = if (selected) ColdWhite else MutedLavender,
                )
                Text(
                  text = animOpt.subtitle,
                  style = MaterialTheme.typography.labelSmall,
                  color = if (selected) LimitlessCyan else MutedLavender.copy(alpha = 0.75f),
                  textAlign = TextAlign.Center,
                  maxLines = 1,
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
          onClick = { onToggleFloatingHzOverlay(!settings.floatingHzOverlayEnabled) },
          modifier =
            Modifier.fillMaxWidth()
              .height(50.dp)
              .testTag("toggle_red_floating_hz"),
          shape = RoundedCornerShape(14.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor =
                if (settings.floatingHzOverlayEnabled) ReversalRed
                else Color(0xFF3B0917),
              contentColor = ColdWhite,
            ),
          border = BorderStroke(1.5.dp, ReversalRed),
        ) {
          Icon(
            imageVector = Icons.Rounded.Layers,
            contentDescription = null,
            tint = ColdWhite,
          )
          Spacer(modifier = Modifier.size(8.dp))
          Text(
            text =
              if (settings.floatingHzOverlayEnabled)
                "Painel Game Turbo (Hz • Render • Animação): ATIVO"
              else
                "Abrir Painel Game Turbo (Hz • Render • 0,0/0,25/0,5)",
            style = MaterialTheme.typography.labelLarge,
            color = ColdWhite,
          )
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
          onClick = { onToggleFloatingFpsMeter(!settings.floatingFpsMeterEnabled) },
          modifier =
            Modifier.fillMaxWidth()
              .height(48.dp)
              .testTag("toggle_floating_fps_meter"),
          shape = RoundedCornerShape(14.dp),
          border =
            BorderStroke(
              1.dp,
              if (settings.floatingFpsMeterEnabled) NeonSuccess else LimitlessCyan.copy(alpha = 0.65f),
            ),
        ) {
          Icon(
            imageVector = Icons.Rounded.Speed,
            contentDescription = null,
            tint = if (settings.floatingFpsMeterEnabled) NeonSuccess else LimitlessCyan,
          )
          Spacer(modifier = Modifier.size(8.dp))
          Text(
            text =
              if (settings.floatingFpsMeterEnabled)
                "Medidor de FPS Flutuante (Android 11+): ATIVO"
              else
                "Medidor de FPS em Tempo Real (Android 11+)",
            style = MaterialTheme.typography.labelLarge,
            color = if (settings.floatingFpsMeterEnabled) NeonSuccess else ColdWhite,
          )
        }
      }
    }

    // ANGLE / Vulkan / SkiaGL Graphics Engine Selector
    item {
      VoidGlassCard(modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp)) {
        SectionHeader(
          title = "Motor Gráfico no Máximo",
          subtitle = "Hardware: ${telemetry.vulkanVersionLabel}",
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          GraphicsBackend.entries.forEach { backend ->
            val selected = settings.graphicsBackend == backend
            Surface(
              modifier =
                Modifier.fillMaxWidth()
                  .clip(RoundedCornerShape(14.dp))
                  .clickable { onSelectGraphicsBackend(backend) }
                  .testTag("backend_${backend.id}"),
              shape = RoundedCornerShape(14.dp),
              color =
                if (selected) HollowPurple.copy(alpha = 0.24f) else VoidSurfaceElevated.copy(alpha = 0.65f),
              border =
                BorderStroke(
                  width = if (selected) 1.5.dp else 1.dp,
                  color = if (selected) HollowPurpleBright else VoidBorder,
                ),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                RadioButton(
                  selected = selected,
                  onClick = { onSelectGraphicsBackend(backend) },
                  colors =
                    RadioButtonDefaults.colors(
                      selectedColor = LimitlessCyan,
                      unselectedColor = MutedLavender,
                    ),
                )
                Column(modifier = Modifier.weight(1f)) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Text(
                      text = backend.title,
                      style = MaterialTheme.typography.titleMedium,
                      color = ColdWhite,
                    )
                    Surface(
                      shape = RoundedCornerShape(6.dp),
                      color = LimitlessCyan.copy(alpha = 0.14f),
                    ) {
                      Text(
                        text = backend.badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = LimitlessCyan,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                      )
                    }
                  }
                  Spacer(modifier = Modifier.height(2.dp))
                  Text(
                    text = backend.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedLavender,
                  )
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Live Graphics Renderer Verification Readout
        val gStatus = state.graphicsStatus
        val matchesSelection =
          gStatus.verifiedViaShizuku && gStatus.activeBackendDetected == settings.graphicsBackend
        val statusBorderColor =
          when {
            matchesSelection -> NeonSuccess.copy(alpha = 0.75f)
            gStatus.verifiedViaShizuku -> LimitlessCyan.copy(alpha = 0.65f)
            else -> VoidBorder
          }
        Surface(
          modifier = Modifier.fillMaxWidth().testTag("graphics_status_panel"),
          shape = RoundedCornerShape(14.dp),
          color = VoidObsidian.copy(alpha = 0.8f),
          border = BorderStroke(1.dp, statusBorderColor),
        ) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Verified,
                  contentDescription = null,
                  tint = if (gStatus.verifiedViaShizuku) NeonSuccess else MutedLavender,
                  modifier = Modifier.size(18.dp),
                )
                Text(
                  text =
                    when {
                      matchesSelection ->
                        "ATIVO NO SISTEMA: ${gStatus.activeBackendDetected?.title}"
                      gStatus.verifiedViaShizuku && gStatus.activeBackendDetected != null ->
                        "DETECTADO: ${gStatus.activeBackendDetected.title}"
                      gStatus.verifiedViaShizuku -> "SISTEMA EM PADRÃO DE FÁBRICA"
                      else -> "STATUS NÃO VERIFICADO"
                    },
                  style = MaterialTheme.typography.labelLarge,
                  color =
                    when {
                      matchesSelection -> NeonSuccess
                      gStatus.verifiedViaShizuku -> LimitlessCyan
                      else -> MutedLavender
                    },
                )
              }
            }
            Text(
              text =
                "HWUI: ${gStatus.hwuiRenderer}  •  Engine: ${gStatus.renderEngineBackend}  •  ANGLE: ${gStatus.anglePackagesSummary}",
              style = MaterialTheme.typography.labelSmall,
              color = ColdWhite,
            )
            if (!gStatus.verifiedViaShizuku) {
              Text(
                text = gStatus.lastVerifiedLabel,
                style = MaterialTheme.typography.labelSmall,
                color = WarningAmber,
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Activate Selected Renderer Button
        Button(
          onClick = onActivateGraphicsBackend,
          modifier =
            Modifier.fillMaxWidth()
              .height(48.dp)
              .testTag("activate_graphics_backend_button"),
          shape = RoundedCornerShape(14.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = HollowPurple,
              contentColor = ColdWhite,
            ),
        ) {
          Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = ColdWhite,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.size(6.dp))
          Text(
            text = "Ativar ${settings.graphicsBackend.title} Agora",
            style = MaterialTheme.typography.labelLarge,
            color = ColdWhite,
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Verify & Restore Buttons Row
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(
            onClick = onVerifyGraphicsBackend,
            modifier =
              Modifier.weight(1f)
                .minimumInteractiveComponentSize()
                .testTag("verify_graphics_backend_button"),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, LimitlessCyan.copy(alpha = 0.7f)),
          ) {
            Icon(
              imageVector = Icons.Rounded.Verified,
              contentDescription = null,
              tint = LimitlessCyan,
              modifier = Modifier.size(17.dp),
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(
              text = "Verificar Ativo",
              style = MaterialTheme.typography.labelLarge,
              color = ColdWhite,
            )
          }

          OutlinedButton(
            onClick = onRestoreGraphicsBackend,
            modifier =
              Modifier.weight(1f)
                .minimumInteractiveComponentSize()
                .testTag("restore_graphics_backend_button"),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, ReversalRed.copy(alpha = 0.7f)),
          ) {
            Icon(
              imageVector = Icons.Rounded.RestartAlt,
              contentDescription = null,
              tint = ReversalRed,
              modifier = Modifier.size(17.dp),
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(
              text = "Restaurar",
              style = MaterialTheme.typography.labelLarge,
              color = ColdWhite,
            )
          }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
          onClick = onOpenDeveloperOptions,
          modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("open_dev_options_button"),
          border = BorderStroke(1.dp, HollowPurpleBright.copy(alpha = 0.55f)),
          shape = RoundedCornerShape(14.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.DeveloperMode,
            contentDescription = null,
            tint = HollowPurpleBright,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.size(8.dp))
          Text(
            text = "Forçar Driver no Sistema (Opções Desenvolvedor)",
            style = MaterialTheme.typography.labelLarge,
            color = ColdWhite,
          )
        }
      }
    }

    // Unified Full Reset Card ("Restaurar Tudo Mesmo")
    item {
      VoidGlassCard(
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        borderColor = ReversalRed.copy(alpha = 0.65f),
      ) {
        SectionHeader(
          title = "Restauração Completa do App",
          subtitle = "Remove tudo que foi alterado no celular, nos jogos e no Infinit Booster",
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text =
            "Reverte de uma só vez: todos os módulos AxManager, DPI (400–1440) e Touch Script dos jogos, painéis flutuantes, resolução, Hz, escala de animação (1.0x), modo Root, renderização e mídias personalizadas.",
          style = MaterialTheme.typography.bodyMedium,
          color = MutedLavender,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
          onClick = { showRestoreEverythingDialog = true },
          modifier =
            Modifier.fillMaxWidth()
              .height(50.dp)
              .testTag("restore_everything_button"),
          shape = RoundedCornerShape(14.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = ReversalRed.copy(alpha = 0.22f),
              contentColor = ColdWhite,
            ),
          border = BorderStroke(1.5.dp, ReversalRed),
        ) {
          Icon(
            imageVector = Icons.Rounded.RestartAlt,
            contentDescription = null,
            tint = ReversalRed,
          )
          Spacer(modifier = Modifier.size(8.dp))
          Text(
            text = "Restaurar Tudo Mesmo",
            style = MaterialTheme.typography.titleMedium,
            color = ColdWhite,
          )
        }
      }
    }

    item { Spacer(modifier = Modifier.height(12.dp)) }
  }

  if (showRestoreEverythingDialog) {
    AlertDialog(
      onDismissRequest = { showRestoreEverythingDialog = false },
      containerColor = VoidSurfaceElevated,
      titleContentColor = ColdWhite,
      textContentColor = MutedLavender,
      modifier = Modifier.testTag("restore_everything_dialog"),
      icon = {
        Icon(
          imageVector = Icons.Rounded.RestartAlt,
          contentDescription = null,
          tint = ReversalRed,
          modifier = Modifier.size(32.dp),
        )
      },
      title = {
        Text(
          text = "Restaurar Tudo Mesmo?",
          style = MaterialTheme.typography.titleLarge,
        )
      },
      text = {
        Text(
          text =
            "Isso vai desfazer absolutamente tudo que foi alterado: módulos ativos, DPI e script touch dos jogos, resolução, taxa de Hz, animações, modo Root, painéis flutuantes, renderização, papel de parede e músicas importadas. Deseja continuar?",
          style = MaterialTheme.typography.bodyMedium,
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showRestoreEverythingDialog = false
            onRestoreEverything()
          },
          colors = ButtonDefaults.buttonColors(containerColor = ReversalRed),
          modifier = Modifier.testTag("confirm_restore_everything_button"),
        ) {
          Text("Sim, Restaurar Tudo", color = ColdWhite)
        }
      },
      dismissButton = {
        TextButton(onClick = { showRestoreEverythingDialog = false }) {
          Text("Cancelar", color = LimitlessCyan)
        }
      },
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuardianAiCard(
  guardian: GuardianState,
  onToggleAutoRepair: (Boolean) -> Unit,
  onRunManualHeal: () -> Unit,
  onDismissAlert: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val healthColor =
    when {
      guardian.healthScore >= 85 -> NeonSuccess
      guardian.healthScore >= 60 -> WarningAmber
      else -> ReversalRed
    }

  VoidGlassCard(
    modifier = modifier,
    borderColor = healthColor.copy(alpha = 0.65f),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.weight(1f),
      ) {
        Icon(
          imageVector = Icons.Rounded.HealthAndSafety,
          contentDescription = null,
          tint = healthColor,
        )
        Column {
          Text(
            text = "IA Guardião • 24h via Shizuku",
            style = MaterialTheme.typography.titleMedium,
            color = ColdWhite,
          )
          Text(
            text = "IA Guardião corrigindo bugs (não desativa suas otimizações)",
            style = MaterialTheme.typography.labelSmall,
            color = LimitlessCyan,
          )
        }
      }

      Surface(
        shape = RoundedCornerShape(8.dp),
        color = healthColor.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, healthColor.copy(alpha = 0.7f)),
      ) {
        Text(
          text = "${guardian.healthScore}% SAÚDE",
          style = MaterialTheme.typography.labelSmall,
          color = healthColor,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = guardian.statusSummary,
      style = MaterialTheme.typography.bodyMedium,
      color = ColdWhite,
    )
    Text(
      text = "${guardian.lastCheckLabel} • ${guardian.totalRepairsCount} correção(ões)",
      style = MaterialTheme.typography.labelSmall,
      color = MutedLavender,
    )

    // 4-Stage Diagnostic Pipeline Strip
    Spacer(modifier = Modifier.height(10.dp))
    val stages =
      listOf(
        GuardianPipelineStage.DETECT,
        GuardianPipelineStage.ANALYZE,
        GuardianPipelineStage.FIX,
        GuardianPipelineStage.VERIFY,
      )
    Row(
      modifier = Modifier.fillMaxWidth().testTag("guardian_pipeline_strip"),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      stages.forEach { step ->
        val isActive = guardian.currentStage == step
        val isDone =
          guardian.currentStage != GuardianPipelineStage.IDLE &&
            guardian.currentStage.stepIndex > step.stepIndex
        val stepColor =
          when {
            isActive -> LimitlessCyan
            isDone -> NeonSuccess
            else -> MutedLavender.copy(alpha = 0.7f)
          }
        Surface(
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(8.dp),
          color = if (isActive || isDone) stepColor.copy(alpha = 0.16f) else VoidObsidian.copy(alpha = 0.65f),
          border = BorderStroke(1.dp, if (isActive) LimitlessCyan else stepColor.copy(alpha = 0.38f)),
        ) {
          Text(
            text = "${step.stepIndex}. ${step.label}",
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive || isDone) ColdWhite else MutedLavender,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
          )
        }
      }
    }

    // Root Cause & Memory Strip
    Spacer(modifier = Modifier.height(8.dp))
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Surface(
        shape = RoundedCornerShape(8.dp),
        color = VoidObsidian.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, HollowPurpleBright.copy(alpha = 0.45f)),
      ) {
        Text(
          text = "Causa recente: ${guardian.lastCauseDetected.label}",
          style = MaterialTheme.typography.labelSmall,
          color = HollowPurpleBright,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }
      Surface(
        shape = RoundedCornerShape(8.dp),
        color = VoidObsidian.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, LimitlessCyan.copy(alpha = 0.45f)),
      ) {
        Text(
          text = "Memória: ${guardian.avoidedRepeatsCount} falha(s) evitada(s)",
          style = MaterialTheme.typography.labelSmall,
          color = LimitlessCyan,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }
    }

    Spacer(modifier = Modifier.height(10.dp))
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(VoidSurfaceElevated.copy(alpha = 0.65f))
          .clickable { onToggleAutoRepair(!guardian.autoRepairEnabled) }
          .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Monitor 24h Automático (Só com Shizuku)",
          style = MaterialTheme.typography.titleSmall,
          color = ColdWhite,
        )
        Text(
          text = "Investiga em 4 etapas e corrige travamentos ao vivo sem desligar seus ajustes",
          style = MaterialTheme.typography.bodySmall,
          color = MutedLavender,
        )
      }
      Switch(
        checked = guardian.autoRepairEnabled,
        onCheckedChange = onToggleAutoRepair,
        modifier = Modifier.testTag("switch_guardian_autorepair"),
        colors =
          SwitchDefaults.colors(
            checkedThumbColor = VoidObsidian,
            checkedTrackColor = NeonSuccess,
          ),
      )
    }

    Spacer(modifier = Modifier.height(10.dp))
    Button(
      onClick = onRunManualHeal,
      enabled = !guardian.isAnalyzing,
      modifier =
        Modifier.fillMaxWidth()
          .height(48.dp)
          .testTag("guardian_manual_heal_button"),
      shape = RoundedCornerShape(14.dp),
      colors =
        ButtonDefaults.buttonColors(
          containerColor = HollowPurple,
          contentColor = ColdWhite,
        ),
    ) {
      if (guardian.isAnalyzing) {
        CircularProgressIndicator(
          modifier = Modifier.size(18.dp),
          color = ColdWhite,
          strokeWidth = 2.dp,
        )
      } else {
        Icon(
          imageVector = Icons.Rounded.AutoFixHigh,
          contentDescription = null,
          tint = LimitlessCyan,
          modifier = Modifier.size(18.dp),
        )
      }
      Spacer(modifier = Modifier.size(8.dp))
      Text(
        text =
          if (guardian.isAnalyzing) "Pipeline: ${guardian.currentStage.label}..."
          else "Investigar & Corrigir (4 Etapas)",
        style = MaterialTheme.typography.labelLarge,
        color = ColdWhite,
      )
    }

    // Per-Game Stability Strip
    if (guardian.gameHealthMap.isNotEmpty()) {
      Spacer(modifier = Modifier.height(10.dp))
      Text(
        text = "Monitoramento por Jogo",
        style = MaterialTheme.typography.labelMedium,
        color = ColdWhite,
      )
      Spacer(modifier = Modifier.height(6.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        guardian.gameHealthMap.values
          .sortedByDescending { it.lagEventsCount }
          .take(4)
          .forEach { gh ->
            val gameAccent =
              if (gh.failedFixesCount > gh.resolvedFixesCount) WarningAmber else NeonSuccess
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = gameAccent.copy(alpha = 0.12f),
              border = BorderStroke(1.dp, gameAccent.copy(alpha = 0.45f)),
            ) {
              val minFpsPart = gh.lowestFpsSeen?.let { " • mín ${it}FPS" }.orEmpty()
              val causePart = gh.lastCause?.label?.let { " • $it" }.orEmpty()
              Text(
                text = "${gh.gameLabel}: ${gh.lagEventsCount} lag(s)$minFpsPart$causePart",
                style = MaterialTheme.typography.labelSmall,
                color = gameAccent,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              )
            }
          }
      }
    }

    // Relatórios Inteligentes
    if (guardian.reports.isNotEmpty()) {
      Spacer(modifier = Modifier.height(10.dp))
      Text(
        text = "Relatórios Inteligentes",
        style = MaterialTheme.typography.labelMedium,
        color = ColdWhite,
      )
      Spacer(modifier = Modifier.height(6.dp))
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        guardian.reports.take(3).forEach { report ->
          val outcomeColor =
            when (report.outcome) {
              GuardianOutcome.RESOLVED -> NeonSuccess
              GuardianOutcome.IMPROVED -> LimitlessCyan
              GuardianOutcome.PREVENTIVE -> WarningAmber
              GuardianOutcome.PERSISTING -> ReversalRed
            }
          Surface(
            modifier = Modifier.fillMaxWidth().testTag("guardian_report_${report.id}"),
            shape = RoundedCornerShape(12.dp),
            color = VoidObsidian.copy(alpha = 0.86f),
            border = BorderStroke(1.dp, outcomeColor.copy(alpha = 0.55f)),
          ) {
            Row(
              modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
              verticalAlignment = Alignment.Top,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = outcomeColor.copy(alpha = 0.18f),
                  ) {
                    Text(
                      text = report.outcome.label.uppercase(),
                      style = MaterialTheme.typography.labelSmall,
                      color = outcomeColor,
                      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                  }
                  if (report.avoidedRepeatedStrategy) {
                    Surface(
                      shape = RoundedCornerShape(6.dp),
                      color = HollowPurple.copy(alpha = 0.25f),
                    ) {
                      Text(
                        text = "ESTRATÉGIA NOVA",
                        style = MaterialTheme.typography.labelSmall,
                        color = HollowPurpleBright,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                      )
                    }
                  }
                  Spacer(modifier = Modifier.weight(1f))
                  Text(
                    text = report.timestampLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedLavender,
                  )
                }
                Text(
                  text = "Problema: ${report.detectedProblem}",
                  style = MaterialTheme.typography.labelLarge,
                  color = ColdWhite,
                )
                Text(
                  text = "Causa: ${report.probableCause.label} — ${report.causeDetail}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MutedLavender,
                )
                Text(
                  text = "Correção: ${report.correctionApplied}",
                  style = MaterialTheme.typography.bodySmall,
                  color = LimitlessCyan,
                )
                Text(
                  text = "Resultado: ${report.verificationResult}",
                  style = MaterialTheme.typography.labelSmall,
                  color = outcomeColor,
                )
              }
              IconButton(
                onClick = { onDismissAlert(report.id) },
                modifier = Modifier.minimumInteractiveComponentSize(),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Close,
                  contentDescription = "Dispensar relatório",
                  tint = MutedLavender,
                  modifier = Modifier.size(16.dp),
                )
              }
            }
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(10.dp))
    if (guardian.alerts.isEmpty() && guardian.reports.isEmpty()) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = VoidObsidian.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, NeonSuccess.copy(alpha = 0.35f)),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = NeonSuccess,
            modifier = Modifier.size(16.dp),
          )
          Text(
            text = "Nenhum bug ou travamento detectado no momento.",
            style = MaterialTheme.typography.labelSmall,
            color = MutedLavender,
          )
        }
      }
    } else if (guardian.alerts.isNotEmpty()) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        guardian.alerts.take(3).forEach { alert ->
          val (accent, icon) =
            when (alert.severity) {
              GuardianSeverity.FIXED -> NeonSuccess to Icons.Rounded.CheckCircle
              GuardianSeverity.WARNING -> WarningAmber to Icons.Rounded.WarningAmber
              GuardianSeverity.INFO -> LimitlessCyan to Icons.Rounded.Info
            }
          Surface(
            modifier = Modifier.fillMaxWidth().testTag("guardian_alert_${alert.id}"),
            shape = RoundedCornerShape(10.dp),
            color = VoidObsidian.copy(alpha = 0.82f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
          ) {
            Row(
              modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
              verticalAlignment = Alignment.Top,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.padding(top = 2.dp).size(18.dp),
              )
              Column(modifier = Modifier.weight(1f)) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Text(
                    text = alert.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = ColdWhite,
                    modifier = Modifier.weight(1f, fill = false),
                  )
                  Text(
                    text = alert.timestampLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                  )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = alert.detail,
                  style = MaterialTheme.typography.bodySmall,
                  color = MutedLavender,
                )
              }
              IconButton(
                onClick = { onDismissAlert(alert.id) },
                modifier = Modifier.minimumInteractiveComponentSize(),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Close,
                  contentDescription = "Dispensar aviso",
                  tint = MutedLavender,
                  modifier = Modifier.size(16.dp),
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun HollowPurpleReactorCard(
  boostActive: Boolean,
  liveFps: Int,
  targetHz: Int,
  currentDisplayHz: Int,
  maxHardwareHz: Int,
  backendBadge: String,
  tempC: Float,
  ramPercent: Int,
  onToggleBoost: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val infinite = rememberInfiniteTransition(label = "reactor_spin")
  val outerAngle by
    infinite.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = if (boostActive) 2200 else 6500, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "outer_angle",
    )
  val innerAngle by
    infinite.animateFloat(
      initialValue = 360f,
      targetValue = 0f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = if (boostActive) 1800 else 5000, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "inner_angle",
    )
  val reactorScale by
    animateFloatAsState(
      targetValue = if (boostActive) 1.04f else 1.0f,
      animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
      label = "reactor_scale",
    )

  VoidGlassCard(
    modifier = modifier,
    borderColor = if (boostActive) HollowPurpleBright else VoidBorder,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(
        modifier =
          Modifier.size(188.dp)
            .graphicsLayer {
              scaleX = reactorScale
              scaleY = reactorScale
            }
            .clip(CircleShape)
            .clickable(onClick = onToggleBoost)
            .testTag("vazio_roxo_reactor"),
        contentAlignment = Alignment.Center,
      ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val center = Offset(size.width / 2f, size.height / 2f)
          val radius = size.minDimension / 2f - 10.dp.toPx()

          // Glow halo
          drawCircle(
            brush =
              Brush.radialGradient(
                colors =
                  listOf(
                    (if (boostActive) HollowPurpleBright else HollowPurple).copy(alpha = 0.45f),
                    Color.Transparent,
                  ),
                center = center,
                radius = radius * 1.15f,
              ),
            center = center,
            radius = radius * 1.15f,
          )

          // Rotating Cyan (Ao) arc
          drawArc(
            color = LimitlessCyan,
            startAngle = outerAngle,
            sweepAngle = 110f,
            useCenter = false,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
          )

          // Rotating Red (Aka) arc
          drawArc(
            color = ReversalRed,
            startAngle = outerAngle + 180f,
            sweepAngle = 110f,
            useCenter = false,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
          )

          // Inner Purple Singularity ring
          drawArc(
            color = HollowPurpleBright,
            startAngle = innerAngle,
            sweepAngle = 240f,
            useCenter = false,
            topLeft = Offset(18.dp.toPx(), 18.dp.toPx()),
            size =
              androidx.compose.ui.geometry.Size(
                size.width - 36.dp.toPx(),
                size.height - 36.dp.toPx(),
              ),
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
          )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
            imageVector = if (boostActive) Icons.Rounded.CheckCircle else Icons.Rounded.Bolt,
            contentDescription = null,
            tint = if (boostActive) NeonSuccess else HollowPurpleBright,
            modifier = Modifier.size(28.dp),
          )
          Text(
            text = "$liveFps FPS",
            style = MaterialTheme.typography.displaySmall,
            color = ColdWhite,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = if (boostActive) "VAZIO ROXO ATIVO" else "PRONTO P/ BOOST",
            style = MaterialTheme.typography.labelSmall,
            color = if (boostActive) LimitlessCyan else MutedLavender,
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text =
          if (boostActive) {
            "$backendBadge • MÁX ${targetHz}Hz + FPS FORÇADO • RAM $ramPercent% • ${String.format("%.1f", tempC)}°C"
          } else {
            "$backendBadge • Tela ${currentDisplayHz}Hz (Máx: ${maxHardwareHz}Hz) • RAM $ramPercent% • ${String.format("%.1f", tempC)}°C"
          },
        style = MaterialTheme.typography.labelMedium,
        color = if (boostActive) LimitlessCyan else MutedLavender,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(14.dp))

      Button(
        onClick = onToggleBoost,
        modifier =
          Modifier.fillMaxWidth()
            .height(52.dp)
            .testTag("activate_vazio_roxo_button"),
        shape = RoundedCornerShape(16.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = if (boostActive) VoidSurfaceElevated else HollowPurple,
            contentColor = ColdWhite,
          ),
        border =
          BorderStroke(
            1.dp,
            if (boostActive) NeonSuccess else HollowPurpleBright,
          ),
      ) {
        Icon(
          imageVector = Icons.Rounded.Whatshot,
          contentDescription = null,
          tint = if (boostActive) NeonSuccess else ColdWhite,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
          text =
            if (boostActive) stringResource(R.string.boost_active)
            else stringResource(R.string.activate_boost),
          style = MaterialTheme.typography.titleMedium,
          color = ColdWhite,
        )
      }
    }
  }
}

@Composable
private fun BoosterSwitchRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  accent: Color,
  testTag: String,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(VoidSurfaceElevated.copy(alpha = 0.55f))
        .clickable { onCheckedChange(!checked) }
        .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = ColdWhite,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MutedLavender,
      )
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = Modifier.testTag(testTag),
      colors =
        SwitchDefaults.colors(
          checkedThumbColor = VoidObsidian,
          checkedTrackColor = accent,
          uncheckedThumbColor = MutedLavender,
          uncheckedTrackColor = VoidObsidian,
        ),
    )
  }
}

@Composable
private fun ShizukuEngineCard(
  status: ShizukuStatus,
  logs: List<String>,
  onAction: () -> Unit,
  onRefresh: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val (accent, badgeText, buttonText) =
    when (status.connectionState) {
      ShizukuConnectionState.CONNECTED ->
        Triple(
          NeonSuccess,
          "CONECTADO • UID ${status.privilegedUid ?: 2000}",
          "Aplicar Comandos via Shizuku",
        )
      ShizukuConnectionState.PERMISSION_REQUIRED ->
        Triple(LimitlessCyan, "AGUARDANDO AUTORIZAÇÃO", "Autorizar Shizuku")
      ShizukuConnectionState.BINDING ->
        Triple(HollowPurpleBright, "CONECTANDO...", "Conectando Serviço...")
      ShizukuConnectionState.NOT_RUNNING ->
        Triple(WarningAmber, "SERVIÇO PARADO", "Abrir App Shizuku")
      ShizukuConnectionState.NOT_INSTALLED ->
        Triple(MutedLavender, "NÃO INSTALADO", "Obter Shizuku")
    }

  VoidGlassCard(
    modifier = modifier,
    borderColor = accent.copy(alpha = 0.6f),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(imageVector = Icons.Rounded.Shield, contentDescription = null, tint = accent)
        Column {
          Text(
            text = "Modo Shizuku (ADB / Root)",
            style = MaterialTheme.typography.titleMedium,
            color = ColdWhite,
          )
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = accent.copy(alpha = 0.16f),
          ) {
            Text(
              text = badgeText,
              style = MaterialTheme.typography.labelSmall,
              color = accent,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        }
      }

      IconButton(
        onClick = onRefresh,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("refresh_shizuku_button"),
      ) {
        Icon(
          imageVector = Icons.Rounded.Refresh,
          contentDescription = "Atualizar status Shizuku",
          tint = MutedLavender,
        )
      }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = status.lastCommandSummary,
      style = MaterialTheme.typography.bodyMedium,
      color = ColdWhite,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text =
        "Desbloqueia 60/90/120Hz no sistema, cmd game mode performance, driver ANGLE, override térmico e limpeza profunda de processos.",
      style = MaterialTheme.typography.labelMedium,
      color = MutedLavender,
    )

    if (logs.isNotEmpty()) {
      Spacer(modifier = Modifier.height(8.dp))
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = VoidObsidian.copy(alpha = 0.75f),
        border = BorderStroke(1.dp, VoidBorder),
      ) {
        Text(
          text = logs.joinToString("  •  "),
          style = MaterialTheme.typography.labelSmall,
          color = LimitlessCyan,
          modifier = Modifier.padding(10.dp),
        )
      }
    }

    Spacer(modifier = Modifier.height(10.dp))
    Button(
      onClick = onAction,
      enabled = status.connectionState != ShizukuConnectionState.BINDING,
      modifier = Modifier.fillMaxWidth().height(48.dp).testTag("shizuku_action_button"),
      shape = RoundedCornerShape(14.dp),
      colors =
        ButtonDefaults.buttonColors(
          containerColor =
            if (status.connectionState == ShizukuConnectionState.CONNECTED) HollowPurple
            else VoidSurfaceElevated,
          contentColor = ColdWhite,
        ),
      border = BorderStroke(1.dp, accent.copy(alpha = 0.7f)),
    ) {
      Text(text = buttonText, style = MaterialTheme.typography.labelLarge, color = ColdWhite)
    }
  }
}

@Composable
private fun SystemPermissionsCard(
  canWriteSettings: Boolean,
  canDrawOverlays: Boolean,
  onRequestWriteSettings: () -> Unit,
  onRequestOverlayPermission: () -> Unit,
  modifier: Modifier = Modifier,
) {
  VoidGlassCard(modifier = modifier) {
    SectionHeader(
      title = "Permissões do Sistema",
      subtitle = "Libere alteração do sistema e sobreposição flutuante sobre jogos",
    )
    Spacer(modifier = Modifier.height(10.dp))

    PermissionActionRow(
      title = "Alterar Config. do Sistema",
      subtitle = "Permite aplicar Hz e ajustes diretamente (WRITE_SETTINGS)",
      granted = canWriteSettings,
      testTag = "perm_write_settings_button",
      onClick = onRequestWriteSettings,
    )
    Spacer(modifier = Modifier.height(8.dp))
    PermissionActionRow(
      title = "Sobrepor a Outros Apps",
      subtitle = "Exibe o Painel Flutuante Vermelho de Hz dentro dos jogos",
      granted = canDrawOverlays,
      testTag = "perm_overlay_button",
      onClick = onRequestOverlayPermission,
    )
  }
}

@Composable
private fun PermissionActionRow(
  title: String,
  subtitle: String,
  granted: Boolean,
  testTag: String,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(VoidSurfaceElevated.copy(alpha = 0.65f))
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(
      imageVector = if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.Settings,
      contentDescription = null,
      tint = if (granted) NeonSuccess else WarningAmber,
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(text = title, style = MaterialTheme.typography.titleMedium, color = ColdWhite)
      Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MutedLavender)
    }
    Surface(
      shape = RoundedCornerShape(8.dp),
      color = (if (granted) NeonSuccess else LimitlessCyan).copy(alpha = 0.16f),
      border = BorderStroke(1.dp, if (granted) NeonSuccess else LimitlessCyan),
      modifier = Modifier.testTag(testTag),
    ) {
      Text(
        text = if (granted) "ATIVO" else "PERMITIR",
        style = MaterialTheme.typography.labelSmall,
        color = if (granted) NeonSuccess else LimitlessCyan,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      )
    }
  }
}

@Composable
private fun DomainAudioCard(
  voiceEnabled: Boolean,
  customAudioName: String,
  onPickAudio: () -> Unit,
  onTestDomainAudio: () -> Unit,
  onClearCustomAudio: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val hasCustom = customAudioName.isNotBlank()
  VoidGlassCard(
    modifier = modifier,
    borderColor = if (hasCustom) LimitlessCyan.copy(alpha = 0.65f) else HollowPurpleBright.copy(alpha = 0.5f),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = Icons.Rounded.GraphicEq,
        contentDescription = null,
        tint = if (hasCustom) LimitlessCyan else HollowPurpleBright,
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Áudio da Expansão de Domínio",
          style = MaterialTheme.typography.titleMedium,
          color = ColdWhite,
        )
        Text(
          text =
            if (hasCustom) "Arquivo ativo: $customAudioName"
            else "Voz Japonesa HD do sistema (ou importe um áudio seu)",
          style = MaterialTheme.typography.bodyMedium,
          color = if (hasCustom) LimitlessCyan else MutedLavender,
        )
      }
      if (!voiceEnabled) {
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = WarningAmber.copy(alpha = 0.16f),
        ) {
          Text(
            text = "MUDO",
            style = MaterialTheme.typography.labelSmall,
            color = WarningAmber,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(10.dp))
    Button(
      onClick = onPickAudio,
      modifier = Modifier.fillMaxWidth().height(48.dp).testTag("import_domain_audio_button"),
      shape = RoundedCornerShape(14.dp),
      colors = ButtonDefaults.buttonColors(containerColor = HollowPurple, contentColor = ColdWhite),
    ) {
      Icon(imageVector = Icons.Rounded.LibraryMusic, contentDescription = null)
      Spacer(modifier = Modifier.size(8.dp))
      Text(
        text =
          if (hasCustom) "Trocar Áudio da Expansão (.mp3 / .wav)"
          else "Importar Áudio do Gojo do Celular (.mp3 / .wav)",
        style = MaterialTheme.typography.labelLarge,
      )
    }

    Spacer(modifier = Modifier.height(8.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedButton(
        onClick = onTestDomainAudio,
        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("test_domain_audio_button"),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, LimitlessCyan.copy(alpha = 0.6f)),
      ) {
        Icon(
          imageVector = Icons.Rounded.AutoAwesome,
          contentDescription = null,
          tint = LimitlessCyan,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text("Testar Expansão", color = ColdWhite)
      }
      if (hasCustom) {
        OutlinedButton(
          onClick = onClearCustomAudio,
          modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("reset_domain_audio_button"),
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, ReversalRed.copy(alpha = 0.65f)),
        ) {
          Icon(
            imageVector = Icons.Rounded.DeleteOutline,
            contentDescription = null,
            tint = ReversalRed,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.size(6.dp))
          Text("Usar Padrão", color = ColdWhite)
        }
      }
    }
  }
}

@Composable
private fun PersonalizationMediaCard(
  bgMusicEnabled: Boolean,
  bgMusicName: String,
  bgMusicVolume: Float,
  wallpaperTheme: WallpaperTheme,
  customWallpaperName: String,
  onPickBgMusic: () -> Unit,
  onToggleBgMusic: (Boolean) -> Unit,
  onVolumeChange: (Float) -> Unit,
  onClearBgMusic: () -> Unit,
  onSelectTheme: (WallpaperTheme) -> Unit,
  onPickWallpaper: () -> Unit,
  onClearWallpaper: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val hasBgMusic = bgMusicName.isNotBlank()
  val hasCustomWallpaper = customWallpaperName.isNotBlank()

  VoidGlassCard(
    modifier = modifier,
    borderColor = HollowPurpleBright.copy(alpha = 0.55f),
  ) {
    SectionHeader(
      title = "Música de Fundo & Papel de Parede",
      subtitle = "Personalize a trilha sonora e o tema visual do aplicativo",
    )
    Spacer(modifier = Modifier.height(12.dp))

    // Background Music Section
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(14.dp))
          .background(VoidSurfaceElevated.copy(alpha = 0.65f))
          .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = Icons.Rounded.MusicNote,
        contentDescription = null,
        tint = if (bgMusicEnabled && hasBgMusic) LimitlessCyan else HollowPurpleBright,
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Música de Fundo no App",
          style = MaterialTheme.typography.titleMedium,
          color = ColdWhite,
        )
        Text(
          text =
            if (hasBgMusic) "Música: $bgMusicName"
            else "Nenhuma música selecionada ainda",
          style = MaterialTheme.typography.bodyMedium,
          color = if (hasBgMusic) LimitlessCyan else MutedLavender,
        )
      }
      Switch(
        checked = bgMusicEnabled,
        onCheckedChange = onToggleBgMusic,
        modifier = Modifier.testTag("switch_bg_music"),
        colors =
          SwitchDefaults.colors(
            checkedThumbColor = VoidObsidian,
            checkedTrackColor = LimitlessCyan,
          ),
      )
    }

    if (hasBgMusic) {
      Spacer(modifier = Modifier.height(8.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = "Volume: ${(bgMusicVolume * 100).toInt()}%",
          style = MaterialTheme.typography.labelMedium,
          color = MutedLavender,
        )
        Slider(
          value = bgMusicVolume,
          onValueChange = onVolumeChange,
          valueRange = 0.05f..1f,
          modifier = Modifier.weight(1f),
          colors =
            SliderDefaults.colors(
              thumbColor = LimitlessCyan,
              activeTrackColor = HollowPurpleBright,
            ),
        )
      }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Button(
        onClick = onPickBgMusic,
        modifier = Modifier.weight(1f).height(48.dp).testTag("import_bg_music_button"),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = HollowPurple, contentColor = ColdWhite),
      ) {
        Icon(imageVector = Icons.Rounded.LibraryMusic, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.size(6.dp))
        Text(
          text = if (hasBgMusic) "Trocar Música" else "Escolher Música Baixada",
          style = MaterialTheme.typography.labelLarge,
        )
      }
      if (hasBgMusic) {
        OutlinedButton(
          onClick = onClearBgMusic,
          modifier = Modifier.height(48.dp).testTag("clear_bg_music_button"),
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, ReversalRed.copy(alpha = 0.65f)),
        ) {
          Icon(imageVector = Icons.Rounded.DeleteOutline, contentDescription = "Remover música", tint = ReversalRed)
        }
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Wallpaper Theme Section
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(imageVector = Icons.Rounded.Wallpaper, contentDescription = null, tint = LimitlessCyan)
      Column {
        Text(
          text = "Tema de Papel de Parede no Fundo",
          style = MaterialTheme.typography.titleMedium,
          color = ColdWhite,
        )
        Text(
          text =
            if (wallpaperTheme == WallpaperTheme.GALERIA_CUSTOM && hasCustomWallpaper)
              "Foto ativa: $customWallpaperName"
            else "Tema ativo: ${wallpaperTheme.label}",
          style = MaterialTheme.typography.labelMedium,
          color = LimitlessCyan,
        )
      }
    }

    Spacer(modifier = Modifier.height(10.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      WallpaperTheme.entries.forEach { theme ->
        val selected = wallpaperTheme == theme
        Surface(
          modifier =
            Modifier.weight(1f)
              .minimumInteractiveComponentSize()
              .clip(RoundedCornerShape(10.dp))
              .clickable {
                if (theme == WallpaperTheme.GALERIA_CUSTOM && !hasCustomWallpaper) {
                  onPickWallpaper()
                } else {
                  onSelectTheme(theme)
                }
              }
              .testTag("wallpaper_theme_${theme.id}"),
          shape = RoundedCornerShape(10.dp),
          color = if (selected) HollowPurple.copy(alpha = 0.35f) else VoidSurfaceElevated,
          border = BorderStroke(1.dp, if (selected) LimitlessCyan else VoidBorder),
        ) {
          Text(
            text = theme.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) ColdWhite else MutedLavender,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(10.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedButton(
        onClick = onPickWallpaper,
        modifier = Modifier.weight(1f).height(48.dp).testTag("import_wallpaper_button"),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, LimitlessCyan.copy(alpha = 0.7f)),
      ) {
        Icon(imageVector = Icons.Rounded.Wallpaper, contentDescription = null, tint = LimitlessCyan, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.size(6.dp))
        Text(
          text = if (hasCustomWallpaper) "Trocar Foto de Fundo" else "Escolher Foto da Galeria",
          color = ColdWhite,
        )
      }
      if (hasCustomWallpaper) {
        OutlinedButton(
          onClick = onClearWallpaper,
          modifier = Modifier.height(48.dp).testTag("clear_wallpaper_button"),
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, ReversalRed.copy(alpha = 0.65f)),
        ) {
          Icon(imageVector = Icons.Rounded.DeleteOutline, contentDescription = "Remover foto", tint = ReversalRed)
        }
      }
    }
  }
}
