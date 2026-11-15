package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.CoolerSpeed
import com.example.ui.BoosterUiState
import com.example.ui.components.SectionHeader
import com.example.ui.components.TelemetryPill
import com.example.ui.components.VoidGlassCard
import com.example.ui.theme.ColdWhite
import com.example.ui.theme.CryoFrost
import com.example.ui.theme.HollowPurpleBright
import com.example.ui.theme.LimitlessCyan
import com.example.ui.theme.MutedLavender
import com.example.ui.theme.NeonSuccess
import com.example.ui.theme.ReversalRed
import com.example.ui.theme.VoidBorder
import com.example.ui.theme.VoidObsidian
import com.example.ui.theme.VoidSurfaceElevated

@Composable
fun CoolerPanelScreen(
  state: BoosterUiState,
  onToggleCooler: () -> Unit,
  onSelectSpeed: (CoolerSpeed) -> Unit,
  onStartCoolingCycle: () -> Unit,
) {
  val settings = state.settings
  val telemetry = state.telemetry
  val active = settings.coolerActive || state.isCoolingCycleRunning

  val infinite = rememberInfiniteTransition(label = "cooler_fan")
  val duration =
    if (!active) 6000
    else
      when (settings.coolerSpeed) {
        CoolerSpeed.ECO -> 1600
        CoolerSpeed.TURBO -> 850
        CoolerSpeed.CRYO_MAX -> 420
      }

  val fanRotation by
    infinite.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec = infiniteRepeatable(tween(duration, easing = LinearEasing), RepeatMode.Restart),
      label = "fan_rotation",
    )

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item {
      Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        SectionHeader(
          title = "Painel Cooler Criogênico",
          subtitle = "Resfriamento ativo por limpeza de processos e alívio térmico",
        )
        Icon(
          imageVector = Icons.Rounded.AcUnit,
          contentDescription = null,
          tint = CryoFrost,
        )
      }
    }

    // Animated Cryo Fan Visual Card
    item {
      VoidGlassCard(
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        borderColor = if (active) CryoFrost else VoidBorder,
      ) {
        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Box(
            modifier =
              Modifier.size(210.dp)
                .clip(RoundedCornerShape(105.dp))
                .clickable(onClick = onToggleCooler)
                .testTag("cooler_fan_dial"),
            contentAlignment = Alignment.Center,
          ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
              val center = Offset(size.width / 2f, size.height / 2f)
              val radius = size.minDimension / 2f - 12.dp.toPx()

              // Cryo halo
              drawCircle(
                brush =
                  Brush.radialGradient(
                    colors =
                      listOf(
                        (if (active) CryoFrost else HollowPurpleBright).copy(alpha = 0.35f),
                        Color.Transparent,
                      ),
                    center = center,
                    radius = radius * 1.1f,
                  ),
                center = center,
                radius = radius * 1.1f,
              )

              // Outer metallic/neon housing ring
              drawCircle(
                color = if (active) CryoFrost else VoidBorder,
                radius = radius,
                center = center,
                style = Stroke(width = 4.dp.toPx()),
              )

              // Spinning 7-blade turbine
              rotate(degrees = if (active) fanRotation else fanRotation * 0.15f, pivot = center) {
                for (i in 0 until 7) {
                  val angle = i * (360f / 7f)
                  rotate(degrees = angle, pivot = center) {
                    val bladePath =
                      Path().apply {
                        moveTo(center.x, center.y - 18.dp.toPx())
                        quadraticTo(
                          center.x + 28.dp.toPx(),
                          center.y - radius * 0.58f,
                          center.x + 10.dp.toPx(),
                          center.y - radius * 0.86f,
                        )
                        quadraticTo(
                          center.x - 10.dp.toPx(),
                          center.y - radius * 0.72f,
                          center.x - 6.dp.toPx(),
                          center.y - 18.dp.toPx(),
                        )
                        close()
                      }
                    drawPath(
                      path = bladePath,
                      brush =
                        Brush.linearGradient(
                          colors =
                            listOf(
                              if (active) CryoFrost else MutedLavender.copy(alpha = 0.45f),
                              if (active) LimitlessCyan.copy(alpha = 0.5f)
                              else HollowPurpleBright.copy(alpha = 0.25f),
                            )
                        ),
                    )
                  }
                }
              }

              // Hub cap
              drawCircle(
                color = VoidObsidian,
                radius = 28.dp.toPx(),
                center = center,
              )
              drawCircle(
                color = if (active) LimitlessCyan else MutedLavender,
                radius = 28.dp.toPx(),
                center = center,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
              )
            }

            Icon(
              imageVector = Icons.Rounded.AcUnit,
              contentDescription = "Ventoinha do Cooler",
              tint = if (active) CryoFrost else MutedLavender,
              modifier = Modifier.size(26.dp),
            )
          }

          Spacer(modifier = Modifier.height(10.dp))
          Text(
            text = "${String.format("%.1f", telemetry.batteryTempC)} °C",
            style = MaterialTheme.typography.displaySmall,
            color = if (telemetry.batteryTempC >= 39f) ReversalRed else CryoFrost,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text =
              if (active) "Cooler Ativo • ${settings.coolerSpeed.rpmText}"
              else "Cooler em Espera • Toque para acionar",
            style = MaterialTheme.typography.labelLarge,
            color = if (active) LimitlessCyan else MutedLavender,
          )
        }
      }
    }

    // Real Thermal Telemetry Strip
    item {
      Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        TelemetryPill(
          label = "ESTADO TÉRMICO",
          value = telemetry.thermalStatusLabel,
          accentColor = if (telemetry.batteryTempC >= 39f) ReversalRed else NeonSuccess,
          modifier = Modifier.weight(1.3f),
        )
        TelemetryPill(
          label = "BATERIA",
          value = "${telemetry.batteryPercent}% ${if (telemetry.isCharging) "⚡" else ""}",
          accentColor = CryoFrost,
          modifier = Modifier.weight(0.9f),
        )
      }
    }

    // Fan Speed Modes & Action
    item {
      VoidGlassCard(modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp)) {
        SectionHeader(
          title = "Potência da Ventoinha",
          subtitle = "Escolha o perfil de resfriamento e execute a varredura térmica",
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          CoolerSpeed.entries.forEach { speed ->
            val selected = settings.coolerSpeed == speed
            Surface(
              modifier =
                Modifier.weight(1f)
                  .minimumInteractiveComponentSize()
                  .clip(RoundedCornerShape(14.dp))
                  .clickable { onSelectSpeed(speed) }
                  .testTag("cooler_speed_${speed.id}"),
              shape = RoundedCornerShape(14.dp),
              color = if (selected) CryoFrost.copy(alpha = 0.2f) else VoidSurfaceElevated,
              border =
                BorderStroke(
                  width = if (selected) 1.5.dp else 1.dp,
                  color = if (selected) CryoFrost else VoidBorder,
                ),
            ) {
              Column(
                modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                Text(
                  text = speed.label,
                  style = MaterialTheme.typography.labelLarge,
                  color = if (selected) ColdWhite else MutedLavender,
                )
                Text(
                  text = speed.rpmText,
                  style = MaterialTheme.typography.labelSmall,
                  color = if (selected) CryoFrost else MutedLavender,
                )
              }
            }
          }
        }

        if (state.isCoolingCycleRunning) {
          Spacer(modifier = Modifier.height(14.dp))
          LinearProgressIndicator(
            progress = { state.coolingProgress },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = CryoFrost,
            trackColor = VoidObsidian,
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "Dissipando carga de cache e estabilizando processos... ${(state.coolingProgress * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = CryoFrost,
          )
        }

        Spacer(modifier = Modifier.height(14.dp))
        Button(
          onClick = onStartCoolingCycle,
          enabled = !state.isCoolingCycleRunning,
          modifier =
            Modifier.fillMaxWidth().height(52.dp).testTag("start_cooling_button"),
          shape = RoundedCornerShape(16.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = CryoFrost,
              contentColor = VoidObsidian,
            ),
        ) {
          Icon(imageVector = Icons.Rounded.Thermostat, contentDescription = null)
          Spacer(modifier = Modifier.size(8.dp))
          Text(
            text =
              if (state.isCoolingCycleRunning) "Resfriando Sistema..."
              else "Iniciar Resfriamento Criogênico",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }
  }
}
