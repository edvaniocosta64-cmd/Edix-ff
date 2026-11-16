package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.model.AxCompatibilityStatus
import com.example.model.AxExtensionModule
import com.example.model.AxPluginType
import com.example.resolution.ResolutionHelper
import com.example.resolution.ResolutionPopupActivity
import com.example.shizuku.ShizukuConnectionState
import com.example.ui.BoosterUiState
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

private enum class AxPluginFilter(val label: String) {
  ALL("Todos"),
  AX_ONLY("Só AxManager"),
  SUPPORTED("Suportados"),
  ACTIVE("Ativos"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesAndResolutionScreen(
  state: BoosterUiState,
  onShizukuAction: () -> Unit,
  onToggleRootTotalPerformance: (Boolean) -> Unit,
  onImportAxPluginZip: (Uri) -> Unit,
  onToggleAxOnlyMode: (Boolean) -> Unit,
  onToggleAxPlugin: (String, Boolean) -> Unit,
  onRemoveAxPlugin: (String) -> Unit,
  onApplyResolution: (Int, Int, Int, Boolean, Int) -> Unit,
  onToggleBackgroundPanel: (Boolean) -> Unit,
  onToggleFloatingResolutionOverlay: (Boolean) -> Unit,
) {
  val context = LocalContext.current
  val settings = state.settings
  val screenSpec = state.telemetry.screenSpec
  val shizukuConnected = state.shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED
  val isRootUid = state.shizukuStatus.privilegedUid == 0
  val isAndroid11Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

  var showRootWarningDialog by remember { mutableStateOf(false) }
  var selectedFilter by remember { mutableStateOf(AxPluginFilter.ALL) }
  var inspectedPluginId by remember { mutableStateOf<String?>(null) }
  var pendingRemovalPlugin by remember { mutableStateOf<AxExtensionModule?>(null) }

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

  val zipPickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        onImportAxPluginZip(uri)
      }
    }

  val filteredPlugins =
    remember(state.axExtensions, selectedFilter, settings.axOnlyModeEnabled) {
      state.axExtensions.filter { plugin ->
        val matchesOnlyToggle =
          !settings.axOnlyModeEnabled || plugin.pluginType == AxPluginType.AX_MANAGER_PLUGIN
        val matchesChip =
          when (selectedFilter) {
            AxPluginFilter.ALL -> true
            AxPluginFilter.AX_ONLY -> plugin.pluginType == AxPluginType.AX_MANAGER_PLUGIN
            AxPluginFilter.SUPPORTED -> plugin.isSupportedForActivation
            AxPluginFilter.ACTIVE -> plugin.enabled
          }
        matchesOnlyToggle && matchesChip
      }
    }

