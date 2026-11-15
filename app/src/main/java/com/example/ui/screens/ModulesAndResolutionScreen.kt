package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.model.IphoneTouchConfig
import com.example.model.PerformanceModule
import com.example.resolution.ResolutionHelper
import com.example.resolution.ResolutionPopupActivity
import com.example.shizuku.ShizukuConnectionState
import com.example.shizuku.ShizukuModulesCatalog
import com.example.ui.BoosterUiState
import com.example.ui.components.IphoneTouchModuleSheet
import com.example.ui.components.SectionHeader
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
import kotlin.math.roundToInt

@Composable
fun ModulesAndResolutionScreen(
  state: BoosterUiState,
  onShizukuAction: () -> Unit,
  onToggleModule: (String, Boolean) -> Unit,
  onRestoreAllDefaults: () -> Unit,
  onApplyResolution: (Int, Int, Int, Boolean, Int) -> Unit,
  onRestoreNativeResolution: () -> Unit,
  onToggleBackgroundPanel: (Boolean) -> Unit,
  onToggleFloatingResolutionOverlay: (Boolean) -> Unit,
  onToggleRootTotalPerformance: (Boolean) -> Unit,
  onUpdateIphoneTouchConfig: (IphoneTouchConfig) -> Unit,
  onApplyIphoneTouchNow: () -> Unit,
  onRestoreIphoneTouchDefaults: () -> Unit,
) {
  val context = LocalContext.current
  val settings = state.settings
  val screenSpec = state.telemetry.screenSpec
  val shizukuConnected = state.shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED
  val isAndroid11Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
  var showRootWarningDialog by remember { mutableStateOf(false) }
  var showIphoneTouchSheet by remember { mutableStateOf(false) }

  var scaleSlider by remember(settings.resolutionScalePercent) {
    mutableFloatStateOf(settings.resolutionScalePercent.toFloat().coerceIn(45f, 120f))
  }
  var lockRatio by remember(settings.lockAspectRatio) { mutableStateOf(settings.lockAspectRatio) }
  var widthText by remember(settings.resolutionCustomWidth, screenSpec.widthPx) {
    val initialW =
      if (settings.resolutionCustomWidth > 0) settings.resolutionCustomWidth
      else ResolutionHelper.computeFromPercent(screenSpec, settings.resolutionScalePercent).widthPx
    mutableStateOf(initialW.toString())
  }
  var heightText by remember(settings.resolutionCustomHeight, screenSpec.heightPx) {
    val initialH =
      if (settings.resolutionCustomHeight > 0) settings.resolutionCustomHeight
      else ResolutionHelper.computeFromPercent(screenSpec, settings.resolutionScalePercent).heightPx
    mutableStateOf(initialH.toString())
  }

  val notifPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        onToggleBackgroundPanel(true)
      }
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // Master Extreme Control Card
    item {
      VoidGlassCard(
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        borderColor = if (shizukuConnected) HollowPurpleBright else WarningAmber.copy(alpha = 0.6f),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          SectionHeader(
            title = "Módulos AxManager + VOID RAM",
            subtitle = "${settings.enabledModuleIds.size}/14 ativos • Escolha individualmente qual ativar",
          )
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = (if (shizukuConnected) NeonSuccess else WarningAmber).copy(alpha = 0.16f),
          ) {
            Text(
              text = if (shizukuConnected) "SHIZUKU ON" else "SHIZUKU OFF",
              style = MaterialTheme.typography.labelSmall,
              color = if (shizukuConnected) NeonSuccess else WarningAmber,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
        }

        if (!shizukuConnected) {
          Spacer(modifier = Modifier.height(10.dp))
          OutlinedButton(
            onClick = onShizukuAction,
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, LimitlessCyan),
          ) {
            Text(
              text = "Conectar / Autorizar Shizuku Primeiro",
              style = MaterialTheme.typography.labelLarge,
              color = LimitlessCyan,
            )
          }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
          text =
            "Cada módulo abaixo funciona no estilo AxManager: você escolhe quais ativar ou desativar individualmente e acompanha a saída em tempo real.",
          style = MaterialTheme.typography.bodyMedium,
          color = MutedLavender,
        )

        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(
          onClick = onRestoreAllDefaults,
          modifier =
            Modifier.fillMaxWidth()
              .height(48.dp)
              .testTag("restore_all_button"),
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(1.dp, ReversalRed.copy(alpha = 0.65f)),
        ) {
          Icon(imageVector = Icons.Rounded.Restore, contentDescription = null, tint = ReversalRed)
          Spacer(modifier = Modifier.size(8.dp))
          Text(
            text = "Restaurar Tudo (Padrão de Fábrica)",
            style = MaterialTheme.typography.labelLarge,
            color = ColdWhite,
          )
        }
      }
    }

    // Featured AxManager WebUI iPhone Touch Module Card
    item {
      val iosActive = settings.iphoneTouchConfig.enabled || ("mod_14_iphone_touch" in settings.enabledModuleIds)
      VoidGlassCard(
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        borderColor = if (iosActive) LimitlessCyan else HollowPurpleBright.copy(alpha = 0.65f),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
          ) {
            Icon(
              imageVector = Icons.Rounded.PhoneIphone,
              contentDescription = null,
              tint = if (iosActive) LimitlessCyan else HollowPurpleBright,
            )
            Column {
              Text(
                text = "Módulo iPhone Touch (AxManager)",
                style = MaterialTheme.typography.titleMedium,
                color = ColdWhite,
              )
              Text(
                text =
                  if (iosActive) "${settings.iphoneTouchConfig.preset.label} • Sensibilidade ${settings.iphoneTouchConfig.pointerSpeed}/7"
                  else "Animação iOS interna + opções de sensibilidade ativas",
                style = MaterialTheme.typography.labelSmall,
                color = if (iosActive) LimitlessCyan else MutedLavender,
              )
            }
          }
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = (if (iosActive) NeonSuccess else WarningAmber).copy(alpha = 0.16f),
          ) {
            Text(
              text = if (iosActive) "ATIVO" else "WEBUI",
              style = MaterialTheme.typography.labelSmall,
              color = if (iosActive) NeonSuccess else WarningAmber,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(
          onClick = { showIphoneTouchSheet = true },
          modifier = Modifier.fillMaxWidth().height(48.dp).testTag("open_iphone_touch_module_button"),
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(containerColor = HollowPurple),
        ) {
          Text(
            text = "Abrir Módulo iPhone (Animação & Opções)",
            style = MaterialTheme.typography.labelLarge,
            color = ColdWhite,
          )
        }
      }
    }

    // Root / Shizuku Total Performance Card (Requires Shizuku + Android 11+)
    item {
      val canActivateRoot = shizukuConnected && isAndroid11Plus
      val rootActive = settings.rootTotalPerformanceEnabled
      VoidGlassCard(
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        borderColor =
          when {
            rootActive -> ReversalRed
            canActivateRoot -> HollowPurpleBright.copy(alpha = 0.6f)
            else -> VoidBorder
          },
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
              imageVector = Icons.Rounded.Security,
              contentDescription = null,
              tint = if (rootActive) ReversalRed else HollowPurpleBright,
            )
            Column {
              Text(
                text = "Desempenho Total (Modo Root)",
                style = MaterialTheme.typography.titleMedium,
                color = ColdWhite,
              )
              Text(
                text = "Requer Shizuku ativo + Android 11 ou superior",
                style = MaterialTheme.typography.labelSmall,
                color = if (canActivateRoot) LimitlessCyan else WarningAmber,
              )
            }
          }

          Switch(
            checked = rootActive,
            enabled = canActivateRoot || rootActive,
            onCheckedChange = { wantEnable ->
              if (wantEnable) {
                showRootWarningDialog = true
              } else {
                onToggleRootTotalPerformance(false)
              }
            },
            modifier = Modifier.testTag("switch_root_total_perf"),
            colors =
              SwitchDefaults.colors(
                checkedThumbColor = ColdWhite,
                checkedTrackColor = ReversalRed,
                uncheckedThumbColor = MutedLavender,
                uncheckedTrackColor = VoidObsidian,
              ),
          )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text =
            "Tenta governador 'performance' + I/O/RAM via Root (su/kernel) e força tudo que é possível no sistema sem root (Fixed Perf Android 11+, SurfaceFlinger zero-atraso, Thermal Override, Anti-Doze, toque 220ms e limpeza de RAM). Pode aquecer o celular.",
          style = MaterialTheme.typography.bodyMedium,
          color = MutedLavender,
        )

        if (rootActive && state.shizukuLogs.isNotEmpty()) {
          Spacer(modifier = Modifier.height(8.dp))
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = VoidObsidian.copy(alpha = 0.85f),
            border = BorderStroke(1.dp, LimitlessCyan.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = state.shizukuLogs.joinToString("\n"),
              style = MaterialTheme.typography.labelSmall,
              color = LimitlessCyan,
              modifier = Modifier.padding(10.dp),
            )
          }
        }

        if (!canActivateRoot && !rootActive) {
          Spacer(modifier = Modifier.height(8.dp))
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = WarningAmber.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth(),
          ) {
            val reason =
              when {
                !isAndroid11Plus -> "Bloqueado: seu dispositivo está abaixo do Android 11."
                else -> "Bloqueado: conecte e autorize o Shizuku para liberar esta opção."
              }
            Text(
              text = reason,
              style = MaterialTheme.typography.labelMedium,
              color = WarningAmber,
              modifier = Modifier.padding(10.dp),
            )
          }
        }
      }
    }

    // Proportional Resolution + 2nd-Plan In-Game Panel Card
    item {
      VoidGlassCard(
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        borderColor = LimitlessCyan.copy(alpha = 0.5f),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          SectionHeader(
            title = "Painel de Resolução Proporcional",
            subtitle =
              "Nativa: ${screenSpec.widthPx}x${screenSpec.heightPx} • Proporção ${screenSpec.aspectRatioLabel}",
          )
          Icon(imageVector = Icons.Rounded.AspectRatio, contentDescription = null, tint = LimitlessCyan)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Background 2nd-plan panel toggle
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(14.dp))
              .background(VoidSurfaceElevated.copy(alpha = 0.7f))
              .padding(horizontal = 12.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.NotificationsActive,
            contentDescription = null,
            tint = LimitlessCyan,
          )
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Rodar Painel em 2º Plano (Dentro do Jogo)",
              style = MaterialTheme.typography.titleMedium,
              color = ColdWhite,
            )
            Text(
              text = "Mantém atalho na barra de notificações para abrir o popup de resolução sobre qualquer jogo.",
              style = MaterialTheme.typography.bodyMedium,
              color = MutedLavender,
            )
          }
          Switch(
            checked = settings.backgroundResolutionPanelEnabled,
            onCheckedChange = { enable ->
              if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted =
                  ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                  ) == PackageManager.PERMISSION_GRANTED
                if (granted) onToggleBackgroundPanel(true)
                else notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
              } else {
                onToggleBackgroundPanel(enable)
              }
            },
            modifier = Modifier.testTag("switch_bg_res_panel"),
            colors =
              SwitchDefaults.colors(
                checkedThumbColor = VoidObsidian,
                checkedTrackColor = LimitlessCyan,
              ),
          )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Floating Custom Resolution Editor Overlay (2nd Plan over any game)
        Button(
          onClick = { onToggleFloatingResolutionOverlay(!settings.floatingResolutionOverlayEnabled) },
          modifier =
            Modifier.fillMaxWidth()
              .height(50.dp)
              .testTag("toggle_floating_resolution_overlay"),
          shape = RoundedCornerShape(14.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor =
                if (settings.floatingResolutionOverlayEnabled) LimitlessCyan
                else HollowPurple,
              contentColor =
                if (settings.floatingResolutionOverlayEnabled) VoidObsidian
                else ColdWhite,
            ),
        ) {
          Icon(imageVector = Icons.Rounded.Layers, contentDescription = null)
          Spacer(modifier = Modifier.size(8.dp))
          Text(
            text =
              if (settings.floatingResolutionOverlayEnabled)
                "Painel Flutuante de Resolução Livre: ATIVO"
              else
                "Abrir Painel Flutuante (Criar Resolução no Jogo)",
            style = MaterialTheme.typography.labelLarge,
          )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Quick Proportional Presets tailored to this device
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          listOf(50, 65, 75, 88, 100).forEach { pct ->
            val preset = ResolutionHelper.computeFromPercent(screenSpec, pct)
            val selected = scaleSlider.roundToInt() == pct
            Surface(
              modifier =
                Modifier.weight(1f)
                  .clip(RoundedCornerShape(12.dp))
                  .clickable {
                    scaleSlider = pct.toFloat()
                    widthText = preset.widthPx.toString()
                    heightText = preset.heightPx.toString()
                  }
                  .testTag("res_preset_$pct"),
              shape = RoundedCornerShape(12.dp),
              color = if (selected) HollowPurple.copy(alpha = 0.4f) else VoidSurfaceElevated,
              border = BorderStroke(1.dp, if (selected) LimitlessCyan else VoidBorder),
            ) {
              Column(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                Text(
                  text = "${pct}%",
                  style = MaterialTheme.typography.labelLarge,
                  color = ColdWhite,
                  textAlign = TextAlign.Center,
                )
                Text(
                  text = "${preset.widthPx}p",
                  style = MaterialTheme.typography.labelSmall,
                  color = LimitlessCyan,
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))
        val previewW = widthText.toIntOrNull() ?: screenSpec.widthPx
        val previewH = heightText.toIntOrNull() ?: screenSpec.heightPx
        val previewDpi = ResolutionHelper.computeProportionalDpi(screenSpec, previewW)
        Text(
          text = "Resolução Alvo: ${previewW}x${previewH} (${previewDpi} DPI • ${scaleSlider.roundToInt()}%)",
          style = MaterialTheme.typography.labelLarge,
          color = LimitlessCyan,
        )
        Slider(
          value = scaleSlider,
          onValueChange = { v ->
            scaleSlider = v
            val c = ResolutionHelper.computeFromPercent(screenSpec, v.roundToInt())
            widthText = c.widthPx.toString()
            heightText = c.heightPx.toString()
          },
          valueRange = 45f..120f,
          colors =
            SliderDefaults.colors(
              thumbColor = LimitlessCyan,
              activeTrackColor = HollowPurpleBright,
            ),
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedTextField(
            value = widthText,
            onValueChange = { raw ->
              val digits = raw.filter { it.isDigit() }.take(4)
              widthText = digits
              val w = digits.toIntOrNull()
              if (lockRatio && w != null && w >= 360) {
                val c = ResolutionHelper.computeFromWidth(screenSpec, w)
                heightText = c.heightPx.toString()
                scaleSlider = c.scalePercent.toFloat().coerceIn(45f, 120f)
              }
            },
            label = { Text("Largura (px)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors =
              OutlinedTextFieldDefaults.colors(
                focusedBorderColor = LimitlessCyan,
                unfocusedBorderColor = VoidBorder,
                focusedTextColor = ColdWhite,
                unfocusedTextColor = ColdWhite,
              ),
            modifier = Modifier.weight(1f),
          )
          OutlinedTextField(
            value = heightText,
            onValueChange = { raw ->
              if (!lockRatio) heightText = raw.filter { it.isDigit() }.take(4)
            },
            enabled = !lockRatio,
            label = { Text("Altura (px)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors =
              OutlinedTextFieldDefaults.colors(
                focusedBorderColor = LimitlessCyan,
                unfocusedBorderColor = VoidBorder,
                focusedTextColor = ColdWhite,
                unfocusedTextColor = ColdWhite,
                disabledTextColor = MutedLavender,
                disabledBorderColor = VoidBorder.copy(alpha = 0.5f),
              ),
            modifier = Modifier.weight(1f),
          )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Manter proporção exata da tela (${screenSpec.aspectRatioLabel})",
            style = MaterialTheme.typography.bodyMedium,
            color = MutedLavender,
          )
          Switch(
            checked = lockRatio,
            onCheckedChange = { lockRatio = it },
            colors =
              SwitchDefaults.colors(
                checkedThumbColor = VoidObsidian,
                checkedTrackColor = LimitlessCyan,
              ),
          )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(
            onClick = {
              scaleSlider = 100f
              widthText = screenSpec.widthPx.toString()
              heightText = screenSpec.heightPx.toString()
              onRestoreNativeResolution()
            },
            modifier =
              Modifier.weight(1f).height(48.dp).testTag("restore_resolution_button"),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, VoidBorder),
          ) {
            Text("Nativa", color = ColdWhite)
          }
          Button(
            onClick = {
              val w = widthText.toIntOrNull()?.coerceIn(360, 4320) ?: screenSpec.widthPx
              val h = heightText.toIntOrNull()?.coerceIn(480, 7680) ?: screenSpec.heightPx
              val dpi = ResolutionHelper.computeProportionalDpi(screenSpec, w)
              onApplyResolution(w, h, dpi, lockRatio, scaleSlider.roundToInt())
            },
            modifier =
              Modifier.weight(1.3f).height(48.dp).testTag("apply_resolution_button"),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HollowPurple),
          ) {
            Text("Aplicar Resolução", color = ColdWhite)
          }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
          onClick = {
            context.startActivity(Intent(context, ResolutionPopupActivity::class.java))
          },
          modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, HollowPurpleBright.copy(alpha = 0.55f)),
        ) {
          Icon(imageVector = Icons.Rounded.Layers, contentDescription = null, tint = HollowPurpleBright)
          Spacer(modifier = Modifier.size(6.dp))
          Text("Abrir Mini-Painel Flutuante", color = ColdWhite)
        }
      }
    }

    // Section Title for Individual AxManager Modules
    item {
      Column(modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp)) {
        SectionHeader(
          title = "Módulos Individuais (Estilo AxManager)",
          subtitle = "Ative e gerencie cada módulo separadamente (${settings.enabledModuleIds.size}/14 ativos)",
        )
      }
    }

    items(ShizukuModulesCatalog.modules, key = { it.id }) { module ->
      val enabled = module.id in settings.enabledModuleIds
      ModuleCard(
        module = module,
        enabled = enabled,
        moduleLog = state.moduleLogs[module.id],
        onToggle = { onToggleModule(module.id, it) },
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
      )
    }

    item {
      Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = "Criador: edix ff",
          style = MaterialTheme.typography.labelMedium,
          color = ColdWhite,
          textAlign = TextAlign.Center,
        )
        Text(
          text = "App em teste — não está na versão completa",
          style = MaterialTheme.typography.labelSmall,
          color = WarningAmber,
          textAlign = TextAlign.Center,
        )
      }
    }
  }

  if (showRootWarningDialog) {
    AlertDialog(
      onDismissRequest = { showRootWarningDialog = false },
      containerColor = VoidSurfaceElevated,
      titleContentColor = ColdWhite,
      textContentColor = MutedLavender,
      modifier = Modifier.testTag("root_thermal_warning_dialog"),
      icon = {
        Icon(
          imageVector = Icons.Rounded.WarningAmber,
          contentDescription = null,
          tint = ReversalRed,
          modifier = Modifier.size(32.dp),
        )
      },
      title = {
        Text(
          text = "Aviso: Pode Aquecer o Celular",
          style = MaterialTheme.typography.titleLarge,
        )
      },
      text = {
        Text(
          text =
            "Ativar o Desempenho Total (Modo Root / Shizuku Android 11+) força a CPU, GPU e modo térmico no máximo. Isso pode aquecer o aparelho e consumir mais bateria, mas pode liberar o desempenho máximo nos jogos. Deseja ativar mesmo assim?",
          style = MaterialTheme.typography.bodyMedium,
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showRootWarningDialog = false
            onToggleRootTotalPerformance(true)
          },
          colors = ButtonDefaults.buttonColors(containerColor = ReversalRed),
          modifier = Modifier.testTag("confirm_root_perf_button"),
        ) {
          Text("Ativar Desempenho Total", color = ColdWhite)
        }
      },
      dismissButton = {
        TextButton(onClick = { showRootWarningDialog = false }) {
          Text("Cancelar", color = LimitlessCyan)
        }
      },
    )
  }

  if (showIphoneTouchSheet) {
    IphoneTouchModuleSheet(
      config = settings.iphoneTouchConfig,
      shizukuConnected = shizukuConnected,
      moduleLog = state.moduleLogs["mod_14_iphone_touch"],
      shizukuLogs = state.shizukuLogs,
      onUpdateConfig = onUpdateIphoneTouchConfig,
      onApplyNow = onApplyIphoneTouchNow,
      onRestoreDefaults = onRestoreIphoneTouchDefaults,
      onDismiss = { showIphoneTouchSheet = false },
    )
  }
}

