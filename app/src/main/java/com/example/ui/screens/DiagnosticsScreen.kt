package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.BottleneckSeverity
import com.example.model.DiagnosticAction
import com.example.ui.BoosterUiState
import com.example.ui.components.SectionHeader
import com.example.ui.components.VoidGlassCard
import com.example.ui.theme.ColdWhite
import com.example.ui.theme.CriticalCrimson
import com.example.ui.theme.HollowPurple
import com.example.ui.theme.HollowPurpleBright
import com.example.ui.theme.LimitlessCyan
import com.example.ui.theme.MutedLavender
import com.example.ui.theme.NeonSuccess
import com.example.ui.theme.VoidBorder
import com.example.ui.theme.VoidObsidian
import com.example.ui.theme.VoidSurfaceElevated
import com.example.ui.theme.WarningAmber

@Composable
fun DiagnosticsScreen(
  state: BoosterUiState,
  onRunScan: () -> Unit,
  onRunGuardianHeal: () -> Unit,
  onBuildXRayText: () -> String,
  onCopyXRay: () -> Unit,
  onShareXRay: () -> Unit,
  onClearXRayLogs: () -> Unit,
  onDiagnosticAction: (DiagnosticAction) -> Unit,
) {
  val telemetry = state.telemetry
  var creatorPanelVisible by remember { mutableStateOf(false) }
  var showXRayPreview by remember { mutableStateOf(false) }
  var secretTapCount by remember { mutableIntStateOf(0) }
  var lastSecretTapMs by remember { mutableLongStateOf(0L) }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // Header + Scan button
    item {
      Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        SectionHeader(
          title = "Diagnóstico do Celular",
          subtitle = "RAM, Armazenamento e causas de travamento",
        )
        Button(
          onClick = onRunScan,
          enabled = !state.isScanningDiagnostics,
          shape = RoundedCornerShape(14.dp),
          colors = ButtonDefaults.buttonColors(containerColor = HollowPurple),
          modifier = Modifier.minimumInteractiveComponentSize().testTag("scan_diagnostics_button"),
        ) {
          if (state.isScanningDiagnostics) {
            CircularProgressIndicator(
              modifier = Modifier.size(18.dp),
              color = ColdWhite,
              strokeWidth = 2.dp,
            )
          } else {
            Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, tint = ColdWhite)
          }
          Spacer(modifier = Modifier.size(6.dp))
          Text(
            text = if (state.isScanningDiagnostics) "Analisando..." else "Verificar",
            style = MaterialTheme.typography.labelLarge,
            color = ColdWhite,
          )
        }
      }
    }

    // IA Guardião Quick Heal Card in Diagnostics
    item {
      val g = state.guardianState
      val gColor = if (g.healthScore >= 85) NeonSuccess else WarningAmber
      VoidGlassCard(
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        borderColor = gColor.copy(alpha = 0.55f),
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
            Icon(imageVector = Icons.Rounded.HealthAndSafety, contentDescription = null, tint = gColor)
            Column {
              Text("IA Guardião 24h corrigindo bugs", style = MaterialTheme.typography.titleMedium, color = ColdWhite)
              Text(g.statusSummary, style = MaterialTheme.typography.bodySmall, color = MutedLavender)
            }
          }
          Surface(shape = RoundedCornerShape(8.dp), color = gColor.copy(alpha = 0.16f)) {
            Text(
              text = "${g.healthScore}%",
              style = MaterialTheme.typography.labelLarge,
              color = gColor,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(
          onClick = onRunGuardianHeal,
          enabled = !g.isAnalyzing,
          border = BorderStroke(1.dp, LimitlessCyan.copy(alpha = 0.7f)),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("diagnostics_guardian_heal_button"),
        ) {
          if (g.isAnalyzing) {
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              color = LimitlessCyan,
              strokeWidth = 2.dp,
            )
          } else {
            Icon(imageVector = Icons.Rounded.AutoFixHigh, contentDescription = null, tint = LimitlessCyan, modifier = Modifier.size(18.dp))
          }
          Spacer(modifier = Modifier.size(6.dp))
          Text(
            text =
              if (g.isAnalyzing) "Analisando (4 etapas)..."
              else "Executar IA Guardião (${g.totalRepairsCount} feitas)",
            color = ColdWhite,
          )
        }
      }
    }

    // Raio-X Secreto do Criador (oculto por padrão — desbloqueado com 7 toques em Verificação do Aparelho)
    if (creatorPanelVisible) {
      item {
        val logAccent = if (state.hasCrashOrErrorLogs) WarningAmber else LimitlessCyan
        VoidGlassCard(
          modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
          borderColor = logAccent.copy(alpha = 0.55f),
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
              Icon(imageVector = Icons.Rounded.BugReport, contentDescription = null, tint = logAccent)
              Column {
                Text(
                  text = "Modo Criador • Raio-X Oculto",
                  style = MaterialTheme.typography.titleMedium,
                  color = ColdWhite,
                )
                Text(
                  text =
                    if (state.hasCrashOrErrorLogs)
                      "Erros ou crash capturados — copie e cole no chat para eu analisar"
                    else
                      "Relatório técnico visível apenas neste painel oculto",
                  style = MaterialTheme.typography.bodySmall,
                  color = MutedLavender,
                )
              }
            }
            IconButton(
              onClick = {
                creatorPanelVisible = false
                showXRayPreview = false
                secretTapCount = 0
              },
              modifier = Modifier.minimumInteractiveComponentSize().testTag("hide_creator_xray_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Ocultar Raio-X do Criador",
                tint = MutedLavender,
              )
            }
          }

          if (state.lastCrashPreview != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = VoidObsidian.copy(alpha = 0.85f),
              border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.55f)),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = state.lastCrashPreview,
                style = MaterialTheme.typography.labelSmall,
                color = WarningAmber,
                modifier = Modifier.padding(10.dp),
              )
            }
          }

          Spacer(modifier = Modifier.height(10.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Button(
              onClick = onCopyXRay,
              shape = RoundedCornerShape(12.dp),
              colors = ButtonDefaults.buttonColors(containerColor = HollowPurple),
              modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("copy_xray_button"),
            ) {
              Icon(imageVector = Icons.Rounded.ContentCopy, contentDescription = null, tint = ColdWhite, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.size(6.dp))
              Text("Copiar Raio-X", color = ColdWhite)
            }
            OutlinedButton(
              onClick = onShareXRay,
              border = BorderStroke(1.dp, LimitlessCyan.copy(alpha = 0.65f)),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.minimumInteractiveComponentSize().testTag("share_xray_button"),
            ) {
              Icon(imageVector = Icons.Rounded.Share, contentDescription = "Compartilhar Raio-X", tint = LimitlessCyan, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.size(6.dp))
              Text("Enviar", color = ColdWhite)
            }
          }

          Spacer(modifier = Modifier.height(6.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            TextButton(
              onClick = { showXRayPreview = !showXRayPreview },
              modifier = Modifier.minimumInteractiveComponentSize().testTag("toggle_xray_preview_button"),
            ) {
              Text(
                text = if (showXRayPreview) "Ocultar Prévia" else "Ver Raio-X na Tela",
                color = LimitlessCyan,
              )
            }
            if (state.hasCrashOrErrorLogs) {
              TextButton(
                onClick = onClearXRayLogs,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("clear_xray_logs_button"),
              ) {
                Icon(imageVector = Icons.Rounded.DeleteOutline, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.size(4.dp))
                Text("Limpar Logs", color = WarningAmber)
              }
            }
          }

          if (showXRayPreview) {
            val previewText = remember(state, showXRayPreview) { onBuildXRayText() }
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = VoidObsidian.copy(alpha = 0.9f),
              border = BorderStroke(1.dp, VoidBorder),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = previewText,
                style = MaterialTheme.typography.labelSmall,
                color = MutedLavender,
                modifier = Modifier.padding(10.dp),
              )
            }
          }
        }
      }
    }

    // Hardware Identity Card (tap header 7x without ripple to toggle Creator Raio-X)
    item {
      val noRippleInteraction = remember { MutableInteractionSource() }
      VoidGlassCard(modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp)) {
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .clickable(
                interactionSource = noRippleInteraction,
                indication = null,
              ) {
                val now = System.currentTimeMillis()
                if (now - lastSecretTapMs > 2500L) {
                  secretTapCount = 1
                } else {
                  secretTapCount++
                }
                lastSecretTapMs = now
                if (secretTapCount >= 7) {
                  creatorPanelVisible = !creatorPanelVisible
                  if (!creatorPanelVisible) showXRayPreview = false
                  secretTapCount = 0
                }
              }
              .testTag("device_spec_secret_header"),
        ) {
          SectionHeader(title = "Verificação do Aparelho", subtitle = telemetry.deviceName)
        }
        Spacer(modifier = Modifier.height(10.dp))
        SpecRow("Sistema", "${telemetry.androidVersion} (API ${telemetry.sdkInt})")
        SpecRow("Arquitetura CPU", "${telemetry.cpuAbi} • ${telemetry.cpuCores} Núcleos")
        SpecRow("Clock / Cores", telemetry.cpuClockLabel)
        SpecRow("GPU Detectada", telemetry.gpuRendererLabel)
        SpecRow("Suporte Vulkan", telemetry.vulkanVersionLabel)
        SpecRow("Taxa Display", "${telemetry.currentDisplayHz}Hz (Máx ${telemetry.maxDisplayHz}Hz)")
      }
    }

    // RAM & Storage Live Gauges
    item {
      VoidGlassCard(modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(imageVector = Icons.Rounded.Memory, contentDescription = null, tint = LimitlessCyan)
          Text("Memória RAM", style = MaterialTheme.typography.titleMedium, color = ColdWhite)
          Spacer(modifier = Modifier.weight(1f))
          Text(
            text = "${telemetry.ramUsedMb} / ${telemetry.ramTotalMb} MB (${telemetry.ramPercent}%)",
            style = MaterialTheme.typography.labelLarge,
            color = if (telemetry.ramPercent >= 80) WarningAmber else LimitlessCyan,
          )
        }
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
          progress = { (telemetry.ramPercent / 100f).coerceIn(0f, 1f) },
          modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
          color = if (telemetry.ramPercent >= 85) CriticalCrimson else LimitlessCyan,
          trackColor = VoidObsidian,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Disponível agora: ${telemetry.ramAvailMb} MB livres",
          style = MaterialTheme.typography.labelMedium,
          color = MutedLavender,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.Storage,
            contentDescription = null,
            tint = HollowPurpleBright,
          )
          Text("Armazenamento Interno", style = MaterialTheme.typography.titleMedium, color = ColdWhite)
          Spacer(modifier = Modifier.weight(1f))
          Text(
            text =
              "${String.format("%.1f", telemetry.storageUsedGb)} / ${String.format("%.1f", telemetry.storageTotalGb)} GB (${telemetry.storagePercent}%)",
            style = MaterialTheme.typography.labelLarge,
            color = if (telemetry.storagePercent >= 88) WarningAmber else HollowPurpleBright,
          )
        }
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
          progress = { (telemetry.storagePercent / 100f).coerceIn(0f, 1f) },
          modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
          color = if (telemetry.storagePercent >= 88) WarningAmber else HollowPurpleBright,
          trackColor = VoidObsidian,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Espaço livre: ${String.format("%.1f", telemetry.storageAvailGb)} GB",
          style = MaterialTheme.typography.labelMedium,
          color = MutedLavender,
        )
      }
    }

    // Bottleneck Section Header
    item {
      Column(modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp)) {
        SectionHeader(
          title = "O que está fazendo o celular travar",
          subtitle = "Análise em tempo real de RAM, temperatura, espaço e energia",
        )
      }
    }

    items(state.findings, key = { it.id }) { finding ->
      val (accent, icon) =
        when (finding.severity) {
          BottleneckSeverity.CRITICAL -> CriticalCrimson to Icons.Rounded.ErrorOutline
          BottleneckSeverity.WARNING -> WarningAmber to Icons.Rounded.WarningAmber
          BottleneckSeverity.OK -> NeonSuccess to Icons.Rounded.CheckCircle
        }

      VoidGlassCard(
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        borderColor = accent.copy(alpha = 0.55f),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Icon(imageVector = icon, contentDescription = null, tint = accent)
          Text(
            text = finding.title,
            style = MaterialTheme.typography.titleMedium,
            color = ColdWhite,
            modifier = Modifier.weight(1f),
          )
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = accent.copy(alpha = 0.16f),
          ) {
            Text(
              text =
                when (finding.severity) {
                  BottleneckSeverity.CRITICAL -> "CRÍTICO"
                  BottleneckSeverity.WARNING -> "ALERTA"
                  BottleneckSeverity.OK -> "ESTÁVEL"
                },
              style = MaterialTheme.typography.labelSmall,
              color = accent,
              modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            )
          }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = finding.detail,
          style = MaterialTheme.typography.bodyMedium,
          color = MutedLavender,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = "Solução: ${finding.recommendation}",
          style = MaterialTheme.typography.labelMedium,
          color = ColdWhite,
        )

        if (finding.action != DiagnosticAction.NONE) {
          Spacer(modifier = Modifier.height(10.dp))
          OutlinedButton(
            onClick = { onDiagnosticAction(finding.action) },
            border = BorderStroke(1.dp, accent.copy(alpha = 0.7f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.minimumInteractiveComponentSize().testTag("fix_${finding.id}"),
          ) {
            Text(
              text =
                when (finding.action) {
                  DiagnosticAction.OPTIMIZE_RAM -> "Liberar RAM e Caches Agora"
                  DiagnosticAction.ACTIVATE_COOLER -> "Abrir Cooler & Resfriar"
                  DiagnosticAction.OPEN_BATTERY_SETTINGS -> "Abrir Config. de Bateria"
                  DiagnosticAction.UNLOCK_FPS -> "Ativar 120Hz & Sem Limite"
                  DiagnosticAction.NONE -> ""
                },
              style = MaterialTheme.typography.labelLarge,
              color = accent,
            )
          }
        }
      }
    }

    item { Spacer(modifier = Modifier.height(12.dp)) }
  }
}

@Composable
private fun SpecRow(label: String, value: String) {
  Surface(
    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    shape = RoundedCornerShape(10.dp),
    color = VoidSurfaceElevated.copy(alpha = 0.55f),
    border = BorderStroke(1.dp, VoidBorder.copy(alpha = 0.5f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(text = label, style = MaterialTheme.typography.labelMedium, color = MutedLavender)
      Text(text = value, style = MaterialTheme.typography.labelLarge, color = ColdWhite)
    }
  }
}