  val activeAxCount = remember(state.axExtensions) { state.axExtensions.count { it.enabled } }
  val inspectedPlugin =
    remember(inspectedPluginId, state.axExtensions) {
      state.axExtensions.firstOrNull { it.id == inspectedPluginId }
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // 1. Root / Shizuku Total Performance Card (directly paired with AX EXTENSION CENTER)
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

    // 2. AX EXTENSION CENTER (Right next to Desempenho Total!)
    item {
      VoidGlassCard(
        modifier =
          Modifier.fillMaxWidth()
            .widthIn(max = 680.dp)
            .testTag("ax_extension_center_card"),
        borderColor = if (shizukuConnected) LimitlessCyan else HollowPurpleBright.copy(alpha = 0.75f),
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
              imageVector = Icons.Rounded.Extension,
              contentDescription = null,
              tint = LimitlessCyan,
              modifier = Modifier.size(28.dp),
            )
            SectionHeader(
              title = "AX EXTENSION CENTER",
              subtitle =
                "${state.axExtensions.size} importado(s) • $activeAxCount ativo(s) • ZIP Magisk & AxManager",
            )
          }
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = (if (shizukuConnected) NeonSuccess else WarningAmber).copy(alpha = 0.16f),
          ) {
            Text(
              text =
                when {
                  shizukuConnected && isRootUid -> "SHIZUKU ROOT"
                  shizukuConnected -> "SHIZUKU ON"
                  else -> "SHIZUKU OFF"
                },
              style = MaterialTheme.typography.labelSmall,
              color = if (shizukuConnected) NeonSuccess else WarningAmber,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Device & Android Compatibility Banner
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp),
          color = VoidSurfaceElevated.copy(alpha = 0.85f),
          border = BorderStroke(1.dp, VoidBorder),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.PhoneAndroid,
              contentDescription = null,
              tint = LimitlessCyan,
              modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
              val devLabel =
                state.telemetry.deviceName.ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}" }
              val androidLabel =
                "Android ${state.telemetry.androidVersion.ifBlank { Build.VERSION.RELEASE }} (API ${Build.VERSION.SDK_INT})"
              val abiLabel =
                state.telemetry.cpuAbi.ifBlank { Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a" }
              Text(
                text = "Compatibilidade do Dispositivo",
                style = MaterialTheme.typography.labelMedium,
                color = ColdWhite,
              )
              Text(
                text = "$androidLabel • $devLabel • ABI $abiLabel",
                style = MaterialTheme.typography.labelSmall,
                color = LimitlessCyan,
              )
            }
          }
        }

        if (!shizukuConnected) {
          Spacer(modifier = Modifier.height(10.dp))
          OutlinedButton(
            onClick = onShizukuAction,
            modifier =
              Modifier.fillMaxWidth()
                .minimumInteractiveComponentSize()
                .testTag("ax_connect_shizuku_button"),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, LimitlessCyan),
          ) {
            Icon(
              imageVector = Icons.Rounded.Terminal,
              contentDescription = null,
              tint = LimitlessCyan,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
              text = "Conectar / Integrar Shizuku para Executar Módulos",
              style = MaterialTheme.typography.labelLarge,
              color = LimitlessCyan,
            )
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // AxManager-Only Plugin Mode Toggle
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(14.dp))
              .background(VoidSurfaceElevated.copy(alpha = 0.72f))
              .padding(horizontal = 12.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.VerifiedUser,
            contentDescription = null,
            tint = if (settings.axOnlyModeEnabled) LimitlessCyan else HollowPurpleBright,
          )
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Somente Plugins de AxManager",
              style = MaterialTheme.typography.titleMedium,
              color = ColdWhite,
            )
            Text(
              text =
                if (settings.axOnlyModeEnabled)
                  "Filtro restrito ativo: aceita apenas plugins feitos para AxManager."
                else
                  "Modo livre: importa módulos ZIP baixados de sites Magisk e plugins AxManager.",
              style = MaterialTheme.typography.bodySmall,
              color = MutedLavender,
            )
          }
          Switch(
            checked = settings.axOnlyModeEnabled,
            onCheckedChange = onToggleAxOnlyMode,
            modifier = Modifier.testTag("switch_ax_only_mode"),
            colors =
              SwitchDefaults.colors(
                checkedThumbColor = VoidObsidian,
                checkedTrackColor = LimitlessCyan,
                uncheckedThumbColor = MutedLavender,
                uncheckedTrackColor = VoidObsidian,
              ),
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Import ZIP Button (zero storage permission via SAF OpenDocument)
        Button(
          onClick = {
            zipPickerLauncher.launch(
              arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
                "*/*",
              )
            )
          },
          enabled = !state.isImportingAxPlugin,
          modifier =
            Modifier.fillMaxWidth()
              .height(52.dp)
              .testTag("import_ax_plugin_button"),
          shape = RoundedCornerShape(14.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = HollowPurple,
              contentColor = ColdWhite,
            ),
        ) {
          if (state.isImportingAxPlugin) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
              color = ColdWhite,
            )
            Spacer(modifier = Modifier.size(10.dp))
            Text(
              text = "Analisando arquivos, scripts e compatibilidade...",
              style = MaterialTheme.typography.labelLarge,
            )
          } else {
            Icon(imageVector = Icons.Rounded.UploadFile, contentDescription = null)
            Spacer(modifier = Modifier.size(8.dp))
            Text(
              text = "Importar Módulo / Plugin (.zip)",
              style = MaterialTheme.typography.labelLarge,
            )
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Filter Chips
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          AxPluginFilter.entries.forEach { filter ->
            val selected = selectedFilter == filter
            FilterChip(
              selected = selected,
              onClick = { selectedFilter = filter },
              label = { Text(filter.label, style = MaterialTheme.typography.labelMedium) },
              modifier = Modifier.testTag("ax_filter_${filter.name.lowercase()}"),
              colors =
                FilterChipDefaults.filterChipColors(
                  containerColor = VoidSurfaceElevated,
                  labelColor = MutedLavender,
                  selectedContainerColor = HollowPurple.copy(alpha = 0.45f),
                  selectedLabelColor = LimitlessCyan,
                ),
              border =
                FilterChipDefaults.filterChipBorder(
                  enabled = true,
                  selected = selected,
                  borderColor = VoidBorder,
                  selectedBorderColor = LimitlessCyan,
                ),
            )
          }
        }
      }
    }

    // 3. Imported Plugins / Modules List or Empty State
    if (filteredPlugins.isEmpty()) {
      item {
        VoidGlassCard(
          modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
          borderColor = VoidBorder,
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.FolderZip,
              contentDescription = null,
              tint = HollowPurpleBright,
              modifier = Modifier.size(40.dp),
            )
            Text(
              text =
                if (state.axExtensions.isEmpty())
                  "Nenhum módulo ou plugin importado ainda"
                else
                  "Nenhum plugin encontrado neste filtro",
              style = MaterialTheme.typography.titleMedium,
              color = ColdWhite,
              textAlign = TextAlign.Center,
            )
            Text(
              text =
                if (state.axExtensions.isEmpty())
                  "Todos os módulos fixos antigos foram apagados. Baixe qualquer módulo .zip (Magisk ou plugin AxManager) e importe acima para analisar scripts, dependências, compatibilidade e rodar com Shizuku."
                else
                  "Mude o filtro acima ou desative 'Somente Plugins de AxManager' para ver todos os arquivos importados.",
              style = MaterialTheme.typography.bodyMedium,
              color = MutedLavender,
              textAlign = TextAlign.Center,
            )
          }
        }
      }
    } else {
      items(filteredPlugins, key = { it.id }) { plugin ->
        AxPluginCard(
          plugin = plugin,
          shizukuConnected = shizukuConnected,
          onToggle = { wantEnable -> onToggleAxPlugin(plugin.id, wantEnable) },
          onInspect = { inspectedPluginId = plugin.id },
          onRequestRemove = { pendingRemovalPlugin = plugin },
          modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        )
      }
    }

    // 4. Proportional Resolution + 2nd-Plan In-Game Panel Card
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
        Button(
          onClick = {
            val w = widthText.toIntOrNull()?.coerceIn(360, 4320) ?: screenSpec.widthPx
            val h = heightText.toIntOrNull()?.coerceIn(480, 7680) ?: screenSpec.heightPx
            val dpi = ResolutionHelper.computeProportionalDpi(screenSpec, w)
            onApplyResolution(w, h, dpi, lockRatio, scaleSlider.roundToInt())
          },
          modifier =
            Modifier.fillMaxWidth().height(48.dp).testTag("apply_resolution_button"),
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(containerColor = HollowPurple),
        ) {
          Text("Aplicar Resolução", color = ColdWhite)
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

  pendingRemovalPlugin?.let { plugin ->
    AlertDialog(
      onDismissRequest = { pendingRemovalPlugin = null },
      containerColor = VoidSurfaceElevated,
      titleContentColor = ColdWhite,
      textContentColor = MutedLavender,
      icon = {
        Icon(
          imageVector = Icons.Rounded.DeleteOutline,
          contentDescription = null,
          tint = ReversalRed,
          modifier = Modifier.size(30.dp),
        )
      },
      title = { Text("Remover Plugin?", style = MaterialTheme.typography.titleLarge) },
      text = {
        Text(
          text =
            "Deseja desinstalar '${plugin.name}' do AX Extension Center? Se ele estiver ativo, os comandos de reversão serão executados antes de remover os arquivos.",
          style = MaterialTheme.typography.bodyMedium,
        )
      },
      confirmButton = {
        Button(
          onClick = {
            val id = plugin.id
            pendingRemovalPlugin = null
            if (inspectedPluginId == id) inspectedPluginId = null
            onRemoveAxPlugin(id)
          },
          colors = ButtonDefaults.buttonColors(containerColor = ReversalRed),
          modifier = Modifier.testTag("confirm_remove_ax_plugin"),
        ) {
          Text("Remover", color = ColdWhite)
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingRemovalPlugin = null }) {
          Text("Cancelar", color = LimitlessCyan)
        }
      },
    )
  }

  if (inspectedPlugin != null) {
    AxPluginInspectorSheet(
      plugin = inspectedPlugin,
      shizukuConnected = shizukuConnected,
      onToggle = { enable -> onToggleAxPlugin(inspectedPlugin.id, enable) },
      onRequestRemove = { pendingRemovalPlugin = inspectedPlugin },
      onDismiss = { inspectedPluginId = null },
    )
  }
}

