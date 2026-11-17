package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
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
  onDiagnosticAction: (DiagnosticAction) -> Unit,
) {
  val telemetry = state.telemetry

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

    // Hardware Identity Card
    item {
      VoidGlassCard(modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp)) {
        SectionHeader(title = "Verificação do Aparelho", subtitle = telemetry.deviceName)
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