@Composable
private fun ModuleCard(
  module: PerformanceModule,
  enabled: Boolean,
  moduleLog: String?,
  onToggle: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val isVoidRam = module.id == "mod_13_void_ram"
  val accentColor =
    when {
      isVoidRam && enabled -> ReversalRed
      enabled -> LimitlessCyan
      isVoidRam -> HollowPurpleBright
      else -> VoidBorder
    }

  VoidGlassCard(
    modifier = modifier,
    borderColor = accentColor.copy(alpha = if (enabled) 0.75f else 0.9f),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (enabled) HollowPurple.copy(alpha = 0.35f) else VoidSurfaceElevated,
        border = BorderStroke(1.dp, if (enabled) accentColor else VoidBorder),
      ) {
        Text(
          text = "#${module.index.toString().padStart(2, '0')}",
          style = MaterialTheme.typography.labelLarge,
          color = if (enabled) accentColor else MutedLavender,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
      }

      Column(modifier = Modifier.weight(1f)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = module.title,
            style = MaterialTheme.typography.titleMedium,
            color = ColdWhite,
            modifier = Modifier.weight(1f, fill = false),
          )
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = (if (isVoidRam) ReversalRed else HollowPurpleBright).copy(alpha = 0.18f),
          ) {
            Text(
              text = module.category,
              style = MaterialTheme.typography.labelSmall,
              color = if (isVoidRam) ReversalRed else HollowPurpleBright,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "Criador: edix ff • ${if (enabled) "ATIVO" else "INATIVO"}",
          style = MaterialTheme.typography.labelSmall,
          color = if (enabled) NeonSuccess else MutedLavender,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = module.description,
          style = MaterialTheme.typography.bodyMedium,
          color = MutedLavender,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = module.commandPreview,
          style = MaterialTheme.typography.labelSmall,
          color = if (enabled) LimitlessCyan else MutedLavender.copy(alpha = 0.8f),
        )
      }

      Switch(
        checked = enabled,
        onCheckedChange = onToggle,
        modifier = Modifier.testTag("module_switch_${module.id}"),
        colors =
          SwitchDefaults.colors(
            checkedThumbColor = VoidObsidian,
            checkedTrackColor = if (isVoidRam) ReversalRed else LimitlessCyan,
            uncheckedThumbColor = MutedLavender,
            uncheckedTrackColor = VoidObsidian,
          ),
      )
    }

    if (!moduleLog.isNullOrBlank()) {
      Spacer(modifier = Modifier.height(8.dp))
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = VoidObsidian.copy(alpha = 0.8f),
        border = BorderStroke(1.dp, VoidBorder),
      ) {
        Text(
          text = "> $moduleLog",
          style = MaterialTheme.typography.labelSmall,
          color = NeonSuccess,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
      }
    }
  }
}