@Composable
private fun AxPluginCard(
  plugin: AxExtensionModule,
  shizukuConnected: Boolean,
  onToggle: (Boolean) -> Unit,
  onInspect: () -> Unit,
  onRequestRemove: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val compatColor =
    when (plugin.compatibility) {
      AxCompatibilityStatus.COMPATIBLE -> NeonSuccess
      AxCompatibilityStatus.PARTIAL -> LimitlessCyan
      AxCompatibilityStatus.ROOT_REQUIRED -> WarningAmber
      AxCompatibilityStatus.INCOMPATIBLE -> ReversalRed
    }
  val typeColor =
    when (plugin.pluginType) {
      AxPluginType.AX_MANAGER_PLUGIN -> LimitlessCyan
      AxPluginType.MAGISK_SHIZUKU_COMPAT -> HollowPurpleBright
      AxPluginType.MAGISK_ROOT_ONLY -> WarningAmber
      AxPluginType.UNSUPPORTED -> ReversalRed
    }

  VoidGlassCard(
    modifier = modifier.testTag("ax_plugin_card_${plugin.id}"),
    borderColor = if (plugin.enabled) LimitlessCyan else compatColor.copy(alpha = 0.55f),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = plugin.name,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = ColdWhite,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "v${plugin.version} • por ${plugin.author} • ${plugin.sourceZipName}",
          style = MaterialTheme.typography.labelSmall,
          color = MutedLavender,
        )
      }

      Switch(
        checked = plugin.enabled,
        enabled = plugin.isSupportedForActivation || plugin.enabled,
        onCheckedChange = onToggle,
        modifier = Modifier.testTag("ax_plugin_switch_${plugin.id}"),
        colors =
          SwitchDefaults.colors(
            checkedThumbColor = VoidObsidian,
            checkedTrackColor = LimitlessCyan,
            uncheckedThumbColor = MutedLavender,
            uncheckedTrackColor = VoidObsidian,
          ),
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Surface(
        shape = RoundedCornerShape(8.dp),
        color = typeColor.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, typeColor.copy(alpha = 0.45f)),
      ) {
        Text(
          text = plugin.pluginType.label,
          style = MaterialTheme.typography.labelSmall,
          color = typeColor,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }
      Surface(
        shape = RoundedCornerShape(8.dp),
        color = compatColor.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, compatColor.copy(alpha = 0.45f)),
      ) {
        Text(
          text = plugin.compatibility.label,
          style = MaterialTheme.typography.labelSmall,
          color = compatColor,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }
      Surface(
        shape = RoundedCornerShape(8.dp),
        color = VoidSurfaceElevated,
        border = BorderStroke(1.dp, VoidBorder),
      ) {
        Text(
          text = "${plugin.totalFilesCount} arq • ${plugin.scriptFiles.size} scripts • ${plugin.applyCommands.size} cmds",
          style = MaterialTheme.typography.labelSmall,
          color = ColdWhite,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = plugin.description,
      style = MaterialTheme.typography.bodyMedium,
      color = MutedLavender,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = plugin.compatibilityReason,
      style = MaterialTheme.typography.labelSmall,
      color = compatColor,
    )

    if (plugin.requiresShizuku && !shizukuConnected && plugin.applyCommands.isNotEmpty()) {
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = "Shizuku desconectado: conecte para executar os ${plugin.applyCommands.size} comando(s) deste plugin.",
        style = MaterialTheme.typography.labelSmall,
        color = WarningAmber,
      )
    }

    if (plugin.lastExecutionLog.isNotBlank()) {
      Spacer(modifier = Modifier.height(8.dp))
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = VoidObsidian.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, VoidBorder),
      ) {
        Text(
          text = "> ${plugin.lastExecutionLog}",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = FontFamily.Monospace,
          color = if (plugin.enabled) NeonSuccess else LimitlessCyan,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
      }
    }

    Spacer(modifier = Modifier.height(10.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedButton(
        onClick = onInspect,
        modifier =
          Modifier.weight(1f)
            .minimumInteractiveComponentSize()
            .testTag("ax_plugin_inspect_${plugin.id}"),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, HollowPurpleBright.copy(alpha = 0.7f)),
      ) {
        Icon(
          imageVector = Icons.Rounded.Code,
          contentDescription = null,
          tint = LimitlessCyan,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
          text = "Analisar Scripts & Dependências",
          style = MaterialTheme.typography.labelMedium,
          color = ColdWhite,
        )
      }

      IconButton(
        onClick = onRequestRemove,
        modifier =
          Modifier.minimumInteractiveComponentSize()
            .testTag("ax_plugin_remove_${plugin.id}"),
      ) {
        Icon(
          imageVector = Icons.Rounded.DeleteOutline,
          contentDescription = "Remover plugin ${plugin.name}",
          tint = ReversalRed,
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AxPluginInspectorSheet(
  plugin: AxExtensionModule,
  shizukuConnected: Boolean,
  onToggle: (Boolean) -> Unit,
  onRequestRemove: () -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val compatColor =
    when (plugin.compatibility) {
      AxCompatibilityStatus.COMPATIBLE -> NeonSuccess
      AxCompatibilityStatus.PARTIAL -> LimitlessCyan
      AxCompatibilityStatus.ROOT_REQUIRED -> WarningAmber
      AxCompatibilityStatus.INCOMPATIBLE -> ReversalRed
    }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = VoidSurfaceElevated,
    contentColor = ColdWhite,
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = plugin.name,
        style = MaterialTheme.typography.headlineSmall,
        color = ColdWhite,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = "ID: ${plugin.id} • v${plugin.version} (${plugin.versionCode}) • ${plugin.sourceZipName}",
        style = MaterialTheme.typography.labelMedium,
        color = LimitlessCyan,
      )
      Text(
        text = plugin.description,
        style = MaterialTheme.typography.bodyMedium,
        color = MutedLavender,
      )

      // Compatibility Card
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = VoidObsidian.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, compatColor.copy(alpha = 0.6f)),
      ) {
        Column(
          modifier = Modifier.padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
            text = "Compatibilidade: ${plugin.compatibility.label}",
            style = MaterialTheme.typography.titleSmall,
            color = compatColor,
          )
          Text(
            text = "Android: ${plugin.androidCompatLabel}",
            style = MaterialTheme.typography.labelMedium,
            color = ColdWhite,
          )
          Text(
            text = "Aparelho: ${plugin.deviceCompatLabel}",
            style = MaterialTheme.typography.labelMedium,
            color = ColdWhite,
          )
          Text(
            text = plugin.compatibilityReason,
            style = MaterialTheme.typography.bodySmall,
            color = MutedLavender,
          )
        }
      }

      // Dependencies Checklist
      Text(
        text = "Dependências Verificadas (${plugin.dependencies.size})",
        style = MaterialTheme.typography.titleMedium,
        color = ColdWhite,
      )
      plugin.dependencies.forEach { dep ->
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp),
          color = VoidObsidian.copy(alpha = 0.72f),
          border =
            BorderStroke(
              1.dp,
              if (dep.satisfied) NeonSuccess.copy(alpha = 0.45f) else WarningAmber.copy(alpha = 0.5f),
            ),
        ) {
          Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(
              imageVector =
                if (dep.satisfied) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
              contentDescription = null,
              tint = if (dep.satisfied) NeonSuccess else WarningAmber,
              modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
              Text(dep.name, style = MaterialTheme.typography.labelLarge, color = ColdWhite)
              Text(dep.detail, style = MaterialTheme.typography.bodySmall, color = MutedLavender)
            }
          }
        }
      }

      // system.prop overrides
      if (plugin.propOverrides.isNotEmpty()) {
        Text(
          text = "Propriedades system.prop (${plugin.propOverrides.size})",
          style = MaterialTheme.typography.titleMedium,
          color = ColdWhite,
        )
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp),
          color = VoidObsidian,
          border = BorderStroke(1.dp, VoidBorder),
        ) {
          Text(
            text = plugin.propOverrides.joinToString("\n") { (k, v) -> "$k=$v" },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = LimitlessCyan,
            modifier = Modifier.padding(10.dp),
          )
        }
      }

      // Analyzed Scripts
      Text(
        text = "Scripts & Arquivos Analisados (${plugin.scriptFiles.size})",
        style = MaterialTheme.typography.titleMedium,
        color = ColdWhite,
      )
      if (plugin.scriptFiles.isEmpty()) {
        Text(
          text = "Nenhum script .sh detectado dentro deste pacote.",
          style = MaterialTheme.typography.bodySmall,
          color = MutedLavender,
        )
      } else {
        plugin.scriptFiles.forEach { script ->
          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = VoidObsidian,
            border = BorderStroke(1.dp, VoidBorder),
          ) {
            Column(
              modifier = Modifier.padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = script.path,
                  style = MaterialTheme.typography.labelLarge,
                  color = LimitlessCyan,
                )
                Text(
                  text = "${script.executableCommandsCount} cmds",
                  style = MaterialTheme.typography.labelSmall,
                  color = HollowPurpleBright,
                )
              }
              Text(
                text = script.summary,
                style = MaterialTheme.typography.labelSmall,
                color = MutedLavender,
              )
              if (script.contentPreview.isNotBlank()) {
                Text(
                  text = script.contentPreview,
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = FontFamily.Monospace,
                  color = ColdWhite.copy(alpha = 0.88f),
                  modifier =
                    Modifier.fillMaxWidth()
                      .heightIn(max = 160.dp)
                      .verticalScroll(rememberScrollState()),
                )
              }
            }
          }
        }
      }

      // Executable commands preview
      if (plugin.applyCommands.isNotEmpty()) {
        Text(
          text = "Comandos Integrados ao Shizuku (${plugin.applyCommands.size})",
          style = MaterialTheme.typography.titleMedium,
          color = ColdWhite,
        )
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp),
          color = VoidObsidian,
          border = BorderStroke(1.dp, LimitlessCyan.copy(alpha = 0.4f)),
        ) {
          Text(
            text = plugin.applyCommands.joinToString("\n"),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = NeonSuccess,
            modifier = Modifier.padding(10.dp),
          )
        }
      }

      // Actions
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Button(
          onClick = { onToggle(!plugin.enabled) },
          enabled = plugin.isSupportedForActivation || plugin.enabled,
          modifier = Modifier.weight(1f).height(48.dp).testTag("ax_sheet_toggle_button"),
          shape = RoundedCornerShape(12.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = if (plugin.enabled) WarningAmber else HollowPurple,
              contentColor = if (plugin.enabled) VoidObsidian else ColdWhite,
            ),
        ) {
          Text(
            text =
              when {
                plugin.enabled -> "Desativar Plugin"
                !plugin.isSupportedForActivation -> "Não Suportado"
                plugin.requiresShizuku && !shizukuConnected -> "Ativar (Shizuku)"
                else -> "Ativar Plugin"
              },
            style = MaterialTheme.typography.labelLarge,
          )
        }

        OutlinedButton(
          onClick = onRequestRemove,
          modifier = Modifier.height(48.dp).testTag("ax_sheet_remove_button"),
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, ReversalRed),
        ) {
          Text("Remover", color = ReversalRed, style = MaterialTheme.typography.labelLarge)
        }
      }

      Spacer(modifier = Modifier.height(18.dp))
    }
  }
}
